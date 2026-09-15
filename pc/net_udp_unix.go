//go:build !windows

// 非 Windows：UDP 绑定语义不同（Linux 需 SO_REUSEADDR 才允许端口重用），
// 且不存在「同端口双 socket」的需求——发现服务只用一只 socket，
// 冲突时由 serveDiscovery 的退避重试处理。
package main

import "net"

func listenUDPExclusive(port int) (*net.UDPConn, error) {
	return net.ListenUDP("udp4", &net.UDPAddr{Port: port})
}
