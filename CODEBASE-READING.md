# MiBackup_sly 代码库全量阅读报告

> 首版生成：2026-08-08（Explore 子代理 very thorough 全量扫描 + 人工核验核心文件）
> 更新于：2026-08-08 16:47（针对 HEAD `0638bfd` 沙箱 v2 提交与首版归因错误做勘误补全）

---

## 0. 一句话本质

这**不是**一个独立备份 App，而是一个 **Xposed / LSPosed 模块**：它通过注入 `com.android.settings` 与 `com.miui.backup` 两个系统进程，**伪造一台"小米智能存储设备（DFS）"**，把小米备份 App 原本发往米联 DFS 服务的 AIDL 文件读写，劫持重定向到用户自建的 **SMB / WebDAV / 自定义 JS 脚本 / 移动云盘139 / 光鸭云盘**。它自己不决定备份什么（那由小米备份 App 原生流程决定），只接管"数据往哪里存"这一环。

---

## 1. 项目性质与技术栈

| 维度 | 结论 | 依据 |
|---|---|---|
| 类型 | Xposed 模块（非普通 App） | `assets/xposed_init` → `com.suileyan.xpmibackup.XposedEntry`；Manifest `xposedmodule=true`、`xposedminversion=82` |
| 语言 | 纯 Java，**0 行 Kotlin** | 49 个 `.java`，未启用 kotlin 插件 |
| 代码量 | **约 13,447 行** Java（首版 12,748，v2 沙箱提交后增加） | 全量统计 |
| minSdk / targetSdk / compileSdk | 28 / 36 / 36，buildTools 36.0.0 | `src/app/build.gradle` |
| Java 版本 | **17**（source & target） | `build.gradle` |
| AGP / Gradle | AGP 9.2.0 / Gradle 9.6.1 | `libs.versions.toml`、`gradle-wrapper.properties` |
| 上游 | fork/延伸自 `zgcwkjOpenProject/XPoser_MiBackup` | README |
| 许可 | MIT | `LICENSE` |

**关键依赖（仅 4 个，刻意最小化，避免与宿主进程 classloader 冲突）：**
- `de.robv.android.xposed:api:82`（compileOnly，不打包）
- `com.hierynomus:smbj:0.13.0`（SMB/CIFS）
- `com.squareup.okhttp3:okhttp:4.12.0`（WebDAV/139/光鸭 HTTP）
- `org.mozilla:rhino:1.9.1`（自定义 JS 脚本运行时）

**没有** AndroidX / Material / Retrofit / Gson；UI 用已废弃的 framework `android.app.Fragment`；JSON 用系统自带 `org.json`。

**NDK / .so / 插件化？** 无 NDK、无 `.so`、无 JNI。仓库里看到的 `.bin` 全是 Gradle 构建缓存，`.txt` 全是构建产物。"插件化"指 **Rhino JS 脚本引擎**（`plugins/*.js`），沙箱严格：`cx.setOptimizationLevel(-1)`（Android 必需解释执行）+ `cx.setClassShutter(className -> false)`（禁止 JS 触达任何 Java 类），且 scope 中不放置任何 Java 实例（防 `getClass().forName()` 反射逃逸，CVE-2025-0982 教训）。

---

## 2. 架构分层（4 层 + Provider 基类，单向依赖）

```
Hook 层 (xpmibackup.hook)   ← 只调 CloudFileHelp 静态方法，不碰具体协议
      ↓
门面层 (comm.CloudFileHelp)  ← 切片/manifest/AUTH_EXPIRED 重试都在这里
      ↓  ProviderRegistry.active()
抽象层 (cloud.CloudProvider)
      ↓
实现层 (cloud.provider.*)    ← Smb / Webdav / Script / Yun139 / Guangya
```

**Provider 基类（`AbstractCloudProvider`）**：NAS 三方案（Smb/Webdav/Script）`extends AbstractCloudProvider implements CloudProvider`，基类统一负责：
- 携带 `Profile`（非敏感参数）+ 按 `profile.id` 从 `EncryptedCredStore` 读敏感凭据；
- 组装 `overrides()` 后经 `ConfigHelp.withAccount()` 的 ThreadLocal 注入底层 `SmbFileHelp/WebdavFileHelp/CustomHttpFileHelp`，多方案互不串扰。

**139 / 光鸭不继承该基类**：它们基于 `CloudAccount`（而非 `Profile`），仍直接 `implements CloudProvider`，每次调用重构造以读取最新凭据。

**登录抽象占位（阶段一）**：`LoginContext` / `LoginState` / `RemoteEntry` 已建（`LoginState.NOT_SUPPORTED` 供 SMB/WebDAV/脚本返回），是为 139/光鸭 WebView 登录统一化预留的脚手架，尚未落地；`RemoteEntry` 由 `CloudFileHelp` 内部类迁至 `cloud` 包供 AIDL 层构造 DFS 对象。

设计意图（README 明示）：**Hook 代码稳定、协议可扩展**。Hook 只停留在公开 DFS AIDL 边界，不去 Hook 小米混淆过的业务函数。

---

## 3. 核心数据流

### 3.1 备份上传链路
```
小米备份 App 写数据到本地 …/MIUI/backup/AllBackupTemp/…
  ↓ 调 DFS SDK 上传
① PackageManager.queryIntentServices → AIDLHook 伪造返回 DFS ServiceInfo（服务"存在")
② ContextWrapper.bindService         → 返回进程内 Proxy Binder，立刻回调 onServiceConnected
③ AIDL upload(w1)/download(G0)       → 动态代理按【参数签名】匹配分发
   ↓ AIDLHook.uploadViaFd()
④ LocalBackupFileHelp.resolveUploadFile(pfd) → 从 fd 反查本地真实文件（零拷贝优化）
   查不到 → 从 fd 流式复制到临时文件
   ↓
⑤ CloudFileHelp.uploadWithProgress()
   ├ 文件 > chunk_size_mb → 切片为 .part00000 + .mibak.json manifest
   └ ProviderRegistry.active() → 具体 Provider.upload()
   ↓
⑥ 进度通过反射回调小米 IFileOperationProgressListener（Y0/D0/l0 方法）
⑦ 上传到文件名 == "end" → 补传 descript.xml + cleanupOldBackups() 清理超量旧备份
```

### 3.2 恢复链路
镜像：`downloadViaFd` → 云端下载到临时文件 → 写回 `ParcelFileDescriptor`；优先读 manifest 合并分片，无 manifest 则按旧版整文件读。

### 3.3 双进程通信
`com.android.settings` 与 `com.miui.backup` UID 不同，无法共享 Android Keystore。解法是 sdcard 上的 JSON 文件 + **"固定种子 + 随文件持久化的随机盐 + PBKDF2"** 派生同一密钥（安全性弱于 Keystore，但换来跨进程可用）。

### 3.4 关键设计决策
- **混淆兼容策略（最聪明的一处）**：小米新版方法名被混淆成 `w1`/`G0`/`i0`/`f0`，模块**不靠方法名匹配，靠参数签名匹配**（`AIDLHook.java`）：新旧方法名都兜住，签名不变就不会挂。对应 commit `a135b41`「因新版混淆问题，改为Hook AIDL 层」。字段同理：按字段类型名查找，兼容新旧字段名（`AIDLHook:1106/1216`）。
- **切片逻辑上提到门面层**：5 种协议共用一套切片/manifest 代码，Provider 只管整文件。
- **Provider 缓存差异化**：NAS 方案按 profileId 缓存实例；云盘账号**故意不缓存**（重新登录后 Authorization/host 会变，每次重构造读最新凭据）。
- **OAuth refresh 串行化**：光鸭 `refresh_token` 单次使用，并发刷新会轮换冲突锁死账号，用 `REFRESH_LOCK` 串行化。
- **脚本沙箱 v2（commit `0638bfd`，16:30）**：
  - 删除 `eval/Function/runCommand/spawn/sync/quit/load` 危险内置函数（eval/Function 是动态代码生成入口，runCommand/spawn 绕过 ClassShutter 直接起进程，load 可读本地文件/远端 URL）；
  - 宿主函数从 `CustomHttpFileHelp` 抽到独立 `ScriptFunctions`（749 行），新增 24 个（加密/HTTP 扩展/文件/工具），见第 5 节；
  - 新增 `ScriptWatchdog`（82 行）指令计数看门狗，30s 超时；
  - SSRF 双重校验（入口 + 302 重定向网络拦截器二次校验）；
  - 临时文件 canonical 路径前缀校验防穿越；
  - 攻击测试 `ref/sandbox-attack-test/SandboxAttackTest.java` 27/27 通过。
- **零 TODO / 问题编号体系**：几乎每处防御性代码标注审查编号（`CRIT-05`/`HIGH-09`/`NEW-C-01`/`M-01`/`MED-10`…），对应 `.trae/specs/codebase-code-review/` 那次 32 项全勾选的代码审查，以及 v2 提交说明中的 H-01/M-01/M-02/M-03/L-01~L-03 两轮修复。

### 3.5 adb / root / shell？
完全不涉及。grep `Runtime.getRuntime|ProcessBuilder|"su"` 零命中。权限依靠 Xposed 框架注入 + `MANAGE_EXTERNAL_STORAGE`（全盘文件访问，主动跳转授权页）。

---

## 4. 关键文件清单（按层全量，49 个）

**Hook 层（模块的"手术刀"）**
| 路径 | 行数 | 作用 |
|---|---|---|
| `xpmibackup/XposedEntry.java` | 98 | 模块总入口，按包名分流安装 Hook，并后台清理残留临时目录（符号链接安全删除 MED-10） |
| `xpmibackup/hook/AIDLHook.java` | **1486** | 全项目最核心：伪造 DFS 服务发现/绑定/Binder，签名匹配上传下载列目录重定向 |
| `xpmibackup/hook/AutoBackupHook.java` | 903 | 打通小米原生"NAS 自动备份"调度链路 |
| `xpmibackup/hook/BackupHook.java` | 775 | 修正备份 App 页面/通知/进度焦点/取消清理 |
| `xpmibackup/hook/SettingsHook.java` | 412 | 在系统设置注入入口，强制"智能存储"功能可见 |

**门面与工具层**
| 路径 | 行数 | 作用 |
|---|---|---|
| `comm/CloudFileHelp.java` | 621 | 门面层：统一 upload/download/list/delete，统一切片+manifest+AUTH_EXPIRED 重试 |
| `comm/CustomHttpFileHelp.java` | 905 | Rhino JS 执行引擎 + SSRF 防护 + 危险内置函数清除（沙箱 v2 改造后） |
| `comm/ScriptFunctions.java` | 749 | **沙箱 v2 新增**：脚本宿主函数库（http/state/crypto/file/console/utility 六组） |
| `comm/ScriptWatchdog.java` | 82 | **沙箱 v2 新增（v2 提交后已入库）**：指令计数看门狗，30s 超时防死循环/ReDoS |
| `comm/WebdavFileHelp.java` | 427 | WebDAV 协议（OkHttp + PROPFIND） |
| `comm/SmbFileHelp.java` | 362 | SMB/CIFS（smbj），复用 Session 批量删除 |
| `comm/ConfigHelp.java` | 223 | `config.ini` 读写，敏感键黑名单 + mtime 缓存 + ThreadLocal 账号参数覆盖 |
| `comm/LocalBackupFileHelp.java` | 171 | 从 fd 反查本地真实备份文件路径，管理 AllBackupTemp |
| `comm/AtomicFile.java` | 80 | 临时文件 + rename 原子写 |
| `comm/Async.java` | 67 | 共享守护线程池 |
| `comm/LogHelp.java` | 273 | 统一日志（脱敏），供脚本 console 落日志 |
| `comm/ProgressCallbackHelp.java` | 41 | 进度回调工具 |

**云端抽象层**
| 路径 | 行数 | 作用 |
|---|---|---|
| `cloud/CloudProvider.java` | 58 | 统一接口，约定"只处理整文件，切片归 CloudFileHelp" |
| `cloud/provider/AbstractCloudProvider.java` | 85 | **Provider 基类**（c86fd16 引入）：profile + 凭据 → overrides() → withAccount() 注入 |
| `cloud/ProviderRegistry.java` | 142 | 按备份目标分发（云盘账号优先 > NAS 激活方案） |
| `cloud/EncryptedCredStore.java` | 278 | AES-256-GCM + PBKDF2(20000) 凭据加密，跨进程共享密钥 |
| `cloud/provider/GuangyaProvider.java` | 850 | 光鸭云盘，OAuth2 refresh_token 自动轮换 |
| `cloud/provider/Yun139Provider.java` | 728 | 移动云盘139，mcloud-sign 签名 + 节点路由 |
| `cloud/login/Yun139Login.java` | 235 | 139 WebView 登录捕获与签名算法 |
| `cloud/Profile.java` / `ProfileStore.java` | 70/219 | NAS 方案模型与持久化 |
| `cloud/CloudAccount.java` / `CloudAccountStore.java` | 53/94 | 云盘账号模型与持久化 |
| `cloud/BackupTarget.java` | 98 | 跨进程备份目标持久化（cloud/profile 二选一） |
| `cloud/AccountDisplay.java` | 117 | 设置页账号展示模型 |
| `cloud/LoginContext.java` / `LoginState.java` | 24/25 | **登录抽象占位**（阶段一，未落地） |
| `cloud/RemoteEntry.java` | 20 | 远端文件条目，AIDL 层构造 DFS SmbFile 用（自 CloudFileHelp 迁出） |
| `cloud/CloudException.java` | 46 | 统一异常（AUTH_EXPIRED / REMOTE 等） |
| `cloud/RetryPolicy.java` | 94 | 重试策略 |
| `cloud/ProgressCallback.java` / `ListenerProgressCallback.java` | 19/65 | 进度回调接口与监听实现 |
| `cloud/provider/ScriptProvider.java` | 98 | 自定义脚本方案 Provider |
| `cloud/provider/SmbProvider.java` / `WebdavProvider.java` | 79/79 | SMB / WebDAV 方案 Provider |

**UI 层（MainActivity 容器 + 8 个 Fragment）**
| 路径 | 行数 | 作用 |
|---|---|---|
| `xpmibackup/MainActivity.java` | 523 | 主界面壳：4 Tab 桌面滑动式布局 + overlay 压层 + 主题切换 + 预测性返回手势（非纯容器，行数未变） |
| `xpmibackup/ui/WebViewLoginFragment.java` | 460 | WebView 网页登录抓取云盘凭据 |
| `xpmibackup/ui/ServiceConfigFragment.java` | 392 | NAS 方案（SMB/WebDAV/脚本）配置 |
| `xpmibackup/ui/BackupFragment.java` | 234 | 备份 Tab 界面 |
| `xpmibackup/ui/AccountConfigFragment.java` | 177 | 云盘账号管理 |
| `xpmibackup/ui/DeviceConfigFragment.java` | 146 | 虚拟设备配置 |
| `xpmibackup/ui/CloudProviderSelectFragment.java` | 124 | 备份目标（云盘/NAS）选择 |
| `xpmibackup/ui/SettingsFragment.java` | 107 | 设置页 |
| `xpmibackup/ui/AboutFragment.java` | 33 | 关于页 |

---

## 5. 安全防护（确实做了，非噱头）

- **Rhino 沙箱**：`setClassShutter(className -> false)` 全拒绝 + scope 不放任何 Java 实例（防 `getClass().forName()` 反射逃逸）；`setOptimizationLevel(-1)`。
- **危险内置函数清除（沙箱 v2）**：删除 `eval`/`Function`/`runCommand`/`spawn`/`sync`/`quit`/`load`。
- **SSRF 防护**：拦截 `10./172.16-31./192.168./169.254./127./::1/fc00::/7`，**对 302 重定向目标二次校验**（网络拦截器），处理八进制/十六进制 IP 编码绕过（`CustomHttpFileHelp`）；确需访问内网 NAS 可在 `config.ini` 设 `script_allow_private=true`。
- **脚本超时看门狗**：`ScriptWatchdog` 指令计数 + 30s deadline；`ScriptTimeoutError extends Error` 故意不继承 Exception，**防止被脚本 `catch(Exception)` 吞掉**。
- **宿主函数白名单（沙箱 v2）**：摘要算法仅 MD5/SHA-1/256/384/512，HMAC 仅 SHA1/256/384/512；AES 仅 GCM 禁 ECB；`readTempFile` 限 16MB 且流式读取消除 TOCTOU；临时文件 canonical 前缀校验防 `..` 穿越与绝对路径逃逸；console 输出 200 字符截断降低凭据落日志泄露面。
- **配置敏感键黑名单**：`ConfigHelp.SENSITIVE_KEYS` 写入层拦截，明文不落盘。
- **manifest 可信上限**：分片数 ≤ 100000、单文件 ≤ 1TB，防远端恶意 manifest 触发 OOM。

---

## 6. 代码质量观感（明显高于同类逆向/Hook 项目）

1. 全项目 0 个 TODO/FIXME/HACK。
2. 贯穿全代码的"问题编号"注释体系（CRIT/HIGH/MED/NEW-C/NEW-H/NEW-M/NEW-L/VRF-L/H/M/L），可追溯，对应系统性代码审查。
3. 安全防护真实落地（v2 还有 27/27 的攻击测试佐证）。
4. 注释解释"为什么"而非复述代码（如 `EncryptedCredStore` 顶部 12 行解释为何不用 Keystore）。
5. 命名方言：`*Help`（非常见 `*Helper`）、包名 `comm`=common；大量 `var`；方法级 Javadoc 覆盖近 100%。

---

## 7. 遗留问题与风险

| 项 | 说明 |
|---|---|
| `minifyEnabled false` | release 未开混淆（`build.gradle:49`），但 proguard 规则已写好，有意先关闭保稳定 |
| `docs/DEVELOPMENT.md` 与实现有偏差 | 本地开发文档（gitignored），包名(`com.zgcwkj`→`com.suileyan`)、加密方案(自实现 AES-GCM 而非 EncryptedSharedPreferences)、账号模型拆分均与实现不同（实现更优，文档未回写） |
| README 文档缺口 | README（15:00 重写）与架构图**未提及** `AbstractCloudProvider` / `ScriptFunctions` / `ScriptWatchdog` / 登录抽象占位 —— "文档从未跟上"，非代码变更 |
| README:44 错别字 | "zndroid 9.0+" 应为 "Android"（README 重写时手误） |
| 加密方案固有弱点 | `KEY_SEED` 硬编码常量（`EncryptedCredStore:48`），拿到 APK + creds.json 即可解密——跨进程需求下的必然妥协 |
| UI 用废弃 API | `android.app.Fragment`，API 28+ deprecated，未来可能移除 |
| `Locale` 默认值 | 设备名按 `Locale.getDefault()` 硬判 zh，非中非英环境 fallback 英文 |

**已修复/已过时的旧条目（不再列为问题）：**
- ~~`ScriptWatchdog.java` 未提交~~ → 已在 v2 提交 `0638bfd` 入库（首版报告生成时刻确实未跟踪，当时正确、现已过时）。
- ~~`plugins/README.md` 文档过期（仍说脚本存 config.ini）~~ → v2 已重写（+133 行），现明确脚本内容存 `EncryptedCredStore`（`creds.json`，AES-GCM），且新增 `hmac-sign-example.js` 示例。
- ~~`ref/` 空目录~~ → 现含 `ref/sandbox-attack-test/SandboxAttackTest.java`（沙箱攻击测试）。

---

## 8. 人工核验结论

- ✅ `XposedEntry.handleLoadPackage` 确按包名分流：`settings`→SettingsHook；`miui.backup`→BackupHook+AutoBackupHook+AIDLHook+后台清理。临时目录删除不跟随符号链接（MED-10）。
- ✅ `AIDLHook` 头部确伪造 DFS 服务（`com.milink.service` / `DistFileClientService`，DESCRIPTOR=`com.xiaomi.dist.file.client.common.IDistFileClientKit`），含 interfaceDescriptor 弱引用缓存（CRIT-07/NEW-M-02）、双重检查锁定线程池（CRIT-05）、守护线程工厂（NEW-H-01）；上传 `w1`/`upload`、下载 `G0`/`download` 双名兜底，签名匹配分发（`AIDLHook:279/317/321/1320`）。
- ✅ `git log` 共 14 次提交（init → v0.1.0 包名迁移 → 重写 README → 沙箱 v2），HEAD=`0638bfd`；`main` 跟踪 `origin/main`。工作区未跟踪：`CODEBASE-READING.md`（本报告）、`ref/`（沙箱攻击测试）。
- ✅ v2 提交 `0638bfd`（16:30）实际只改 8 个文件：`CustomHttpFileHelp`(改)、`ScriptFunctions`(新749)、`ScriptWatchdog`(新82)、`plugins/README.md`(改+133)、`plugins/example.js`(改)、`plugins/hmac-sign-example.js`(新109)、`plugins/yun139.js`(改)、`res/raw/custom_default.js`(改)；`assembleDebug BUILD SUCCESSFUL`，攻击测试 27/27。
- ✅ **归因勘误**：`AbstractCloudProvider` / `LoginContext` / `LoginState` / `RemoteEntry` / 8 个 UI Fragment 均由 `git log -- <file>` 核实，最早出现在 `c86fd16`（v0.1.0 包名迁移），**早于首版报告**——首版把它们误归为 v2 新增，实为报告精选清单未列全 + 文档（README/报告）未跟上。

---

## 9. 附：plugins/、docs/、ref/、.trae/

- **plugins/**（不参与 Android 编译，供用户复制粘贴）：`example.js`（通用 HTTP 模板，与 APK 内 `res/raw/custom_default.js` 一致）、`yun139.js`（139 完整 JS 实现）、`hmac-sign-example.js`（v2 新增，HMAC 签名示例）、`README.md`（脚本开发文档，已更新为 creds.json 存储说明）。脚本 API 契约：6 个可实现函数（testConnection/listEntries/parseList/uploadFile/downloadFile/deletePath）+ 宿主函数（httpRequest/httpDownload/httpRequestMultipart/httpHead/getResponseHeader/stateGet/stateSet/base64Encode/base64Decode/hashHex/hashHexBytes/hmacHex/hmacBase64/aesEncrypt/aesDecrypt/sha256Hex/md5Hex/tempFile/tempFileName/readTempFile/writeTempFile/deleteTempFile/fileHashHex/fileSize/console.log/uuid/timestampSeconds/timestampMillis/randomHex）。
- **docs/DEVELOPMENT.md**（20KB，gitignored，本地开发文档）：分两阶段重构迁移施工方案，计划已基本落地但有偏差（见第 7 节）。
- **ref/**：`sandbox-attack-test/SandboxAttackTest.java`（沙箱攻击测试，27/27）。
- **.trae/**：IDE 生成的代码审查任务与文档，checklist 32 项全勾选（安全/性能/最佳实践/Xposed 特定检查）。
