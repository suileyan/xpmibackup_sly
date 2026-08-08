# 自定义 HTTP 脚本

这个目录用于存放可以粘贴到 App 自定义 HTTP 配置里的 JS 脚本。

- `example.js`：通用 HTTP 服务示例
- `yun139.js`：移动云盘 139 示例
- `hmac-sign-example.js`：HMAC-SHA256 签名请求示例

App 内置默认模板在 `src/app/src/main/res/raw/custom_default.js`，用于设置页首次打开时展示。`plugins` 目录下的脚本不打包进 App，适合单独维护、测试和复制到手机上使用

## 使用方式

在设置页选择自定义 HTTP，把完整 JS 脚本粘贴到脚本输入框。服务器地址、Token、Cookie、签名和刷新逻辑都写在 JS 里，界面不会再单独提供这些输入项。

脚本内容（Base64）会保存到 `EncryptedCredStore`（`/sdcard/MIUI/backup/creds.json`，AES-GCM 加密落盘），按脚本方案 id 隔离，明文不会出现在 `config.ini` 上。脚本需要持久化刷新后的 Cookie 或 Token 时，使用 `stateGet` 和 `stateSet`，同样加密存储

不要把真实 Cookie、Token、手机号或抓包内容提交到公开仓库。发布示例脚本时请使用占位值

## 运行环境与安全边界

脚本运行在 Rhino JS 引擎（解释模式），遵循以下安全边界：

- **不能访问任何 Java 类**：`ClassShutter` 全拒绝，`Java.type`、`java.lang.Runtime`、反射、`getClass().forName` 全部不可用
- **eval / Function / runCommand / spawn / sync / quit / load 已禁用**：动态代码生成与命令执行入口被移除
- **单次调用 30 秒超时**：死循环、ReDoS（正则指数级回溯）会被指令计数看门狗自动终止
- **网络请求受限**：默认禁止访问内网/私有地址（10.x、172.16-31.x、192.168.x、169.254.x、127.x、::1、fc00::/7 等），302 重定向目标同样校验；确需访问内网 NAS 时可在 `config.ini` 配置 `script_allow_private=true`
- **临时文件受限**：文件操作只能读写当前脚本账号的临时目录（`/sdcard/MIUI/backup/script_temp/<账号id>/`），路径穿越会被拒绝
- **算法白名单**：摘要仅 `MD5`/`SHA-1`/`SHA-256`/`SHA-384`/`SHA-512`，HMAC 仅 `HmacSHA1`/`HmacSHA256`/`HmacSHA384`/`HmacSHA512`，其余算法名直接拒绝

正则表达式（含命名捕获组、lookbehind、Unicode 模式）在 Rhino 1.9.x 中可用，无需回避正则字面量。

## 切片规则

切片大小由设置页的 `chunk_size_mb` 控制：

| 值 | 行为 |
| --- | --- |
| `0` | 不切片，只上传原文件 |
| `> 0` | 大于该大小的文件会在 Cloud 层切片 |

启用切片时，Cloud 层会生成：

```text
原文件.part00000
原文件.part00001
原文件.mibak.json
```

脚本不需要理解切片格式。对脚本来说，`uploadFile(ctx)` 和 `downloadFile(ctx)` 收到的都是普通远端路径，可能是原文件，也可能是内部 part 或 manifest 文件。恢复时优先读取 manifest 并合并分片，manifest 不存在时会按旧版未切片文件读取。

## 脚本函数

脚本可实现这些函数：

```js
function testConnection(ctx) { return true; }
function listEntries(ctx) { return { method, url, headers, body }; }
function parseList(ctx, response) { return [{ name, size, directory, modifiedTime }]; }
function uploadFile(ctx) { return { method, url, headers }; }
function downloadFile(ctx) { return { method, url, headers }; }
function deletePath(ctx) { return { method, url, headers }; }
```

| 函数 | 说明 |
| --- | --- |
| `testConnection(ctx)` | 测试连接，必须返回 `true` 或 `false` |
| `listEntries(ctx)` | 列出 `ctx.remoteDir`，可直接返回条目数组，也可返回请求对象并配合 `parseList` |
| `parseList(ctx, response)` | 把 HTTP 响应转换为 `[{ name, size, directory, modifiedTime }]` |
| `uploadFile(ctx)` | 上传 `ctx.remotePath` 对应的单个普通文件，可返回请求对象，也可自行上传后返回 `{ handled: true }` |
| `downloadFile(ctx)` | 下载 `ctx.remotePath` 到当前恢复目标，可返回请求对象，也可调用 `httpDownload` 后返回 `{ handled: true }` |
| `deletePath(ctx)` | 删除 `ctx.remotePath`，可返回请求对象，也可自行删除后返回 `{ handled: true }` |

`uploadFile`、`downloadFile` 和 `deletePath` 有两种写法：

```js
// 写法一：返回请求对象，由 Java 执行单个 HTTP 请求
function downloadFile(ctx) {
  return {
    method: 'GET',
    url: 'https://example.com/files/' + encodeURIComponent(ctx.remotePath),
    headers: { Authorization: 'Bearer token' }
  };
}

// 写法二：脚本自己完成多步流程，最后返回 handled
function downloadFile(ctx) {
  var response = httpDownload({
    method: 'GET',
    url: 'https://example.com/files/' + encodeURIComponent(ctx.remotePath),
    headers: { Authorization: 'Bearer token' }
  });
  return { handled: response.code >= 200 && response.code < 300 };
}
```

## ctx 字段

| 字段 | 说明 |
| --- | --- |
| `backupPath` | 备份根目录配置 |
| `remoteDir` | 当前要列出的远端目录，或上传文件所在目录 |
| `remotePath` | 当前要上传、下载或删除的远端路径 |
| `fileName` | 当前上传文件名 |
| `fileSize` | 当前上传文件大小 |
| `contentHash` | 当前上传文件的 SHA256 |
| `contentHashAlgorithm` | 当前上传文件哈希算法，当前为 `SHA256` |
| `localPath` | 当前下载目标路径 |

## 请求对象

返回给 Java 执行的 HTTP 请求对象格式：

```js
{
  method: 'GET',
  url: 'https://example.com/api',
  headers: {
    Authorization: 'Bearer token'
  },
  body: '',
  streamFile: false,
  readBody: true,
  connectTimeout: 10,
  readTimeout: 60,
  writeTimeout: 60
}
```

| 字段 | 说明 |
| --- | --- |
| `method` | HTTP 方法，默认 `GET` |
| `url` | 完整请求地址 |
| `headers` | 请求头对象 |
| `body` | 文本请求体 |
| `streamFile` | 上传当前文件时设为 `true`，Java 会把当前本地文件作为请求体流式上传 |
| `readBody` | 是否读取响应体，默认 `true`；大文件流式下载场景可设为 `false` |
| `connectTimeout` | 连接超时（秒），上限 300，可选 |
| `readTimeout` | 读超时（秒），上限 300，可选 |
| `writeTimeout` | 写超时（秒），上限 300，可选 |

## 宿主函数

脚本里可直接调用这些通用辅助函数（v2 完整清单）：

```js
// ---- HTTP ----
httpRequest(spec)                          // 普通请求，返回 { code, body, headers }；spec 支持自定义超时
httpDownload(spec)                         // 下载并把响应流写入当前下载目标文件
httpRequestMultipart(spec)                 // multipart/form-data 表单上传
httpHead(url, headers)                     // HEAD 请求（探测大小 / ETag）
getResponseHeader(response, name)          // 取响应头（大小写不敏感，多值逗号拼接）

// ---- 状态 ----
stateGet(key, defaultValue)                // 读取加密持久化状态
stateSet(key, value)                       // 写入加密持久化状态

// ---- 编码 / 哈希 / 加密 ----
base64Encode(text)                         // UTF-8 文本 -> Base64
base64Decode(text)                         // Base64 -> UTF-8 文本
hashHex(algorithm, text)                   // 文本摘要 hex，如 hashHex('MD5', text)
hashHexBytes(algorithm, base64Bytes)       // Base64 字节数据摘要 hex
hmacHex(algorithm, key, message)           // HMAC 摘要 hex，如 hmacHex('HmacSHA1', key, msg)
hmacBase64(algorithm, key, message)        // HMAC 摘要 Base64（AWS SigV4 风格）
aesEncrypt(keyBase64, ivBase64, data)      // AES-GCM 加密 -> Base64 密文
aesDecrypt(keyBase64, ivBase64, dataBase64)// AES-GCM 解密 -> 文本
sha256Hex(text)                            // SHA-256 快捷封装
md5Hex(text)                               // MD5 快捷封装

// ---- 文件（仅限脚本临时目录）----
tempFile(prefix, suffix)                   // 创建临时文件，返回绝对路径
tempFileName()                             // 生成唯一临时文件名（不创建文件）
readTempFile(path)                         // 读临时文件为 Base64（限 16MB）
writeTempFile(path, base64Data)            // 写 Base64 数据到临时文件
deleteTempFile(path)                       // 删除临时文件
fileHashHex(path, algorithm)               // 流式计算文件哈希（大文件）
fileSize(path)                             // 返回文件大小

// ---- 工具 / 日志 ----
uuid()                                     // 随机 UUID（请求 ID）
timestampSeconds()                         // 秒级时间戳
timestampMillis()                          // 毫秒时间戳
randomHex(byteLength)                      // SecureRandom 随机 hex（nonce）
console.log(...args)                       // 调试日志
console.error(...args)                     // 错误日志
```

| 函数 | 说明 |
| --- | --- |
| `httpRequest(spec)` | 发起普通 HTTP 请求，返回 `{ code, body, headers }`；上传当前文件时设置 `streamFile: true`；spec 直接支持 `connectTimeout` / `readTimeout` / `writeTimeout`（秒，上限 300） |
| `httpDownload(spec)` | 发起 HTTP 请求，并把响应流写入当前下载目标文件，返回 `{ code, body, headers }` |
| `httpRequestMultipart(spec)` | multipart/form-data 上传；`fields` 为文本字段，`parts` 为文件（`file` 必须是 `tempFile()` 创建的路径） |
| `httpHead(url, headers)` | 执行 HEAD 请求，适合探测文件大小、ETag |
| `getResponseHeader(response, name)` | 从响应对象取指定响应头，大小写不敏感；同名多值时以逗号拼接返回 |
| `stateGet(key, defaultValue)` | 读取脚本持久化状态，适合保存刷新后的 Cookie 或 Token（加密存储，按账号隔离） |
| `stateSet(key, value)` | 写入脚本持久化状态 |
| `base64Encode(text)` | 把 UTF-8 文本编码为 Base64 |
| `base64Decode(text)` | 把 Base64 解码为 UTF-8 文本 |
| `hashHex(algorithm, text)` | 计算文本摘要，例如 `MD5`、`SHA-1`、`SHA-256` |
| `hashHexBytes(algorithm, base64Bytes)` | 对 Base64 编码的二进制数据计算摘要（例如分片内容哈希） |
| `hmacHex(algorithm, key, message)` | HMAC 计算，输出小写 hex；算法如 `HmacSHA1`、`HmacSHA256`（OSS / 阿里云网关签名） |
| `hmacBase64(algorithm, key, message)` | HMAC 计算，输出 Base64（AWS SigV4 风格签名） |
| `aesEncrypt(keyBase64, ivBase64, data)` | AES-256-GCM 加密（key 为 32 字节 Base64，iv 为 12 字节 Base64），输出 Base64 密文 |
| `aesDecrypt(keyBase64, ivBase64, dataBase64)` | AES-256-GCM 解密，返回 UTF-8 文本；密钥/IV/密文均为 Base64 |
| `sha256Hex(text)` / `md5Hex(text)` | 常用摘要快捷封装 |
| `tempFile(prefix, suffix)` | 在当前脚本账号临时目录创建临时文件并返回绝对路径；prefix/suffix 不允许路径分隔符或 `..` |
| `tempFileName()` | 生成唯一临时文件名（不落盘），适合用作远端唯一文件名 |
| `readTempFile(path)` | 读取临时文件为 Base64，单次上限 16MB；文件不存在抛异常 |
| `writeTempFile(path, base64Data)` | 把 Base64 数据写入临时文件；文件不存在（如父目录未创建）抛异常 |
| `deleteTempFile(path)` | 删除临时文件；文件不存在返回 `false`（幂等清理） |
| `fileHashHex(path, algorithm)` | 流式计算文件哈希（8KB buffer，不整文件读入内存），适合大文件秒传；文件不存在抛异常；算法限 `MD5`/`SHA-1`/`SHA-256`/`SHA-384`/`SHA-512` |
| `fileSize(path)` | 返回临时文件大小（字节）；文件不存在返回 `0` |
| `uuid()` | 生成随机 UUID 字符串 |
| `timestampSeconds()` / `timestampMillis()` | 秒 / 毫秒级当前时间戳 |
| `randomHex(byteLength)` | 用 SecureRandom 生成指定字节长度的随机 hex（nonce / 请求 ID） |
| `console.log(...)` / `console.error(...)` | 输出到模块日志；引擎不做凭据脱敏，请勿在参数中打印 Token/密码（超长参数自动截断） |

### httpRequestMultipart 示例

```js
var filePath = tempFile('upload', '.bin');
writeTempFile(filePath, base64Encode('hello world'));

var response = httpRequestMultipart({
  method: 'POST',
  url: 'https://example.com/upload',
  headers: { Authorization: 'Bearer token' },
  fields: { path: '/backup/20260101_000000', mode: 'full' },
  parts: [
    { name: 'file', file: filePath, filename: 'backup.bin', contentType: 'application/octet-stream' }
  ]
});
deleteTempFile(filePath);
```

### 签名示例（HMAC）

```js
var timestamp = timestampSeconds();
var signString = 'GET\n' + '/backup/file\n' + timestamp;
var signature = hmacBase64('HmacSHA256', 'your-secret-key', signString);

var response = httpRequest({
  method: 'GET',
  url: 'https://example.com/files/backup',
  headers: {
    'X-Timestamp': String(timestamp),
    'X-Signature': signature
  }
});
```

完整可运行示例见 `hmac-sign-example.js`。

## 最小示例

```js
var SERVER = 'https://example.com/backup';
var TOKEN = 'replace-with-your-token';

function joinUrl(base, path) {
  base = trimRightSlash(base);
  path = trimLeftSlash(path);
  return base + '/' + path.split('/').map(encodeURIComponent).join('/');
}

function trimLeftSlash(value) {
  value = String(value || '');
  while (value.length > 0 && value.charAt(0) === '/') {
    value = value.substring(1);
  }
  return value;
}

function trimRightSlash(value) {
  value = String(value || '');
  while (value.length > 0 && value.charAt(value.length - 1) === '/') {
    value = value.substring(0, value.length - 1);
  }
  return value;
}

function authHeaders() {
  return { Authorization: 'Bearer ' + stateGet('token', TOKEN) };
}

function testConnection(ctx) {
  var response = httpRequest({
    method: 'GET',
    url: SERVER,
    headers: authHeaders()
  });
  return response.code >= 200 && response.code < 400;
}

function listEntries(ctx) {
  return {
    method: 'GET',
    url: joinUrl(SERVER, ctx.remoteDir) + '?list=1',
    headers: authHeaders()
  };
}

function parseList(ctx, response) {
  return JSON.parse(response.body || '[]');
}

function uploadFile(ctx) {
  return {
    method: 'PUT',
    url: joinUrl(SERVER, ctx.remotePath),
    headers: authHeaders(),
    streamFile: true
  };
}

function downloadFile(ctx) {
  return {
    method: 'GET',
    url: joinUrl(SERVER, ctx.remotePath),
    headers: authHeaders()
  };
}

function deletePath(ctx) {
  return {
    method: 'DELETE',
    url: joinUrl(SERVER, ctx.remotePath),
    headers: authHeaders()
  };
}
```
