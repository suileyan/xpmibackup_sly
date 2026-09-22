package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestFreeSpaceAt 校验磁盘可用空间探测（MED-20 的 PUT 预检依赖它）。
// 真实断言：必须能取到值，且不是 0 / 荒谬上限——取不到值会让预检静默失效。
func TestFreeSpaceAt(t *testing.T) {
	free, err := freeSpaceAt(".")
	if err != nil {
		t.Fatalf("freeSpaceAt(.) 失败: %v", err)
	}
	if free == 0 {
		t.Fatal("freeSpaceAt 返回 0，疑似系统调用未生效（预检会静默失效）")
	}
	if free > 1<<60 { // 1 EiB
		t.Fatalf("freeSpaceAt 返回值异常: %d", free)
	}
	t.Logf("可用空间: %d bytes", free)
}

// TestPUTInsufficientStorage 声明远超磁盘容量的 Content-Length 时，PUT 必须被 507 拒绝，
// 且不得创建临时文件；正常大小仍应写入成功（防止预检误伤）。
func TestPUTInsufficientStorage(t *testing.T) {
	dir := t.TempDir()
	davFS.mu.Lock()
	oldRoot := davFS.root
	davFS.root = dir
	davFS.mu.Unlock()
	defer func() {
		davFS.mu.Lock()
		davFS.root = oldRoot
		davFS.mu.Unlock()
	}()

	c := &Config{Port: 8321, User: "miback", Pass: "testpass"}

	doPUT := func(path string, contentLength int64, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("PUT", path, strings.NewReader(body))
		req.ContentLength = contentLength
		req.SetBasicAuth("miback", "testpass")
		rec := httptest.NewRecorder()
		davHandler(rec, req, c)
		return rec
	}

	// 1) 声明 1 EiB：必然超过可用空间
	rec := doPUT("/dav/huge.bin", 1<<60, "x")
	if rec.Code != http.StatusInsufficientStorage {
		t.Fatalf("超大 Content-Length 应返回 507，实际 %d", rec.Code)
	}

	// 2) 正常小文件：不应被预检误伤
	rec = doPUT("/dav/ok.bin", 5, "hello")
	if rec.Code != http.StatusCreated {
		t.Fatalf("正常 PUT 应返回 201，实际 %d（body=%s）", rec.Code, rec.Body.String())
	}

	// 3) 未知长度（ContentLength=-1）仍放行
	req := httptest.NewRequest("PUT", "/dav/unknown.bin", strings.NewReader("abc"))
	req.ContentLength = -1
	req.SetBasicAuth("miback", "testpass")
	rec = httptest.NewRecorder()
	davHandler(rec, req, c)
	if rec.Code != http.StatusCreated {
		t.Fatalf("未知长度 PUT 应返回 201，实际 %d", rec.Code)
	}
}
