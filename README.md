<div align="center">
  <img src="assets/icon.svg" width="140" alt="MiBackup_sly"/>
  <h1>MiBackup_sly</h1>
  <p><b>小米云备份助手</b> · 把小米备份存到你自己的云盘 / NAS / 电脑</p>
  <p>
    <img src="https://img.shields.io/github/v/release/suileyan/xpmibackup_sly?label=Release" alt="Release"/>
    <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"/>
    &nbsp;
    <img src="https://img.shields.io/badge/Android-11.0%E2%80%9317-blue" alt="Android 11.0–17"/>
    <img src="https://img.shields.io/badge/LSPosed-supported-green" alt="LSPosed supported"/>
    &nbsp;
    <img src="https://img.shields.io/badge/Xposed-Module-green" alt="Xposed Module"/>
    <img src="https://img.shields.io/badge/Java-17-orange" alt="Java 17"/>
    &nbsp;
    <img src="https://img.shields.io/badge/Upstream-XPoser__MiBackup-blue" alt="Upstream: XPoser_MiBackup"/>
  </p>
</div>

---

## 项目介绍

小米手机自带的「小米备份」只能存到小米云或本地。**MiBackup_sly** 是一个 Xposed / LSPosed 模块：通过虚拟小米智能存储设备，把小米备份 App 的 DFS 存储流程重定向到自建 SMB、WebDAV、自定义 HTTP 脚本，或内置的移动云盘（139）、光鸭云盘、夸克云盘、阿里云盘、天翼云盘（189）、百度网盘、联通沃盘，实现备份与恢复数据的云端存储——小米备份原本怎么用，现在还怎么用，只是目标变成了你自己的存储。

本项目是 [XPoser\_MiBackup](https://github.com/zgcwkjOpenProject/XPoser_MiBackup) 仓库的延伸版本，在原版 SMB / WebDAV / 自定义 HTTP 脚本三种通道基础上，新增多账号管理、凭据加密存储（AES-256-GCM）、移动云盘（139）、光鸭云盘、夸克云盘、阿里云盘、天翼云盘（189）、百度网盘、联通沃盘内置 Provider、OAuth2/会话自动刷新、备份至 PC 等能力。

- 源码：https://github.com/suileyan/xpmibackup_sly
- 下载（签名 APK + 电脑端 mibackpc.exe）：https://github.com/suileyan/xpmibackup_sly/releases
- 各版本变更见 [CHANGELOG.md](CHANGELOG.md)

## 原理

小米备份 App 通过 DFS 服务连接小米智能存储设备，经 AIDL 接口执行目录查询、文件上传、下载和进度回调。本模块注入 `com.android.settings` 与 `com.miui.backup` 两个进程：在设置页注入配置入口，并在备份 App 的 DFS/AIDL 边界把文件操作改由所配置的云端协议完成。

```text
小米备份 App
  -> 查询智能存储设备：返回虚拟设备
  -> 连接 DFS 服务：模拟在线和已连接
  -> DFS AIDL 上传：写入 SMB / WebDAV / 脚本 / 139 / 光鸭 / 夸克 / 阿里 / 189 / 百度 / 沃盘 / PC
  -> DFS AIDL 下载：从对应云端读取
  -> 进度与完成回调：回传给小米备份原流程
```

主要 Hook 边界停留在 DFS AIDL、设置页入口和明确的备份 UI/服务事件，避免直接依赖混淆业务函数，提升对小米备份版本变更的兼容性。

## 功能

- 在系统设置中注入「云备份助手」配置入口
- 拦截 DFS 连接，模拟小米智能存储设备在线状态
- 支持十一种传输通道：SMB/CIFS、WebDAV、自定义 HTTP 脚本、移动云盘（139）、光鸭云盘、夸克云盘、阿里云盘、123云盘（v0.9.5 起暂停支持）、天翼云盘（189）、百度网盘、联通沃盘
- **备份至 PC**：备份页自动扫描局域网 / USB 发现电脑端 mibackpc（`pc/` 目录），手机弹窗连接 + 电脑端确认配对，免手动填地址；连接成功后备份方式出现「备份至 PC」
- **USB 通道优先（v1.0.0）**：电脑端在「设备在线但 `adb reverse` 未建立」时自动建通道；手机发现 `127.0.0.1` 应答即把方案切到 USB（仅当电脑名与已配对的一致，避免写到别的机器），拔线自动回落局域网，无需重新配对
- 多账号 / 多方案管理：NAS 方案（SMB/WebDAV/脚本）与云盘账号（139/光鸭/夸克/189/百度/沃盘）可并存，按需切换备份目标
- 凭据加密存储：密码、Token、Cookie 经 AES-256-GCM 加密落盘，按账号隔离；网盘 Token 自动刷新（光鸭 OAuth2、夸克 __puus 续期、阿里/天翼 refresh_token 轮换等）
- 大文件在 Cloud 层统一切片上传，十一种协议共用同一套切片逻辑
- **手机端峰值占用可控（v1.0.0）**：切片前取「在途分片额度」（背压），磁盘分片锁死在 `(并发+2) × chunk_size`（默认 ≤640MB，逐项模式 ≤192MB）；不再整份复制源文件；孤儿分片自动清理（6 小时阈值，不误伤在途分片）
- **实时上传速率（v1.0.0）**：备份页总进度条右侧显示每秒刷新的速率角标
- **取消即停（v1.0.0）**：备份页取消后 1~2 秒内停止上传（最多再传完当前一片），且失败/取消一律不删本地源文件
- **进度口径修正（v1.0.0）**：按宿主自己的分母折算上报，备份项进度不再是「贴住 30% 然后直接跳 100%」
- 自动清理超出数量限制的旧备份；备份页支持「自动删除本地已上传文件」「逐项串行备份」开关
- Android 11~17 适配：edge-to-edge（含底部导航栏 insets，仅 Android 15+ 强制）、Android 17 本地网络保护（SMB/WebDAV 专项提示）、static final 反射限制审计、配置变更行为兼容
- Hook 跨版本兼容（HIGH-25）：小米备份类名/混淆方法名漂移时多候选自动降级 + 诊断日志，DFS AIDL transact code 漂移可观测
- 顶部「备份」按钮点击进入智能存储备份页，长按进入备份升级页
- 自定义 HTTP 脚本通道内置 SSRF 防护与 Rhino JS 沙箱（ClassShutter deny-all + 30s watchdog），WebView 登录关闭文件访问与通用 JS 接口

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 系统版本 | Android 11.0 ~ 17（minSdk 30 / targetSdk 37） |
| Xposed 框架 | LSPosed / LSPatch / EdXposed（Xposed API 82+） |
| 模块作用域 | 勾选 `com.android.settings` 与 `com.miui.backup` |
| 备份至 PC | 电脑端 mibackpc.exe（GitHub Release 提供，Go 交叉编译） |

> **Android 17 本地网络说明**：SMB/WebDAV 局域网通道的实际网络请求运行在 `com.miui.backup` 宿主进程，
> 其是否受 Android 17 本地网络保护（ACCESS_LOCAL_NETWORK）影响取决于小米备份 App 自身声明；
> 模块已声明该权限并在 SMB 连接失败且目标为局域网时给出专项提示（ERR_SMB_LOCALNET）。

> **生效方式**：安装模块并在 LSPosed 勾选作用域后，需**强制停止「小米备份」再重新打开**，宿主才会加载模块代码。配置入口：系统设置 → 云备份助手。

## 项目结构

模块采用分层架构，自上而下分为 Hook 层、门面层、Provider 抽象层和 Provider 实现层。Hook 层只与门面层 `CloudFileHelp` 交互，不直接接触具体协议实现，保证 Hook 代码稳定、协议可扩展。

```text
小米备份 App（宿主）
   │  DFS AIDL 重定向
┌──▼──────────────────────────────────────────────────────┐
│  Hook 层    XposedEntry → AIDLHook / BackupHook          │
│             / AutoBackupHook / SettingsHook              │
├─────────────────────────────────────────────────────────┤
│  门面层    CloudFileHelp：统一 upload/download/list       │
│            + 切片（背压/取消/分片回收）+ 过期重试          │
├─────────────────────────────────────────────────────────┤
│  Provider 层  CloudProvider 接口 + ProviderRegistry       │
│               AbstractCloudProvider 基类                  │
├──────────┬──────────┬──────────┬──────────┬─────────────┤
│ NAS 方案  │ 云盘账号  │ 云盘账号  │ 备份至 PC │ 自定义脚本   │
│ Smb      │ 139/光鸭  │ 189/百度  │ mibackpc │ Rhino 沙箱  │
│ WebDAV   │ 夸克/阿里 │ 沃盘      │ (Go)     │ ScriptProvider│
└──────────┴──────────┴──────────┴──────────┴─────────────┘
```

### 双进程注入

模块注入两个不同 UID 的进程，进程间通过 sdcard 上的 JSON 文件通信：

| 进程 | 职责 | 注入的 Hook |
| --- | --- | --- |
| `com.android.settings` | 展示配置 UI、管理账号 / 方案、发起登录 | SettingsHook |
| `com.miui.backup` | 拦截 DFS AIDL、执行备份 / 恢复文件传输 | AIDLHook、BackupHook、AutoBackupHook |

由于 Android Keystore 密钥按进程 UID 隔离无法跨进程共享，凭据加密存储采用「固定种子 + 随机文件盐 + PBKDF2（600000 迭代）派生」方案，使两个进程读同一文件得到同一密钥；跨进程配置一致性靠方案文件的 mtime + 长度变化整体失效缓存。

### 代码目录

```text
app/src/main/java/com/suileyan/
  cloud/                          云端抽象与账号层
    CloudProvider.java            统一接口
    provider/AbstractCloudProvider  Provider 公共基类
    provider/                     Smb / Webdav / Yun139 / Guangya / Quark
                                  / Tianyi / Baidu / Wo Provider（Pan123、Pan115 代码保留）
    ProviderRegistry.java         Provider 注册表与活跃目标分发
    ProfileStore / CloudAccountStore / BackupTarget  方案 / 账号 / 目标持久化
    EncryptedCredStore            凭据加密存储（AES-256-GCM + PBKDF2）
    RetryPolicy / CloudException / ProgressCallback  重试 / 异常 / 进度回调
  comm/                           文件操作门面与配置层
    CloudFileHelp                 统一入口 + 切片（背压/取消/分片回收）
    SmbFileHelp / WebdavFileHelp / CustomHttpFileHelp  协议实现与脚本入口
    ScriptFunctions / ScriptWatchdog  沙箱宿主函数库（24+）与超时保护
    ConfigHelp / LocalBackupFileHelp  配置读写与目录布局迁移
    PcDiscovery / PcPair          电脑端发现（USB 优先归一化）与配对
    BackupCancel / TransferSpeed  取消信号（按请求时刻判定）/ 实时速率统计
    Secp256k1 / Async / AtomicFile  ECDH 曲线 / 守护线程池 / 原子文件写入
    LogHelp / CrashLog            分段日志与崩溃留痕
  xpmibackup/                     Xposed 模块入口与 Hook
    XposedEntry                   模块入口
    hook/                         AIDLHook / BackupHook / AutoBackupHook
                                  / SettingsHook / ProgressSpeedBadge
    ui/                           配置界面 Fragment（HyperOS 设计语言）
pc/                               电脑端 mibackpc（Go 单文件，端口 8321/8322）
plugins/                          自定义 HTTP 脚本示例
```

## 从源码编译

模块端需要 JDK 17+ 和 Android SDK（compileSdk 37）：

```bash
cd src
gradlew assembleDebug
```

调试 APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

电脑端 mibackpc（可选，Go 1.22+，纯标准库零 CGO）：

```bash
cd pc
gofmt -l . && go vet ./... && go test ./...
GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o dist/mibackpc.exe .
```

安装后在 Xposed/LSPosed 中启用模块，勾选作用域 `com.android.settings` 与 `com.miui.backup`，强制停止并重新打开「小米备份」即可生效。

## 依赖

| 库 | 版本 | 用途 |
| --- | --- | --- |
| [Xposed API](https://api.xposed.info/) | 82 | 框架 Hook 能力 |
| [smbj](https://github.com/hierynomus/smbj) | 0.13.0 | SMB/CIFS 协议 |
| [OkHttp](https://square.github.io/okhttp/) | 4.12.0 | HTTP 客户端（WebDAV / 139 / 光鸭 / 夸克 / 189 / 百度 / 沃盘 / 阿里） |
| [Rhino](https://github.com/mozilla/rhino) | 1.9.1 | 自定义 HTTP 脚本 JS 运行时（沙箱） |
| androidx.annotation | 1.6.0 | 仅 `@RequiresApi` 注解（不打包进 APK） |

电脑端 mibackpc 为 Go 实现，纯标准库、零第三方依赖、零 CGO。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
