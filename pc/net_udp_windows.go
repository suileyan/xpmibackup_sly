//go:build windows

// Windows UDP 绑定。零 CGO，走 syscall。
//
// listenUDPExclusive：SO_EXCLUSIVEADDRUSE —— 独占发现端口，任何第三方进程
// （含本程序的第二个实例、dist 里的旧构建）都无法再绑同一端口，
// 从根上杜绝「同端口多 socket → 投递未定义 → 发现通道失聪」。
//
// 刻意**不提供** SO_REUSEADDR 版本：共享绑定会把上述故障静默复现，
// 明确失败并让监督器退避重试 + 告警，才是可诊断的行为。
package main

import (
	"net"
	"os"
	"syscall"
)

// SO_EXCLUSIVEADDRUSE 在 Windows 上等价于 ~SO_REUSEADDR（0xFFFFFFFB），
// Go 的 syscall 未导出该常量，此处直接给出。
const soExclusiveAddrUse = ^syscall.SO_REUSEADDR

// listenUDPExclusive 独占绑定（必须在 bind 之前设置，且不可与 SO_REUSEADDR 同用）
func listenUDPExclusive(port int) (*net.UDPConn, error) {
	var sa syscall.Sockaddr = &syscall.SockaddrInet4{Port: port}
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return nil, err
	}
	if serr := syscall.SetsockoptInt(fd, syscall.SOL_SOCKET, soExclusiveAddrUse, 1); serr != nil {
		_ = syscall.Close(fd)
		return nil, serr
	}
	if berr := syscall.Bind(fd, sa); berr != nil {
		_ = syscall.Close(fd)
		return nil, berr
	}
	return udpFromFd(fd)
}

func udpFromFd(fd syscall.Handle) (*net.UDPConn, error) {
	file := os.NewFile(uintptr(fd), "udp4")
	defer file.Close()
	conn, ferr := net.FileConn(file)
	if ferr != nil {
		return nil, ferr
	}
	udp, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, net.UnknownNetworkError("not a UDP socket")
	}
	return udp, nil
}
