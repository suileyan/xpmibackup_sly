// 自定义 HTTP 存储脚本示例：HMAC-SHA256 签名请求
//
// 适用场景：服务端要求对每个请求做 HMAC 签名（如 OSS、阿里云网关、自建签名服务）。
// 本例演示如何用宿主提供的 hmacBase64 / hmacHex / timestampSeconds 构造签名头。
//
// 宿主函数说明（完整清单见 README.md）：
// hmacHex(algorithm, key, message)    HMAC 摘要（小写 hex），例如 hmacHex('HmacSHA256', key, msg)
// hmacBase64(algorithm, key, message)  HMAC 摘要（Base64）
// timestampSeconds()                   当前秒级时间戳
// httpRequest(spec)                    执行 HTTP 请求，返回 { code, body, headers }
//
// 安全边界：脚本无法访问任何 Java 类；eval/Function/runCommand 等已禁用；
// 单次调用 30 秒超时；默认禁止内网地址。

// 服务端根地址（占位值，使用前请替换）
var SERVER = 'https://example.com/backup';

// 签名密钥与账号（占位值；生产环境建议用 stateSet 持久化密钥变更）
var ACCESS_KEY = 'your-access-key';
var SECRET_KEY = 'your-secret-key';

// 拼接 URL，逐段 encode 远端路径
function joinUrl(base, path) {
  base = String(base || '');
  path = String(path || '');
  while (base.length > 0 && base.charAt(base.length - 1) === '/') {
    base = base.substring(0, base.length - 1);
  }
  while (path.charAt(0) === '/') {
    path = path.substring(1);
  }
  return base + '/' + path.split('/').map(encodeURIComponent).join('/');
}

// 构造签名
// 签名串格式：METHOD\nPATH\nTIMESTAMP\nSECRET
// 实际签名算法以你的服务端约定为准，这里演示常见模式
function signRequest(method, url, body) {
  var timestamp = String(timestampSeconds());
  var path = url.replace(SERVER, '') || '/';
  if (path.charAt(0) !== '/') {
    path = '/' + path;
  }
  var stringToSign = method + '\n' + path + '\n' + timestamp + '\n' + (body || '');
  var signature = hmacBase64('HmacSHA256', SECRET_KEY, stringToSign);
  return {
    'X-Access-Key': ACCESS_KEY,
    'X-Timestamp': timestamp,
    'X-Signature': signature
  };
}

// 构造带签名的请求对象
function signedRequest(method, url, body, streamFile, readBody) {
  var spec = {
    method: method,
    url: url,
    headers: signRequest(method, url, body)
  };
  if (body !== undefined && body !== null) {
    spec.body = body;
  }
  if (streamFile) {
    spec.streamFile = true;
  }
  if (readBody === false) {
    spec.readBody = false;
  }
  return spec;
}

// 测试连接：签名 GET 根路径，2xx/3xx 视为成功
function testConnection(ctx) {
  var response = httpRequest(signedRequest('GET', SERVER, null));
  return response.code >= 200 && response.code < 400;
}

// 列目录：返回请求对象，由 Java 执行后交给 parseList 解析
function listEntries(ctx) {
  return signedRequest('GET', joinUrl(SERVER, ctx.remoteDir) + '?list=1', null);
}

// 解析列目录响应，服务端返回 JSON 数组
// [{ "name": "20260101_000000", "size": 0, "directory": true, "modifiedTime": 1784394123000 }]
function parseList(ctx, response) {
  return JSON.parse(response.body || '[]');
}

// 上传：streamFile=true 使用当前文件流，readBody=false 不读响应体
function uploadFile(ctx) {
  return signedRequest('PUT', joinUrl(SERVER, ctx.remotePath), null, true, false);
}

// 下载：返回请求对象，Java 流式写入本地
function downloadFile(ctx) {
  return signedRequest('GET', joinUrl(SERVER, ctx.remotePath), null);
}

// 删除
function deletePath(ctx) {
  return signedRequest('DELETE', joinUrl(SERVER, ctx.remotePath), null);
}

// 校验服务端返回的签名（可选，双向签名场景）
// function verifyServer(response) {
//   var expected = hmacHex('HmacSHA256', SECRET_KEY,
//     String(response.code) + '\n' + getResponseHeader(response, 'X-Timestamp'));
//   return getResponseHeader(response, 'X-Server-Signature') === expected;
// }
