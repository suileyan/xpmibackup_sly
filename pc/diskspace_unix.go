//go:build linux || darwin || freebsd || netbsd || openbsd || dragonfly

package main

import "syscall"

// freeSpaceAt 返回路径所在文件系统的可用字节数（MED-20：PUT 前预检，防客户端把磁盘写满）。
// 非 Windows 平台用 Statfs；Bavail 是「非特权用户可用块数」，比 Bfree 更贴近真实可写量。
func freeSpaceAt(path string) (uint64, error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0, err
	}
	return uint64(st.Bavail) * uint64(st.Bsize), nil
}
