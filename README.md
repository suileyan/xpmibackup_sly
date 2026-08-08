# MiBackup_sly - 小米云备份助手

![Android](https://img.shields.io/badge/Android-9.0+-blue)

![LSPosed](https://img.shields.io/badge/LSPosed-supported-green)

![XposedModule](https://img.shields.io/badge/XposedModule-green)

![Upstream](https://img.shields.io/badge/Upstream-XPoser__MiBackup-blue)

本项目是 [XPoser\_MiBackup](https://github.com/zgcwkjOpenProject/XPoser_MiBackup) 仓库的延申版本，在原版 SMB / WebDAV / 自定义 HTTP 脚本三种通道基础上，新增多账号管理、凭据加密存储、移动云盘（139）与光鸭云盘内置 Provider、OAuth2 Token 自动刷新等能力。

通过 Xposed 模块虚拟小米智能存储设备，将小米备份 App 的 DFS 存储流程重定向到自建 SMB、WebDAV、自定义 HTTP 脚本，或内置的移动云盘（139）、光鸭云盘，实现备份与恢复数据的云端存储。

## 原理

小米备份 App 通过 DFS 服务连接小米智能存储设备，经 AIDL 接口执行目录查询、文件上传、下载和进度回调。本模块注入 `com.android.settings` 与 `com.miui.backup` 两个进程：在设置页注入配置入口，并在备份 App 的 DFS/AIDL 边界把文件操作改由所配置的云端协议完成。

```text
小米备份 App
  -> 查询智能存储设备：返回虚拟设备
  -> 连接 DFS 服务：模拟在线和已连接
  -> DFS AIDL 上传：写入 SMB / WebDAV / 脚本 / 139 / 光鸭
  -> DFS AIDL 下载：从对应云端读取
  -> 进度与完成回调：回传给小米备份原流程
```

主要 Hook 边界停留在 DFS AIDL、设置页入口和明确的备份 UI/服务事件，避免直接依赖混淆业务函数，提升对小米备份版本变更的兼容性。

## 功能

- 在系统设置中注入「云备份助手」配置入口
- 拦截 DFS 连接，模拟小米智能存储设备在线状态
- 支持五种传输通道：SMB/CIFS、WebDAV、自定义 HTTP 脚本、移动云盘（139）、光鸭云盘
- 多账号 / 多方案管理：NAS 方案（SMB/WebDAV/脚本）与云盘账号（139/光鸭）可并存，按需切换备份目标
- 凭据加密存储：密码、Token、Cookie 经 AES-GCM 加密落盘，按账号隔离
- 网盘 Token 自动刷新：光鸭 OAuth2 refresh_token 自动轮换，401 自动重试
- 大文件在 Cloud 层统一切片上传，五种协议共用同一套切片逻辑
- 自动清理超出数量限制的旧备份
- 顶部「备份」按钮点击进入智能存储备份页，长按进入备份升级页

## 环境要求

- zndroid 9.0+（minSdk 28）
- 已安装 Xposed 框架（LSPatch / LSPosed / EdXposed 等）
- 支持的 Xposed 作用域：`com.android.settings`、`com.miui.backup`

## 架构设计

模块采用分层架构，自上而下分为 Hook 层、门面层、Provider 抽象层和 Provider 实现层。Hook 层只与门面层 `CloudFileHelp` 交互，不直接接触具体协议实现，从而保证 Hook 代码稳定、协议可扩展。

```text
┌─────────────────────────────────────────────────────────────┐
│  Hook 层 (com.suileyan.xpmibackup.hook)                     │
│  XposedEntry → AIDLHook / BackupHook / AutoBackupHook       │
│  / SettingsHook                                              │
│  注入 com.android.settings 与 com.miui.backup 两个进程        │
└───────────────────────────┬─────────────────────────────────┘
                            │ 调用 CloudFileHelp 静态方法
┌───────────────────────────▼─────────────────────────────────┐
│  门面层 (com.suileyan.comm.CloudFileHelp)                    │
│  统一入口：upload / download / list / delete / cleanup       │
│  统一切片：uploadChunked / downloadChunked / manifest        │
│  AUTH_EXPIRED 捕获：调用 provider.refresh() 后重试一次        │
└───────────────────────────┬─────────────────────────────────┘
                            │ 委托 ProviderRegistry.active()
┌───────────────────────────▼─────────────────────────────────┐
│  Provider 抽象层 (com.suileyan.cloud)                        │
│  CloudProvider 接口 + ProviderRegistry 注册表                │
│  ProgressCallback / CloudException / RetryPolicy             │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────┬───────────┴───────────┬─────────────────────┐
│  NAS 方案     │  云盘账号              │  自定义脚本          │
│  SmbProvider  │  Yun139Provider        │  ScriptProvider     │
│  WebdavProvider│ GuangyaProvider       │  (Rhino JS 运行时)  │
└───────────────┴───────────────────────┴─────────────────────┘
```

### 双进程注入

模块注入两个不同 UID 的进程，进程间通过 sdcard 上的 JSON 文件通信：

| 进程                     | 职责                        | 注入的 Hook                           |
| ---------------------- | ------------------------- | ---------------------------------- |
| `com.android.settings` | 展示配置 UI、管理账号 / 方案、发起登录    | SettingsHook                       |
| `com.miui.backup`      | 拦截 DFS AIDL、执行备份 / 恢复文件传输 | AIDLHook、BackupHook、AutoBackupHook |

由于 Android Keystore 密钥按进程 UID 隔离无法跨进程共享，凭据加密存储（见下文）采用「固定种子 + 随机文件盐 + PBKDF2 派生」方案，使两个进程读同一文件得到同一密钥。

### 备份目标分发

`ProviderRegistry.active()` 按以下优先级返回当前备份目标的 Provider：

1. **云盘备份目标**（`backup_target.json` 中 `mode=cloud`）：按云盘账号 id 构造 `Yun139Provider` / `GuangyaProvider`
2. **NAS 激活方案**（`profiles.json` 中 `activeId`）：按方案 type 构造 `SmbProvider` / `WebdavProvider` / `ScriptProvider`

NAS 方案 Provider 实例按 profileId 缓存；云盘账号 Provider **不缓存**（凭据可能因重新登录变化，每次重新构造读取最新凭据）。

### 切片与 manifest

切片逻辑统一在 `CloudFileHelp` 层，Provider 只处理整文件传输：

- 文件大于 `chunk_size_mb` 时切片，生成 `原文件.part00000`、`原文件.part00001` … 和 `原文件.mibak.json` manifest
- 上传失败时清理已上传的远端分片与残留 manifest，避免重试把旧分片当有效数据
- 恢复时优先读 manifest 合并分片，manifest 不存在时按未切片的旧文件读取
- manifest 含分片数 / 文件大小可信上限校验，防止远端恶意 manifest 导致异常

## Token 与凭据管理

### 凭据加密存储（EncryptedCredStore）

所有敏感凭据（密码、Token、Cookie、脚本正文）经 `EncryptedCredStore` 加密后落盘到 `/sdcard/MIUI/backup/creds.json`，明文不出现在 sdcard 上。

| 维度     | 方案                                        |
| ------ | ----------------------------------------- |
| 算法     | AES-256-GCM（带 128-bit 认证标签），IV 随机且随密文存储   |
| 密钥派生   | PBKDF2-HmacSHA256（20000 迭代），固定种子 + 随机文件盐  |
| 跨进程一致性 | 文件盐随文件持久化，settings 与 backup 进程读同一文件得到同一密钥 |
| 隔离     | 按 accountId / profileId 命名空间隔离，账号间互不可见    |
| 原子写入   | 临时文件 + rename 原子替换 + 文件锁，防截断与并发覆盖         |
| 兼容     | 旧版无盐格式自动兼容读取，任意写操作触发升级重加密                 |
| 缓存     | 进程内解密缓存（TTL 10 分钟），文件修改时间 + 大小变化自动失效      |

### 各通道凭据与刷新策略

| 通道     | 凭据类型                         | 获取方式           | 静默刷新                       | 过期处理                  |
| ------ | ---------------------------- | -------------- | -------------------------- | --------------------- |
| SMB    | 账号密码                         | 设置页录入          | 无需                         | 重新录入                  |
| WebDAV | 账号密码                         | 设置页录入          | 无需                         | 重新录入                  |
| 自定义脚本  | 脚本内自管理                       | 设置页粘贴 JS       | 脚本 `stateGet/stateSet` 持久化 | 脚本自行处理                |
| 139 云盘 | Authorization（Basic）         | WebView 网页登录捕获 | 不支持（Cookie 型）              | 引导重新登录                |
| 光鸭云盘   | access_token + refresh_token | WebView 网页登录捕获 | OAuth2 refresh_token 自动轮换  | refresh_token 失效则引导重登 |

### 光鸭云盘 OAuth2 刷新

光鸭采用 OAuth2 授权码体系，`access_token` 过期后用 `refresh_token` 换新：

- 任意业务 API 返回 401 时，`callApi` 自动调用 `refreshAccessToken()` 刷新后重试一次
- `refresh_token` 为单次使用（每次刷新返回新的 refresh_token），刷新过程加 `REFRESH_LOCK` 串行化，避免多线程并发刷新导致 refresh_token 轮换冲突使账号锁定
- 刷新成功后新 access_token / refresh_token 立即写回 `EncryptedCredStore`
- refresh_token 也失效时抛出 `AUTH_EXPIRED`，由 `CloudFileHelp` 回调通知 UI 引导重新登录

### 139 云盘签名与路由

139 云盘无标准 OAuth，凭据为网页登录后的 `Authorization`（Basic Base64），API 请求需附加 `mcloud-sign` 签名头：

- 签名算法：`sign = MD5( MD5(Base64(sort(encodeURIComponent(body)))) + MD5(ts + ":" + rand) )` 大写
- 签名头格式：`Mcloud-Sign: ts,rand,sign`（三段，缺一不可）
- API 节点：登录时捕获的 personal 节点 host 优先；缺失时调 `qryRoutePolicy` 路由查询解析 personal 节点；最终回退主站
- 过期处理：401/403 抛 `AUTH_EXPIRED`，因 Cookie 型无法静默刷新，引导用户重新 WebView 登录

### 自定义脚本状态持久化

脚本通道通过 `stateGet(key, default)` / `stateSet(key, value)` 持久化刷新后的 Cookie 或 Token，状态同样经 `EncryptedCredStore` 加密落盘，按脚本账号 id 隔离。脚本可自行实现 token 刷新逻辑并在 `stateSet` 中保存新 token。

### 备份中途过期处理

`CloudFileHelp.uploadWithProgress` / `downloadFile` 捕获 `CloudException(AUTH_EXPIRED)`：

- 调用 `provider.refresh()`，成功则重试当前操作一次
- 切片上传中途过期：重试当前 part，不丢弃已传分片
- 连续失败上报 `onFinish(code=-1, msg="登录已过期")`

## 配置

配置入口：系统设置 -> 云备份助手

### 配置文件

| 文件                                        | 说明                             | 敏感性    |
| ----------------------------------------- | ------------------------------ | ------ |
| `/sdcard/MIUI/backup/config.ini`          | 全局非敏感配置（路径、线程数、切片大小、设备名等）      | 非敏感    |
| `/sdcard/MIUI/backup/profiles.json`       | NAS 方案列表 + activeId            | 非敏感元数据 |
| `/sdcard/MIUI/backup/cloud_accounts.json` | 云盘账号列表                         | 非敏感元数据 |
| `/sdcard/MIUI/backup/backup_target.json`  | 当前备份目标（cloud / profile + id）   | 非敏感    |
| `/sdcard/MIUI/backup/creds.json`          | 加密凭据（密码 / Token / Cookie / 脚本） | **加密** |

### 全局配置项

| 配置项               | 说明                   | 默认值           |
| ----------------- | -------------------- | ------------- |
| `backup_path`     | 云端备份根目录              | `MIUI/backup` |
| `upload_threads`  | 并发上传线程数              | `3`           |
| `chunk_size_mb`   | 上传切片大小（MB）；`0` 表示不切片 | `64`          |
| `backup_max`      | 最大保留备份数；`0` 表示不自动清理  | `5`           |
| `device_name`     | 设置页展示的虚拟设备名称         | -             |
| `device_describe` | 设置页展示的虚拟设备描述         | -             |

### NAS 方案配置（profiles.json）

每条方案包含非敏感连接参数，敏感凭据（密码）单独存入加密存储：

**SMB 方案参数**

| 参数                                                   | 说明                       |
| ---------------------------------------------------- | ------------------------ |
| `smb_server` / `smb_port` / `smb_share` / `smb_user` | 连接参数（非敏感）                |
| `smb_pass`                                           | 密码（存 EncryptedCredStore） |

**WebDAV 方案参数**

| 参数                           | 说明                       |
| ---------------------------- | ------------------------ |
| `webdav_url` / `webdav_user` | 连接参数（非敏感）                |
| `webdav_pass`                | 密码（存 EncryptedCredStore） |

**自定义脚本方案参数**

| 参数                  | 说明                                     |
| ------------------- | -------------------------------------- |
| `custom_script_b64` | Base64 编码的 JS 脚本（存 EncryptedCredStore） |

自定义脚本的写法、接口说明和示例见 [自定义 HTTP 脚本文档](plugins/README.md)。

### 云盘账号配置（cloud_accounts.json）

云盘账号通过设置页「账号」Tab 添加，选择网盘类型后经 WebView 登录捕获凭据：

| 字段         | 说明                     |
| ---------- | ---------------------- |
| `id`       | 账号唯一标识                 |
| `provider` | 网盘类型：`139` 或 `guangya` |
| `account`  | 登录账号（139 为手机号）         |
| `name`     | 显示名称                   |

登录捕获的 `Authorization` / `access_token` / `refresh_token` / `host` 等敏感凭据存入 EncryptedCredStore，按账号 id 隔离。

### 旧配置自动迁移

首次启动检测到旧 `config.ini` 含 `protocol` + `smb_*` / `webdav_*` / `custom_script_b64` 且无方案时，自动迁移为一条默认 NAS 方案并设为激活，敏感凭据迁入 EncryptedCredStore，升级无感。

## 云端目录

默认远端根目录 `MIUI/backup`，备份目录示例：

```text
MIUI/backup/20260711_000000/
  descript.xml
  end
  ...
```

DFS 虚拟路径中的 `.AllBackup`、`.AppBackup` 等片段不会写入云端真实路径。

## 项目结构

```text
app/src/main/java/com/suileyan/
  cloud/                          云端抽象与账号层
    CloudProvider.java            统一接口
    CloudProvider 实现：SmbProvider / WebdavProvider / ScriptProvider
                      Yun139Provider / GuangyaProvider  (provider/)
    ProviderRegistry.java         Provider 注册表与活跃目标分发
    ProfileStore / Profile        NAS 方案持久化与模型
    CloudAccountStore / CloudAccount  云盘账号持久化与模型
    BackupTarget                  跨进程备份目标持久化
    EncryptedCredStore            凭据加密存储（AES-GCM + PBKDF2）
    RetryPolicy / CloudException  重试策略与统一异常
    ProgressCallback / ListenerProgressCallback  进度回调
    login/Yun139Login             139 登录与签名算法
  comm/                           文件操作门面与配置层
    CloudFileHelp                 统一入口 + 切片 + AUTH_EXPIRED 重试
    SmbFileHelp / WebdavFileHelp  协议实现
    CustomHttpFileHelp            自定义 HTTP 脚本（Rhino）实现
    ConfigHelp                    全局配置读写
    Async                         共享守护线程池
    AtomicFile                    原子文件写入
  xpmibackup/                     Xposed 模块入口与 Hook
    XposedEntry                   模块入口
    hook/
      AIDLHook                    DFS AIDL 重定向
      BackupHook                  备份页面 / 通知 / 取消处理
      AutoBackupHook              自动备份调度
      SettingsHook                设置页入口注入
    ui/                           配置界面 Fragment
```

### 实现说明

| 模块                   | 作用                                         |
| -------------------- | ------------------------------------------ |
| `SettingsHook`       | 在设置 App 中注入配置入口，展示虚拟智能存储设备                 |
| `AIDLHook`           | 模拟 DFS 服务连接，拦截上传、下载、目录查询，分发到 CloudFileHelp |
| `CloudFileHelp`      | 统一分发五种通道，处理跨协议切片与 AUTH_EXPIRED 重试          |
| `BackupHook`         | 修正备份 App 页面、通知、进度焦点和取消清理                   |
| `AutoBackupHook`     | 接入备份 App 原生自动备份设置和调度链路                     |
| `ProviderRegistry`   | 按备份目标（云盘账号优先，其次激活方案）取 Provider 实例          |
| `EncryptedCredStore` | AES-GCM 加密凭据，按账号隔离，跨进程共享                   |

## 编译

需要 JDK 17+ 和 Android SDK（compileSdk 36）。

```bash
cd src
gradlew assembleDebug
```

调试 APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

安装后在 Xposed/LSPosed 中启用模块，勾选作用域 `com.android.settings` 与 `com.miui.backup`，重启目标 App 或设备。

## 依赖

| 库                                          | 用途                          |
| ------------------------------------------ | --------------------------- |
| [Xposed API](https://api.xposed.info/)     | 框架 Hook 能力                  |
| [smbj](https://github.com/hierynomus/smbj) | SMB/CIFS 协议                 |
| [OkHttp](https://square.github.io/okhttp/) | HTTP 客户端（WebDAV / 139 / 光鸭） |
| [Rhino](https://github.com/mozilla/rhino)  | 自定义 HTTP 脚本 JS 运行时          |

## 安全说明

- 所有密码、Token、Cookie 经 AES-256-GCM 加密落盘，明文不接触 sdcard
- 凭据按账号 / 方案 id 隔离，互不可见
- 自定义 HTTP 通道内置 SSRF 防护：拦截私有 IP（10/172.16/192.168/169.254/127/::1/ULA fc00::/7）、禁止重定向到内网、对八进制 / 十六进制 IP 编码做解析后校验
- WebView 登录关闭文件访问与通用 JS 接口，登录完成清理 Cookie
- 日志输出脱敏，不打印完整 Token / 密码

## 许可证

[MIT License](LICENSE)
