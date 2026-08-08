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
import com.suileyan.cloud.login.Yun139Login;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 中国移动云盘（139）Provider
 *
 * 实现参考：plugins/yun139.js（路径/接口语义）+ alist/OpenList drivers/139（签名与请求头格式修正）。
 * 关键修正（相对脚本）：mcloud-sign 头必须是 "ts,rand,sign" 三段（alist 两套请求体系均如此），
 * 脚本只放 sign 是错误写法；API 用网页真实请求捕获到的 personal 节点（host），回退 yun.139.com。
 *
 * 约定：只处理整文件上传/下载；大文件切片由 CloudFileHelp 层完成（默认 64MB/片），
 * 139 单分片上限 100MB，切片文件单分片 PUT 即可，无需 getUploadUrl 多分片流程。
 */
public class Yun139Provider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "yun139";
    private static final String DEFAULT_HOST = "https://yun.139.com";
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final int BUFFER_SIZE = 64 * 1024;

    private final CloudAccount account;
    private final String authorization;
    /** 懒解析的 API 基础地址：登录时捕获的节点 host 优先，缺失时调 qryRoutePolicy 路由查询，最后回退主站 */
    private volatile String apiBase;

    public Yun139Provider(CloudAccount account) {
        this.account = account;
        this.authorization = EncryptedCredStore.get(account.id, "authorization");
        this.apiBase = null;
    }

    /** 解析 API 基础地址（线程安全懒加载） */
    private String apiBase() {
        if (apiBase != null) return apiBase;
        synchronized (this) {
            if (apiBase != null) return apiBase;
            var host = EncryptedCredStore.get(account.id, "host");
            // 仅接受 personal 节点：旧账号可能存了 share/user 等节点（文件 API 不支持，404），忽略走路由查询
            if (host != null && (host.startsWith("personal-") || host.contains("personal."))) {
                apiBase = "https://" + host;
            } else {
                if (host != null && !host.isEmpty()) {
                    LogHelp.w(TAG, "139 stored host is not personal node, ignore and query route: " + host);
                }
                // host 缺失或非 personal 节点：调路由查询接口解析 personal 节点（alist 同款）
                var resolved = queryPersonalHost();
                apiBase = (resolved != null && !resolved.isEmpty()) ? resolved : DEFAULT_HOST;
            }
            LogHelp.i(TAG, "139 apiBase resolved: " + apiBase);
            return apiBase;
        }
    }

    /**
     * 路由查询：获取 personal 模块的 httpsUrl 节点（alist drivers/139 requestRoute 同款）
     * 响应 data.routePolicyList[] 中 modName=="personal" 的 httpsUrl
     */
    private String queryPersonalHost() {
        var request = new Request.Builder()
                .url("https://user-njs.yun.139.com/user/route/qryRoutePolicy")
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Basic " + authorization)
                .header("Caller", "web")
                .header("Origin", "https://yun.139.com")
                .header("Referer", "https://yun.139.com/w/")
                .build();
        try (var resp = Yun139Login.getClient().newCall(request).execute()) {
            var code = resp.code();
            var respBody = resp.body() != null ? resp.body().string() : "";
            if (code < 200 || code >= 300) {
                LogHelp.e(TAG, "139 route query HTTP " + code + " body=" + truncate(respBody, 200));
                return null;
            }
            var json = new JSONObject(respBody);
            var data = json.optJSONObject("data");
            if (data == null) return null;
            var list = data.optJSONArray("routePolicyList");
            if (list != null) {
                for (var i = 0; i < list.length(); i++) {
                    var item = list.optJSONObject(i);
                    if (item != null && "personal".equals(item.optString("modName"))) {
                        var url = item.optString("httpsUrl", "");
                        if (!url.isEmpty()) {
                            return url;
                        }
                    }
                }
            }
            LogHelp.e(TAG, "139 route query no personal host: " + truncate(respBody, 300));
            return null;
        } catch (Exception e) {
            LogHelp.e(TAG, "139 route query failed", e);
            return null;
        }
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
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "139云盘";
    }

    @Override
    public boolean isLoggedIn() {
        return authorization != null && !authorization.isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        // 139 凭据来自 WebView 网页登录捕获，无应用内登录流程
        return LoginState.NOT_SUPPORTED;
    }

    // ========== 目录与列表 ==========

    @Override
    public boolean testConnection() throws CloudException {
        var json = callApi("/hcy/file/list", buildListBody("/", ""));
        return json != null;
    }

    @Override
    public List<String> listDirs() throws CloudException {
        var entries = listParent("/");
        var dirs = new ArrayList<String>();
        for (var e : entries) {
            if (e.directory) dirs.add(e.name);
        }
        return dirs;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        var parentId = resolvePath(remoteDir, false);
        if (parentId == null) {
            return new ArrayList<>();
        }
        return listParent(parentId);
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
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
        var parentId = resolvePath(remoteDir, true);
        if (cb != null) cb.onStart(taskId);

        var fileSize = localFile.length();
        try {
            var contentHash = sha256Hex(localFile);
            var createBody = new JSONObject();
            createBody.put("fileRenameMode", "auto_rename");
            createBody.put("contentType", "application/octet-stream");
            createBody.put("type", "file");
            createBody.put("name", localFile.getName());
            createBody.put("size", fileSize);
            createBody.put("contentHashAlgorithm", "SHA256");
            createBody.put("contentHash", contentHash);
            var partInfos = new JSONArray();
            var partInfo = new JSONObject();
            var parallelCtx = new JSONObject();
            parallelCtx.put("partOffset", 0);
            partInfo.put("parallelHashCtx", parallelCtx);
            partInfo.put("partNumber", 1);
            partInfo.put("partSize", fileSize);
            partInfos.put(partInfo);
            createBody.put("partInfos", partInfos);
            createBody.put("parentFileId", parentId);
            createBody.put("parallelUpload", true);
            // 对齐 alist 新版（MetaPersonalNew）：create body 不含 localCreatedAt，
            // 该字段格式校验严格（04000002 本地创建时间格式不符合标准），缺省更稳
            LogHelp.i(TAG, "139 file create body: " + truncate(createBody.toString(), 500));

            var createJson = callApi("/hcy/file/create", createBody);
            var data = createJson.optJSONObject("data");
            if (data == null) {
                LogHelp.e(TAG, "139 create missing data: " + truncate(createJson.toString(), 300));
                throw new CloudException(CloudException.Kind.REMOTE, "139 create missing data");
            }
            var fileId = data.optString("fileId", "");
            if (fileId.isEmpty()) {
                LogHelp.e(TAG, "139 create missing fileId: " + truncate(createJson.toString(), 300));
                throw new CloudException(CloudException.Kind.REMOTE, "139 create missing fileId");
            }
            var rapid = data.optBoolean("rapidUpload", false);
            var uploadId = data.optString("uploadId", "");
            var parts = data.optJSONArray("partInfos");
            var uploadUrl = "";
            if (parts != null && parts.length() > 0) {
                var p0 = parts.optJSONObject(0);
                if (p0 != null) {
                    uploadUrl = p0.optString("uploadUrl", p0.optString("cdnUploadUrl", ""));
                }
            }
            LogHelp.i(TAG, "139 create ok name=" + localFile.getName() + " size=" + fileSize
                    + " fileId=" + fileId + " rapidUpload=" + rapid
                    + " uploadId=" + (uploadId.isEmpty() ? "-" : "yes")
                    + " uploadUrl=" + (uploadUrl.isEmpty() ? "-" : hostOf(uploadUrl)));
            // 秒传命中：无需 PUT 与 complete
            if (rapid) {
                LogHelp.i(TAG, "139 rapidUpload hit, skip PUT/complete: " + localFile.getName());
                if (cb != null) cb.onProgress(taskId, fileSize, fileSize);
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }

            if (uploadUrl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "139 missing uploadUrl: " + createJson.toString());
            }
            if (uploadId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "139 missing uploadId");
            }

            // PUT 上传切片文件（CloudFileHelp 已切好，≤64MB，单分片）
            putFile(uploadUrl, localFile, cb, taskId);

            // complete 完成上传
            var completeBody = new JSONObject();
            completeBody.put("fileId", fileId);
            completeBody.put("uploadId", uploadId);
            completeBody.put("contentHash", contentHash);
            completeBody.put("contentHashAlgorithm", "SHA256");
            callApi("/hcy/file/complete", completeBody);

            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "139 upload failed", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 下载 ==========

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            var name = pathName(remote);
            var parentPath = pathParent(remote);
            var entry = findEntry(parentPath, name);
            if (entry == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "139 file not found: " + remotePath);
            }
            var fileId = entry.fileId;
            var downloadUrl = entry.downloadUrl;
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                var urlBody = new JSONObject();
                urlBody.put("fileId", fileId);
                var urlJson = callApi("/hcy/file/getDownloadUrl", urlBody);
                var data = urlJson.optJSONObject("data");
                if (data != null) {
                    downloadUrl = data.optString("downloadURL", data.optString("cdnUrl", data.optString("url", "")));
                }
            }
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                LogHelp.e(TAG, "139 missing download url for " + remotePath + " (fileId=" + fileId + ")");
                throw new CloudException(CloudException.Kind.REMOTE, "139 missing download url: " + remotePath);
            }
            LogHelp.i(TAG, "139 download start name=" + name + " fileId=" + fileId + " host=" + hostOf(downloadUrl) + " -> " + localPath);
            var request = new Request.Builder().url(downloadUrl).build();
            try (var resp = Yun139Login.getClient().newCall(request).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    LogHelp.e(TAG, "139 download HTTP " + code + " name=" + name);
                    throw new CloudException(CloudException.Kind.REMOTE, "139 download HTTP " + code);
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "139 download empty body");
                }
                try (var out = new FileOutputStream(localPath); var in = body.byteStream()) {
                    var buffer = new byte[BUFFER_SIZE];
                    var read = 0;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            LogHelp.i(TAG, "139 download done: " + remotePath);
            return "OK: " + remotePath + " -> " + localPath;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
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
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null || entry.fileId == null || entry.fileId.isEmpty()) {
                // 目标不存在按成功处理，便于清理旧备份重试
                return;
            }
            var body = new JSONObject();
            var ids = new JSONArray();
            ids.put(entry.fileId);
            body.put("fileIds", ids);
            callApi("/hcy/recyclebin/batchTrash", body);
            LogHelp.i(TAG, "139 trashed: " + remotePath + " (fileId=" + entry.fileId + ")");
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 139 API 封装 ==========

    /** 调用 139 JSON API（personal 新 API 体系：x-yun-* 头 + Mcloud-Sign=ts,rand,sign） */
    private JSONObject callApi(String path, JSONObject body) throws CloudException {
        var bodyStr = body != null ? body.toString() : "";
        var ts = Yun139Login.currentTimestamp();
        var rand = Yun139Login.randomString(16);
        var sign = Yun139Login.calSign(bodyStr, ts, rand);
        var base = apiBase();
        var builder = new Request.Builder()
                .url(base + path)
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "Basic " + authorization)
                .header("Caller", "web")
                .header("CMS-DEVICE", "default")
                .header("Mcloud-Channel", "1000101")
                .header("Mcloud-Client", "10701")
                .header("Mcloud-Route", "001")
                .header("Mcloud-Sign", ts + "," + rand + "," + sign)
                .header("Mcloud-Version", "7.14.0")
                .header("Origin", "https://yun.139.com")
                .header("Referer", "https://yun.139.com/w/")
                .header("x-DeviceInfo", "||9|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||")
                .header("x-huawei-channelSrc", "10000034")
                .header("x-inner-ntwk", "2")
                .header("x-m4c-caller", "PC")
                .header("x-m4c-src", "10002")
                .header("x-SvcType", "1")
                .header("Inner-Hcy-Router-Https", "1")
                .header("X-Yun-Api-Version", "v1")
                .header("X-Yun-App-Channel", "10000034")
                .header("X-Yun-Channel-Source", "10000034")
                .header("X-Yun-Client-Info", "||13|7.14.0|chrome|120.0.0.0|||windows 10||zh-CN|||dW5kZWZpbmVk||")
                .header("X-Yun-Module-Type", "100")
                .header("X-Yun-Svc-Type", "1")
                .header("Content-Type", "application/json;charset=UTF-8");
        if (bodyStr != null && !bodyStr.isEmpty()) {
            builder.post(RequestBody.create(JSON, bodyStr));
        }
        var url = base + path;
        LogHelp.d(TAG, "139 req POST " + path + " -> " + base + " (auth=" + (authorization != null && !authorization.isEmpty()) + ")");
        try (var resp = Yun139Login.getClient().newCall(builder.build()).execute()) {
            var code = resp.code();
            var respBody = resp.body() != null ? resp.body().string() : "";
            if (code == 401 || code == 403) {
                LogHelp.e(TAG, "139 auth expired: HTTP " + code + " path=" + path + " body=" + truncate(respBody, 300));
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "139 auth expired: HTTP " + code);
            }
            if (code < 200 || code >= 300) {
                LogHelp.e(TAG, "139 API HTTP " + code + " path=" + path + " body=" + truncate(respBody, 300));
                throw new CloudException(CloudException.Kind.REMOTE, "139 API HTTP " + code + ": " + truncate(respBody, 200));
            }
            // 2xx 但返回 HTML（非 JSON）说明打到了错误的主机（主站不提供 /hcy API，返回网页）
            if (respBody.startsWith("<!DOCTYPE") || respBody.startsWith("<html")) {
                LogHelp.e(TAG, "139 API returned HTML (wrong host?) base=" + base + " path=" + path + " head=" + truncate(respBody, 120));
                throw new CloudException(CloudException.Kind.REMOTE, "139 API returned HTML, check api node");
            }
            var json = new JSONObject(respBody);
            if (!json.isNull("success") && !json.optBoolean("success", true)) {
                LogHelp.e(TAG, "139 API success=false path=" + path + " body=" + truncate(respBody, 300));
                throw new CloudException(CloudException.Kind.REMOTE,
                        "139 API failed: " + json.optString("message", "unknown"));
            }
            LogHelp.d(TAG, "139 resp " + code + " " + path);
            return json;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "139 API exception path=" + path + " url=" + url, e);
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** PUT 上传切片文件并回调进度 */
    private void putFile(String uploadUrl, File localFile, ProgressCallback cb, String taskId) throws Exception {
        var fileSize = localFile.length();
        var body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return OCTET_STREAM;
            }

            @Override
            public long contentLength() {
                return fileSize;
            }

            @Override
            public void writeTo(okio.BufferedSink sink) throws IOException {
                var buffer = new byte[BUFFER_SIZE];
                var written = 0L;
                var lastReport = 0L;
                try (var in = new FileInputStream(localFile)) {
                    var read = 0;
                    while ((read = in.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                        written += read;
                        var now = System.currentTimeMillis();
                        if (cb != null && (now - lastReport >= 200 || written == fileSize)) {
                            lastReport = now;
                            cb.onProgress(taskId, written, fileSize);
                        }
                    }
                }
            }
        };
        var request = new Request.Builder()
                .url(uploadUrl)
                .header("Content-Type", "application/octet-stream")
                .header("Origin", "https://yun.139.com")
                .header("Referer", "https://yun.139.com/")
                .put(body)
                .build();
        LogHelp.d(TAG, "139 PUT upload size=" + fileSize + " host=" + hostOf(uploadUrl));
        try (var resp = Yun139Login.getClient().newCall(request).execute()) {
            var code = resp.code();
            // 必须消费 body 以复用连接
            var respBody = resp.body() != null ? resp.body().string() : "";
            if (code == 401 || code == 403) {
                LogHelp.e(TAG, "139 upload auth expired: HTTP " + code + " body=" + truncate(respBody, 300));
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "139 upload auth expired: HTTP " + code);
            }
            if (code < 200 || code >= 300) {
                LogHelp.e(TAG, "139 upload HTTP " + code + " body=" + truncate(respBody, 300));
                throw new CloudException(CloudException.Kind.REMOTE, "139 upload HTTP " + code);
            }
            LogHelp.d(TAG, "139 PUT upload done code=" + code);
        }
    }

    // ========== 路径解析（移植 yun139.js） ==========

    /** 分页收集目录下所有条目（list 返回条目结构） */
    private List<Item> collectItems(String parentFileId) throws CloudException {
        var items = new ArrayList<Item>();
        var cursor = "";
        for (var guard = 0; guard < 100; guard++) {
            var json = callApi("/hcy/file/list", buildListBody(parentFileId, cursor));
            var data = json.optJSONObject("data");
            if (data == null) break;
            var arr = data.optJSONArray("items");
            if (arr != null) {
                for (var i = 0; i < arr.length(); i++) {
                    var obj = arr.optJSONObject(i);
                    if (obj != null) items.add(Item.fromJson(obj));
                }
            }
            cursor = data.optString("nextPageCursor", "");
            if (cursor.isEmpty()) break;
        }
        return items;
    }

    /** 列出父目录下条目并转换为 RemoteEntry */
    private List<RemoteEntry> listParent(String parentFileId) throws CloudException {
        var items = collectItems(parentFileId);
        var entries = new ArrayList<RemoteEntry>(items.size());
        for (var it : items) {
            entries.add(new RemoteEntry(it.name, it.size, it.isDir, it.modifiedTime));
        }
        return entries;
    }

    /** 在父目录下查找子项 */
    private Item findChild(String parentFileId, String name) throws CloudException {
        for (var it : collectItems(parentFileId)) {
            if (it.name.equals(name)) return it;
        }
        return null;
    }

    /** 按路径解析目录 fileId；createMissing=true 时自动创建缺失目录 */
    private String resolvePath(String path, boolean createMissing) throws CloudException {
        var value = trimSlashes(path);
        if (value.isEmpty()) return "/";
        var parts = value.split("/");
        var parentId = "/";
        for (var i = 0; i < parts.length; i++) {
            var name = parts[i];
            if (name.isEmpty()) continue;
            var child = findChild(parentId, name);
            if (child == null) {
                if (!createMissing) return null;
                parentId = createFolder(parentId, name);
                continue;
            }
            if (!child.isDir) {
                if (!createMissing) return null;
                throw new CloudException(CloudException.Kind.REMOTE, "139 path is not folder: " + name);
            }
            parentId = child.fileId;
        }
        return parentId;
    }

    /** 创建目录并返回新目录 fileId */
    private String createFolder(String parentFileId, String name) throws CloudException {
        try {
            var body = new JSONObject();
            body.put("type", "folder");
            body.put("name", name);
            body.put("parentFileId", parentFileId);
            // 对齐 alist：建目录不带 localCreatedAt（该字段格式校验严格，缺省最稳）
            LogHelp.i(TAG, "139 create folder body: " + truncate(body.toString(), 300));
            var json = callApi("/hcy/file/create", body);
            var data = json.optJSONObject("data");
            if (data == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "139 create folder missing data");
            }
            var id = data.optString("fileId", data.optString("id", ""));
            if (id.isEmpty()) {
                LogHelp.e(TAG, "139 create folder missing fileId parent=" + parentFileId + " name=" + name + " resp=" + truncate(json.toString(), 300));
                throw new CloudException(CloudException.Kind.REMOTE, "139 create folder missing fileId");
            }
            LogHelp.i(TAG, "139 create folder ok name=" + name + " parent=" + parentFileId + " -> " + id);
            return id;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 按父路径与文件名查找条目 */
    private Item findEntry(String parentPath, String targetName) throws CloudException {
        var parentId = resolvePath(parentPath, false);
        if (parentId == null) return null;
        for (var it : collectItems(parentId)) {
            if (it.name.equals(targetName)) return it;
        }
        return null;
    }

    // ========== 工具 ==========

    private static JSONObject buildListBody(String parentFileId, String pageCursor) {
        try {
            var body = new JSONObject();
            var thumbs = new JSONArray();
            thumbs.put("Small");
            thumbs.put("Large");
            body.put("imageThumbnailStyleList", thumbs);
            body.put("orderBy", "updated_at");
            body.put("orderDirection", "DESC");
            var pageInfo = new JSONObject();
            pageInfo.put("pageCursor", pageCursor == null ? "" : pageCursor);
            pageInfo.put("pageSize", 1000);
            body.put("pageInfo", pageInfo);
            body.put("parentFileId", parentFileId == null || parentFileId.isEmpty() ? "/" : parentFileId);
            return body;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 流式计算文件 SHA256 */
    private static String sha256Hex(File file) throws IOException {
        try (var in = new FileInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[BUFFER_SIZE];
            var read = 0;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            var out = new StringBuilder();
            for (var b : digest.digest()) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IOException("sha256 failed", e);
        }
    }

    private static String trimSlashes(String path) {
        var value = path == null ? "" : path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String pathParent(String path) {
        var value = trimSlashes(path);
        var idx = value.lastIndexOf('/');
        return idx < 0 ? "/" : value.substring(0, idx);
    }

    private static String pathName(String path) {
        var value = trimSlashes(path);
        var idx = value.lastIndexOf('/');
        return idx < 0 ? value : value.substring(idx + 1);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 提取 URL 主机用于日志（uploadUrl/downloadUrl 可能带签名参数，只记录 host） */
    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            var u = new java.net.URI(url);
            return u.getHost() != null ? u.getHost() : url;
        } catch (Exception e) {
            return url.length() > 80 ? url.substring(0, 80) + "..." : url;
        }
    }

    /** list 接口返回条目（兼容 fileId/name/type/downloadUrl 字段） */
    private static class Item {
        final String fileId;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;
        final String downloadUrl;

        Item(String fileId, String name, long size, boolean isDir, long modifiedTime, String downloadUrl) {
            this.fileId = fileId;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
            this.downloadUrl = downloadUrl;
        }

        static Item fromJson(JSONObject obj) {
            var fileId = obj.optString("fileId", obj.optString("id", ""));
            var name = obj.optString("fileName", obj.optString("name", ""));
            var isDir = "folder".equals(obj.optString("type"))
                    || "folder".equals(obj.optString("fileType"))
                    || "folder".equals(obj.optString("category"));
            var downloadUrl = obj.optString("downloadUrl", "");
            if (downloadUrl.isEmpty()) downloadUrl = obj.optString("cdnUrl", "");
            if (downloadUrl.isEmpty()) downloadUrl = obj.optString("url", "");
            var modified = 0L;
            var updated = obj.optString("updatedAt", "");
            if (!updated.isEmpty()) {
                try {
                    modified = java.time.Instant.parse(updated).toEpochMilli();
                } catch (Exception ignored) {
                }
            }
            if (modified == 0L) modified = System.currentTimeMillis();
            return new Item(fileId, name, obj.optLong("size", 0L), isDir, modified, downloadUrl);
        }
    }
}
