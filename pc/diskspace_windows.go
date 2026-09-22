//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

// kernel32 的 GetDiskFreeSpaceExW：直接取「调用者可用」字节数
// （第三个参数才是「总可用」，前者已扣除配额限制，更贴近可写量）。
var (
	kernel32            = syscall.NewLazyDLL("kernel32.dll")
	getDiskFreeSpaceExW = kernel32.NewProc("GetDiskFreeSpaceExW")
)

// freeSpaceAt 返回路径所在卷的可用字节数（MED-20：PUT 前预检，防客户端把磁盘写满）。
// 用 LazyDLL 动态调用，避免依赖 syscall 包在 Windows 上是否导出该符号。
func freeSpaceAt(path string) (uint64, error) {
	p, err := syscall.UTF16PtrFromString(path)
	if err != nil {
		return 0, err
	}
	var freeToCaller, total, totalFree uint64
	r, _, callErr := getDiskFreeSpaceExW.Call(
		uintptr(unsafe.Pointer(p)),
		uintptr(unsafe.Pointer(&freeToCaller)),
		uintptr(unsafe.Pointer(&total)),
		uintptr(unsafe.Pointer(&totalFree)),
	)
	if r == 0 {
		return 0, callErr
	}
	return freeToCaller, nil
}
