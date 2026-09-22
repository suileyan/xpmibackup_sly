package com.suileyan.cloud.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.LoginContext;
import com.suileyan.cloud.LoginState;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.comm.LogHelp;
import com.suileyan.comm.Secp256k1;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 阿里云盘（alipan.com）Provider
 *
 * 协议参考 GitHub 在维护的第三方实现 AlistGo/alist drivers/aliyundrive（Web API + Android 客户端仿真）：
 * - 认证：auth.alipan.com/v2/account/token 用 refresh_token 换新（轮换，access_token 约 2 小时）
 * - 设备签名：user_id 派生 secp256k1 密钥，create_session 注册公钥，每请求带
 *   X-Signature（SHA-256("secpAppID:deviceID:userID:0") 的签名）与 X-Device-Id、X-Canary
 * - 上传：adrive/v2/file/createWithFolders（预签名 OSS 分片地址）→ PUT 分片 → v2/file/complete
 * - 列表/下载/删除：v2/file/list（marker 翻页）、v2/file/get_download_url（下载需带 Referer）、
 *   v2/recyclebin/trash（入回收站）
 */
public class AliDriveProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "aliyun";
    private static final String API_BASE = "https://api.alipan.com";
    private static final String AUTH_BASE = "https://auth.alipan.com";
    private static final String ORIGIN = "https://www.alipan.com";
    /** 阿里 Android 客户端签名的固定 secpAppID（与 alist 对齐） */
    private static final String SECP_APP_ID = "5dde4e1bdf9e4966b387ba58f4b3fdc3";
    /** 分片大小 10MB（对齐 alist；createWithFolders 预签名直传 OSS） */
    private static final long PART_SIZE = 10L * 1024 * 1024;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int BUFFER_SIZE = 64 * 1024;

    private final CloudAccount account;
    /** 身份派生缓存（Provider 实例内复用；跨实例经 EncryptedCredStore 持久化） */
    private String userId;
    private String signature;
    private String deviceId;

    public AliDriveProvider(CloudAccount account) {
        this.account = account;
    }

    @Override
    public String id() {
        return account != null ? account.id : "";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "阿里云盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !EncryptedCredStore.get(id(), "refresh_token").isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        return LoginState.NOT_SUPPORTED;
    }

    // ========== 连接测试（user/get 拉身份：user_id/drive_id/nickname） ==========

    @Override
    public boolean testConnection() throws CloudException {
        var user = request("/v2/user/get", new JSONObject());
        var uid = user.optString("user_id", "");
        if (uid.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "阿里云盘 user/get 缺少 user_id");
        }
        var driveId = user.optString("default_drive_id", "");
        if (driveId.isEmpty()) {
            driveId = user.optString("resource_drive_id", "");
        }
        var nickname = user.optString("nickname", user.optString("user_name", ""));
        EncryptedCredStore.put(id(), "user_id", uid);
        if (!driveId.isEmpty()) EncryptedCredStore.put(id(), "drive_id", driveId);
        if (!nickname.isEmpty()) EncryptedCredStore.put(id(), "nickname", nickname);
        LogHelp.i(TAG, "阿里云盘连接成功 user=" + nickname + " drive=" + driveId);
        return true;
    }

    // ========== 目录与列表 ==========

    @Override
    public List<String> listDirs() throws CloudException {
        ensureIdentityKeys();
        var out = new ArrayList<String>();
        for (var e : listChildren("root")) {
            if (e.isDir) out.add(e.name);
        }
        return out;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        ensureIdentityKeys();
        var parentId = resolvePath(remoteDir, false);
        if (parentId == null) {
            // 路径解析失败会以"空列表"的形式返回给宿主，与"目录确实为空"无法区分。
            // 2026-09-16 恢复列表为空的排查里这是盲区之一，必须留痕。
            LogHelp.d(TAG, "阿里云盘目录解析失败（不存在或某一级 list 为空）: " + remoteDir);
            return new ArrayList<>();
        }
        var out = new ArrayList<RemoteEntry>();
        for (var e : listChildren(parentId)) {
            out.add(new RemoteEntry(e.name, e.size, e.isDir, e.modifiedTime));
        }
        return out;
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
        ensureIdentityKeys();
        resolvePath(remoteDir, true);
    }

    // ========== 上传 ==========

    @Override
    public String upload(String localPath, String remoteDir) throws CloudException {
        uploadWithProgress(localPath, null, remoteDir, "");
        return "OK: " + localPath;
    }

    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException {
        var localFile = new File(localPath);
        if (!localFile.exists()) {
            throw new CloudException(CloudException.Kind.LOCAL, "file not found: " + localPath);
        }
        try {
            ensureIdentityKeys();
            var parentId = resolvePath(remoteDir, true);
            if (cb != null) cb.onStart(taskId);
            var size = localFile.length();
            // 0 字节文件（备份完成标记 end）：对齐百度/沃盘/光鸭约定直接 mock 成功
            if (size == 0) {
                LogHelp.i(TAG, "阿里云盘跳过 0 字节文件: " + localFile.getName());
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }

            // 1. create：预签名分片地址（10MB 分片，与 alist 对齐）
            var partCount = (int) ((size + PART_SIZE - 1) / PART_SIZE);
            var createBody = new JSONObject();
            createBody.put("check_name_mode", "overwrite");
            createBody.put("drive_id", driveId());
            createBody.put("name", localFile.getName());
            createBody.put("parent_file_id", parentId);
            createBody.put("part_info_list", partNumbers(partCount));
            createBody.put("size", size);
            createBody.put("type", "file");
            createBody.put("content_hash_name", "none");
            createBody.put("proof_version", "v1");
            var created = request("/adrive/v2/file/createWithFolders", createBody);
            var fileId = created.optString("file_id", "");
            var uploadId = created.optString("upload_id", "");
            var parts = created.optJSONArray("part_info_list");
            if (fileId.isEmpty() || parts == null || parts.length() == 0) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "阿里云盘 create 响应缺少分片信息: " + truncate(created.toString(), 300));
            }
            LogHelp.i(TAG, "阿里云盘 upload start name=" + localFile.getName()
                    + " size=" + size + " parts=" + parts.length() + "/" + partCount);

            // 2. 逐分片 PUT 到预签名 OSS 地址（无鉴权头，进度跨分片累计）
            long written = 0;
            for (var i = 0; i < parts.length(); i++) {
                var part = parts.optJSONObject(i);
                var uploadUrl = part != null ? part.optString("upload_url", "") : "";
                if (uploadUrl.isEmpty()) {
                    throw new CloudException(CloudException.Kind.REMOTE, "阿里云盘分片 " + (i + 1) + " 缺少 upload_url");
                }
                written += putPart(uploadUrl, localFile, i * PART_SIZE,
                        Math.min(PART_SIZE, size - i * PART_SIZE), cb, taskId, size);
            }

            // 3. complete 提交
            var completeBody = new JSONObject();
            completeBody.put("drive_id", driveId());
            completeBody.put("file_id", fileId);
            completeBody.put("upload_id", uploadId);
            var done = request("/v2/file/complete", completeBody);
            if (done.optString("file_id", "").isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "阿里云盘 complete 响应异常: " + truncate(done.toString(), 300));
            }
            LogHelp.i(TAG, "阿里云盘 upload done name=" + localFile.getName() + " size=" + size);
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "阿里云盘上传失败", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    private static JSONArray partNumbers(int count) throws org.json.JSONException {
        var arr = new JSONArray();
        for (var i = 1; i <= count; i++) {
            arr.put(new JSONObject().put("part_number", i));
        }
        return arr;
    }

    /** 上传单个分片：从本地 offset 读 length 字节 PUT 到预签名地址 */
    private long putPart(String uploadUrl, File localFile, long offset, long length,
                         ProgressCallback cb, String taskId, long totalSize) throws Exception {
        // 预签名 URL 是阿里云盘按"空 Content-Type"生成的 OSS 签名（对齐 alist：PUT 裸发，无任何自定义头）。
        // 若请求体带上 Content-Type，OSS 重新计算的规范串会多一个头，与签名时不一致 → 403 SignatureDoesNotMatch。
        // 因此这里 body 的 MediaType 必须为 null（OkHttp 对 null MediaType 不发送 Content-Type 头）。
        var request = new Request.Builder().url(uploadUrl).put(new RequestBody() {
            @Override
            public MediaType contentType() {
                return null; // 关键：不携带 Content-Type，与预签名一致
            }

            @Override
            public long contentLength() {
                return length;
            }

            @Override
            public void writeTo(okio.BufferedSink sink) throws IOException {
                var buffer = new byte[BUFFER_SIZE];
                var done = 0L;
                try (var in = new FileInputStream(localFile)) {
                    var skipped = in.skip(offset);
                    while (skipped < offset) {
                        var more = in.skip(offset - skipped);
                        if (more == 0) throw new IOException("seek failed: " + offset);
                        skipped += more;
                    }
                    long read;
                    while (done < length && (read = in.read(buffer, 0,
                            (int) Math.min(buffer.length, length - done))) != -1) {
                        sink.write(buffer, 0, (int) read);
                        done += read;
                        if (cb != null) cb.onProgress(taskId, offset + done, totalSize);
                    }
                }
            }
        }).build();
        try (var resp = client().newCall(request).execute()) {
            var code = resp.code();
            if (code < 200 || code >= 300) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "阿里云盘分片上传 HTTP " + code + ": " + truncate(resp.body() != null ? resp.body().string() : "", 200));
            }
        }
        return length;
    }

    // ========== 下载 ==========

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            ensureIdentityKeys();
            var remote = trimSlashes(remotePath);
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null || entry.fileId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "阿里云盘文件不存在: " + remotePath);
            }
            var body = new JSONObject();
            body.put("drive_id", driveId());
            body.put("file_id", entry.fileId);
            body.put("expire_sec", 14400);
            var urlJson = request("/v2/file/get_download_url", body);
            var dl = urlJson.optString("url", "");
            if (dl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "阿里云盘缺少下载地址: " + truncate(urlJson.toString(), 300));
            }
            fetchToFile(dl, entry, localPath);
            var localLen = new File(localPath).length();
            LogHelp.i(TAG, "阿里云盘 download done name=" + entry.name
                    + " local=" + localLen + " remote=" + entry.size
                    + (localLen == entry.size ? "" : " **SIZE-MISMATCH**"));
            return "OK: " + remotePath + " -> " + localPath;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 阿里云盘 CDN 的防盗链不只看 Referer，也看 User-Agent；OkHttp 默认 UA（okhttp/x.y）会被判为非浏览器来源 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 用预签名下载地址取文件：逐个请求头变体尝试，全部失败才抛错，并把失败响应体带进异常。
     *
     * 之前只带 `Referer: https://www.alipan.com/` 且失败时把响应体丢掉了，logcat 里只剩一行
     * "阿里云盘下载 HTTP 403"，无法判断是防盗链（Referer/UA 被判非法）还是签名过期（OSS SignatureDoesNotMatch）。
     * 变体顺序：
     *   1) alipan Referer + 桌面浏览器 UA —— 与 alist Link() 的 Header 组合对齐（+UA 修正）
     *   2) 裸请求（不带 Referer）—— 防盗链对空 Referer 一般放行（浏览器地址栏直连即此形态）
     *   3) 旧域名 aliyundrive.com Referer —— CDN 白名单可能是旧域名
     */
    private void fetchToFile(String downloadUrl, Entry entry, String localPath) throws CloudException {
        String[] referers = { ORIGIN + "/", null, "https://www.aliyundrive.com/" };
        var lastError = "";
        for (var referer : referers) {
            var builder = new Request.Builder().url(downloadUrl).header("User-Agent", DESKTOP_UA);
            if (referer != null) builder.header("Referer", referer);
            try (var resp = client().newCall(builder.build()).execute()) {
                var code = resp.code();
                var respBody = resp.body();
                if (code < 200 || code >= 300) {
                    var detail = respBody != null ? truncate(respBody.string(), 200) : "";
                    lastError = "HTTP " + code + (detail.isEmpty() ? "" : " body=" + detail);
                    LogHelp.w(TAG, "阿里云盘下载被拒 referer="
                            + (referer == null ? "<none>" : referer) + " name=" + entry.name + " " + lastError);
                    continue;
                }
                if (respBody == null) {
                    lastError = "空响应";
                    continue;
                }
                try (var out = new FileOutputStream(localPath); var in = respBody.byteStream()) {
                    var buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                if (referer == null) {
                    LogHelp.i(TAG, "阿里云盘下载需去掉 Referer 才放行（CDN 防盗链按空 Referer 放行）");
                }
                return;
            } catch (IOException e) {
                lastError = e.toString();
                LogHelp.w(TAG, "阿里云盘下载异常 referer="
                        + (referer == null ? "<none>" : referer) + " " + lastError);
            }
        }
        throw new CloudException(CloudException.Kind.REMOTE, "阿里云盘下载失败（已试 3 种请求头）: " + lastError);
    }

    // ========== 删除 ==========

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        deletePath(remoteDir);
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        deletePath(remotePath);
    }

    private void deletePath(String remotePath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            if (remote.isEmpty()) return;
            ensureIdentityKeys();
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null || entry.fileId.isEmpty()) return;
            var body = new JSONObject();
            body.put("drive_id", driveId());
            body.put("file_id", entry.fileId);
            request("/v2/recyclebin/trash", body);
            LogHelp.i(TAG, "阿里云盘已移入回收站: " + remote);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 静默刷新 ==========

    /** 刷新锁：refresh_token 轮换单次有效，多线程并发刷新会互相挤掉（对齐光鸦 HIGH-06） */
    private static final Object REFRESH_LOCK = new Object();

    @Override
    public boolean refresh() {
        try {
            refreshAccessToken();
            return true;
        } catch (Exception e) {
            LogHelp.w(TAG, "阿里云盘刷新失败: " + e.getMessage());
            return false;
        }
    }

    private String refreshAccessToken() throws CloudException {
        synchronized (REFRESH_LOCK) {
            var rt = EncryptedCredStore.get(id(), "refresh_token");
            if (rt.isEmpty()) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "阿里云盘无 refresh_token，需重新登录");
            }
            var body = new JSONObject();
            try {
                body.put("refresh_token", rt);
                body.put("grant_type", "refresh_token");
            } catch (Exception ignored) {
            }
            var resp = httpPost(AUTH_BASE + "/v2/account/token", authHeaders(), body);
            try {
                var j = new JSONObject(resp.body);
                var token = j.optString("access_token", "");
                if (token.isEmpty()) {
                    // 错误响应（HTTP 4xx 或 200+错误 JSON）统一按凭据失效处理
                    throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                            "阿里云盘刷新失败: " + j.optString("code", "") + " "
                                    + j.optString("message", truncate(resp.body, 120)));
                }
                EncryptedCredStore.put(id(), "access_token", token);
                // refresh_token 轮换：必须持久化新值，旧值即刻失效
                var newRt = j.optString("refresh_token", "");
                if (!newRt.isEmpty()) {
                    EncryptedCredStore.put(id(), "refresh_token", newRt);
                }
                LogHelp.i(TAG, "阿里云盘 token 已刷新");
                return token;
            } catch (CloudException e) {
                throw e;
            } catch (Exception e) {
                throw new CloudException(CloudException.Kind.REMOTE, e);
            }
        }
    }

    // ========== 业务 API（请求头体系 + 设备签名 + 错误自愈重试） ==========

    private static final int MAX_RETRY = 4;

    /** POST 业务 API：AccessTokenInvalid → 刷新重试；DeviceSessionSignatureInvalid → 注册设备重试 */
    private JSONObject request(String path, JSONObject data) throws CloudException {
        var retriedRefresh = false;
        var retriedSession = false;
        var lastCode = 0;
        for (var i = 0; i < MAX_RETRY; i++) {
            HttpResponse resp;
            try {
                resp = httpPost(API_BASE + path, apiHeaders(), data);
            } catch (CloudException e) {
                // MED-08：网络抖动同样退避重试（与 Pan115/RetryPolicy 语义一致），最后一轮才上抛
                if (e.kind() == CloudException.Kind.NETWORK && i < MAX_RETRY - 1) {
                    backoff(i, path, "网络错误 " + e.getMessage());
                    continue;
                }
                throw e;
            }
            lastCode = resp.code;
            var code = "";
            var message = "";
            if (resp.code < 500 && !resp.body.isEmpty()) {
                try {
                    var j = new JSONObject(resp.body);
                    code = j.optString("code", "");
                    message = j.optString("message", "");
                } catch (Exception ignored) {
                }
            }
            // MED-08：5xx 属可重试错误，此前首轮即抛且无退避
            if (resp.code >= 500 && i < MAX_RETRY - 1) {
                backoff(i, path, "HTTP " + resp.code);
                continue;
            }
            // HIGH-03：官方错误码为 IllegalToken（两个 l）。历史实现只匹配 "IlegalToken"（少一个 l），
            // 命中时 authExpired=false → 跳过 refreshAccessToken() 自愈，且 HTTP 200 让后面的
            // 4xx/2xx 判定也放行，错误体被当成业务数据返回。这里两种拼写都接受。
            var tokenInvalid = isIllegalToken(code);
            var authExpired = resp.code == 401 || "AccessTokenInvalid".equals(code) || tokenInvalid;
            if (authExpired && !retriedRefresh) {
                retriedRefresh = true;
                LogHelp.i(TAG, "阿里云盘 token 失效，刷新重试: " + path);
                refreshAccessToken();
                continue;
            }
            if ("DeviceSessionSignatureInvalid".equals(code) && !retriedSession) {
                retriedSession = true;
                LogHelp.i(TAG, "阿里云盘设备签名未注册，create_session 重试: " + path);
                createSession();
                continue;
            }
            if (resp.code == 401 || resp.code == 403) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                        "阿里云盘认证失败 HTTP " + resp.code + ": " + truncate(resp.body, 200));
            }
            if (resp.code < 200 || resp.code >= 300) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "阿里云盘 API HTTP " + resp.code + " " + code + ": " + truncate(resp.body, 200));
            }
            // HIGH-03：HTTP 200 + 已知错误码同样必须抛错。此前直接把错误体当成功返回，
            // 调用方拿到没有 items 的 JSON 就当成"空结果"，症状是静默的空列表/缺文件。
            if (isKnownErrorCode(code)) {
                throw new CloudException(tokenInvalid ? CloudException.Kind.AUTH_EXPIRED : CloudException.Kind.REMOTE,
                        "阿里云盘业务错误 code=" + code + " msg=" + message
                                + " path=" + path + " raw=" + truncate(resp.body, 200));
            }
            try {
                // 部分端点（recyclebin/trash 等）成功时可能返回空 body
                return resp.body.isBlank() ? new JSONObject() : new JSONObject(resp.body);
            } catch (Exception e) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "阿里云盘响应非 JSON: " + truncate(resp.body, 200));
            }
        }
        throw new CloudException(CloudException.Kind.REMOTE,
                "阿里云盘重试次数用尽: " + path + " lastHTTP=" + lastCode);
    }

    /** HIGH-03：官方错误码为 IllegalToken（两个 l），历史拼写 IlegalToken 也一并接受 */
    private static boolean isIllegalToken(String code) {
        return "IllegalToken".equalsIgnoreCase(code) || "IlegalToken".equalsIgnoreCase(code);
    }

    /** 明确属于错误的业务码（HTTP 200 也会出现）：命中即抛错，禁止把错误体当成功数据返回 */
    private static final java.util.Set<String> KNOWN_ERROR_CODES = java.util.Set.of(
            "TooManyRequests", "Throttling", "QuotaExhausted", "InternalError", "ServiceUnavailable",
            "ParamError", "InvalidParameter", "Forbidden", "AccessTokenInvalid", "IllegalToken",
            "IlegalToken", "DeviceSessionSignatureInvalid", "SignatureDoesNotMatch");

    private static boolean isKnownErrorCode(String code) {
        if (code == null || code.isEmpty()) return false;
        if (KNOWN_ERROR_CODES.contains(code)) return true;
        // 前缀型错误码（NotFound.File / ParamError.xxx / Forbidden.xxx）
        return code.startsWith("NotFound.") || code.startsWith("ParamError.")
                || code.startsWith("Forbidden.");
    }

    /** 退避等待（500ms 起指数增长，封顶 4s）；中断时复位中断标志避免取消信号丢失 */
    private static void backoff(int attempt, String path, String why) {
        var ms = Math.min(500L << Math.min(attempt, 3), 4000L);
        LogHelp.w(TAG, "阿里云盘 " + why + "，退避 " + ms + "ms 后重试: " + path);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 注册设备公钥（首次签名 403/签名失效时触发，对齐 alist createSession） */
    private void createSession() throws CloudException {
        var identity = ensureIdentityKeys();
        var body = new JSONObject();
        try {
            body.put("deviceName", "samsung");
            body.put("modelName", "SM-G9810");
            body.put("nonce", 0);
            body.put("pubKey", hex(Secp256k1.publicKey(deviceKeyFromMessage(
                    SECP_APP_ID + ":" + identity.deviceId + ":" + identity.uid + ":0"))));
            body.put("refreshToken", EncryptedCredStore.get(id(), "refresh_token"));
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
        var resp = httpPost(API_BASE + "/users/v1/users/device/create_session", apiHeaders(), body);
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "阿里云盘 create_session HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        // create_session 响应可能附带新 token（设备会话刷新）：持久化回来，避免用旧 access_token 继续
        try {
            if (!resp.body.isBlank()) {
                var j = new JSONObject(resp.body);
                var at = j.optString("access_token", "");
                var rt = j.optString("refresh_token", "");
                if (!at.isEmpty()) EncryptedCredStore.put(id(), "access_token", at);
                if (!rt.isEmpty()) EncryptedCredStore.put(id(), "refresh_token", rt);
            }
        } catch (Exception ignored) {
        }
        LogHelp.i(TAG, "阿里云盘设备会话已注册");
    }

    /** 业务请求头（对齐 alist：Bearer 制表符分隔、origin/referer、Android canary、设备签名） */
    private Map<String, String> apiHeaders() {
        var h = new LinkedHashMap<String, String>();
        // 注意：对齐 alist 用 "Bearer\t"（制表符）分隔——服务端按前缀解析，勿改成空格以外随意变更
        h.put("Authorization", "Bearer\t" + EncryptedCredStore.get(id(), "access_token"));
        h.put("Content-Type", "application/json");
        h.put("origin", ORIGIN);
        h.put("Referer", "https://alipan.com/");
        h.put("x-request-id", UUID.randomUUID().toString());
        h.put("X-Canary", "client=Android,app=adrive,version=v4.1.0");
        var uid = EncryptedCredStore.get(id(), "user_id");
        if (!uid.isEmpty()) {
            ensureSignature(uid);
            h.put("X-Device-Id", deviceId);
            h.put("X-Signature", signature);
        }
        // user_id 为空时不带签名头——这是 /v2/user/get 建身份的合法状态；
        // 其它端点在 user_id 缺失时由调用入口先 ensureIdentityKeys()（不再在 apiHeaders 内反向触发，
        // 避免 apiHeaders ↔ ensureIdentityKeys ↔ testConnection ↔ request 死循环）
        return h;
    }

    private Map<String, String> authHeaders() {
        var h = new LinkedHashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("origin", ORIGIN);
        h.put("Referer", "https://alipan.com/");
        return h;
    }

    /** 派生结果（deviceId / signature） */
    private final class Identity {
        final String uid;
        final String deviceId;
        final String signature;

        Identity(String uid, String deviceId, String signature) {
            this.uid = uid;
            this.deviceId = deviceId;
            this.signature = signature;
        }
    }

    /** 确保 deviceId / signature 已派生（委托 deriveSignature，离线可复算） */
    private void ensureSignature(String uid) {
        var identity = deriveSignature(uid);
        if (identity.uid.isEmpty()) return;
        userId = identity.uid;
        deviceId = identity.deviceId;
        signature = identity.signature;
    }

    /** 建立/补齐身份（user_id / drive_id / nickname），返回当前身份 */
    private Identity ensureIdentityKeys() throws CloudException {
        var uid = EncryptedCredStore.get(id(), "user_id");
        if (uid.isEmpty()) {
            // 身份未建立：先 user/get（该端点允许空签名）
            testConnection();
            uid = EncryptedCredStore.get(id(), "user_id");
        }
        if (uid.isEmpty()) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "阿里云盘身份不可用，请重新登录");
        }
        var identity = deriveSignature(uid);
        return new Identity(identity.uid, identity.deviceId, identity.signature);
    }

    /** 按 user_id 派生 secp256k1 签名三要素（deviceId / signature），不触发任何网络请求 */
    private Identity deriveSignature(String uid) {
        if (uid.equals(userId) && signature != null) {
            return new Identity(uid, deviceId, signature);
        }
        var hex = sha256Hex(uid);
        var message = SECP_APP_ID + ":" + hex + ":" + uid + ":0";
        var sig = hexSecp256k1(message);
        return new Identity(uid, hex, sig);
    }

    private String sha256Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(hash.length * 2);
            for (var b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String hexSecp256k1(String message) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8));
            var key = deviceKeyFromMessage(message);
            var sig = com.suileyan.comm.Secp256k1.sign(hash, key);
            var sb = new StringBuilder(sig.length * 2);
            for (var b : sig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            LogHelp.e(TAG, "阿里云盘签名派生失败（消息: " + truncate(message, 32) + "）", e);
            return signature != null ? signature : "";
        }
    }

    /**
     * 派生 secp256k1 私钥（32 字节大端，按 hex 解码）。
     *
     * 对齐 alist：deviceID = SHA-256(user_id) 的 hex 字符串，即私钥本身。
     * 注意必须按 16 进制**解码回 32 字节**；若直接把 64 位 hex 的 ASCII 字节当私钥，
     * Secp256k1.sign 的 `new BigInteger(1, bytes)` 会解析出远超群阶 N 的值 → "invalid private key"。
     */
    private byte[] deviceKeyFromMessage(String message) {
        // message 形如 "SECP_APP_ID:DEVICE_ID_HEX:USER_ID:0"，DEVICE_ID_HEX 是第 2 段
        var parts = message.split(":");
        var hex = parts.length >= 2 ? parts[1] : "";
        if (hex.isEmpty()) return new byte[32];
        var b = new java.math.BigInteger(1, hex.getBytes(StandardCharsets.US_ASCII)).toByteArray();
        var out = new byte[32];
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, out, 0, 32);
        } else {
            System.arraycopy(b, 0, out, 32 - b.length, b.length);
        }
        return out;
    }

    // ========== 路径解析 ==========

    private String driveId() throws CloudException {
        var did = EncryptedCredStore.get(id(), "drive_id");
        if (!did.isEmpty()) return did;
        // 统一走身份入口（user/get 建身份），不在这里直接 testConnection 制造第二条递归路径
        ensureIdentityKeys();
        did = EncryptedCredStore.get(id(), "drive_id");
        if (did.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "阿里云盘缺少 drive_id");
        }
        return did;
    }

    private static class Entry {
        final String fileId;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;

        Entry(String fileId, String name, long size, boolean isDir, long modifiedTime) {
            this.fileId = fileId;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
        }
    }

    /** 分页枚举目录（v2/file/list，marker 翻页，200/页） */
    private List<Entry> listChildren(String parentId) throws CloudException {
        var items = new ArrayList<Entry>();
        var marker = "";
        for (var guard = 0; guard < 100; guard++) {
            var body = new JSONObject();
            try {
                body.put("drive_id", driveId());
                body.put("parent_file_id", parentId == null || parentId.isEmpty() ? "root" : parentId);
                body.put("limit", 200);
                body.put("order_by", "name");
                body.put("order_direction", "ASC");
                body.put("fields", "*");
                if (!marker.isEmpty()) body.put("marker", marker);
            } catch (Exception ignored) {
            }
            var resp = request("/v2/file/list", body);
            var arr = resp.optJSONArray("items");
            if (arr == null) {
                // 阿里云盘的错误响应并非都是 4xx：HTTP 200 + {"code":...,"message":...} 同样常见
                // （TooManyRequests / NotFound.File / ParamError…）。此前被静默当成"空目录"，
                // 症状是「有文件的目录 list 出 0 项 → 报文件不存在 → 恢复列表为空」，
                // 且日志里没有任何线索。这里把 code/message 还原成异常。
                var errCode = resp.optString("code", "");
                if (!errCode.isEmpty()) {
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "阿里云盘 list 失败 parent=" + parentId + " code=" + errCode
                                    + " msg=" + resp.optString("message", "")
                                    + " raw=" + truncate(resp.toString(), 200));
                }
            } else {
                for (var i = 0; i < arr.length(); i++) {
                    var o = arr.optJSONObject(i);
                    if (o != null) items.add(toEntry(o));
                }
            }
            marker = resp.optString("next_marker", "");
            if (marker.isEmpty()) break;
        }
        if (items.isEmpty()) {
            LogHelp.d(TAG, "阿里云盘 list 结果为空 parent=" + parentId + "（目录不存在 / 确为空 / 响应缺 items）");
        }
        return items;
    }

    private static Entry toEntry(JSONObject o) {
        var isDir = "folder".equals(o.optString("type")) || o.optBoolean("is_folder", false);
        var modified = o.optLong("updated_at", 0L);
        if (o.has("updated_at") && o.optString("updated_at", "").contains("T")) {
            try {
                modified = java.time.Instant.parse(o.optString("updated_at", "")).toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        return new Entry(o.optString("file_id", ""), o.optString("name", ""),
                o.optLong("size", 0L), isDir, modified);
    }

    private Entry findChild(String parentId, String name) throws CloudException {
        for (var e : listChildren(parentId)) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    /** 解析路径为目录 file_id；createMissing=true 时逐级建目录 */
    private String resolvePath(String path, boolean createMissing) throws CloudException {
        var v = trimSlashes(path);
        if (v.isEmpty()) return "root";
        var parentId = "root";
        for (var part : v.split("/")) {
            var name = cleanName(part);
            if (name.isEmpty()) continue;
            var child = findChild(parentId, name);
            if (child == null) {
                if (!createMissing) return null;
                parentId = createFolder(parentId, name);
                continue;
            }
            if (!child.isDir) {
                throw new CloudException(CloudException.Kind.REMOTE, "阿里云盘路径非目录: " + name);
            }
            parentId = child.fileId;
        }
        return parentId;
    }

    private String createFolder(String parentId, String name) throws CloudException {
        var body = new JSONObject();
        try {
            body.put("check_name_mode", "refuse");
            body.put("drive_id", driveId());
            body.put("name", name);
            body.put("parent_file_id", parentId);
            body.put("type", "folder");
        } catch (Exception ignored) {
        }
        var resp = request("/adrive/v2/file/createWithFolders", body);
        var fileId = resp.optString("file_id", "");
        if (fileId.isEmpty()) {
            var child = findChild(parentId, name);
            if (child != null) fileId = child.fileId;
        }
        if (fileId.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "阿里云盘建目录失败: " + name);
        }
        LogHelp.i(TAG, "阿里云盘 mkdir name=" + name + " -> " + fileId);
        return fileId;
    }

    private Entry findEntry(String parentPath, String targetName) throws CloudException {
        var parentId = resolvePath(parentPath, false);
        if (parentId == null) return null;
        return findChild(parentId, targetName);
    }

    // ========== HTTP 层 ==========

    private static class HttpResponse {
        int code;
        String body = "";
    }

    private HttpResponse httpPost(String url, Map<String, String> headers, JSONObject data) throws CloudException {
        var builder = new Request.Builder().url(url);
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        builder.post(RequestBody.create(JSON, data.toString()));
        try (var r = client().newCall(builder.build()).execute()) {
            var resp = new HttpResponse();
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            return resp;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    private static OkHttpClient sClient;

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (AliDriveProvider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.MINUTES)
                    .writeTimeout(10, TimeUnit.MINUTES)
                    .build();
            return sClient;
        }
    }

    // ========== 工具 ==========

    private static String hex(byte[] bytes) {
        var out = new StringBuilder(bytes.length * 2);
        for (var b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String trimSlashes(String path) {
        var v = path == null ? "" : path.replace('\\', '/');
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String pathParent(String path) {
        var v = trimSlashes(path);
        var i = v.lastIndexOf('/');
        return i < 0 ? "" : v.substring(0, i);
    }

    private static String pathName(String path) {
        var v = trimSlashes(path);
        var i = v.lastIndexOf('/');
        return i < 0 ? v : v.substring(i + 1);
    }

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐光鸦 cleanName） */
    private static String cleanName(String name) {
        if (name == null) return "";
        var out = new StringBuilder();
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            var code = (int) c;
            if ((code >= 0x0000 && code <= 0x001F) || (code >= 0x007F && code <= 0x009F)
                    || (code >= 0x200B && code <= 0x200F) || code == 0xFEFF) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }
}
