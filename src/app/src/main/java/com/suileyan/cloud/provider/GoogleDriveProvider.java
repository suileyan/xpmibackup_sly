package com.suileyan.cloud.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.cloud.login.GDriveOAuth;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Google Drive（个人盘）Provider
 *
 * 协议参考（协议细节对照已拉取的参考实现核对）：
 * - tmp/gdrive/alist/drivers/google_drive/（REST 直连蓝本，refresh_token 配置 + resumable 分片）
 * - tmp/gdrive/google-api-java-client MediaHttpUploader（256KB 对齐/308 状态机的官方权威）
 *
 * 认证：OAuth 2.0（loopback 换取，见 cloud/login/GDriveOAuth）
 * - 凭据键（EncryptedCredStore）：client_id / client_secret / refresh_token /
 *   access_token / token_expiry / proxy_host / proxy_port / email / nickname
 * - scope=drive.file：只见本应用创建的文件；refresh_token 不轮换，access_token 约 1h
 *
 * 上传：resumable（uploadType=resumable → Location 会话地址 → 分片 PUT，
 * 片大小须为 256KB 整数倍；308 续传；断点用 Content-Range: bytes *\/total 查询）
 * 列表：files.list（q='<id>' in parents，pageSize 1000，pageToken 翻页）
 * 路径：Drive v3 无路径语义，逐级映射 folder ID（对齐 AliDriveProvider.resolvePath）
 * 删除：入回收站（trashed=true，对齐阿里「入回收站」约定，30 天后自动清理）
 */
public class GoogleDriveProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "gdrive";
    private static final String API_BASE = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3";
    private static final String MIME_FOLDER = "application/vnd.google-apps.folder";
    /** 分片 8MB（256KB 整数倍；官方推荐 5–10MB，对齐磁盘峰值约定按最小片控） */
    private static final long CHUNK_SIZE = 8L * 1024 * 1024;
    private static final long MIN_CHUNK_ALIGN = 256L * 1024;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_RETRY = 4;
    /** token 过期预判提前量 */
    private static final long TOKEN_EXPIRY_MARGIN_MS = 60_000L;

    private final CloudAccount account;
    /**
     * 进程内目录 ID 缓存：`accountId + '|' + 绝对路径` → folderId。
     *
     * 必须**静态**：`ProviderRegistry.forAccount()` 为读到最新凭据每次都新构造实例
     * （见 ProviderRegistry 注释），实例级缓存等于没有缓存——实测「备份项并发」>1 时
     * 多个上传任务各自从零解析路径，每个都新建一份同名目录，文件被散落到不同重名目录里，
     * 恢复列表只看到其中一个，表现为「缺项」甚至「为空」。
     *
     * 必须**线程安全**：并发上传会同时解析同一路径。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> DIR_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 路径解析闸门：Drive 允许同目录重名，find-or-create 不是原子操作，必须串行 */
    private static final Object PATH_LOCK = new Object();

    public GoogleDriveProvider(CloudAccount account) {
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
        return account != null && account.name != null && !account.name.isEmpty()
                ? account.name : "Google Drive";
    }

    // ========== 登录态 ==========

    @Override
    public boolean isLoggedIn() {
        return !EncryptedCredStore.get(id(), "refresh_token").isEmpty()
                && !clientId().isEmpty();
    }

    @Override
    public com.suileyan.cloud.LoginState login(com.suileyan.cloud.LoginContext ctx) {
        // 授权走 UI 进程 loopback 流程（GDriveLoginFragment → LoopbackAuthServer → GDriveOAuth）
        return com.suileyan.cloud.LoginState.NOT_SUPPORTED;
    }

    // ========== 连接测试（about.get 拉身份） ==========

    @Override
    public boolean testConnection() throws CloudException {
        var url = HttpUrl.parse(API_BASE + "/about").newBuilder()
                .addQueryParameter("fields", "user(displayName,emailAddress)")
                .build();
        var resp = request("GET", url.toString(), null);
        var user = resp.optJSONObject("user");
        var email = user != null ? user.optString("emailAddress", "") : "";
        var nickname = user != null ? user.optString("displayName", "") : "";
        if (email.isEmpty() && nickname.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "Google Drive about 响应缺少 user: " + truncate(resp.toString(), 300));
        }
        if (!email.isEmpty()) EncryptedCredStore.put(id(), "email", email);
        if (!nickname.isEmpty()) EncryptedCredStore.put(id(), "nickname", nickname);
        LogHelp.i(TAG, "Google Drive 连接成功 user=" + nickname
                + (email.isEmpty() ? "" : " <" + email + ">"));
        return true;
    }

    // ========== 目录与列表 ==========

    @Override
    public List<String> listDirs() throws CloudException {
        var out = new ArrayList<String>();
        for (var e : listChildren("root")) {
            if (e.isDir) out.add(e.name);
        }
        return out;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        var parentId = resolvePath(remoteDir, false);
        if (parentId == null) {
            // 路径解析失败必须留痕：与「目录确实为空」不可混同（恢复列表为空排查盲区，见阿里 2026-09-16 案例）
            LogHelp.d(TAG, "Google Drive 目录解析失败（不存在或某一级 list 为空）: " + remoteDir);
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
        resolvePath(remoteDir, true);
    }

    // ========== 上传（resumable） ==========

    @Override
    public String upload(String localPath, String remoteDir) throws CloudException {
        uploadWithProgress(localPath, null, remoteDir, "");
        return "OK: " + localPath;
    }

    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb,
                                   String remoteDir, String taskId) throws CloudException {
        var localFile = new File(localPath);
        if (!localFile.exists()) {
            throw new CloudException(CloudException.Kind.LOCAL, "file not found: " + localPath);
        }
        try {
            var parentId = resolvePath(remoteDir, true);
            if (cb != null) cb.onStart(taskId);
            var size = localFile.length();
            // 0 字节文件（备份完成标记 end）：对齐百度/沃盘/光鸭/阿里约定直接 mock 成功
            if (size == 0) {
                LogHelp.i(TAG, "Google Drive 跳过 0 字节文件: " + localFile.getName());
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }

            // 1. 创建 resumable 会话（禁跟随重定向，取 Location 会话地址）
            var uploadUrl = createResumableSession(localFile.getName(), parentId, size);
            LogHelp.i(TAG, "Google Drive upload start name=" + localFile.getName()
                    + " size=" + size + " chunk=" + CHUNK_SIZE);

            // 2. 分片 PUT（8MB，256KB 对齐），308 续传，网络/5xx/限流退避重试
            var offset = 0L;
            var refreshed = false;
            var completed = false;
            for (var attempt = 0; attempt < MAX_RETRY * 4 && offset < size && !completed; ) {
                var chunkEnd = Math.min(offset + CHUNK_SIZE, size) - 1;
                var code = 0;
                var failure = "";
                try {
                    code = putChunk(uploadUrl, localFile, offset,
                            chunkEnd - offset + 1, size, cb, taskId);
                } catch (IOException e) {
                    failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                if (code >= 200 && code < 300) {
                    completed = true; // 最后一片已受理，上传完成
                    break;
                }
                if (code == 308) {
                    // 本片受理（或部分受理），以服务端游标为准推进
                    offset = resyncOffset(uploadUrl, size);
                    attempt = 0;
                    continue;
                }
                if (code == 401 && !refreshed) {
                    refreshed = true;
                    LogHelp.i(TAG, "Google Drive 上传 401，刷新 token 后对齐服务端游标重试");
                    refreshAccessToken();
                    offset = resyncOffset(uploadUrl, size);
                    continue;
                }
                var retryable = code == 403 || code >= 500 || code == 0;
                if (retryable && attempt < MAX_RETRY - 1) {
                    attempt++;
                    backoff(attempt, "上传分片 HTTP " + (code == 0 ? failure : code));
                    offset = resyncOffset(uploadUrl, size);
                    continue;
                }
                throw new CloudException(CloudException.Kind.REMOTE,
                        "Google Drive 分片上传失败 HTTP " + code
                                + (failure.isEmpty() ? "" : " " + failure)
                                + " offset=" + offset);
            }
            // completed（末片 2xx）或 offset==size（断点查询确认受理完整）才算成功；
            // 循环预算耗尽绝不静默当成功（血泪约束：别把"没抛异常"当"成功"）
            if (!completed && offset < size) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "Google Drive 上传未完成 offset=" + offset + "/" + size);
            }
            LogHelp.i(TAG, "Google Drive upload done name=" + localFile.getName() + " size=" + size);
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "Google Drive 上传失败", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 创建 resumable 会话，返回 Location 会话地址（Location 可能相对，统一解析为绝对地址） */
    private String createResumableSession(String name, String parentId, long size) throws CloudException {
        var metadata = new JSONObject();
        try {
            metadata.put("name", name);
            metadata.put("parents", new JSONArray().put(parentId));
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.LOCAL, e);
        }
        var url = HttpUrl.parse(UPLOAD_BASE + "/files").newBuilder()
                .addQueryParameter("uploadType", "resumable")
                .addQueryParameter("fields", "id")
                .build();
        var req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, metadata.toString()))
                .header("Authorization", "Bearer " + accessToken())
                // 会话总长声明：服务端据此校验 Content-Range 总长
                .header("X-Upload-Content-Type", "application/octet-stream")
                .header("X-Upload-Content-Length", String.valueOf(size))
                .build();
        try (var resp = noRedirectClient().newCall(req).execute()) {
            var code = resp.code();
            if (code < 200 || code >= 300) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "Google Drive 会话创建失败 HTTP " + code + ": "
                                + truncate(resp.body() != null ? resp.body().string() : "", 200));
            }
            var location = resp.header("Location");
            if (location == null || location.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "Google Drive 会话创建响应缺少 Location");
            }
            return location.startsWith("http") ? location
                    : "https://www.googleapis.com" + (location.startsWith("/") ? "" : "/") + location;
        } catch (CloudException e) {
            throw e;
        } catch (IOException e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    /**
     * PUT 单个分片。返回 HTTP 状态码（308 = 本片未完整受理需对齐游标；
     * 2xx = 完成；IOException 抛给调用方走退避重试；token 刷新失败抛 CloudException 终止）。
     */
    private int putChunk(String uploadUrl, File localFile, long offset, long length,
                         long totalSize, ProgressCallback cb, String taskId)
            throws IOException, CloudException {
        var contentRange = "bytes " + offset + "-" + (offset + length - 1) + "/" + totalSize;
        var req = new Request.Builder()
                .url(uploadUrl)
                .put(new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.parse("application/octet-stream");
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
                            skipFully(in, offset);
                            long read;
                            while (done < length && (read = in.read(buffer, 0,
                                    (int) Math.min(buffer.length, length - done))) != -1) {
                                sink.write(buffer, 0, (int) read);
                                done += read;
                                if (cb != null) cb.onProgress(taskId, offset + done, totalSize);
                            }
                        }
                    }
                })
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Range", contentRange)
                .build();
        try (var resp = client().newCall(req).execute()) {
            return resp.code();
        }
    }

    /**
     * 查询服务端已受理游标：空 body PUT + Content-Range: bytes *\/total。
     * 返回下一写入偏移；会话已完整时（2xx）返回 total。
     */
    private long resyncOffset(String uploadUrl, long totalSize) throws CloudException {
        var req = new Request.Builder()
                .url(uploadUrl)
                .put(RequestBody.create(null, new byte[0]))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Range", "bytes */" + totalSize)
                .build();
        try (var resp = client().newCall(req).execute()) {
            var code = resp.code();
            if (code >= 200 && code < 300) {
                return totalSize;
            }
            if (code == 308) {
                var range = resp.header("Range"); // 形如 bytes=0-12345
                if (range != null && range.contains("-")) {
                    try {
                        return Long.parseLong(range.substring(range.indexOf('-') + 1).trim()) + 1;
                    } catch (NumberFormatException ignored) {
                    }
                }
                return 0;
            }
            throw new CloudException(CloudException.Kind.REMOTE,
                    "Google Drive 断点查询失败 HTTP " + code);
        } catch (CloudException e) {
            throw e;
        } catch (IOException e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    // ========== 下载 ==========

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null || entry.id.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "Google Drive 文件不存在: " + remotePath);
            }
            var url = HttpUrl.parse(API_BASE + "/files/" + entry.id).newBuilder()
                    .addQueryParameter("alt", "media")
                    .build();
            var req = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + accessToken())
                    .build();
            try (var resp = client().newCall(req).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "Google Drive 下载 HTTP " + code + ": "
                                    + truncate(resp.body() != null ? resp.body().string() : "", 200));
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "Google Drive 下载空响应");
                }
                try (var out = new FileOutputStream(localPath); var in = body.byteStream()) {
                    var buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            var localLen = new File(localPath).length();
            LogHelp.i(TAG, "Google Drive download done name=" + entry.name
                    + " local=" + localLen + " remote=" + entry.size
                    + (localLen == entry.size ? "" : " **SIZE-MISMATCH**"));
            return "OK: " + remotePath + " -> " + localPath;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 删除（入回收站） ==========

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        trashPath(remoteDir);
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        trashPath(remotePath);
    }

    private void trashPath(String remotePath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            if (remote.isEmpty()) return;
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null || entry.id.isEmpty()) {
                LogHelp.w(TAG, "Google Drive 删除目标不存在: " + remote);
                return;
            }
            var req = new Request.Builder()
                    .url(API_BASE + "/files/" + entry.id)
                    .patch(RequestBody.create(JSON, new JSONObject().put("trashed", true).toString()))
                    .header("Authorization", "Bearer " + accessToken())
                    .build();
            try (var resp = client().newCall(req).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "Google Drive 回收站操作失败 HTTP " + code + ": "
                                    + truncate(resp.body() != null ? resp.body().string() : "", 200));
                }
            }
            LogHelp.i(TAG, "Google Drive 已移入回收站: " + remote);
            // 目录被回收后其缓存 ID 立即失效：否则后续 list/upload 会继续用已删目录的 ID，
            // 表现为「目录解析成功但列表为空」这类难查的空结果
            invalidateDirCache(remote);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 静默刷新 ==========

    /** 刷新锁：多线程并发刷新只放一个进（对齐阿里 HIGH-06 模式） */
    private static final Object REFRESH_LOCK = new Object();

    @Override
    public boolean refresh() {
        try {
            refreshAccessToken();
            return true;
        } catch (Exception e) {
            LogHelp.w(TAG, "Google Drive 刷新失败: " + e.getMessage());
            return false;
        }
    }

    private String refreshAccessToken() throws CloudException {
        synchronized (REFRESH_LOCK) {
            var clientId = clientId();
            var clientSecret = EncryptedCredStore.get(id(), "client_secret");
            var rt = EncryptedCredStore.get(id(), "refresh_token");
            if (clientId.isEmpty() || rt.isEmpty()) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                        "Google Drive 凭据不完整，需重新授权");
            }
            var token = GDriveOAuth.refresh(clientId, clientSecret, rt);
            EncryptedCredStore.put(id(), "access_token", token.accessToken);
            EncryptedCredStore.put(id(), "token_expiry", String.valueOf(token.expiresAt));
            // 防御性持久化：refresh_token 不轮换，但撤销/重授权场景可能下发新值
            if (!token.refreshToken.isEmpty()) {
                EncryptedCredStore.put(id(), "refresh_token", token.refreshToken);
            }
            return token.accessToken;
        }
    }

    /** 取可用 access_token：过期预判（提前 60s）不通过则刷新 */
    private String accessToken() throws CloudException {
        var token = EncryptedCredStore.get(id(), "access_token");
        var expiry = parseLong(EncryptedCredStore.get(id(), "token_expiry"));
        if (!token.isEmpty() && expiry > System.currentTimeMillis() + TOKEN_EXPIRY_MARGIN_MS) {
            return token;
        }
        return refreshAccessToken();
    }

    private String clientId() {
        var cid = EncryptedCredStore.get(id(), "client_id");
        return !cid.isEmpty() ? cid : GDriveOAuth.BUILTIN_CLIENT_ID;
    }

    // ========== 业务 API（重试 + 错误还原成异常） ==========

    private static class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /**
     * 带 Bearer 的业务请求：网络错误/5xx/限流退避重试，401 刷新重试一次，
     * 其余非 2xx 还原成异常（错误体必须带出，禁止静默吞）。
     * 返回解析后的 JSON（空 body 返回空对象）。
     */
    private JSONObject request(String method, String url, RequestBody body) throws CloudException {
        var retriedRefresh = false;
        var lastCode = 0;
        for (var i = 0; i < MAX_RETRY; i++) {
            var reqBuilder = new Request.Builder().url(url)
                    .header("Authorization", "Bearer " + accessToken());
            if ("GET".equals(method)) {
                reqBuilder.get();
            } else if ("DELETE".equals(method)) {
                reqBuilder.delete();
            } else if ("PATCH".equals(method) && body != null) {
                reqBuilder.patch(body);
            } else if ("POST".equals(method) && body != null) {
                reqBuilder.post(body);
            } else if (body != null) {
                reqBuilder.method(method, body);
            } else {
                reqBuilder.method(method, RequestBody.create(null, new byte[0]));
            }
            HttpResult resp;
            try (var r = client().newCall(reqBuilder.build()).execute()) {
                resp = new HttpResult(r.code(), r.body() != null ? r.body().string() : "");
            } catch (IOException e) {
                if (i < MAX_RETRY - 1) {
                    backoff(i, "网络错误 " + e.getMessage());
                    continue;
                }
                throw new CloudException(CloudException.Kind.NETWORK, e);
            }
            lastCode = resp.code;
            if (resp.code == 401 && !retriedRefresh) {
                retriedRefresh = true;
                LogHelp.i(TAG, "Google Drive token 失效，刷新重试");
                refreshAccessToken();
                continue;
            }
            if ((resp.code == 403 || resp.code >= 500) && i < MAX_RETRY - 1) {
                // 403 可能是限流（rateLimitExceeded/userRateLimitExceeded）也可能是权限问题；
                // 重试一轮仍失败则按错误体如实上抛
                backoff(i, "HTTP " + resp.code);
                continue;
            }
            if (resp.code < 200 || resp.code >= 300) {
                // 403 仅在确属凭据问题时按 AUTH_EXPIRED；限流（rateLimitExceeded 等）属 REMOTE，
                // 避免把配额/限流误报成「登录态过期」触发上层误清账号
                var authExpired = resp.code == 401
                        || (resp.code == 403 && !isRateLimitBody(resp.body));
                throw new CloudException(authExpired
                        ? CloudException.Kind.AUTH_EXPIRED : CloudException.Kind.REMOTE,
                        "Google Drive API HTTP " + resp.code + ": " + truncate(resp.body, 200));
            }
            var trimmed = resp.body.isBlank() ? "{}" : resp.body;
            try {
                return new JSONObject(trimmed);
            } catch (Exception e) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "Google Drive 响应非 JSON: " + truncate(resp.body, 200));
            }
        }
        throw new CloudException(CloudException.Kind.REMOTE,
                "Google Drive 重试次数用尽 lastHTTP=" + lastCode + " url=" + url);
    }

    /** 判断 403 响应体是否为限流类错误（rateLimitExceeded / userRateLimitExceeded / dailyLimitExceeded） */
    private static boolean isRateLimitBody(String body) {
        if (body == null) return false;
        var lower = body.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("ratelimitexceeded") || lower.contains("userratelimit")
                || lower.contains("dailylimitexceeded") || lower.contains("quotaexceeded");
    }

    /** 退避等待（500ms 起指数增长，封顶 4s）；中断时复位中断标志 */
    private static void backoff(int attempt, String why) {
        var ms = Math.min(500L << Math.min(attempt, 3), 4000L);
        LogHelp.w(TAG, "Google Drive " + why + "，退避 " + ms + "ms 后重试");
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ========== 路径解析（Drive 无路径语义 → 逐级 folder ID 映射） ==========

    private static class Entry {
        final String id;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;

        Entry(String id, String name, long size, boolean isDir, long modifiedTime) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
        }
    }

    /** 分页枚举目录（files.list，pageToken 翻页，1000/页） */
    private List<Entry> listChildren(String parentId) throws CloudException {
        var items = new ArrayList<Entry>();
        var pageToken = "";
        for (var guard = 0; guard < 100; guard++) {
            var url = HttpUrl.parse(API_BASE + "/files").newBuilder()
                    .addQueryParameter("q", "'" + parentId + "' in parents and trashed = false")
                    .addQueryParameter("pageSize", "1000")
                    .addQueryParameter("orderBy", "folder,name")
                    .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                    .build();
            if (!pageToken.isEmpty()) {
                url = url.newBuilder().addQueryParameter("pageToken", pageToken).build();
            }
            var resp = request("GET", url.toString(), null);
            var arr = resp.optJSONArray("files");
            if (arr == null) {
                // files 正常必有（空目录为 []）；缺字段视为异常，禁止当空目录吞掉
                throw new CloudException(CloudException.Kind.REMOTE,
                        "Google Drive list 响应缺少 files: " + truncate(resp.toString(), 200));
            }
            for (var i = 0; i < arr.length(); i++) {
                var o = arr.optJSONObject(i);
                if (o != null) items.add(toEntry(o));
            }
            pageToken = resp.optString("nextPageToken", "");
            if (pageToken.isEmpty()) break;
        }
        return items;
    }

    private static Entry toEntry(JSONObject o) {
        var modified = 0L;
        var mt = o.optString("modifiedTime", "");
        if (mt.contains("T") || mt.contains("Z")) {
            try {
                modified = java.time.Instant.parse(mt).toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        return new Entry(o.optString("id", ""), o.optString("name", ""),
                o.optLong("size", 0L), MIME_FOLDER.equals(o.optString("mimeType", "")), modified);
    }

    private Entry findChild(String parentId, String name) throws CloudException {
        for (var e : listChildren(parentId)) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    /**
     * 解析路径为目录 folderId；createMissing=true 时逐级建目录。
     *
     * 并发安全：整个 find-or-create 串行（PATH_LOCK），并**逐级**写入缓存
     * （不只缓存最终路径）。否则并发解析 `A/B/x` 与 `A/B/y` 时，两个线程都可能
     * 认为 `A/B` 不存在而各建一份——Drive 允许同目录重名，服务端不会拦。
     * 目录 ID 一旦缓存，后续同进程内的上传/列表都复用同一个 ID。
     */
    private String resolvePath(String path, boolean createMissing) throws CloudException {
        var v = trimSlashes(path);
        if (v.isEmpty()) return "root";
        var accountKey = id();
        var fullKey = accountKey + "|" + v;
        var hit = DIR_CACHE.get(fullKey);
        if (hit != null) return hit;

        synchronized (PATH_LOCK) {
            hit = DIR_CACHE.get(fullKey);
            if (hit != null) return hit;

            var parentId = "root";
            var prefix = new StringBuilder();
            for (var part : v.split("/")) {
                var name = cleanName(part);
                if (name.isEmpty()) continue;
                if (prefix.length() > 0) prefix.append('/');
                prefix.append(name);

                var key = accountKey + "|" + prefix;
                var cachedId = DIR_CACHE.get(key);
                if (cachedId != null) {
                    parentId = cachedId;
                    continue;
                }

                var child = findChild(parentId, name);
                if (child == null) {
                    if (!createMissing) return null;
                    parentId = createFolder(parentId, name);
                } else {
                    if (!child.isDir) {
                        throw new CloudException(CloudException.Kind.REMOTE,
                                "Google Drive 路径非目录: " + name);
                    }
                    parentId = child.id;
                }
                DIR_CACHE.put(key, parentId);
            }
            return parentId;
        }
    }

    /** 删除/回收某路径后失效其自身与子孙的目录缓存，避免继续使用已删目录的 ID */
    private void invalidateDirCache(String path) {
        var prefix = id() + "|" + trimSlashes(path);
        DIR_CACHE.keySet().removeIf(k -> k.equals(prefix) || k.startsWith(prefix + "/"));
    }

    private String createFolder(String parentId, String name) throws CloudException {
        var body = new JSONObject();
        try {
            body.put("name", name);
            body.put("parents", new JSONArray().put(parentId));
            body.put("mimeType", MIME_FOLDER);
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.LOCAL, e);
        }
        var resp = request("POST", API_BASE + "/files?fields=id",
                RequestBody.create(JSON, body.toString()));
        var id = resp.optString("id", "");
        if (id.isEmpty()) {
            // 允许重复建目录：名字冲突时兜底查一次（Drive 同目录允许重名，取首个）
            var child = findChild(parentId, name);
            if (child != null) id = child.id;
        }
        if (id.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "Google Drive 建目录失败: " + name);
        }
        LogHelp.i(TAG, "Google Drive mkdir name=" + name + " -> " + id);
        return id;
    }

    private Entry findEntry(String parentPath, String targetName) throws CloudException {
        var parentId = resolvePath(parentPath, false);
        if (parentId == null) return null;
        return findChild(parentId, cleanName(targetName));
    }

    // ========== HTTP 客户端（支持账号级代理，决策 Q5） ==========

    /** OkHttp 定制：代理注入 + 连接池复用 */
    private OkHttpClient buildClient(boolean noRedirect) {
        var builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES)
                .retryOnConnectionFailure(true);
        if (noRedirect) {
            builder.followRedirects(false).followSslRedirects(false);
        }
        var host = EncryptedCredStore.get(id(), "proxy_host");
        var portStr = EncryptedCredStore.get(id(), "proxy_port");
        if (!host.isEmpty() && !portStr.isEmpty()) {
            try {
                var port = Integer.parseInt(portStr);
                builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
                LogHelp.i(TAG, "Google Drive 使用代理 " + host + ":" + port);
            } catch (Exception e) {
                LogHelp.w(TAG, "Google Drive 代理参数无效: " + host + ":" + portStr);
            }
        }
        return builder.build();
    }

    private OkHttpClient sClient;
    private OkHttpClient sNoRedirectClient;

    private synchronized OkHttpClient client() {
        if (sClient == null) sClient = buildClient(false);
        return sClient;
    }

    private synchronized OkHttpClient noRedirectClient() {
        if (sNoRedirectClient == null) sNoRedirectClient = buildClient(true);
        return sNoRedirectClient;
    }

    // ========== 工具 ==========

    private static void skipFully(FileInputStream in, long offset) throws IOException {
        var skipped = 0L;
        while (skipped < offset) {
            var more = in.skip(offset - skipped);
            if (more == 0) throw new IOException("seek failed: " + offset);
            skipped += more;
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
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

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐阿里 cleanName） */
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
