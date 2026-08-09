package com.suileyan.comm;

import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * WebDAV文件操作工具类
 * 基于OkHttp实现WebDAV协议（PROPFIND/MKCOL/PUT/GET）
 * OkHttp原生支持自定义HTTP方法，无Android兼容性问题
 */
public class WebdavFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final int BUFFER_SIZE = 131072;
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final String XML_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>";

    // PROPFIND成功响应的HTTP状态码（Multi-Status），用于准确判断连接是否真正可达
    private static final int HTTP_MULTISTATUS = 207;

    // PROPFIND 解析正则（预编译，避免每次列表都重新编译，NEW-L-15）
    private static final Pattern HREF_PATTERN =
            Pattern.compile("<\\w*:?href>([^<]+)</\\w*:?href>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_PATTERN =
            Pattern.compile("<\\w*:?response[\\s\\S]*?</\\w*:?response>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LENGTH_PATTERN =
            Pattern.compile("<\\w*:?getcontentlength>(\\d+)</\\w*:?getcontentlength>", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIR_PATTERN =
            Pattern.compile("<\\w*:?collection\\s*/?>", Pattern.CASE_INSENSITIVE);

    // 单例OkHttpClient：内部含连接池/线程池，必须全局复用，否则每次new都会重新握手且造成资源泄漏
    private static volatile OkHttpClient sClient;

    // ========== 连接管理 ==========

    /** 显式开启 webdav_insecure_tls=true 时的信任所有证书客户端 */
    private static volatile OkHttpClient sInsecureClient;

    /**
     * 获取OkHttpClient
     * 默认走系统证书校验（防 MITM 窃取 Basic 凭据）；
     * 仅当配置显式开启 webdav_insecure_tls=true 时（内网自签名 NAS 场景）才信任所有证书，
     * 并单独缓存，两种模式互不串扰。
     */
    private static OkHttpClient getClient() {
        var insecure = "true".equalsIgnoreCase(ConfigHelp.getString("webdav_insecure_tls", "false"));
        if (!insecure) {
            var c = sClient;
            if (c != null) return c;
            synchronized (WebdavFileHelp.class) {
                if (sClient == null) {
                    sClient = new OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.MINUTES)
                            .writeTimeout(10, TimeUnit.MINUTES)
                            .retryOnConnectionFailure(true)
                            .protocols(java.util.List.of(okhttp3.Protocol.HTTP_1_1))
                            .build();
                }
                return sClient;
            }
        }
        var c = sInsecureClient;
        if (c != null) return c;
        synchronized (WebdavFileHelp.class) {
            if (sInsecureClient != null) return sInsecureClient;
            var trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c2, String a2) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c2, String a2) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }
            };
            var builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true)
                .protocols(java.util.List.of(okhttp3.Protocol.HTTP_1_1));
            try {
                var sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((h, s) -> true);
            } catch (Exception ignored) {}
            sInsecureClient = builder.build();
            return sInsecureClient;
        }
    }

    /** 获取WebDAV基础URL */
    private static String baseUrl() {
        var url = ConfigHelp.getString("webdav_url", "");
        if (!url.endsWith("/")) url += "/";
        return url;
    }

    /** 构建带Basic Auth的Request.Builder */
    private static Request.Builder newRequest(String url) {
        var builder = new Request.Builder().url(url);
        var user = ConfigHelp.getString("webdav_user", "");
        var pass = ConfigHelp.getString("webdav_pass", "");
        if (!user.isEmpty()) {
            builder.header("Authorization", okhttp3.Credentials.basic(user, pass));
        }
        return builder;
    }

    /** 按URL路径段编码，避免中文备份文件名在WebDAV GET/PUT时被服务器拒绝 */
    private static String remoteUrl(String remotePath) {
        var base = baseUrl();
        if (remotePath == null || remotePath.isEmpty()) {
            return base;
        }
        var encoded = new StringBuilder();
        for (var part : remotePath.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (encoded.length() > 0) {
                encoded.append("/");
            }
            encoded.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return base + encoded;
    }

    /** 执行PROPFIND请求，返回[状态码, 响应XML] */
    private static String[] propfind(String url, int depth) throws Exception {
        var body = RequestBody.create(XML, XML_BODY);
        var request = newRequest(url).method("PROPFIND", body)
            .header("Depth", String.valueOf(depth)).build();
        try (var resp = getClient().newCall(request).execute()) {
            var xml = resp.body() != null ? resp.body().string() : "";
            return new String[]{ String.valueOf(resp.code()), xml };
        }
    }

    // ========== 公共方法 ==========

    /** 测试WebDAV连接是否可达（必须返回207 Multi-Status才算真正成功） */
    public static boolean testConnection() throws Exception {
        var url = baseUrl();
        var res = propfind(url, 0);
        var ok = Integer.parseInt(res[0]) == HTTP_MULTISTATUS;
        LogHelp.i(TAG, "WebDAV test " + (ok ? "OK" : "FAILED") + " http=" + res[0] + " host=" + hostOf(url));
        return ok;
    }

    /** 列出backup_path目录中的备份子目录名 */
    public static List<String> listDirs() throws Exception {
        var dirs = new ArrayList<String>();
        var backupPath = ConfigHelp.getString("backup_path", "");
        var entries = listDirectory(backupPath);
        for (var name : entries) {
            if (!name.isEmpty()) dirs.add(name);
        }
        LogHelp.i(TAG, "WebDAV listDirs OK path=" + backupPath + " count=" + dirs.size());
        return dirs;
    }

    /** 列出指定路径下的子项名称 */
    private static List<String> listDirectory(String path) throws Exception {
        var names = new ArrayList<String>();
        var url = remoteUrl(path);
        if (!url.endsWith("/")) url += "/";
        // propfind返回[状态码, xml]，取响应体
        var xml = propfind(url, 1)[1];
        // 兼容不同服务器的命名空间前缀（D:/d:/oc:/lp1:/无前缀），大小写不敏感
        // PROPFIND首个href是当前目录本身，跳过它
        var matcher = HREF_PATTERN.matcher(xml);
        var first = true;
        while (matcher.find()) {
            var href = matcher.group(1);
            var clean = href.replaceAll("/$", "");
            var lastSlash = clean.lastIndexOf('/');
            var name = lastSlash >= 0 ? clean.substring(lastSlash + 1) : clean;
            // 第一个 href 是目录自身，无条件跳过（即使为空名也不影响后续推进，NEW-L-16）
            if (first) {
                first = false;
                continue;
            }
            if (name.isEmpty()) continue;
            names.add(decodeName(name));
        }
        return names;
    }

    /** 列出指定WebDAV目录下的文件条目，路径相对于WebDAV根URL */
    public static List<RemoteEntry> listEntries(String path) throws Exception {
        var entries = new ArrayList<RemoteEntry>();
        var url = remoteUrl(path);
        if (!url.endsWith("/")) url += "/";
        var xml = propfind(url, 1)[1];
        var matcher = RESPONSE_PATTERN.matcher(xml);
        var first = true;
        while (matcher.find()) {
            var block = matcher.group();
            var hrefMatcher = HREF_PATTERN.matcher(block);
            if (!hrefMatcher.find()) continue;
            var href = hrefMatcher.group(1).replaceAll("/$", "");
            var lastSlash = href.lastIndexOf('/');
            var name = lastSlash >= 0 ? href.substring(lastSlash + 1) : href;
            // 第一个 response 是目录自身，无条件跳过（即使 name 为空也不影响后续推进，VRF-M-01）
            if (first) {
                first = false;
                continue;
            }
            if (name.isEmpty()) continue;
            var lengthMatcher = LENGTH_PATTERN.matcher(block);
            var isDir = DIR_PATTERN.matcher(block).find();
            var size = (!isDir && lengthMatcher.find()) ? Long.parseLong(lengthMatcher.group(1)) : 0L;
            // 服务端 href 是 URL 编码的，解码后返回避免二次编码（NEW-M-08）
            entries.add(new RemoteEntry(decodeName(name), size, isDir, System.currentTimeMillis()));
        }
        LogHelp.i(TAG, "WebDAV listEntries OK dir=" + path + " count=" + entries.size());
        return entries;
    }

    /** URL 解码 WebDAV 返回的名称（NEW-M-08）；解码失败时原样返回 */
    private static String decodeName(String name) {
        try {
            return java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Exception e) {
            return name;
        }
    }

    /** 创建远程目录（MKCOL） */
    public static void mkdir(String path) throws Exception {
        var url = remoteUrl(path);
        if (!url.endsWith("/")) url += "/";
        var request = newRequest(url).method("MKCOL", null).build();
        try (var resp = getClient().newCall(request).execute()) {
            // 必须消费响应体，否则 OkHttp 关闭连接时可能抛 EIO，且影响连接复用
            if (resp.body() != null) resp.body().close();
        }
    }

    /** 递归创建目录链 */
    public static void mkdirs(String remoteDir) throws Exception {
        var parts = remoteDir.split("/");
        var current = "";
        for (var part : parts) {
            if (part.isEmpty()) continue;
            current = current.isEmpty() ? part : current + "/" + part;
            try { mkdir(current); } catch (Exception ignored) {}
        }
    }

    /** 删除远端目录及其所有内容
     * 按RFC 4918，WebDAV的DELETE天然递归删除非空集合，无需客户端先列再删
     * 少数不支持递归删除的服务器再走回退方案
     */
    public static void deleteDir(String remoteDir) throws Exception {
        LogHelp.i(TAG, "WebDAV delete dir start: " + remoteDir);
        var url = remoteUrl(remoteDir);
        if (!url.endsWith("/")) url += "/";
        var request = newRequest(url).method("DELETE", null).build();
        var code = 0;
        try (var resp = getClient().newCall(request).execute()) {
            code = resp.code();
            if (resp.body() != null) resp.body().close();
        }
        // 2xx/3xx视为成功；若服务器不支持递归删除（409/501等），回退到逐项删除
        if (code < 200 || code >= 400) {
            try {
                var entries = listDirectory(remoteDir);
                for (var name : entries) {
                    if (name.isEmpty()) continue;
                    try { deleteDir(remoteDir + "/" + name); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            var retry = newRequest(url).method("DELETE", null).build();
            try (var resp = getClient().newCall(retry).execute()) {
                if (resp.body() != null) resp.body().close();
            }
        }
    }

    /** 删除远端单个文件（WebDAV DELETE） */
    public static void deleteFile(String remotePath) throws Exception {
        var url = remoteUrl(remotePath);
        var request = newRequest(url).method("DELETE", null).build();
        try (var resp = getClient().newCall(request).execute()) {
            if (resp.body() != null) resp.body().close();
            LogHelp.i(TAG, "WebDAV delete file " + (resp.code() >= 200 && resp.code() < 300 ? "done" : "failed http=" + resp.code())
                    + ": " + remotePath);
        }
    }

    // ========== 上传 ==========

    /** 上传本地文件到WebDAV（无进度回调） */
    public static String upload(String localPath, String remoteDir) throws Exception {
        var localFile = new File(localPath);
        if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localPath);
        mkdirs(remoteDir);
        var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();
        LogHelp.i(TAG, "WebDAV upload start: " + remotePath + " size=" + localFile.length());
        var requestBody = RequestBody.create(MediaType.parse("application/octet-stream"), localFile);
        var request = newRequest(remoteUrl(remotePath)).put(requestBody).build();
        try (var resp = getClient().newCall(request).execute()) {
            var code = resp.code();
            // 必须消费响应体，否则 OkHttp 关闭连接时可能抛 EIO 且无法复用连接（NEW-L-03）
            if (resp.body() != null) resp.body().close();
            if (code >= 200 && code < 300) {
                LogHelp.i(TAG, "WebDAV upload done: " + remotePath + " size=" + localFile.length());
                return "OK: " + remotePath + " (" + localFile.length() + " bytes)";
            }
            LogHelp.w(TAG, "WebDAV upload failed http=" + code + " path=" + remotePath);
            return "ERROR: HTTP " + code;
        }
    }

    /** 上传文件并回调进度（备份用） */
    public static void uploadToWebdav(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws Exception {
        var localFile = new File(localPath);
        if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localPath);

        var fileSize = localFile.length();

        mkdirs(remoteDir);
        var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();
        LogHelp.i(TAG, "WebDAV backup upload start: " + remotePath + " size=" + fileSize);

        // try-with-resources保证异常时fis被关闭，避免文件句柄泄漏
        try (var fis = new FileInputStream(localFile)) {
            var progressBody = new ProgressRequestBody(fis, fileSize, cb, taskId);
            var request = newRequest(remoteUrl(remotePath)).put(progressBody).build();
            try (var resp = getClient().newCall(request).execute()) {
                var code = resp.code();
                // 必须消费response body，否则OkHttp关闭连接时会抛出EIO异常
                if (resp.body() != null) resp.body().string();
                LogHelp.i(TAG, "WebDAV backup upload " + (code >= 200 && code < 300 ? "done" : "failed http=" + code)
                        + ": " + remotePath + " size=" + fileSize);
                if (cb != null) {
                    if (code >= 200 && code < 300) {
                        cb.onFinish(taskId, 0, "success");
                    } else {
                        cb.onFinish(taskId, -1, "HTTP " + code);
                    }
                }
            }
        }
    }

    /** 带进度回调的RequestBody */
    private static class ProgressRequestBody extends RequestBody {
        private final java.io.InputStream inputStream;
        private final long totalSize;
        private final ProgressCallback cb;
        private final String taskId;

        ProgressRequestBody(java.io.InputStream is, long totalSize, ProgressCallback cb, String taskId) {
            this.inputStream = is;
            this.totalSize = totalSize;
            this.cb = cb;
            this.taskId = taskId;
        }

        @Override
        public MediaType contentType() { return MediaType.parse("application/octet-stream"); }

        @Override
        public long contentLength() { return totalSize; }

        @Override
        public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
            // try-with-resources保证okio.Source关闭（它关闭会连带关闭底层inputStream）
            try (var source = okio.Okio.source(inputStream)) {
                var buffer = new okio.Buffer();
                var totalWritten = 0L;
                var lastReportTime = 0L;
                var read = 0L;
                while ((read = source.read(buffer, BUFFER_SIZE)) != -1) {
                    sink.write(buffer, read);
                    totalWritten += read;
                    var now = System.currentTimeMillis();
                    if (cb != null && (now - lastReportTime >= 200 || totalWritten == totalSize)) {
                        lastReportTime = now;
                        cb.onProgress(taskId, totalWritten, totalSize);
                    }
                }
            }
        }
    }

    // ========== 下载 ==========

    /** 下载单个文件从WebDAV到本地 */
    public static String downloadFile(String remotePath, String localPath) throws Exception {
        LogHelp.i(TAG, "WebDAV download start: " + remotePath + " -> " + localPath);
        var request = newRequest(remoteUrl(remotePath)).get().build();
        try (var resp = getClient().newCall(request).execute()) {
            var code = resp.code();
            if (code != 200) {
                LogHelp.w(TAG, "WebDAV download failed http=" + code + " path=" + remotePath);
                return "ERROR: HTTP " + code;
            }
            var localFile = new File(localPath);
            var parent = localFile.getParentFile();
            if (parent != null) parent.mkdirs();
            var body = resp.body();
            if (body == null) return "ERROR: empty response body";
            // try-with-resources保证输入输出流都被关闭
            try (var is = body.byteStream(); var fos = new FileOutputStream(localFile)) {
                var total = streamCopy(is, fos);
                LogHelp.i(TAG, "WebDAV download done: " + remotePath + " bytes=" + total);
                return "OK: " + remotePath + " -> " + localPath + " (" + total + " bytes)";
            }
        }
    }

    // ========== 工具方法 ==========

    /** 日志只打印主机名（webdav_url 不含凭据，但仍避免长 URL/query 噪音） */
    private static String hostOf(String url) {
        try {
            var u = java.net.URI.create(url);
            return u.getHost() != null ? u.getHost() : url;
        } catch (Exception e) {
            return url.length() > 80 ? url.substring(0, 80) + "..." : url;
        }
    }

    /** 流拷贝（不负责关闭流，由调用方用try-with-resources管理） */
    private static long streamCopy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var total = 0L;
        var len = 0;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
            total += len;
        }
        return total;
    }
}
