# XPoser_MiBackup 网盘登录与多账号备份 — 开发文档

> 目标：模块内登录网盘获取登录信息 → 选择目标网盘 → 劫持小米备份 App → 完成备份至其他网盘。
> 范围确认：**阶段一先完成重构迁移基础**（接口抽象 / 多账号 / 凭据加密 / UI），**阶段二后期再做内置网盘 Provider 与三类登录引擎**（WebView 抓 Cookie / OAuth 授权码 / 账号密码表单）。
> 不含开发周期，仅说明每一步干什么。

---

## 现状关键事实（已探查确认）

- `CloudFileHelp` 按 `protocol` 单选分发到 `SmbFileHelp` / `WebdavFileHelp` / `CustomHttpFileHelp`，全局仅一份配置。
- 接口不对称：上传方法名 `uploadToSmb`/`uploadToWebdav`/`uploadWithProgress` 不统一；完成回调 `CloudFileHelp` 反射 `onFinish`，底层反射 `l0`；进度统一 `D0`，启动 `Y0`。
- `WebdavFileHelp` 公开了 `mkdir/mkdirs/deleteFile`，`SmbFileHelp` 的 mkdir 为内部方法；切片与 manifest 仅在 `CloudFileHelp` 层，底层只处理整文件。
- AIDLHook 调用点（精确行）：
  - `sendMockConnectResult` (L319) → `CloudFileHelp.testConnection()`
  - `sendMockList` (L414) → `CloudFileHelp.listEntries(remoteDir)`
  - `uploadViaFd` (L922) → `CloudFileHelp.uploadWithProgress(localPath, listener, remoteDir, taskId)`
  - `uploadViaFd` (L925) → `CloudFileHelp.cleanupOldBackups()`（仅当文件名为 `end`）
  - `uploadLocalDescriptorIfPresent` (L954) → `CloudFileHelp.upload(descript.xml, remoteDir)`
  - `downloadViaFd` (L976) → `CloudFileHelp.downloadFile(remotePath, tmpFile)`
  - `ensureRestoreDescriptors` (L1124) → `CloudFileHelp.listAndDownloadXml(tempPath)`
- `deleteRemoteDir` 在 `BackupHook.java:501` 调用（非 AIDLHook）；`cleanupOldBackups()` 内部级联调用 `listDirs/readBackupXmls/deleteRemoteDir`。
- listener/taskId 来自 AIDL `args[1]=taskId`、`args[5]=listener`（`Object`，反射调小米 `IFileOperationProgressListener`）。
- `uploadExecutor` = `newFixedThreadPool(upload_threads)`，仅 `mockUpload` 使用。
- 配置全在 `/sdcard/MIUI/backup/config.ini` 明文；脚本凭据在 `custom_state_b64`（Base64 JSON，亦明文）。
- UI：`activity_main.xml` 底部 Tab 仅 `tab_device`/`tab_service`；`fragment_container` 为 FrameLayout。

---

# 阶段一：重构迁移基础（先做）

## 步骤 1：建立 cloud 抽象包与核心接口

**做什么**：新建 `com.zgcwkj.cloud` 包，定义统一接口与 DTO，为后续所有 Provider 收口。

**新建文件**：
- `cloud/CloudProvider.java` — 统一接口
- `cloud/ProgressCallback.java` — 进度回调抽象（替代反射 `Object` listener）
- `cloud/CloudException.java` — 统一异常（区分 `AUTH_EXPIRED`/`NETWORK`/`REMOTE`）
- `cloud/RemoteEntry.java` — 迁移 `CloudFileHelp.RemoteEntry` 到 cloud 包（原内部类保留为别名过渡）

**CloudProvider 接口要点**：
```
String id();                 // 账号唯一标识
String type();               // smb|webdav|script|baidu|aliyun|quark|yun139|tianyi|onedrive
String displayName();
boolean testConnection();
List<String> listDirs();
List<RemoteEntry> listEntries(String remoteDir);
void mkdirs(String remoteDir);                 // 统一暴露，SMB 也要实现
String upload(String localPath, String remoteDir);
void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId);
String downloadFile(String remotePath, String localPath);
void deleteDir(String remoteDir);
void deleteFile(String remotePath);
LoginState login(LoginContext ctx);            // 阶段二填充，阶段一返回 NOT_SUPPORTED
boolean refresh();                             // 静默刷新 token；不支持返回 false
boolean isLoggedIn();
```

**约定**：CloudProvider 只处理**整文件**上传/下载；切片/manifest 仍由 `CloudFileHelp` 层统一处理，不进入 Provider。

## 步骤 2：统一进度回调抽象

**做什么**：消除 `onFinish`/`l0`/`D0`/`Y0` 反射不一致，所有 Provider 经 `ProgressCallback` 回调。

**改什么**：
- 新建 `cloud/ProgressCallback.java`：`onStart(taskId)` / `onProgress(taskId,current,total)` / `onFinish(taskId,code,msg)`。
- 新建 `cloud/ListenerProgressCallback.java`：把 AIDL 传入的 `Object listener` 反射适配为 `ProgressCallback`，内部封装 `Y0`/`D0`/`l0` 反射查找（复用 `ProgressCallbackHelp` 现有兜底逻辑）。
- `SmbFileHelp`/`WebdavFileHelp`/`CustomHttpFileHelp` 的进度回调改走 `ProgressCallback`（删除各自重复的 `invokeProgress` 私有方法）。

## 步骤 3：现有实现迁移为 Provider

**做什么**：把三套现有实现各包成一个 Provider，对外只暴露 `CloudProvider`，底层类降为包内实现。

**新建**：
- `cloud/provider/SmbProvider.java` — 持有 smb 配置，委托 `SmbFileHelp`，补齐 `mkdirs`（调内部 session mkdir）。
- `cloud/provider/WebdavProvider.java` — 委托 `WebdavFileHelp`。
- `cloud/provider/ScriptProvider.java` — 委托 `CustomHttpFileHelp`，`type()="script"`。

**统一项**：
- 上传方法统一为 `uploadWithProgress`（SMB 的 `uploadToSmb`、WebDAV 的 `uploadToWebdav` 改为内部方法或别名）。
- 重试策略统一：SMB 现有 3 次重试提取为 `cloud/RetryPolicy`，WebDAV/Script 默认也套用（可配置）。

**验收**：迁移后 SMB/WebDAV/脚本三条链路行为与现状一致（不接 UI，单测或日志验证）。

## 步骤 4：ProviderRegistry 与活跃账号选择

**做什么**：`CloudFileHelp` 从"按 protocol 分发"改为"按活跃账号取 Provider 实例"。

**新建**：
- `cloud/ProviderRegistry.java` — 维护 `accountId → CloudProvider` 实例缓存，按需构造。
- `cloud/ActiveAccount.java` — 读写当前 `active_account_id`。

**改 `CloudFileHelp`**：
- 删除 `isCustom()/isWebdav()/getProtocol()` 三分支分发。
- 所有 `upload/uploadWithProgress/downloadFile/listEntries/listDirs/deleteRemoteDir/testConnection` 改为 `registry.get(activeAccountId).<method>()`。
- 切片 `uploadChunked`/`downloadChunked`/`normalizeChunkEntries` 保留不变，底层调 `provider.upload/downloadFile`。
- `cleanupOldBackups/readBackupXmls/listAndDownloadXml` 级联调用随之自动走活跃账号。

**AIDLHook 调用点不变**（仍调 `CloudFileHelp` 静态方法），零改动通过。

## 步骤 5：账号模型与账号存储

**做什么**：把"单份配置"升级为"账号列表"。

**新建**：
- `cloud/Account.java` — `{id, type, displayName, credRef, createdAt, active}`。
- `cloud/AccountStore.java` — 账号列表持久化（JSON，存 `/sdcard/MIUI/backup/accounts.json`，仅存非敏感元数据；敏感凭据走步骤 6）。

**操作**：`list()/get(id)/add(account)/remove(id)/setActive(id)/getActive()`。

**兼容迁移**：首次启动检测到旧 `config.ini` 的 `protocol+smb_*/webdav_*/custom_script_b64`，自动迁移为一条"内置迁移账号"并设为活跃，保证升级无感。

## 步骤 6：凭据加密存储

**做什么**：Cookie/Token/密码不再明文落盘。

**新建**：
- `cloud/EncryptedCredStore.java` — 基于 `androidx.security:crypto` 的 `EncryptedSharedPreferences`（minSdk 28 满足），或 Android Keystore + AES-GCM 自实现。
- API：`put(accountId, key, value)` / `get(accountId, key)` / `removeAccount(accountId)`。

**迁移**：
- `config.ini` 的 `smb_pass`/`webdav_pass` 迁入按 accountId 隔离的加密存储。
- `custom_state_b64`（脚本持久化 Cookie）迁入加密存储，`ScriptProvider` 的 `stateGet/stateSet` 改走 `EncryptedCredStore`（按当前脚本账号隔离）。
- `config.ini` 只保留非敏感项（协议、路径、线程数、切片大小、设备名等）。

**依赖**：`build.gradle` 加 `androidx.security:crypto:1.1.0-alpha06`（或选稳定版）。

## 步骤 7：配置层适配

**做什么**：`ConfigHelp` 拆分敏感/非敏感，配合账号化。

**改 `ConfigHelp`**：
- 保留 `load()/save()/getString()/getInt()` 用于非敏感全局配置。
- 删除 `smb_pass`/`webdav_pass`/`custom_script_b64`/`custom_state_b64` 的默认项与读写（迁入账号/加密存储）。
- `custom_script_b64` 改为挂在 `ScriptProvider` 账号上（账号元数据存脚本引用，脚本正文存加密存储或独立脚本目录）。

## 步骤 8：UI 账号管理 Tab

**做什么**：新增第三个底部 Tab「账号」。

**改 `activity_main.xml`**：底部 Tab 栏加 `tab_account`（含 `tab_account_icon`/`tab_account_text`），三等分布局。

**改 `MainActivity.java`**：`switchTab` 增加 `"account"` 分支 → `AccountConfigFragment`；Tab 颜色切换逻辑扩展到三个。

**新建 `ui/AccountConfigFragment.java`**：
- 账号列表（RecyclerView 或 LinearLayout 动态填充）：每行 = 网盘类型图标 + 显示名 + 状态徽标（已登录/已过期/未登录）+ 操作（设为活跃/测试连接/重新登录/删除）。
- 顶部「添加账号」按钮 → 选类型（阶段一仅 smb/webdav/script 三类可添加，阶段二扩展网盘类型）。
- 添加表单：复用现有 SMB/WebDAV/脚本输入项，保存为一条账号并写加密存储。
- 设为活跃 → `AccountStore.setActive(id)` + `ProviderRegistry` 失效缓存。

**新建布局**：`layout/fragment_account_config.xml`、`layout/item_account.xml`、`drawable` 账号状态徽标。

**改 `strings.xml`**：新增 `tab_account_config`/`title_account_config`/`add_account`/`set_active`/`account_logged_in`/`account_expired`/`account_not_logged`/`relogin`/`delete_account` 等 key。

## 步骤 9：ServiceConfigFragment 适配

**做什么**：协议面板从"全局单选"改为"账号参数录入"。

**改 `ServiceConfigFragment.java`**：
- 协议 RadioGroup 改为"添加账号"时选类型用；保存改为写一条账号而非全局 protocol。
- 「测试并保存」改为针对当前编辑账号测试。
- 保留全局项：`upload_threads`/`chunk_size_mb`（仍全局）。

## 步骤 10：登录态刷新框架（接口先行）

**做什么**：搭好 `refresh()` 调用链，阶段二再填充具体实现。

**改 `CloudFileHelp`**：
- `uploadWithProgress`/`downloadFile` 捕获 `CloudException(AUTH_EXPIRED)` → 调 `provider.refresh()` → 成功则重试当前操作一次 → 失败则回调 `onFinish(code=-1, msg="登录已过期")`。
- 切片上传中途过期：重试当前 part，不丢弃已传分片。

**新建 `cloud/LoginState.java`**：`{success, expiresAt, message}`；`cloud/LoginContext.java`：承载登录所需输入（账密/OAuth code/WebView 结果）。

**阶段一**：`SmbProvider/WebdavProvider/ScriptProvider` 的 `login()` 返回 `NOT_SUPPORTED`（这些用凭据直连，无需登录流程），`isLoggedIn()` 检查凭据是否非空。

## 步骤 11：阶段一回归验证

**做什么**：确保重构不改变现有行为。

**验证清单**：
- SMB 备份/恢复全流程（上传含切片、列目录、下载合并、旧备份清理）。
- WebDAV 同上。
- 自定义脚本（用 `example.js`）同上。
- 旧 `config.ini` 自动迁移为账号后能正常备份。
- 加密存储读写正确、卸载重装不丢失（同设备）。
- AIDLHook 七个调用点日志正常、进度回调正常。

---

# 阶段二：内置网盘 Provider 与三类登录引擎（后期）

## 步骤 12：登录引擎抽象

**做什么**：定义三类登录引擎统一接口，由 `AccountManager` 调度。

**新建**：
- `cloud/login/LoginEngine.java` — `LoginResult login(LoginContext ctx)`；`LoginEngineType type()` → `OAUTH`/`WEBVIEW`/`PASSWORD`。
- `cloud/login/LoginResult.java` — `{success, credentials(Map), expiresAt, message}`。
- `cloud/login/AccountManager.java` — 按 Provider 类型选引擎、发起登录、结果写 `EncryptedCredStore`、更新账号状态。

## 步骤 13：账号密码表单引擎 + 移动云盘 139 Provider

**做什么**：实现最简单的登录引擎，并把现有 `yun139.js` 沉淀为内置 Java Provider。

**新建**：
- `cloud/login/PasswordLoginEngine.java` — 表单收集手机号+密码，调网盘登录 API 算出凭据。
- `cloud/provider/Yun139Provider.java` — 移植 `yun139.js` 的签名（`mcloudSign`/`apiHeaders`/`callApi`）、list/create/complete/upload/download/delete 全流程为 Java；`login()` 走账密→Basic Auth；`refresh()` 基本不过期返回 false。

**网盘接口要点（来自 yun139.js）**：API_BASE `https://personal-kd-njs.yun.139.com`；`mcloud-sign` = body URL 编码排序→Base64→MD5→混时间随机串→MD5；`/hcy/file/create` 带 `commonAccountInfo`；直传 `uploadUrl` PUT 后 `/hcy/file/complete`。

## 步骤 14：WebView 抓 Cookie 引擎 + 百度网盘 Provider

**做什么**：网页登录型网盘的通用引擎。

**新建**：
- `cloud/login/WebViewLoginActivity.java` — 加载网盘登录页，注入 JS 监听 URL/Cookie 变化，登录成功后 `CookieManager.getCookie(url)` 抓取关键 Cookie，回传 `LoginResult`。
- `cloud/login/WebViewLoginEngine.java` — 配置登录页 URL、成功判定 URL 模式、需提取的 Cookie 名清单。
- `cloud/provider/BaiduProvider.java` — `login()` 走 WebView 抓 `BDUSS`/`STOKEN`；上传走百度网盘开放接口或 PCS；`refresh()` Cookie 型无法静默刷新，返回 false → 触发重登提示。

**注意**：模块注入 `com.android.settings` 进程，起 `WebViewActivity` 需在 `AndroidManifest` 注册并 `exported=false`；WebView 在精简 ROM 可能不可用，降级提示用户用脚本通道。

## 步骤 15：OAuth 授权码引擎 + 阿里云盘 Provider

**做什么**：开放平台型网盘的通用引擎。

**新建**：
- `cloud/login/OAuthLoginActivity.java` — 用 `CustomTabsIntent` 或内置 WebView 走授权码流程，回调拦截 `redirect_uri` 取 code，换 token。
- `cloud/login/OAuthLoginEngine.java` — 配置 `authorizeUrl`/`tokenUrl`/`clientId`/`redirectUri`/`scope`。
- `cloud/provider/AliyunProvider.java` — `login()` 走 OAuth 拿 `access_token`+`refresh_token`；`refresh()` 用 refresh_token 换新 access_token；上传走阿里云盘 openAPI。

## 步骤 16：登录态持久化与刷新

**做什么**：补全 `refresh()` 与过期处理。

**改 `CloudFileHelp`（完善步骤 10 的框架）**：
- OAuth 型：401 → `refresh()` 成功重试；refresh_token 也失效 → 通知 UI「需重新登录」。
- Cookie 型（百度/夸克）：关键 Cookie 失效 → 直接通知 UI 重登（不假装能续期）。
- 139 Basic：基本不过期；密码改了 → 重新账密登录。
- 备份中途过期：当前 part 重试，不丢已传分片；连续失败上报 `onFinish(-1)`。

**UI 联动**：账号列表状态徽标根据 `isLoggedIn()`/最近 `refresh()` 结果刷新；过期账号在备份发起前弹提示。

## 步骤 17：网盘 Provider 扩展模板

**做什么**：把新增网盘做成可复用模板，降低后续接入成本。

**提供**：
- `cloud/provider/AbstractHttpCloudProvider.java` — 抽象基类，封装 OkHttp client、分页 list、路径解析（参考 yun139.js 的 `resolvePath`/`findChild`）、通用重试。
- 新增网盘步骤文档（接入清单：登录引擎选择、API 端点、签名算法、上传/下载/删除/列表实现、refresh 策略）。
- 后续按需实现：夸克（WebView Cookie）、天翼（账密）、OneDrive（OAuth）。

## 步骤 18：自定义脚本 login() 钩子

**做什么**：让脚本通道也能声明登录方式，覆盖未内置网盘。

**改 `CustomHttpFileHelp`/`ScriptProvider`**：
- 脚本约定可选 `function login(ctx) { return { success, credentials, expiresAt } }`。
- 实现了 `login` 的脚本账号在 UI 上可点「登录」，由脚本自发起请求并 `stateSet` 结果；未实现则维持手填凭据。
- 宿主函数新增 `openWebView(url, successPattern, cookieNames)`（可选），让脚本也能借用 WebView 引擎。

## 步骤 19：阶段二集成验证

**验证清单**：
- 139 账密登录→备份→恢复→过期重登全流程。
- 百度 WebView 登录→Cookie 抓取→上传/下载。
- 阿里 OAuth 登录→token 刷新→上传/下载。
- 多账号切换目标网盘备份，互不串扰（各 Provider 独立 OkHttp client）。
- 脚本 `login()` 钩子可用。
- Cookie 型过期提示正确弹出。

---

# 关键文件清单

## 新建
- `cloud/CloudProvider.java`、`cloud/ProgressCallback.java`、`cloud/CloudException.java`、`cloud/RemoteEntry.java`
- `cloud/ListenerProgressCallback.java`、`cloud/RetryPolicy.java`
- `cloud/ProviderRegistry.java`、`cloud/ActiveAccount.java`
- `cloud/Account.java`、`cloud/AccountStore.java`、`cloud/EncryptedCredStore.java`
- `cloud/LoginState.java`、`cloud/LoginContext.java`
- `cloud/provider/SmbProvider.java`、`WebdavProvider.java`、`ScriptProvider.java`
- `cloud/login/LoginEngine.java`、`LoginResult.java`、`AccountManager.java`（阶段二）
- `cloud/login/PasswordLoginEngine.java`、`WebViewLoginEngine.java`、`OAuthLoginEngine.java`（阶段二）
- `cloud/login/WebViewLoginActivity.java`、`OAuthLoginActivity.java`（阶段二）
- `cloud/provider/Yun139Provider.java`、`BaiduProvider.java`、`AliyunProvider.java`、`AbstractHttpCloudProvider.java`（阶段二）
- `ui/AccountConfigFragment.java`
- `res/layout/fragment_account_config.xml`、`res/layout/item_account.xml`

## 修改
- `comm/CloudFileHelp.java` — 分发改 ProviderRegistry、加 AUTH_EXPIRED 重试（步骤 4/10/16）
- `comm/SmbFileHelp.java` — 补 `mkdirs`、统一上传方法名、进度回调改 ProgressCallback（步骤 2/3）
- `comm/WebdavFileHelp.java` — 统一上传方法名、进度回调改 ProgressCallback（步骤 2/3）
- `comm/CustomHttpFileHelp.java` — `stateGet/stateSet` 改走 EncryptedCredStore、加 `login()` 钩子（步骤 6/18）
- `comm/ConfigHelp.java` — 删除敏感默认项、拆分（步骤 7）
- `comm/ProgressCallbackHelp.java` — 复用为 ListenerProgressCallback 底层（步骤 2）
- `xpmibackup/MainActivity.java` — 第三 Tab（步骤 8）
- `xpmibackup/ui/ServiceConfigFragment.java` — 账号化录入（步骤 8/9）
- `res/layout/activity_main.xml` — 加 `tab_account`（步骤 8）
- `res/values/strings.xml`、`res/values-en/strings.xml` — 账号相关文案（步骤 8）
- `app/build.gradle` — 加 `androidx.security:crypto`（步骤 6）
- `AndroidManifest.xml` — 注册 WebView/OAuth Activity（阶段二步骤 14/15）

## 不改动（保持稳定）
- `xpmibackup/hook/AIDLHook.java` — 七个调用点仍调 `CloudFileHelp` 静态方法，零改动
- `xpmibackup/hook/BackupHook.java` — `deleteRemoteDir` 调用不变
- `xpmibackup/hook/SettingsHook.java`、`AutoBackupHook.java`
- 切片/manifest 逻辑（`uploadChunked`/`downloadChunked`/`normalizeChunkEntries`）

---

# 改造点对照表（AIDLHook 调用 → 重构后去向）

| AIDLHook 调用点 | 现状 | 重构后 |
|---|---|---|
| `sendMockConnectResult` L319 | `CloudFileHelp.testConnection()` | `registry.get(active).testConnection()` |
| `sendMockList` L414 | `listEntries(remoteDir)` | `registry.get(active).listEntries(remoteDir)` |
| `uploadViaFd` L922 | `uploadWithProgress(...)` | `registry.get(active).uploadWithProgress(...)` + AUTH_EXPIRED 重试 |
| `uploadViaFd` L925 | `cleanupOldBackups()` | 不变（内部级联走活跃账号） |
| `uploadLocalDescriptorIfPresent` L954 | `upload(descript.xml, remoteDir)` | `registry.get(active).upload(...)` |
| `downloadViaFd` L976 | `downloadFile(remotePath, tmpFile)` | `registry.get(active).downloadFile(...)` |
| `ensureRestoreDescriptors` L1124 | `listAndDownloadXml(tempPath)` | 不变（内部级联走活跃账号） |
| `BackupHook` L501 | `deleteRemoteDir(...)` | `registry.get(active).deleteDir(...)` |
