# mibackpc —— 电脑端备份接收器

把电脑上的任意文件夹变成一台 **WebDAV 服务器**，手机端 XpMiBackup 的
「备份至 PC」自动发现并连接它，备份/恢复数据直接落到电脑磁盘。

单文件 exe、纯 Go 标准库、零第三方依赖、无需安装。

## 为什么是 WebDAV 而不是 FTP

手机端 `WebdavFileHelp`（OkHttp 客户端）是既有且经过真机验证的链路：
切片、断点、进度回调、AUTH 处理全部复用。选 FTP 意味着在无真机调试的
前提下从零写一个「控制连接 + PASV 数据连接」客户端，风险完全不可控。
PC 端实现 WebDAV 子集只有约 250 行 Go，且两端源码都在仓库里，可代码级互验
（见下文「验证」）。

## 使用

1. 电脑双击 `mibackpc.exe`，自动打开控制页 `http://127.0.0.1:8321/`
   （首次自动生成随机密码，账号密码在控制页可查可改）
2. 控制页确认「备份落地目录」
3. 手机进入「备份」页：自动扫描局域网 / USB 通道（无需填写任何地址），
   发现本机后手机弹窗询问是否连接
4. 手机点「连接」→ 电脑端弹出确认框（或控制页「连接请求」卡片）→ 点「允许」
5. 连接成功，手机备份方式出现「备份至 PC」，点「开始备份」即落到电脑磁盘

USB 通道：电脑端点控制页「重连」（执行 adb reverse，需装有 adb 并连接手机），
或手机与电脑在同一 WiFi 时自动走局域网，无需手动区分。
通道状态由后台协程按 15s → 5min 指数退避探测（adb 子进程带 3s 硬超时），
控制页 1.2s 的轮询只读缓存、不 spawn 进程；失败原因（无 adb / 无设备 /
未授权 / 未建立）直接显示在卡片上。

命令行参数：`-port 8321` `-root D:\备份目录` `-no-browser`

## 自动发现与配对协议

- **发现**：手机发 UDP 广播（端口 8322，探测码 `MIBACKPC_DISCOVER_V1`），
  本机单播回 `{service, name, tcp, ver, lan, lans}`。
  本机只用**一只** socket 收发（`SO_EXCLUSIVEADDRUSE` 独占 8322），
  应答源端口天然是 8322；**不要**用两个 socket 绑同一端口——
  Windows 对同端口多 socket 的投递是未定义的，实测会导致发现通道永久失聪。
  `lan` / `lans` 是局域网地址候选（物理网卡优先、虚拟网卡沉底），
  手机据此做 UDP 单播与 HTTP 兜底探测。
  Windows 需放行 **UDP 8322 入站**（首次运行弹出防火墙窗口时
  **「专用」和「公用」都要勾**：家用 WiFi 常被系统判为「公用」，
  只勾「专用」会留下缺口）；或直接依赖 `mibackpc-udp` 这条 UDP 8322 端口规则。
  USB 走 `adb reverse` 后探测 `GET /miback/info`（同一 JSON，局域网亦可访问）
- **配对**：手机 `POST /pair/requests`（携带设备名 + 随机 reqId）→ 本机弹
  Windows 原生确认框（非 Windows 由控制页确认）→ 手机轮询
  `GET /pair/requests/{reqId}/result` → `approved` 时**单次下发** WebDAV 凭据
- 配对结果仅下发给发起请求的同一 IP；凭据取走即焚；请求 5 分钟未确认自动过期

## 发现通道自愈（UDP 8322）

- **看守自检**：每 5s 从本机回环发一发探测码，连续 3 次无应答即判定
  自己的 socket 失聪，主动关闭并重新绑定（回环流量不受防火墙影响，
  因此该自检能精确定位「socket 收不到包」，而不是把问题推给防火墙）
- **读循环容错**：只有致命错误才退出；`WSAECONNRESET`（Windows 收到
  ICMP port unreachable 时的正常反馈）等瞬时错误就地忽略，
  不会因为手机一次「发完就退」的探测被打死
- **绑定/重建退避**：绑定失败或循环退出后自动重建，退避 1s → 30s 封顶
- **可观测**：控制页「连接」卡片显示发现通道状态与最近一次探测来源；
  收到非探测码的包会限速记录日志，便于区分「没收到」与「收到但格式不符」
- **单实例约束**：发现端口是**独占绑定**，第二个实例会绑定失败并在日志里
  明确提示（`UDP 8322 已被占用（很可能有另一个 mibackpc 在运行，请只保留一个实例）`），
  **不会**静默共享端口——同端口多 socket 会让发现通道静默失聪。
  请勿同时运行 `pc/mibackpc.exe` 与 `pc/dist/mibackpc.exe`；更换 exe 时先结束旧进程。

## 备份实时状态（速度 / 进度）

控制页新增「备份实时状态」卡片，**1.5s 轮询 `GET /api/live`**（本机访问）：

- 当前速度（最近 ~3s 窗口的 MB/s）+ 本次会话累计（字节 / 文件数）
- 在途上传列表：路径 + 已收/总量 + 百分比进度条
- 速度窗口每 3s 重开；备份空闲时显示 `—`

`/api/live` 与 `/api/state` 同属控制面，**仅回环访问**，局域网手机拿不到。

## 安全

- `/dav/*` 需要 Basic 认证（首次启动随机生成密码，`mibackpc.json` 保存）；
  密码仅在配对被电脑端明确同意后下发
- 控制页与 `/api/*` **只允许本机回环访问**，局域网设备无法控制电脑端
  （`/miback/info` 与 `/pair/*` 除外：前者只含主机名/端口，后者必须经确认）
- 路径越界防护（`..` / 绝对路径 / 盘符一律拒绝）
- PUT 落盘为 `.mibackpart` 临时文件再 rename，断连不产生半截文件
  （手机端分片重试不会把坏分片当有效数据）

## 实现的 WebDAV 子集（对齐手机端 WebdavFileHelp）

| 方法 | 语义 | 对端行为 |
|---|---|---|
| PROPFIND Depth 0/1 | **207 Multi-Status**，首条 href 为目录自身 | `testConnection()` 以 207 判定连通；列表正则解析 href/getcontentlength/collection |
| MKCOL | 递归建目录，已存在返回 405 | `mkdirs()` 逐级创建并吞掉异常 |
| PUT | 2xx 成功 | 上传/分片上传 |
| GET/HEAD | 文件内容（支持 Range） | 恢复下载 |
| DELETE | 递归删除，2xx/3xx 视为成功 | 旧备份清理 |
| OPTIONS | DAV: 1,2 | 兼容探测 |

## 验证（无真机，代码级逻辑通路）

1. **黑盒**：`python blackbox_test.py`（23 项）——按客户端线上格式逐端点打真实
   服务端：207/正则解析/中文 URL 解码/分片重传覆盖/越界/401/递归删除
2. **端到端**：`harness/run.sh`（11 项）——把**应用真实源码**
   `WebdavFileHelp` + `ConfigHelp` 编译到桌面 JVM（stub `android.util.Log` +
   `layoutlib.jar` 提供运行期实现），直接打 Go 服务端：
   testConnection → mkdirs → upload/uploadToWebdav(进度回调) → listDirs/listEntries
   → 中文名+空格上传下载字节比对 → deleteFile/deleteDir
3. **应用构建**：`:app:assembleDebug` + `lint`（NewApi 0）
4. **真机**（warsaw / Android 17）：备份页自动发现弹窗、电脑端确认、
   「备份至 PC」开始备份落到电脑目录（待晨间人工确认）

## 构建

```bash
cd pc
GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o mibackpc.exe .
```
