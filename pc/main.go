// mibackpc —— XpMiBackup 电脑端接收器
//
// 单文件、纯标准库、零 CGO：把任意文件夹变成一台 WebDAV 服务器，
// 供手机端 XpMiBackup 备份/恢复到电脑。
//
// 连接流程（手机端无需手动配置）：
//
//	发现：手机备份页自动探测 —— 局域网走 UDP 8322 广播应答
//	      （PC 应答 socket 源端口固定 8322，与手机绑定端口对齐；Windows 需放行 UDP 8322 入站）
//	      USB 走 adb reverse 后探测 http://127.0.0.1:8321/miback/info
//	配对：手机 POST /pair/requests → 本机弹 Windows 原生确认框
//	      （或控制页「连接请求」卡片）→ 同意后凭据单次下发
//	传输：http://<电脑局域网IP>:8321/dav/（USB 为 http://127.0.0.1:8321/dav/）
//	监控：控制页新增「备份实时状态」卡片，1.5s 轮询 /api/live
//	      （当前速度 + 在途文件进度 + 本次累计）
//
// 协议子集按 src/app/.../comm/WebdavFileHelp.java 的线上行为逐条对齐：
//
//	PROPFIND(Depth 0/1) 必须返回 207，首条 href 为目录自身（客户端跳过），
//	href 需 URL 转义；getcontentlength/collection 节点供客户端正则解析；
//	MKCOL/PUT/GET/DELETE 为基础语义；客户端强制 HTTP/1.1 + Basic Auth。
package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// ---------- 配置持久化 ----------

type Config struct {
	Root string   `json:"root"` // 备份文件落地目录
	Port int      `json:"port"`
	User string   `json:"user"`
	Pass string   `json:"pass"`
	Pair []string `json:"paired"` // 已配对过的手机设备名（展示用，最新在后，最多保留 8 台）
}

func configPath() string {
	exe, err := os.Executable()
	if err != nil {
		return "mibackpc.json"
	}
	return filepath.Join(filepath.Dir(exe), "mibackpc.json")
}

func loadConfig() *Config {
	c := &Config{Port: 8321, User: "miback"}
	if b, err := os.ReadFile(configPath()); err == nil {
		// 解析失败必须留痕：曾出现配置文件被写入 BOM 后静默回落默认端口
		// （表现为「改了配置却没生效」），这里的告警是唯一线索
		if jerr := json.Unmarshal(b, c); jerr != nil {
			logf("配置文件 %s 解析失败（%v），已回落默认配置", configPath(), jerr)
		}
	}
	if c.Port <= 0 || c.Port > 65535 {
		c.Port = 8321
	}
	if c.User == "" {
		c.User = "miback"
	}
	if c.Pass == "" {
		b := make([]byte, 8)
		_, _ = rand.Read(b)
		c.Pass = hex.EncodeToString(b)
	}
	return c
}

func (c *Config) save() {
	b, _ := json.MarshalIndent(c, "", "  ")
	_ = os.WriteFile(configPath(), b, 0600)
}

// recordPairedDevice 记录已配对手机（去重，最多 8 台，供控制页展示）
func (c *Config) recordPairedDevice(name string) {
	if name == "" {
		return
	}
	for _, p := range c.Pair {
		if p == name {
			return
		}
	}
	c.Pair = append(c.Pair, name)
	if len(c.Pair) > 8 {
		c.Pair = c.Pair[len(c.Pair)-8:]
	}
	c.save()
}

// ---------- 实时传输统计（供控制页「备份进度/速度」卡片轮询） ----------

type liveTransfer struct {
	Path  string `json:"path"`
	Total int64  `json:"total"` // Content-Length 声明的总字节；0 = 未声明
	Recv  int64  `json:"recv"`  // 已接收字节
}

type liveTracker struct {
	mu           sync.Mutex
	byConn       map[string]*liveTransfer // key = 连接 RemoteAddr
	lastSeen     map[string]time.Time     // 30s 无写入视为中断，清理
	sessionBytes int64
	sessionFiles int
	speedWin     *speedWindow // 滚动 3s 字节窗口
}

var live = newLiveTracker()

// speedWindow：滚动 ~3s 字节窗，UI 1.5s 轮询拿到的速率近似稳定（每满 3s 窗口重开）
type speedWindow struct {
	mu    sync.Mutex
	start time.Time
	bytes int64
}

func newSpeedWindow() *speedWindow {
	return &speedWindow{start: time.Now()}
}

// add 逐包计入字节；满 3s 窗口自动重开
func (w *speedWindow) add(n int64) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if time.Since(w.start) >= 3*time.Second {
		w.start = time.Now()
		w.bytes = 0
	}
	w.bytes += n
}

// mbps 最近窗口的平均 MB/s
func (w *speedWindow) mbps() float64 {
	w.mu.Lock()
	defer w.mu.Unlock()
	age := time.Since(w.start)
	if age < 200*time.Millisecond { // 刚开窗不出 0，避免闪烁
		return 0
	}
	return float64(w.bytes) / age.Seconds() / 1024 / 1024
}

func newLiveTracker() *liveTracker {
	return &liveTracker{byConn: map[string]*liveTransfer{}, lastSeen: map[string]time.Time{}, speedWin: newSpeedWindow()}
}

// registerPUT 在 PUT 开始时登记一条在途传输（connKey = 连接 RemoteAddr 字符串）
func (t *liveTracker) registerPUT(connKey, relPath string, contentLength int64) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.byConn[connKey] = &liveTransfer{Path: relPath, Total: contentLength}
	t.lastSeen[connKey] = time.Now()
}

// feedPUT 逐包计入已收字节（放在 davHandler 的写循环里）
func (t *liveTracker) feedPUT(connKey string, n int) {
	if n <= 0 {
		return
	}
	t.speedWin.add(int64(n))
	t.mu.Lock()
	tf, ok := t.byConn[connKey]
	if ok {
		tf.Recv += int64(n)
	}
	t.lastSeen[connKey] = time.Now()
	t.mu.Unlock()
}

// finishPUT 结束一条在途传输，记入 session 累计
func (t *liveTracker) finishPUT(connKey string, success bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if success {
		if tf, ok := t.byConn[connKey]; ok {
			t.sessionBytes += tf.Recv
			t.sessionFiles++
		}
	}
	delete(t.byConn, connKey)
	delete(t.lastSeen, connKey)
}

// snapshot 返回当前 UI 数据（拷贝后编码）
func (t *liveTracker) snapshot() map[string]any {
	t.mu.Lock()
	active := make([]*liveTransfer, 0, len(t.byConn))
	var cur *liveTransfer
	for k, tf := range t.byConn {
		if last, ok := t.lastSeen[k]; !ok || time.Since(last) >= 30*time.Second {
			continue
		}
		active = append(active, tf)
		if tf.Recv > 0 && tf.Path != "" && (cur == nil || tf.Recv > cur.Recv) {
			cur = tf
		}
	}
	bytes, files := t.sessionBytes, t.sessionFiles
	t.mu.Unlock()

	sort.Slice(active, func(i, j int) bool { return active[i].Path < active[j].Path })

	curOut := map[string]any{"path": "", "recv": 0, "total": 0, "pct": 0}
	if cur != nil {
		pct := 0
		if cur.Total > 0 {
			pct = int(cur.Recv * 100 / cur.Total)
		}
		curOut = map[string]any{"path": cur.Path, "recv": cur.Recv, "total": cur.Total, "pct": pct}
	}
	return map[string]any{
		"speedMBps": t.speedWin.mbps(),
		"session":   map[string]any{"bytes": bytes, "files": files},
		"active":    active,
		"current":   curOut,
	}
}

// ---------- 传输日志（环形） ----------

const logMax = 300

var (
	logMu  sync.Mutex
	logBuf []string
)

func logf(format string, a ...any) {
	line := time.Now().Format("15:04:05 ") + fmt.Sprintf(format, a...)
	logMu.Lock()
	logBuf = append(logBuf, line)
	if len(logBuf) > logMax {
		logBuf = logBuf[len(logBuf)-logMax:]
	}
	logMu.Unlock()
	fmt.Println(line)
}

// ---------- WebDAV 子集 ----------

var davFS struct {
	mu   sync.RWMutex
	root string // 绝对路径；空串表示未设置
}

func davRoot() string {
	davFS.mu.RLock()
	defer davFS.mu.RUnlock()
	return davFS.root
}

// safeJoin 把 WebDAV 路径映射到本地文件系统，拒绝越界（.. / 绝对路径 / 盘符）
func safeJoin(rel string) (string, error) {
	rel = strings.ReplaceAll(rel, "\\", "/")
	rel = strings.TrimPrefix(rel, "/")
	clean := path.Clean("/" + rel) // 前导 / 使 .. 无法越过根
	clean = strings.TrimPrefix(clean, "/")
	if clean == "." || clean == "" {
		return davRoot(), nil
	}
	for _, seg := range strings.Split(clean, "/") {
		if seg == ".." || seg == "." || seg == "" {
			return "", fmt.Errorf("bad path")
		}
		if strings.HasSuffix(seg, ":") { // 盘符
			return "", fmt.Errorf("bad path")
		}
	}
	return filepath.Join(davRoot(), filepath.FromSlash(clean)), nil
}

// hrefEscape 生成 href：URL 路径转义（空格→%20 等）+ XML 转义（& → &amp;）
func hrefEscape(rel string) string {
	parts := strings.Split(strings.ReplaceAll(rel, "\\", "/"), "/")
	for i, p := range parts {
		parts[i] = url.PathEscape(p)
	}
	h := "/dav/" + strings.TrimPrefix(path.Clean("/"+strings.Join(parts, "/")), "/")
	h = strings.ReplaceAll(h, "&", "&amp;")
	h = strings.ReplaceAll(h, "<", "&lt;")
	h = strings.ReplaceAll(h, ">", "&gt;")
	return h
}

func xmlEscape(s string) string {
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	return s
}

// propfindXML 生成 Multi-Status。首条恒为目录自身（客户端无条件跳过首条）。
// 仅输出客户端正则实际解析的三类节点：href / getcontentlength / collection。
func propfindXML(rel string, depth string) string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="utf-8"?>` + "\n")
	b.WriteString(`<D:multistatus xmlns:D="DAV:">` + "\n")

	writeEntry := func(relPath string, isDir bool, size int64) {
		b.WriteString("<D:response>\n")
		fmt.Fprintf(&b, "<D:href>%s</D:href>\n", hrefEscape(relPath))
		b.WriteString("<D:propstat><D:prop>\n")
		if isDir {
			b.WriteString("<D:resourcetype><D:collection/></D:resourcetype>\n")
		} else {
			b.WriteString("<D:resourcetype/>\n")
			fmt.Fprintf(&b, "<D:getcontentlength>%d</D:getcontentlength>\n", size)
		}
		b.WriteString("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>\n")
		b.WriteString("</D:response>\n")
	}

	writeEntry(rel, true, 0) // 目录自身

	if depth != "0" {
		if local, err := safeJoin(rel); err == nil {
			if entries, err2 := os.ReadDir(local); err2 == nil {
				sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
				base := strings.TrimSuffix("/"+strings.Trim(rel, "/"), "/")
				for _, e := range entries {
					child := base + "/" + e.Name()
					if e.IsDir() {
						writeEntry(child, true, 0)
					} else {
						var size int64
						if info, err := e.Info(); err == nil {
							size = info.Size()
						}
						writeEntry(child, false, size)
					}
				}
			}
		}
	}

	b.WriteString("</D:multistatus>")
	return b.String()
}

func davAuth(w http.ResponseWriter, r *http.Request, c *Config) bool {
	user, pass, ok := r.BasicAuth()
	if !ok ||
		subtle.ConstantTimeCompare([]byte(user), []byte(c.User)) != 1 ||
		subtle.ConstantTimeCompare([]byte(pass), []byte(c.Pass)) != 1 {
		w.Header().Set("WWW-Authenticate", `Basic realm="mibackpc", charset="UTF-8"`)
		http.Error(w, "401 Unauthorized", http.StatusUnauthorized)
		return false
	}
	return true
}

// countingWriter 记录传输字节数与速度；带 connKey 时同步上报实时统计（GET 方向）
type countingWriter struct {
	http.ResponseWriter
	n       int64
	connKey string // 非空时逐块喂入 live tracker（恢复/下载方向）
}

func (cw *countingWriter) Write(p []byte) (int, error) {
	n, err := cw.ResponseWriter.Write(p)
	cw.n += int64(n)
	if cw.connKey != "" {
		live.feedPUT(cw.connKey, n)
	}
	return n, err
}

func speedOf(n int64, d time.Duration) string {
	if d <= 0 {
		d = time.Millisecond
	}
	mbps := float64(n) / d.Seconds() / 1024 / 1024
	if n < 1024*1024 {
		return fmt.Sprintf("%.0f KB/s", float64(n)/d.Seconds()/1024)
	}
	return fmt.Sprintf("%.1f MB/s", mbps)
}

func human(n int64) string {
	const kb, mb, gb = 1024, 1024 * 1024, 1024 * 1024 * 1024
	switch {
	case n >= gb:
		return fmt.Sprintf("%.2f GB", float64(n)/gb)
	case n >= mb:
		return fmt.Sprintf("%.1f MB", float64(n)/mb)
	case n >= kb:
		return fmt.Sprintf("%.1f KB", float64(n)/kb)
	default:
		return fmt.Sprintf("%d B", n)
	}
}

// copyReporting 按块拷贝并在每块写入后上报实时统计（PUT 用；io.Copy 拿不到逐块字节）
func copyReporting(dst io.Writer, src io.Reader, connKey string) (int64, error) {
	var total int64
	buf := make([]byte, 256*1024)
	for {
		n, rerr := src.Read(buf)
		if n > 0 {
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return total, werr
			}
			total += int64(n)
			live.feedPUT(connKey, n)
		}
		if rerr == io.EOF {
			return total, nil
		}
		if rerr != nil {
			return total, rerr
		}
	}
}

// davHandler 实现 PROPFIND / MKCOL / PUT / GET / HEAD / DELETE / OPTIONS
func davHandler(w http.ResponseWriter, r *http.Request, c *Config) {
	if !davAuth(w, r, c) {
		return
	}
	if davRoot() == "" {
		http.Error(w, "backup root not set", http.StatusPreconditionFailed)
		return
	}

	rel := strings.TrimPrefix(r.URL.Path, "/dav")
	rel = strings.TrimSuffix(rel, "/")
	if rel == "" {
		rel = "/"
	}

	start := time.Now()
	switch r.Method {
	case "OPTIONS":
		w.Header().Set("DAV", "1, 2")
		w.Header().Set("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL")
		w.WriteHeader(http.StatusOK)

	case "PROPFIND":
		if _, err := safeJoin(rel); err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		local, err := safeJoin(rel)
		if err != nil || !dirExists(local) {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		depth := r.Header.Get("Depth")
		if depth == "" {
			depth = "1"
		}
		w.Header().Set("Content-Type", `application/xml; charset=utf-8`)
		w.WriteHeader(http.StatusMultiStatus) // 客户端 testConnection 以 207 判定连通
		_, _ = io.WriteString(w, propfindXML(rel, depth))

	case "MKCOL":
		local, err := safeJoin(rel)
		if err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if pathExists(local) {
			w.WriteHeader(http.StatusMethodNotAllowed) // 客户端 mkdir 逐级吞掉该错误
			return
		}
		if err := os.MkdirAll(local, 0o755); err != nil {
			http.Error(w, "409 Conflict", http.StatusConflict)
			return
		}
		logf("MKCOL %s", rel)
		w.WriteHeader(http.StatusCreated)

	case "PUT":
		local, err := safeJoin(rel)
		if err != nil || strings.HasSuffix(rel, "/") {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if err := os.MkdirAll(filepath.Dir(local), 0o755); err != nil {
			http.Error(w, "409 Conflict", http.StatusConflict)
			return
		}
		// 临时文件 + rename：断连/中途失败不落半截文件（分片重试才不会把坏分片当有效数据）
		tmp := local + ".mibackpart"
		f, err := os.Create(tmp)
		if err != nil {
			http.Error(w, "403 Forbidden", http.StatusForbidden)
			return
		}
		// 逐块写入并上报实时统计（/api/live：速度 + 当前文件进度）
		connKey := r.RemoteAddr
		live.registerPUT(connKey, rel, r.ContentLength)
		n, copyErr := copyReporting(f, r.Body, connKey)
		closeErr := f.Close()
		if copyErr == nil {
			copyErr = closeErr
		}
		if copyErr != nil {
			live.finishPUT(connKey, false)
			_ = os.Remove(tmp)
			logf("PUT  FAIL %s (%s)", rel, copyErr)
			http.Error(w, "500 write failed", http.StatusInternalServerError)
			return
		}
		if err := os.Rename(tmp, local); err != nil {
			_ = os.Remove(tmp)
			live.finishPUT(connKey, false)
			logf("PUT  FAIL %s (%s)", rel, err)
			http.Error(w, "500 rename failed", http.StatusInternalServerError)
			return
		}
		live.finishPUT(connKey, true)
		logf("PUT  OK   %s  %s  %s", rel, human(n), speedOf(n, time.Since(start)))
		w.WriteHeader(http.StatusCreated)

	case "GET", "HEAD":
		local, err := safeJoin(rel)
		if err != nil || dirExists(local) {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		f, err := os.Open(local)
		if err != nil {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil {
			http.Error(w, "404 Not Found", http.StatusNotFound)
			return
		}
		cw := &countingWriter{ResponseWriter: w}
		// 下载（恢复方向）也上报实时统计：登记 + 每写一块 feed（countingWriter.connKey）
		if r.Method == "GET" {
			cw.connKey = r.RemoteAddr
			live.registerPUT(cw.connKey, rel, st.Size())
		}
		http.ServeContent(cw, r, st.Name(), st.ModTime(), f)
		if r.Method == "GET" {
			live.finishPUT(cw.connKey, true)
			logf("GET  OK   %s  %s  %s", rel, human(cw.n), speedOf(cw.n, time.Since(start)))
		}

	case "DELETE":
		local, err := safeJoin(rel)
		if err != nil || local == davRoot() {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if !pathExists(local) {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		if err := os.RemoveAll(local); err != nil { // RFC4918：DELETE 天然递归
			http.Error(w, "500 delete failed", http.StatusInternalServerError)
			return
		}
		logf("DEL  %s", rel)
		w.WriteHeader(http.StatusNoContent)

	default:
		w.Header().Set("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL")
		http.Error(w, "405 Method Not Allowed", http.StatusMethodNotAllowed)
	}
}

func pathExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func dirExists(p string) bool {
	st, err := os.Stat(p)
	return err == nil && st.IsDir()
}

// ---------- 自动发现（UDP 广播应答） ----------

// discoverMagic 手机端探测码（与 PcDiscovery.MAGIC 逐字对齐）
const discoverMagic = "MIBACKPC_DISCOVER_V1"

// discoverPort 是变量而非常量：单元测试要换端口跑，避免与正在运行的服务抢 8322
var discoverPort = 8322

func hostName() string {
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return "PC"
}

func discoverPayload(c *Config) []byte {
	// lan / lans：手机拿到后存为 pc_lan_addr / pc_lan_addrs，下次 UDP 单播探测
	// 可绕过 AP 隔离；lans 是全部候选（物理网卡优先），手机逐个试，虚拟网卡排最后。
	ips := lanIPs()
	lan := ""
	if len(ips) > 0 {
		lan = ips[0]
	}
	b, _ := json.Marshal(map[string]any{
		"service": "mibackpc",
		"name":    hostName(),
		"tcp":     c.Port,
		"ver":     2,
		"lan":     lan,
		"lans":    ips,
	})
	return b
}

// ---------- 发现服务运行时状态（控制页展示 + 看守判断） ----------

var discovery struct {
	mu        sync.Mutex
	conn      *net.UDPConn
	ready     bool
	lastProbe time.Time // 最后一次「真实」（非回环）探测；回环自检不计入
	peer      string
	probes    int64
	rebuilds  int
	lastErr   string
}

func discoveryInfo() (ready bool, last time.Time, peer string, probes int64, rebuilds int, lastErr string) {
	discovery.mu.Lock()
	defer discovery.mu.Unlock()
	return discovery.ready, discovery.lastProbe, discovery.peer, discovery.probes, discovery.rebuilds, discovery.lastErr
}

// discoveryNoteProbe 只登记非回环探测：回环探测来自本机自检，若计入
// lastProbe，看守会永远认为「刚有探测」，从而失去自检意义。
func discoveryNoteProbe(ip string) {
	discovery.mu.Lock()
	discovery.lastProbe = time.Now()
	discovery.peer = ip
	discovery.probes++
	discovery.mu.Unlock()
}

func discoveryCurrent() *net.UDPConn {
	discovery.mu.Lock()
	defer discovery.mu.Unlock()
	return discovery.conn
}

// discoveryStatText 控制页展示用文案：能直接看出「有没有手机探测到达本机」
func discoveryStatText(ready bool, last time.Time, peer string, probes int64) string {
	if !ready {
		return "未就绪"
	}
	if probes == 0 || last.IsZero() {
		return "已就绪 · 尚未收到探测"
	}
	return fmt.Sprintf("已就绪 · 最近探测 %s（%s）", humanAgo(time.Since(last)), peer)
}

func humanAgo(d time.Duration) string {
	switch {
	case d < time.Second:
		return "刚刚"
	case d < time.Minute:
		return fmt.Sprintf("%d 秒前", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%d 分钟前", int(d.Minutes()))
	default:
		return fmt.Sprintf("%d 小时前", int(d.Hours()))
	}
}

// ---------- 自动发现（UDP 广播应答）· 单 socket + 看守重建 ----------

const (
	discoveryHealthEvery = 5 * time.Second
	discoverySelfTimeout = 800 * time.Millisecond
	discoverySelfFailMax = 3
	discoveryIdleFor     = 90 * time.Second // 超过这么久没有真实探测，才允许因自检失败重建
)

// openDiscovery 以**独占**方式绑定发现端口。
//
// 不再退回 SO_REUSEADDR：同端口多 socket 正是本故障的根因（Windows 对投递
// 语义的定义是 indeterminate），静默共享比明确失败更难排查——实测双击
// dist 里的旧实例后，新实例的兜底共享绑定会让 8322 上出现 3 只 socket，
// 把"发现通道失聪"原样复现。端口真被占用就让监督器退避重试并告警。
func openDiscovery() (*net.UDPConn, error) {
	conn, err := listenUDPExclusive(discoverPort)
	if err != nil {
		if isAddrInUse(err) {
			return nil, fmt.Errorf("UDP %d 已被占用（很可能有另一个 mibackpc 在运行，请只保留一个实例）：%v",
				discoverPort, err)
		}
		return nil, err
	}
	return conn, nil
}

// serveDiscovery 监督发现循环：绑定失败或读循环退出都会自动重建（指数退避封顶 30s）。
//
// 原实现把「应答 socket」和「监听 socket」都绑到 0.0.0.0:8322（SO_REUSEADDR），
// 读循环只读后绑的那只。Windows 对同端口多 socket 的数据报投递是未定义的
// （MSDN：the behavior for all sockets bound to that port is indeterminate），
// 实测会出现读循环那只被饿死 → 发现通道永久失聪（Socket 仍在绑定态、无日志）。
// 改为一只 socket 既收又发：应答源端口天然是 8322，与手机侧语义完全一致。
func serveDiscovery(c *Config) {
	backoff := time.Second
	for {
		conn, err := openDiscovery()
		if err != nil {
			discovery.mu.Lock()
			discovery.ready, discovery.lastErr = false, err.Error()
			discovery.mu.Unlock()
			logf("自动发现(UDP %d)绑定失败：%v；%s 后重试", discoverPort, err, backoff)
			time.Sleep(backoff)
			backoff = nextBackoff(backoff, 30*time.Second)
			continue
		}
		discovery.mu.Lock()
		discovery.conn, discovery.ready, discovery.lastErr = conn, true, ""
		discovery.mu.Unlock()
		logf("自动发现已就绪（UDP %d，应答源端口 %d，单 socket 收发）",
			discoverPort, conn.LocalAddr().(*net.UDPAddr).Port)

		started := time.Now()
		rerr := discoveryLoop(conn, c)
		_ = conn.Close()

		discovery.mu.Lock()
		if discovery.conn == conn {
			discovery.conn, discovery.ready = nil, false
		}
		discovery.lastErr = rerr.Error()
		discovery.rebuilds++
		rebuilds := discovery.rebuilds
		discovery.mu.Unlock()

		if time.Since(started) > time.Minute {
			backoff = time.Second // 稳定运行过一段时间，重置退避
		}
		logf("自动发现循环退出（%v），%s 后重建（累计第 %d 次）", rerr, backoff, rebuilds)
		time.Sleep(backoff)
		backoff = nextBackoff(backoff, 30*time.Second)
	}
}

// discoveryLoop 单 socket 收发循环。只有致命错误才退出（交由 serveDiscovery 重建），
// 瞬时错误就地 continue——Windows 下 UDP 收到 ICMP port unreachable 会让读返回
// WSAECONNRESET，属常见瞬时错误；原实现一律 return，手机一次「发完就退」的探测
// 就足以把发现服务打死。
func discoveryLoop(conn *net.UDPConn, c *Config) error {
	buf := make([]byte, 256)
	bad := 0
	for {
		n, raddr, rerr := conn.ReadFromUDP(buf)
		if rerr != nil {
			if errors.Is(rerr, net.ErrClosed) {
				return rerr
			}
			if isTransientNetErr(rerr) {
				continue
			}
			return rerr
		}
		loopback := raddr.IP.IsLoopback()
		if !loopback {
			discoveryNoteProbe(raddr.IP.String())
		}
		if strings.TrimSpace(string(buf[:n])) != discoverMagic {
			bad++
			// 回环自检包不记日志；其余限速记录（前 3 条 + 每 100 条一次），
			// 便于区分「没收到」与「收到但格式不符」——原实现此处静默丢弃。
			if !loopback && (bad <= 3 || bad%100 == 0) {
				logf("发现端口收到非探测包（%s，%d 字节，累计 %d 个），已忽略", raddr.IP, n, bad)
			}
			continue
		}
		if !loopback {
			logf("收到发现探测: %s", raddr.IP)
		}
		if _, werr := conn.WriteToUDP(discoverPayload(c), raddr); werr != nil {
			logf("发现应答发送失败（%s）：%v", raddr.IP, werr)
			if isTransientNetErr(werr) {
				continue
			}
			return werr
		}
	}
}

// startDiscoveryKeeper 局域网发现通道的看守（原实现完全没有的那一环）：
// 每 5s 从本机回环打一发探测码，连续 3 次收不到应答即判定自己的 socket 失聪，
// 主动 Close 触发 serveDiscovery 重新绑定。
// 回环流量不受 Windows 防火墙影响，因此该自检能精确定位「自己的 socket 收不到包」。
func startDiscoveryKeeper() {
	go func() {
		fails, strikes := 0, 0
		interval := discoveryHealthEvery
		for {
			time.Sleep(interval)
			conn := discoveryCurrent()
			if conn == nil {
				continue // 未就绪，交给 serveDiscovery 的退避重试
			}
			if discoverySelfCheck() {
				if fails >= discoverySelfFailMax {
					logf("自动发现自检已恢复正常")
				}
				fails, strikes, interval = 0, 0, discoveryHealthEvery
				continue
			}
			fails++
			_, last, _, _, _, _ := discoveryInfo()
			if !last.IsZero() && time.Since(last) < discoveryIdleFor {
				fails = 0 // 近期仍有真实探测进来，socket 是活的，自检失败当环境噪声处理
				continue
			}
			if fails < discoverySelfFailMax {
				continue
			}
			if strikes >= 3 {
				// 连续重建仍自检失败：停止频繁重建，避免打转（如本机回环被第三方 LSP 劫持）
				logf("自动发现自检持续失败，暂停自动重建（服务保留）；请检查是否有其他程序占用 UDP %d", discoverPort)
				interval = time.Minute
				continue
			}
			strikes++
			fails = 0
			logf("自动发现自检失败 %d 次（回环探测无应答），重建 UDP %d 监听", discoverySelfFailMax, discoverPort)
			_ = conn.Close() // 读循环随之返回 → serveDiscovery 重绑
		}
	}()
}

// discoverySelfCheck 从本机回环发探测码并等应答，验证发现 socket 是否还能收包
func discoverySelfCheck() bool {
	probe, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return false
	}
	defer probe.Close()
	dst := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: discoverPort}
	if _, err := probe.WriteToUDP([]byte(discoverMagic), dst); err != nil {
		return false
	}
	_ = probe.SetReadDeadline(time.Now().Add(discoverySelfTimeout))
	buf := make([]byte, 512)
	for {
		n, _, rerr := probe.ReadFromUDP(buf)
		if rerr != nil {
			return false
		}
		if strings.Contains(string(buf[:n]), `"mibackpc"`) {
			return true
		}
	}
}

// isTransientNetErr 判断 UDP 读写错误是瞬时（可 continue）还是致命（需重建）。
// 瞬时：Windows 收到 ICMP port unreachable → WSAECONNRESET；Linux 同类 → ECONNREFUSED；
// 以及各类超时。
func isTransientNetErr(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, syscall.ECONNRESET) || errors.Is(err, syscall.ECONNREFUSED) {
		return true
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return true
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "connection reset") || strings.Contains(s, "forcibly closed") ||
		strings.Contains(s, "connection refused") || strings.Contains(s, "timed out")
}

// nextBackoff 指数退避（×2，封顶 max）
func nextBackoff(cur, max time.Duration) time.Duration {
	next := cur * 2
	if next > max {
		next = max
	}
	return next
}

// ---------- 手机配对（请求 → 电脑端确认 → 下发凭据） ----------

const pairTTL = 5 * time.Minute

type pairReq struct {
	ID      string    `json:"id"`
	Device  string    `json:"device"`
	IP      string    `json:"ip"`
	Created time.Time `json:"created"`
	Status  string    `json:"status"` // pending / approved / denied
}

var pairStore struct {
	mu      sync.Mutex
	current *pairReq // 同时只保留一个请求；新请求顶替旧的（旧弹窗选择按 ID 作废）
}

// pairDecide 电脑端落定结果。仅当 id 仍是当前 pending 时生效；approved 保留在
// current 等手机取件，denied 保留供结果端点返回后清掉。
func pairDecide(c *Config, id string, approve bool) bool {
	pairStore.mu.Lock()
	defer pairStore.mu.Unlock()
	req := pairStore.current
	if req == nil || req.ID != id || req.Status != "pending" {
		return false
	}
	if approve {
		req.Status = "approved"
		c.recordPairedDevice(req.Device)
		logf("已允许手机「%s」（%s）连接", req.Device, req.IP)
	} else {
		req.Status = "denied"
		logf("已拒绝手机「%s」（%s）连接", req.Device, req.IP)
	}
	return true
}

func pairView(req *pairReq) map[string]any {
	if req == nil || req.Status != "pending" {
		return nil
	}
	return map[string]any{
		"device": req.Device,
		"ip":     req.IP,
		"age":    int(time.Since(req.Created).Seconds()),
	}
}

// pairHandler 面向局域网手机：POST /pair/requests 发起请求，GET …/result 轮询结果。
// 结果仅下发给发起请求的同一 IP，凭据取走即焚。
func pairHandler(w http.ResponseWriter, r *http.Request, c *Config) {
	callerIP, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil || net.ParseIP(callerIP) == nil {
		http.Error(w, "bad client addr", http.StatusBadRequest)
		return
	}

	switch {
	case r.URL.Path == "/pair/requests" && r.Method == "POST":
		var req struct {
			Device string `json:"device"`
			ReqID  string `json:"reqId"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		req.Device = strings.TrimSpace(req.Device)
		req.ReqID = strings.TrimSpace(req.ReqID)
		if req.Device == "" || len([]rune(req.Device)) > 64 || len(req.ReqID) < 16 || len(req.ReqID) > 64 {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		pending := &pairReq{
			ID:      req.ReqID,
			Device:  req.Device,
			IP:      callerIP,
			Created: time.Now(),
			Status:  "pending",
		}
		pairStore.mu.Lock()
		pairStore.current = pending
		pairStore.mu.Unlock()
		logf("收到手机「%s」连接请求（%s）", pending.Device, callerIP)
		pairPopup(pending.Device, func(ok bool) { pairDecide(c, pending.ID, ok) })
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"status": "pending"})

	case strings.HasPrefix(r.URL.Path, "/pair/requests/") && strings.HasSuffix(r.URL.Path, "/result") && r.Method == "GET":
		id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/pair/requests/"), "/result")
		pairStore.mu.Lock()
		req := pairStore.current
		var resp map[string]any
		switch {
		case req == nil || req.ID != id:
			resp = map[string]any{"status": "expired"}
		case time.Since(req.Created) > pairTTL:
			resp = map[string]any{"status": "expired"}
			pairStore.current = nil
		case req.IP != callerIP:
			resp = map[string]any{"status": "forbidden"}
		case req.Status == "pending":
			resp = map[string]any{"status": "pending"}
		case req.Status == "approved":
			resp = map[string]any{
				"status": "approved",
				"name":   hostName(),
				"user":   c.User,
				"pass":   c.Pass,
				"port":   c.Port,
			}
			pairStore.current = nil // 凭据取走即焚
		default: // denied
			resp = map[string]any{"status": "denied"}
			pairStore.current = nil
		}
		pairStore.mu.Unlock()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(resp)

	default:
		http.NotFound(w, r)
	}
}

// ---------- 控制面（仅本机访问） ----------

// virtualIfKeywords 虚拟/隧道类网卡名关键字（小写）。这些网卡的私有地址通常无法被
// 手机访问（VMware/VirtualBox/Hyper-V 宿主虚拟网段、WSL/Docker/Tailscale 隧道），
// 一旦被当作「局域网地址」下发给手机，手机据此探测必然失败。
var virtualIfKeywords = []string{
	"vmware", "vmnet", "virtualbox", "vbox", "hyper-v", "vethernet", "wsl",
	"docker", "veth", "br-", "tap-", "tun", "utun", "npcap", "loopback",
	"bluetooth", "zerotier", "tailscale", "hamachi", "openvpn", "wireguard",
	"radmin", "ppp", "virtual", "pseudo",
}

func ifIsVirtual(name string) bool {
	n := strings.ToLower(name)
	for _, k := range virtualIfKeywords {
		if strings.Contains(n, k) {
			return true
		}
	}
	return false
}

// lanIPRank 越小越优先：物理网卡 + 家用/办公常见网段（192.168.x）排最前；
// 虚拟网卡统一沉底（仍保留在候选列表里，手机可逐个尝试）。
func lanIPRank(ifName string, ip net.IP) int {
	rank := 0
	if ifIsVirtual(ifName) {
		rank += 100
	}
	switch {
	case ip[0] == 192 && ip[1] == 168:
		// 最优：家用/办公局域网
	case ip[0] == 10:
		rank += 5
	default: // 172.16/12 等
		rank += 15
	}
	return rank
}

// lanIPs 返回可下发给手机的局域网地址候选，物理网卡优先（原实现只按字符串排序，
// 可能把 VMware/Hyper-V 虚拟网段排在真实局域网地址之前）。
func lanIPs() []string {
	type cand struct {
		ip   string
		rank int
	}
	var cands []cand
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	for _, ia := range ifaces {
		if ia.Flags&net.FlagUp == 0 || ia.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ia.Addrs()
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipnet.IP.To4()
			if ip4 == nil || !ip4.IsPrivate() || ip4.IsLinkLocalUnicast() {
				continue
			}
			cands = append(cands, cand{ip4.String(), lanIPRank(ia.Name, ip4)})
		}
	}
	sort.SliceStable(cands, func(i, j int) bool {
		if cands[i].rank != cands[j].rank {
			return cands[i].rank < cands[j].rank
		}
		return cands[i].ip < cands[j].ip
	})
	seen := make(map[string]bool, len(cands))
	out := make([]string, 0, len(cands))
	for _, c := range cands {
		if seen[c.ip] {
			continue
		}
		seen[c.ip] = true
		out = append(out, c.ip)
	}
	return out
}

func isLoopback(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func controlHandler(w http.ResponseWriter, r *http.Request, c *Config) {
	// 控制面只允许本机：局域网设备只应访问 /dav/*
	if !isLoopback(r) {
		http.Error(w, "control interface is local-only", http.StatusForbidden)
		return
	}

	switch {
	case r.URL.Path == "/api/state" && r.Method == "GET":
		pairStore.mu.Lock()
		pending := pairView(pairStore.current)
		pairStore.mu.Unlock()
		adb := adbGet()
		dReady, dLast, dPeer, dProbes, dRebuilds, dErr := discoveryInfo()
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"root":             davRoot(),
			"port":             c.Port,
			"user":             c.User,
			"pass":             c.Pass,
			"lanIPs":           lanIPs(),
			"adbURL":           fmt.Sprintf("http://127.0.0.1:%d/dav/", c.Port),
			"adbOK":            adb.OK,
			"adbReason":        adbReasonText(adb),
			"name":             hostName(),
			"pairPending":      pending,
			"pairedDevices":    c.Pair,
			"discoverPort":     discoverPort,
			"discoverReady":    dReady,
			"discoverStat":     discoveryStatText(dReady, dLast, dPeer, dProbes),
			"discoverRebuilds": dRebuilds,
			"discoverErr":      dErr,
		})

	case r.URL.Path == "/api/pair/decide" && r.Method == "POST":
		var req struct {
			Approve bool `json:"approve"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		pairStore.mu.Lock()
		var id string
		if pairStore.current != nil {
			id = pairStore.current.ID
		}
		pairStore.mu.Unlock()
		ok := id != "" && pairDecide(c, id, req.Approve)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": ok})

	case r.URL.Path == "/api/root" && r.Method == "POST":
		var req struct {
			Root string `json:"root"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		req.Root = strings.TrimSpace(req.Root)
		if req.Root == "" {
			http.Error(w, "empty root", http.StatusBadRequest)
			return
		}
		abs, err := filepath.Abs(req.Root)
		if err != nil {
			http.Error(w, "bad root", http.StatusBadRequest)
			return
		}
		if err := os.MkdirAll(abs, 0o755); err != nil {
			http.Error(w, "cannot create dir: "+err.Error(), http.StatusBadRequest)
			return
		}
		davFS.mu.Lock()
		davFS.root = abs
		davFS.mu.Unlock()
		c.Root = abs
		c.save()
		logf("备份目录设置为 %s", abs)
		w.WriteHeader(http.StatusNoContent)

	case r.URL.Path == "/api/cred" && r.Method == "POST":
		var req struct {
			User string `json:"user"`
			Pass string `json:"pass"`
		}
		_ = json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req)
		req.User = strings.TrimSpace(req.User)
		if req.User == "" || len(req.Pass) < 4 {
			http.Error(w, "user 不能为空，pass 至少 4 位", http.StatusBadRequest)
			return
		}
		c.User, c.Pass = req.User, req.Pass
		c.save()
		logf("WebDAV 账号已更新为 %s", req.User)
		w.WriteHeader(http.StatusNoContent)

	case r.URL.Path == "/api/live" && r.Method == "GET":
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(live.snapshot())

	case r.URL.Path == "/api/log" && r.Method == "GET":
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		logMu.Lock()
		defer logMu.Unlock()
		cp := make([]string, len(logBuf))
		copy(cp, logBuf)
		_ = json.NewEncoder(w).Encode(map[string]any{"lines": cp})

	case r.URL.Path == "/api/adb" && r.Method == "POST":
		ok := adbForce(c.Port)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": ok})

	case r.URL.Path == "/" || r.URL.Path == "/index.html":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, indexHTML)

	default:
		http.NotFound(w, r)
	}
}

// ---------- USB 通道（adb reverse）· 后台探测 + 硬超时 + 指数退避 ----------
//
// 原实现把「spawn adb 子进程」放在全局 adbState.mu 里，且 CombinedOutput 无超时：
// adb server 一旦卡住，1.2s 轮询 /api/state 的请求会全部串行堵在同一把锁上，
// 控制页连带卡死。现在改为后台协程独自探测并缓存结果，
// /api/state 只读缓存、永不 spawn 子进程。

var errAdbMissing = errors.New("adb not found")

// USB 通道后台探测的退避区间（见 startAdbMonitor 注释里的理由）
const (
	adbProbeMinInterval = 15 * time.Second
	adbProbeMaxInterval = 60 * time.Second
)

type adbSnapshot struct {
	OK      bool
	Reason  string // 人类可读原因（无 adb / 无设备 / 未授权 / 未建立）
	Checked time.Time
}

var adbState struct {
	mu   sync.Mutex
	snap adbSnapshot
}

func adbGet() adbSnapshot {
	adbState.mu.Lock()
	defer adbState.mu.Unlock()
	return adbState.snap
}

func adbPut(s adbSnapshot) {
	adbState.mu.Lock()
	adbState.snap = s
	adbState.mu.Unlock()
}

// adbRun 执行 adb 子进程（带硬超时，绝不无限等待）
func adbRun(timeout time.Duration, args ...string) (string, error) {
	bin := ""
	for _, exe := range []string{"adb", "adb.exe"} {
		if p, err := exec.LookPath(exe); err == nil {
			bin = p
			break
		}
	}
	if bin == "" {
		return "", errAdbMissing
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	out, err := exec.CommandContext(ctx, bin, args...).CombinedOutput()
	if ctx.Err() != nil {
		return string(out), fmt.Errorf("adb 响应超时（%s）", timeout)
	}
	return string(out), err
}

// adbProbe 只读探测 USB 通道（不发 "reverse" 子命令）并给出可读原因
func adbProbe(port int) adbSnapshot {
	s := adbSnapshot{Checked: time.Now()}
	out, err := adbRun(3*time.Second, "reverse", "--list")
	if err != nil {
		if errors.Is(err, errAdbMissing) {
			s.Reason = "未找到 adb（未安装或不在 PATH）"
		} else {
			s.Reason = adbErrReason(out, err)
		}
		return s
	}
	needle := fmt.Sprintf("tcp:%d", port)
	for _, line := range strings.Split(out, "\n") {
		if strings.Contains(line, needle) {
			s.OK = true
			s.Reason = fmt.Sprintf("已建立 (%d)", port)
			return s
		}
	}
	if r := adbErrReason(out, nil); strings.Contains(r, "设备") || strings.Contains(r, "授权") {
		s.Reason = r
		return s
	}
	s.Reason = "USB 通道未建立"
	return s
}

// adbErrReason 把 adb 输出归纳成可读原因（区分「无设备」与「adb 本身故障」）
func adbErrReason(out string, err error) string {
	msg := strings.TrimSpace(out)
	low := strings.ToLower(msg)
	switch {
	case strings.Contains(low, "no devices") || strings.Contains(low, "device not found") || strings.Contains(low, "no device"):
		return "未检测到设备（USB 未连接或手机未授权调试）"
	case strings.Contains(low, "unauthorized"):
		return "设备未授权（请在手机上确认 USB 调试申请）"
	case strings.Contains(low, "cannot connect") || strings.Contains(low, "server"):
		return "adb server 异常：" + firstLine(msg)
	}
	if msg != "" {
		return "adb 执行失败：" + firstLine(msg)
	}
	if err != nil {
		return err.Error()
	}
	return "USB 通道未建立"
}

func firstLine(s string) string {
	if i := strings.IndexAny(s, "\r\n"); i >= 0 {
		s = s[:i]
	}
	return strings.TrimSpace(s)
}

// startAdbMonitor USB 通道后台轮询：15s ~ 60s 指数退避（成功即复位），状态变化才记日志。
// 控制页的 1.2s 轮询因此不再触发任何 adb 子进程。
//
// 退避上限刻意取 60s 而非更长：退避过长会让「无设备 → 插上手机并 adb reverse 后」
// 的控制页状态长时间停留在旧的「未建立」，用户会以为没生效。
func startAdbMonitor(port int) {
	go func() {
		backoff := adbProbeMinInterval
		for {
			s := adbProbe(port)
			prev := adbGet()
			if !prev.Checked.IsZero() && (prev.OK != s.OK || prev.Reason != s.Reason) {
				logf("USB 通道状态变化：%s", s.Reason)
			}
			adbPut(s)
			if s.OK {
				backoff = adbProbeMinInterval
			} else {
				backoff = nextBackoff(backoff, adbProbeMaxInterval)
			}
			time.Sleep(backoff)
		}
	}()
}

// adbForce 控制页「重连」按钮：真正执行 adb reverse 建立通道（同步，5s 硬上限）
func adbForce(port int) bool {
	local := fmt.Sprintf("tcp:%d", port)
	out, rerr := adbRun(5*time.Second, "reverse", local, local)
	s := adbProbe(port)
	adbPut(s)
	switch {
	case s.OK:
		logf("USB 通道已重新建立 (%d)", port)
	case rerr != nil:
		logf("adb reverse 建立失败：%s", adbErrReason(out, rerr))
	default:
		logf("USB 通道仍不可用：%s", s.Reason)
	}
	return s.OK
}

// adbReasonText 控制页展示用文案
func adbReasonText(s adbSnapshot) string {
	if s.Checked.IsZero() {
		return "检测中…"
	}
	return s.Reason
}

// ---------- 控制页（Apple 设计语言：系统字体栈 / 中性表面 / 单一强调色 / 明暗自适应） ----------

const indexHTML = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>mibackpc · 电脑备份接收器</title>
<style>
 :root{
  --bg:#f5f5f7; --card:#ffffff; --text:#1d1d1f; --text2:#6e6e73;
  --hair:rgba(0,0,0,.08); --field:rgba(120,120,128,.08);
  --accent:#0071e3; --accent-press:#0060c2;
  --good:#34c759; --bad:#ff3b30; --chip:#e8e8ed;
  --shadow:0 1px 2px rgba(0,0,0,.03),0 8px 24px rgba(0,0,0,.05);
 }
 @media (prefers-color-scheme:dark){
  :root{
   --bg:#000000; --card:#1c1c1e; --text:#f5f5f7; --text2:#86868b;
   --hair:rgba(255,255,255,.12); --field:rgba(120,120,128,.22);
   --accent:#0a84ff; --accent-press:#3395ff;
   --good:#30d158; --bad:#ff453a; --chip:rgba(120,120,128,.24);
   --shadow:0 1px 2px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.4);
  }
 }
 *{box-sizing:border-box}
 body{margin:0;background:var(--bg);color:var(--text);
  font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","SF Pro Text","PingFang SC","Segoe UI","Microsoft YaHei UI",sans-serif;
  -webkit-font-smoothing:antialiased;font-size:15px;line-height:1.47059}
 .wrap{max-width:640px;margin:0 auto;padding:48px 22px 64px}
 h1{font-size:32px;font-weight:700;letter-spacing:-.015em;margin:0}
 .sub{color:var(--text2);font-size:15px;margin:4px 0 0}
 header{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:28px}
 .badge{display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:600;color:var(--text2);
  background:var(--chip);border-radius:980px;padding:4px 12px;white-space:nowrap}
 .dot{width:7px;height:7px;border-radius:50%;background:var(--good);flex:none}
 .card{background:var(--card);border-radius:18px;box-shadow:var(--shadow);
  padding:20px;margin-bottom:16px}
 .card h2{font-size:17px;font-weight:600;letter-spacing:-.01em;margin:0 0 4px}
 .hint{color:var(--text2);font-size:13px;margin:0 0 14px}
 .row{display:flex;gap:10px;align-items:center;margin:10px 0}
 .row>input{flex:1}
 .row>.btn{flex:none}
 .url{display:flex;align-items:center;gap:8px;margin:10px 0;
  background:var(--field);border-radius:10px;padding:10px 14px;font-size:14px}
 .url code{font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;font-size:13px;
  word-break:break-all;flex:1;user-select:all}
 .kv{display:flex;align-items:baseline;justify-content:space-between;gap:12px;padding:10px 0}
 .kv+.kv{border-top:1px solid var(--hair)}
 .kv .k{color:var(--text2);font-size:13px;flex:none}
 .kv .v{font-size:14px;text-align:right;word-break:break-all}
 .litem{padding:10px 0}
 .litem+.litem{border-top:1px solid var(--hair)}
 .litem .bar{height:4px;background:var(--field);border-radius:2px;margin-top:6px;overflow:hidden}
 .litem .bar i{display:block;height:100%;background:var(--accent);border-radius:2px;transition:width .6s}
 .litem .lmeta{display:flex;justify-content:space-between;gap:10px;font-size:12px;color:var(--text2);font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace}
 #liveList:empty::after{content:"暂无进行中的传输";color:var(--text2);font-size:13px}
 label{display:block;font-size:13px;font-weight:600;color:var(--text2);margin:14px 0 6px}
 input{width:100%;background:var(--field);border:none;outline:none;color:var(--text);
  border-radius:10px;padding:10px 14px;font-size:15px;font-family:inherit;
  transition:box-shadow .15s}
 input:focus{box-shadow:0 0 0 3.5px color-mix(in srgb,var(--accent) 35%,transparent)}
 button{font-family:inherit;cursor:pointer;border:none;border-radius:980px;
  padding:9px 18px;font-size:14px;font-weight:600;letter-spacing:-.005em;
  background:var(--accent);color:#fff;transition:background .15s,opacity .15s,transform .1s}
 button:active{opacity:.8;transform:scale(.98)}
 button.plain{background:var(--chip);color:var(--text)}
 button.small{padding:6px 13px;font-size:13px}
 button:disabled{opacity:.45;cursor:default}
 .chip{display:inline-flex;align-items:center;gap:6px;font-size:13px;font-weight:600}
 .chip .dot{width:7px;height:7px}
 .chip.no .dot{background:var(--bad)}
 .chip.ok{color:var(--text)}
 .chip.no{color:var(--text)}
 #pairCard{border:1.5px solid var(--accent)}
 #pairCard .who{font-size:15px;font-weight:600;margin:12px 0 2px}
 #pairCard .meta{color:var(--text2);font-size:13px;margin-bottom:16px}
 .pair-actions{display:flex;gap:10px}
 .pair-actions button{flex:1;padding:11px 0}
 .pair-actions button.plain{flex:0 0 96px}
 #devices:empty::after{content:"暂无已配对设备";color:var(--text2);font-size:13px}
 .device{display:inline-flex;align-items:center;gap:7px;background:var(--chip);
  border-radius:980px;padding:5px 14px;font-size:13px;margin:4px 6px 0 0}
 #log{background:var(--field);border-radius:10px;height:200px;overflow:auto;padding:12px 14px;
  font:12px/1.6 ui-monospace,"SF Mono",Menlo,Consolas,monospace;white-space:pre-wrap;color:var(--text2)}
 #toast{position:fixed;left:50%;bottom:32px;transform:translateX(-50%) translateY(20px);
  background:rgba(29,29,31,.92);color:#fff;font-size:14px;padding:10px 22px;border-radius:980px;
  opacity:0;pointer-events:none;transition:opacity .25s,transform .25s;max-width:86%}
 #toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
 @media (prefers-color-scheme:dark){ #toast{background:rgba(245,245,247,.92);color:#1d1d1f} }
</style></head><body>
<div class="wrap">
 <header>
  <div><h1>mibackpc</h1><p class="sub">电脑备份接收器 · 局域网自动发现</p></div>
  <span class="badge"><span class="dot"></span>运行中</span>
 </header>

 <div class="card" id="pairCard" hidden>
  <h2>连接请求</h2>
  <p class="hint">一台手机请求备份到这台电脑，请确认是否允许。</p>
  <div class="who" id="pairWho">—</div>
  <div class="meta" id="pairMeta"></div>
  <div class="pair-actions">
   <button class="plain" onclick="decide(false)">拒绝</button>
   <button onclick="decide(true)">允许</button>
  </div>
 </div>

 <div class="card">
  <h2>连接</h2>
  <p class="hint">手机端在「备份」页自动发现本机并请求连接，无需手动填写地址。</p>
  <div id="lanURLs"></div>
  <div class="url"><code id="adbURL">…</code>
   <span class="chip" id="adbState"></span>
   <button class="small plain" onclick="doAdb()">重连</button></div>
  <p class="hint" id="adbReason" style="margin:8px 0 0"></p>
  <p class="hint" id="discoverState" style="margin:4px 0 0"></p>
  <p class="hint" style="margin:10px 0 0">USB 通道需要数据线连接且电脑已安装 adb；手机与电脑同一 WiFi 时走局域网。</p>
 </div>

 <div class="card" id="liveCard">
  <h2>备份实时状态</h2>
  <p class="hint">手机备份到本机的速度与进度（手机侧逐项串行备份；当前文件完成即切换）。</p>
  <div class="row" style="justify-content:space-between">
   <div>
    <div class="kv"><span class="k" style="color:var(--text2);font-size:13px">当前速度</span>
     <span class="v" id="liveSpeed" style="font-size:28px;font-weight:700;letter-spacing:-.02em">—</span></div>
   </div>
   <div style="text-align:right">
    <div class="kv"><span class="k" style="color:var(--text2);font-size:13px">本次累计</span>
     <span class="v" id="liveSession">0 B</span></div>
   </div>
  </div>
  <div id="liveList"></div>
 </div>

 <div class="card">
  <h2>设置</h2>
  <label>备份落地目录</label>
  <div class="row"><input id="root"><button class="plain" onclick="setRoot()">保存</button></div>
  <label>WebDAV 账号</label>
  <div class="row"><input id="user" autocomplete="off"><input id="pass" autocomplete="off"><button class="plain" onclick="setCred()">更新</button></div>
 </div>

 <div class="card">
  <h2>已配对设备</h2>
  <p class="hint">配对成功的手机会记录在这里（本地留存，仅展示）。</p>
  <div id="devices"></div>
 </div>

 <div class="card">
  <h2>传输日志</h2>
  <div id="log"></div>
 </div>
</div>
<div id="toast"></div>
<script>
const esc = s => (s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;");
let lastPair = "";
function toast(m){ const t=document.getElementById("toast"); t.textContent=m; t.classList.add("show");
  clearTimeout(t._h); t._h=setTimeout(()=>t.classList.remove("show"),2200); }
async function copy(text){ try{ await navigator.clipboard.writeText(text); toast("已拷贝"); }
  catch(e){ toast(text); } }
async function refresh(){
  const st = await (await fetch("/api/state")).json();
  // 输入框获焦时跳过更新，避免轮询覆盖用户正在编辑的值
  if (document.activeElement !== document.getElementById("root"))
    document.getElementById("root").value = st.root || "";
  if (document.activeElement !== document.getElementById("user"))
    document.getElementById("user").value = st.user || "";
  if (document.activeElement !== document.getElementById("pass"))
    document.getElementById("pass").value = st.pass || "";
  const ips = st.lanIPs || [];
  document.getElementById("lanURLs").innerHTML = ips.length
    ? ips.map(ip => '<div class="url"><code>http://'+esc(ip)+':'+st.port+'/dav/</code>'+
        '<button class="small plain" onclick="copy(this.previousElementSibling.textContent)">拷贝</button></div>').join("")
    : '<div class="url"><code>未检测到局域网 IP，请确认已连接 WiFi/网线</code></div>';
  document.getElementById("adbURL").textContent = st.adbURL;
  const s = document.getElementById("adbState");
  s.className = "chip " + (st.adbOK ? "ok" : "no");
  s.innerHTML = '<span class="dot"></span>' + (st.adbOK ? "已建立" : "未建立");
  document.getElementById("adbReason").textContent = "USB：" + (st.adbReason || "检测中…");
  const dc = document.getElementById("discoverState");
  dc.textContent = "局域网发现（UDP " + st.discoverPort + "）：" + (st.discoverStat || "—")
    + (st.discoverRebuilds ? " · 已重建 " + st.discoverRebuilds + " 次" : "");
  dc.style.color = st.discoverReady ? "" : "var(--bad)";
  const dv = document.getElementById("devices");
  dv.innerHTML = (st.pairedDevices||[]).map(d => '<span class="device"><span class="dot"></span>'+esc(d)+"</span>").join("");
  const p = st.pairPending;
  const card = document.getElementById("pairCard");
  if (p) {
    const sig = p.device + "|" + p.ip;
    card.hidden = false;
    if (sig !== lastPair) { lastPair = sig; }
    document.getElementById("pairWho").textContent = "手机 · " + p.device;
    document.getElementById("pairMeta").textContent = p.ip + " · 等待确认 " + p.age + " 秒";
  } else { card.hidden = true; lastPair = ""; }
}
async function poll(){
  const j = await (await fetch("/api/log")).json();
  const el = document.getElementById("log");
  el.textContent = (j.lines||[]).join("\n");
  el.scrollTop = el.scrollHeight;
}
function fmtBytes(n){
  if (n == null) return "—";
  const u = ["B","KB","MB","GB","TB"]; let i = 0;
  while (n >= 1024 && i < u.length-1) { n /= 1024; i++; }
  return n.toFixed(n < 10 && i > 0 ? 1 : 0) + " " + u[i];
}
async function pollLive(){
  try {
   const j = await (await fetch("/api/live")).json();
   const sp = document.getElementById("liveSpeed");
   sp.textContent = j.speedMBps > 0 ? j.speedMBps.toFixed(1) + " MB/s" : "—";
   const sess = j.session || {};
   document.getElementById("liveSession").textContent = fmtBytes(sess.bytes||0) + " · " + (sess.files||0) + " 文件";
   const items = j.active || [];
   document.getElementById("liveList").innerHTML = items.map(t => {
     const pct = t.total > 0 ? Math.min(100, Math.round(t.recv*100/t.total)) : 0;
     return '<div class="litem"><div class="lmeta"><span>'+esc(t.path)+'</span><span>'+fmtBytes(t.recv)+(t.total>0 ? ' / '+fmtBytes(t.total)+' · '+pct+'%' : '')+'</span></div>'+
      '<div class="bar"><i style="width:'+pct+'%"></i></div></div>';
   }).join("");
  } catch(e){}
}
async function decide(ok){
  await fetch("/api/pair/decide",{method:"POST",body:JSON.stringify({approve:ok})});
  toast(ok ? "已允许连接" : "已拒绝连接");
  refresh();
}
async function setRoot(){ const r = await fetch("/api/root",{method:"POST",body:JSON.stringify({root:document.getElementById("root").value})}); if(!r.ok) toast(await r.text()); else toast("已保存"); refresh(); }
async function setCred(){ const r = await fetch("/api/cred",{method:"POST",body:JSON.stringify({user:document.getElementById("user").value,pass:document.getElementById("pass").value})}); if(!r.ok) toast(await r.text()); else toast("已更新"); refresh(); }
async function doAdb(){ await fetch("/api/adb",{method:"POST"}); refresh(); }
refresh(); poll(); pollLive();
setInterval(refresh,1200); setInterval(poll,2000); setInterval(pollLive,1500);
</script></body></html>`

// ---------- 入口 ----------

func main() {
	port := flag.Int("port", 0, "监听端口（默认取配置文件，首次 8321）")
	root := flag.String("root", "", "备份落地目录（默认取配置文件，首次为 exe 同级 mibackups）")
	noBrowser := flag.Bool("no-browser", false, "不自动打开控制页")
	flag.Parse()

	c := loadConfig()
	if *port != 0 {
		c.Port = *port
	}
	rootDir := *root
	if rootDir == "" {
		rootDir = c.Root
	}
	if rootDir == "" {
		exe, err := os.Executable()
		if err == nil {
			rootDir = filepath.Join(filepath.Dir(exe), "mibackups")
		} else {
			rootDir = "mibackups"
		}
	}
	abs, err := filepath.Abs(rootDir)
	if err == nil {
		rootDir = abs
	}
	_ = os.MkdirAll(rootDir, 0o755)
	davFS.mu.Lock()
	davFS.root = rootDir
	davFS.mu.Unlock()
	c.Root = rootDir
	if *port != 0 {
		c.Port = *port
	}
	c.save()

	mux := http.NewServeMux()
	mux.HandleFunc("/dav/", func(w http.ResponseWriter, r *http.Request) { davHandler(w, r, c) })
	mux.HandleFunc("/dav", func(w http.ResponseWriter, r *http.Request) { davHandler(w, r, c) })
	// 手机端自动发现：USB 通道（adb reverse 后手机访问 127.0.0.1）也走这里探测
	mux.HandleFunc("/miback/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_, _ = w.Write(discoverPayload(c))
	})
	mux.HandleFunc("/pair/", func(w http.ResponseWriter, r *http.Request) { pairHandler(w, r, c) })
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { controlHandler(w, r, c) })
	go serveDiscovery(c)
	go startDiscoveryKeeper()  // 局域网发现通道看守：失聪即重建
	go startAdbMonitor(c.Port) // USB 通道后台探测：不占用控制页轮询的锁

	addr := ":" + strconv.Itoa(c.Port)
	logf("mibackpc 启动：端口 %d，备份目录 %s", c.Port, rootDir)
	logf("局域网地址：%s", func() string {
		var s []string
		for _, ip := range lanIPs() {
			s = append(s, fmt.Sprintf("http://%s:%d/dav/", ip, c.Port))
		}
		if len(s) == 0 {
			return "（未检测到局域网 IP，请确认已连接 WiFi/网线）"
		}
		return strings.Join(s, "  ")
	}())
	if !*noBrowser {
		go func() {
			time.Sleep(300 * time.Millisecond)
			url := "http://127.0.0.1:" + strconv.Itoa(c.Port) + "/"
			_ = exec.Command("cmd", "/c", "start", "", url).Start()
			_ = mime.TypeByExtension(".html") // 保持 mime 包被引用
		}()
	}
	// 监听带重试：端口被上一实例占用时不再直接 os.Exit(1)（现场出现过
	// ":8321 Only one usage of each socket address" 秒退）
	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 15 * time.Second}
	var lastErr error
	for attempt := 1; attempt <= httpBindAttempts; attempt++ {
		lastErr = srv.ListenAndServe()
		if lastErr == nil || errors.Is(lastErr, http.ErrServerClosed) {
			return
		}
		if !isAddrInUse(lastErr) {
			break
		}
		logf("端口 %d 被占用（第 %d/%d 次）：%v；3 秒后重试（可能有另一个 mibackpc 正在运行）",
			c.Port, attempt, httpBindAttempts, lastErr)
		time.Sleep(3 * time.Second)
	}
	fmt.Println("监听失败：", lastErr)
	fmt.Println("按回车退出...")
	_, _ = fmt.Scanln()
	os.Exit(1)
}

// httpBindAttempts HTTP 监听端口占用时的重试次数（3s 一次）
const httpBindAttempts = 20

// isAddrInUse 判断监听失败是否属于「端口被占用」（可重试）
func isAddrInUse(err error) bool {
	if errors.Is(err, syscall.EADDRINUSE) {
		return true
	}
	s := strings.ToLower(err.Error())
	return strings.Contains(s, "only one usage of each socket address") ||
		strings.Contains(s, "address already in use")
}
