#!/usr/bin/env python3
"""mibackpc 黑盒验证：按 WebdavFileHelp.java 的线上行为逐条打服务端。

关键对应关系（与 Java 客户端逐字对齐）：
- testConnection(): PROPFIND baseUrl Depth 0 → 必须返回 207
- listDirectory/listEntries(): PROPFIND 目录(Depth 1)，正则
    HREF      = <\\w*:?href>([^<]+)</\\w*:?href>
    LENGTH    = <\\w*:?getcontentlength>(\\d+)</\\w*:?getcontentlength>
    DIR       = <\\w*:?collection\\s*/?>
  首条 href 无条件跳过；名字做 URLDecoder.decode
- mkdir(): MKCOL 目录末尾带 '/'，逐级创建且吞掉所有异常 → 服务端 405/409 均可
- uploadToWebdav(): PUT application/octet-stream，2xx 视为成功；URL 段用
    URLEncoder.encode(s,"UTF-8").replace("+","%20")
- downloadFile(): GET 200 → 字节流
- deleteDir(): DELETE 目录(带'/')，2xx/3xx 视为成功递归删除
"""
import io
import re
import sys
import urllib.request

# Windows 控制台默认 GBK，脚本末尾的中文/符号输出会 UnicodeEncodeError 直接中断，
# 让「23 项全 PASS」看起来像失败。统一转 UTF-8 并容错。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# 本机回环不走系统代理（用户环境配置了 http_proxy，会把 127.0.0.1 打到代理上返回 502）
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

BASE = "http://127.0.0.1:18321"
USER, PASS = "miback", "cdb6659ba5b8a68e"
AUTH = "Basic " + __import__("base64").b64encode(f"{USER}:{PASS}".encode()).decode()

HREF = re.compile(r"<\w*:?href>([^<]+)</\w*:?href>", re.I)
LENGTH = re.compile(r"<\w*:?getcontentlength>(\d+)</\w*:?getcontentlength>", re.I)
DIR = re.compile(r"<\w*:?collection\s*/?>", re.I)

fails = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"   [{detail}]" if detail else ""))
    if not cond:
        fails.append(name)


def req(method, url, body=None, headers=None, auth=True):
    r = urllib.request.Request(url, data=body, method=method)
    if auth:
        r.add_header("Authorization", AUTH)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with _opener.open(r, timeout=10) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def enc(segment):
    # 等价 URLEncoder.encode(s,"UTF-8").replace("+","%20")（路径段）
    from urllib.parse import quote
    return quote(segment, safe="")


# 1) testConnection：PROPFIND /dav/ Depth 0 → 207
st, body = req("PROPFIND", BASE + "/dav/", b'<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:allprop/></D:propfind>',
               {"Depth": "0", "Content-Type": "application/xml; charset=utf-8"})
check("testConnection: PROPFIND base Depth0 返回 207", st == 207, f"got {st}")
check("testConnection: 响应含 multistatus", b"multistatus" in body)

# 2) listDirectory 语义：Depth1 且首条 href 为目录自身
st, body = req("PROPFIND", BASE + "/dav/", headers={"Depth": "1"})
xml = body.decode("utf-8", "replace")
hrefs = HREF.findall(xml)
check("PROPFIND Depth1 返回 207", st == 207, f"got {st}")
check("首条 href 为目录自身(/dav/)", hrefs and hrefs[0].rstrip("/").endswith("/dav"), hrefs[:1])

# 3) mkdirs 逐级 MKCOL（客户端吞掉一切异常）
backup_dir = "MIUI/backup"
ts_dir = backup_dir + "/20260905_000000"
for d in ["MIUI", backup_dir, ts_dir]:
    st, _ = req("MKCOL", BASE + "/dav/" + enc(d) + "/")
    check(f"MKCOL {d}", st in (201, 405), f"got {st}")

# 4) PUT：普通文件 + 中文文件名 + 分片命名 + manifest
payload = bytes(range(256)) * 41  # 10496 bytes
cn_name = "中文名 备份.bin"
st, _ = req("PUT", BASE + "/dav/" + ts_dir.replace("/", "%2F") if False else BASE + "/dav/" + "/".join(enc(p) for p in ts_dir.split("/")) + "/" + enc(cn_name), payload,
            {"Content-Type": "application/octet-stream"})
check("PUT 中文文件名 2xx", 200 <= st < 300, f"got {st}")

part_payload = b"PARTDATA-" * 500
part_name = ts_dir + "/AllBackup.part00000"
st, _ = req("PUT", BASE + "/dav/" + "/".join(enc(p) for p in part_name.split("/")), part_payload,
            {"Content-Type": "application/octet-stream"})
check("PUT 分片文件 2xx", 200 <= st < 300, f"got {st}")

manifest = '{"version":1,"name":"中文名 备份.bin","size":10496,"chunkSize":64,"parts":1,"createdTime":1788600000000}'.encode()
st, _ = req("PUT", BASE + "/dav/" + "/".join(enc(p) for p in (ts_dir + "/中文名 备份.bin.mibak.json").split("/")), manifest,
            {"Content-Type": "application/octet-stream"})
check("PUT .mibak.json manifest 2xx", 200 <= st < 300, f"got {st}")

# 5) listEntries 语义：正则解析 + 中文解码 + 大小
st, body = req("PROPFIND", BASE + "/dav/" + "/".join(enc(p) for p in ts_dir.split("/")) + "/", headers={"Depth": "1"})
xml = body.decode("utf-8", "replace")
import urllib.parse
entries = {}
first = True
for block in re.finditer(r"<\w*:?response[\s\S]*?</\w*:?response>", xml, re.I):
    hm = HREF.search(block.group(0))
    if not hm:
        continue
    href = hm.group(1).rstrip("/")
    name = href.split("/")[-1]
    if first:
        first = False
        continue
    lm = LENGTH.search(block.group(0))
    is_dir = bool(DIR.search(block.group(0)))
    entries[urllib.parse.unquote(name)] = (int(lm.group(1)) if lm else 0, is_dir)
check("listEntries 含中文文件名（URL 解码）", cn_name in entries, str(list(entries.keys())))
check("listEntries 中文文件大小正确", entries.get(cn_name, (0,))[0] == len(payload), str(entries.get(cn_name)))
check("listEntries 分片大小正确", entries.get("AllBackup.part00000", (0,))[0] == len(part_payload))
check("listEntries 目录/文件类型区分", entries.get("AllBackup.part00000", (0, True))[1] is False)

# 6) GET 下载字节一致性
st, got = req("GET", BASE + "/dav/" + "/".join(enc(p) for p in (ts_dir + "/" + cn_name).split("/")))
check("GET 200 且字节一致", st == 200 and got == payload, f"st={st} len={len(got)}")

# 7) 断点续传语义：同名 PUT 覆盖（重传分片）
new_part = part_payload + b"-RETRY"
st, _ = req("PUT", BASE + "/dav/" + "/".join(enc(p) for p in part_name.split("/")), new_part,
            {"Content-Type": "application/octet-stream"})
st2, got2 = req("GET", BASE + "/dav/" + "/".join(enc(p) for p in part_name.split("/")))
check("分片重传覆盖成功", st in (200, 201) and got2 == new_part)

# 8) 越界防护
st, _ = req("GET", BASE + "/dav/..%2F..%2Fmibackpc.json", auth=True)
check("路径越界被拒绝", st in (403, 404), f"got {st}")

# 9) 鉴权
st, _ = req("PROPFIND", BASE + "/dav/", headers={"Depth": "0"}, auth=False)
check("无凭据 PROPFIND 被拒 401", st == 401, f"got {st}")
bad = "Basic " + __import__("base64").b64encode(b"miback:wrongpass").decode()
r = urllib.request.Request(BASE + "/dav/", method="PROPFIND")
r.add_header("Authorization", bad)
try:
    with _opener.open(r, timeout=10) as resp:
        st = resp.status
except urllib.error.HTTPError as e:
    st = e.code
check("错误密码被拒 401", st == 401, f"got {st}")

# 10) deleteDir：递归删除目录（带尾斜杠）
st, _ = req("DELETE", BASE + "/dav/" + "/".join(enc(p) for p in ts_dir.split("/")) + "/")
check("DELETE 目录递归删除 2xx", 200 <= st < 300, f"got {st}")
st, body = req("PROPFIND", BASE + "/dav/" + "/".join(enc(p) for p in ts_dir.split("/")) + "/", headers={"Depth": "1"})
check("删除后 PROPFIND 404", st == 404, f"got {st}")

# 11) 控制面仅本机（从本机发起验证可访问；局域网拒绝由 remote addr 判定，无法在本机模拟）
st, body = req("GET", BASE + "/api/state", auth=False)
check("控制面 /api/state 本机可访问", st == 200, f"got {st}")
check("控制面不下发内容给非预期路径", b"lanIPs" in body)

print()
if fails:
    print("失败 %d 项: %s" % (len(fails), fails))
    sys.exit(1)
print("全部通过 ✓")
