// 发现服务（UDP 8322）单元测试。
//
// 背景：原实现把「应答 socket」和「监听 socket」都绑到 0.0.0.0:8322，
// 读循环只读后绑的那只；Windows 对同端口多 socket 的投递未定义，
// 实测会让读循环那只永久收不到包（发现通道失聪、无日志、不自愈）。
// 这些用例把「单 socket 能收发」「失聪后能自愈」「瞬时错误不致死」固化成回归网。
package main

import (
	"errors"
	"net"
	"strings"
	"syscall"
	"testing"
	"time"
)

func testConfig() *Config { return &Config{Port: 8321, User: "miback"} }

// freeUDPPort 借一只临时 socket 拿一个空闲端口，避免与在跑的服务冲突
func freeUDPPort(t *testing.T) int {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("取空闲端口失败: %v", err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// withDiscoverPort 临时替换全局发现端口（discoveryLoop/serveDiscovery 读该变量）
func withDiscoverPort(t *testing.T, port int) {
	t.Helper()
	old := discoverPort
	discoverPort = port
	t.Cleanup(func() { discoverPort = old })
}

func probeOnce(t *testing.T, port int, payload string) (string, error) {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return "", err
	}
	defer c.Close()
	if _, err := c.WriteToUDP([]byte(payload), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}); err != nil {
		return "", err
	}
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 512)
	n, _, err := c.ReadFromUDP(buf)
	if err != nil {
		return "", err
	}
	return string(buf[:n]), nil
}

func waitFor(t *testing.T, d time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("等待超时：%s", what)
}

// 单 socket 既能收也能发：探测码进来必须拿到 JSON 应答
func TestDiscoveryLoopReplies(t *testing.T) {
	port := freeUDPPort(t)
	withDiscoverPort(t, port)
	conn, err := listenUDPExclusive(port)
	if err != nil {
		t.Fatalf("绑定失败: %v", err)
	}
	done := make(chan error, 1)
	go func() { done <- discoveryLoop(conn, testConfig()) }()
	defer func() {
		_ = conn.Close()
		<-done
	}()

	body, err := probeOnce(t, port, discoverMagic)
	if err != nil {
		t.Fatalf("探测未获得应答: %v", err)
	}
	for _, want := range []string{`"service":"mibackpc"`, `"tcp":8321`, `"lans"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("应答缺少 %s: %s", want, body)
		}
	}

	// 非探测码：必须静默（不应答），且不退出循环
	if _, err := probeOnce(t, port, "GARBAGE"); err == nil {
		t.Fatal("非探测码不应获得应答")
	}
	if _, err := probeOnce(t, port, discoverMagic); err != nil {
		t.Fatalf("非探测码之后循环不应退出: %v", err)
	}
}

// 看守自检：没有监听时判定失败，有监听时判定成功（这是「失聪检测」的判据）
func TestDiscoverySelfCheck(t *testing.T) {
	port := freeUDPPort(t)
	withDiscoverPort(t, port)

	start := time.Now()
	if discoverySelfCheck() {
		t.Fatal("无监听时自检不应成功")
	}
	if el := time.Since(start); el > 3*time.Second {
		t.Fatalf("自检耗时过长（%s），应受 discoverySelfTimeout 约束", el)
	}

	conn, err := listenUDPExclusive(port)
	if err != nil {
		t.Fatalf("绑定失败: %v", err)
	}
	done := make(chan error, 1)
	go func() { done <- discoveryLoop(conn, testConfig()) }()
	defer func() {
		_ = conn.Close()
		<-done
	}()
	if !discoverySelfCheck() {
		t.Fatal("有监听时自检应成功")
	}
}

// 监督器：socket 被外部关闭（模拟失聪）后必须自动重建，且重建后仍可收发
func TestDiscoverySupervisorRebinds(t *testing.T) {
	port := freeUDPPort(t)
	withDiscoverPort(t, port)

	go serveDiscovery(testConfig())

	waitFor(t, 3*time.Second, "首次就绪", func() bool { return discoveryCurrent() != nil })
	first := discoveryCurrent()
	if _, err := probeOnce(t, port, discoverMagic); err != nil {
		t.Fatalf("首次就绪后探测失败: %v", err)
	}

	// 制造失聪：直接关掉正在服务的 socket
	_ = first.Close()

	waitFor(t, 6*time.Second, "自动重建", func() bool {
		c := discoveryCurrent()
		return c != nil && c != first
	})
	if _, err := probeOnce(t, port, discoverMagic); err != nil {
		t.Fatalf("重建后探测失败: %v", err)
	}
	if _, _, _, _, rebuilds, _ := discoveryInfo(); rebuilds == 0 {
		t.Fatal("重建次数应大于 0")
	}

	// 收尾：停掉本次测试起的发现服务，避免影响其它用例
	if c := discoveryCurrent(); c != nil {
		_ = c.Close()
	}
}

// 端口被占用时必须明确失败并给出可诊断提示，**绝不能退化共享绑定**。
// 实测：第二个实例（如 dist 里的旧构建）共享绑定后，8322 上出现多只 socket，
// 把「发现通道失聪」原样复现；静默共享比明确失败难排查得多。
func TestOpenDiscoveryRefusesWhenPortTaken(t *testing.T) {
	port := freeUDPPort(t)
	withDiscoverPort(t, port)

	holder, err := listenUDPExclusive(port)
	if err != nil {
		t.Fatalf("占位失败: %v", err)
	}
	defer holder.Close()

	if _, err := openDiscovery(); err == nil {
		t.Fatal("端口被占用时 openDiscovery 必须失败（不得退化共享绑定）")
	} else if !strings.Contains(err.Error(), "已被占用") {
		t.Fatalf("错误信息应提示端口占用与多实例，实际: %v", err)
	}
}

func TestIsTransientNetErr(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"ECONNRESET 包一层", &net.OpError{Op: "read", Err: syscall.ECONNRESET}, true},
		{"ECONNREFUSED", syscall.ECONNREFUSED, true},
		{"字符串兜底", errors.New("wsarecv: An existing connection was forcibly closed by the remote host."), true},
		{"超时", &net.OpError{Op: "read", Err: timeoutErr{}}, true},
		{"致命错误", errors.New("unknown network error"), false},
	}
	for _, c := range cases {
		if got := isTransientNetErr(c.err); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

type timeoutErr struct{}

func (timeoutErr) Error() string   { return "i/o timeout" }
func (timeoutErr) Timeout() bool   { return true }
func (timeoutErr) Temporary() bool { return true }

func TestNextBackoff(t *testing.T) {
	if got := nextBackoff(time.Second, 30*time.Second); got != 2*time.Second {
		t.Errorf("1s -> %s", got)
	}
	if got := nextBackoff(20*time.Second, 30*time.Second); got != 30*time.Second {
		t.Errorf("20s 应封顶 30s，得到 %s", got)
	}
}

// 虚拟网卡必须排在物理网卡之后（否则下发给手机的地址是 VMware/Hyper-V 段）
func TestLanIPRank(t *testing.T) {
	real := net.IPv4(192, 168, 31, 83).To4()
	vm := net.IPv4(192, 168, 232, 1).To4()
	if !(lanIPRank("WLAN", real) < lanIPRank("VMware Network Adapter VMnet1", vm)) {
		t.Error("物理网卡应优先于虚拟网卡")
	}
	if !(lanIPRank("以太网", real) < lanIPRank("以太网", net.IPv4(10, 0, 0, 5).To4())) {
		t.Error("192.168.x 应优先于 10.x")
	}
	for _, name := range []string{"VMware Network Adapter VMnet8", "vEthernet (WSL)", "Hyper-V Virtual Ethernet Adapter", "VirtualBox Host-Only Network"} {
		if !ifIsVirtual(name) {
			t.Errorf("%s 应被识别为虚拟网卡", name)
		}
	}
	for _, name := range []string{"WLAN", "以太网", "Wi-Fi", "Ethernet"} {
		if ifIsVirtual(name) {
			t.Errorf("%s 不应被识别为虚拟网卡", name)
		}
	}
}

func TestIsAddrInUse(t *testing.T) {
	if !isAddrInUse(syscall.EADDRINUSE) {
		t.Error("EADDRINUSE 应判定为端口占用")
	}
	if !isAddrInUse(errors.New("listen tcp :8321: bind: Only one usage of each socket address (protocol/network address/port) is normally permitted.")) {
		t.Error("Windows 文案应被识别")
	}
	if isAddrInUse(errors.New("permission denied")) {
		t.Error("非端口占用不应重试")
	}
}

func TestDiscoveryStatText(t *testing.T) {
	if got := discoveryStatText(false, time.Time{}, "", 0); got != "未就绪" {
		t.Errorf("未就绪文案异常: %s", got)
	}
	if got := discoveryStatText(true, time.Time{}, "", 0); !strings.Contains(got, "尚未收到探测") {
		t.Errorf("无探测文案异常: %s", got)
	}
	got := discoveryStatText(true, time.Now().Add(-90*time.Second), "192.168.31.227", 3)
	if !strings.Contains(got, "192.168.31.227") || !strings.Contains(got, "分钟前") {
		t.Errorf("探测来源文案异常: %s", got)
	}
}

func TestFirstLineAndAdbReason(t *testing.T) {
	if got := firstLine("first\r\nsecond"); got != "first" {
		t.Errorf("firstLine=%q", got)
	}
	if got := adbErrReason("error: no devices/emulators found", errors.New("exit status 1")); !strings.Contains(got, "未检测到设备") {
		t.Errorf("无设备原因未识别: %s", got)
	}
	if got := adbErrReason("error: device unauthorized", errors.New("exit status 1")); !strings.Contains(got, "未授权") {
		t.Errorf("未授权原因未识别: %s", got)
	}
}
