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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 夸克云盘（pan.quark.cn）Provider
 *
 * API 参考 tmp/QuarkPan（Python 客户端）与 AList drivers/quark_uc（Go 驱动）：
 * - 认证：整体 Cookie（含 __puus 会话凭证），存 EncryptedCredStore "cookie" 键
 * - 列表：GET /1/clouddrive/file/sort（pdir_fid 分页）
 * - 上传：pre → update/hash → auth(PUT) → OSS PUT 分片 → auth(POST) → OSS Complete → finish
 *   - 分片大小取 pre 响应 metadata.part_size（服务端下发，AList 同款逻辑），无 hash_ctx（AList 实测不需要）
 *   - 上传 URL 为 https://{bucket}.{upload_url}/{obj_key}?partNumber=&uploadId=
 * - 下载：POST /file/download 换直链（download_url）后流式下载（带 Cookie/Referer/UA）
 * - __puus 有效期约 3 小时：每次响应合并 Set-Cookie 中的新 __puus；
 *   401 时 refresh() 用「剥离 __puus 的 Cookie」请求 /config，让服务端重新下发会话凭证（AList#830）
 */
public class QuarkProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "quark";
    private static final String API_BASE = "https://drive-pc.quark.cn/1/clouddrive";
    private static final String ROOT_ID = "0";
    private static final String REFERER = "https://pan.quark.cn";
    /** 客户端 UA（AList 同款，接近夸克客户端） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    /** OSS 上传 UA（AList 实测可用） */
    private static final String OSS_UA = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit";
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final int BUFFER_SIZE = 64 * 1024;
    /** 分片大小上限：超过上限拆多个 part（服务端下发的 part_size 兜底值） */
    private static final int DEFAULT_PART_SIZE = 4 * 1024 * 1024;

    private final CloudAccount account;

    public QuarkProvider(CloudAccount account) {
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
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "夸克云盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !cookie().isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        // 夸克凭据来自 WebView 网页登录捕获 Cookie，无应用内登录流程
        return LoginState.NOT_SUPPORTED;
    }

    // ========== 目录与列表 ==========

    @Override
    public boolean testConnection() throws CloudException {
        // 用根目录列表做轻量验证（/capacity 端点已下线返回 404，/config 不校验登录态）
        var params = new LinkedHashMap<String, String>();
        params.put("pdir_fid", ROOT_ID);
        params.put("_page", "1");
        params.put("_size", "1");
        params.put("_sort", "file_name:asc");
        params.put("_fetch_total", "1");
        callApiGet("/file/sort", params);
        return true;
    }

    @Override
    public List<String> listDirs() throws CloudException {
        var out = new ArrayList<String>();
        for (var e : listParent(ROOT_ID)) {
            if (e.directory) out.add(e.name);
        }
        return out;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        var parentId = resolvePath(remoteDir, false);
        if (parentId == null) return new ArrayList<>();
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
        try {
            var parentId = resolvePath(remoteDir, true);
            if (cb != null) cb.onStart(taskId);
            var size = localFile.length();

            // 1. 预上传（服务端返回 task_id/auth_info/upload_id/obj_key/bucket/callback/part_size）
            var now = System.currentTimeMillis();
            var preBody = new JSONObject();
            preBody.put("ccp_hash_update", true);
            preBody.put("dir_name", "");
            preBody.put("file_name", localFile.getName());
            preBody.put("format_type", mimeOf(localFile.getName()));
            preBody.put("l_created_at", now);
            preBody.put("l_updated_at", now);
            preBody.put("pdir_fid", parentId == null ? ROOT_ID : parentId);
            preBody.put("size", size);
            var preJson = callApi("/file/upload/pre", preBody, null);
            var preData = preJson.optJSONObject("data");
            var preMeta = preJson.optJSONObject("metadata");
            if (preData == null || preData.isNull("task_id")) {
                throw new CloudException(CloudException.Kind.REMOTE, "夸克预上传失败: " + truncate(preJson.toString(), 300));
            }
            var taskId2 = preData.optString("task_id");
            var authInfo = preData.optString("auth_info", "");
            var uploadId = preData.optString("upload_id", "");
            var objKey = preData.optString("obj_key", "");
            var bucket = preData.optString("bucket", "");
            var uploadHost = preData.optString("upload_url", "");
            var callbackObj = preData.optJSONObject("callback");
            // 诊断：预上传关键字段（uploadId/objKey 是否含特殊字符——URL 编码与 auth_meta 一致性排查）
            LogHelp.i(TAG, "夸克 pre task=" + truncate(taskId2, 16) + " uploadId=" + truncate(uploadId, 30)
                    + " objKey=" + truncate(objKey, 40) + " bucket=" + truncate(bucket, 20)
                    + " host=" + truncate(uploadHost, 30) + " finish=" + preData.optBoolean("finish", false));
            // 秒传：预上传直接完成（服务端已有该文件）
            if (preData.optBoolean("finish", false) || taskId2.isEmpty()) {
                callApi("/file/upload/finish", new JSONObject().put("task_id", taskId2).put("obj_key", objKey), null);
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }

            // 2. 计算 md5/sha1 并上报（服务端可能据此秒传）
            var md5 = md5Hex(localFile);
            var sha1 = sha1Hex(localFile);
            var hashBody = new JSONObject();
            hashBody.put("task_id", taskId2);
            hashBody.put("md5", md5);
            hashBody.put("sha1", sha1);
            var hashJson = callApi("/file/update/hash", hashBody, null);
            var hashData = hashJson.optJSONObject("data");
            if (hashData != null && hashData.optBoolean("finish", false)) {
                callApi("/file/upload/finish", new JSONObject().put("task_id", taskId2).put("obj_key", objKey), null);
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }

            // 3. 分片上传（part 大小取服务端下发值，兜底 4MB；无需 hash_ctx，AList 实测）
            var partSize = preMeta != null ? preMeta.optInt("part_size", 0) : 0;
            if (partSize <= 0) partSize = DEFAULT_PART_SIZE;
            var etags = new ArrayList<String>();
            var offset = 0L;
            var partNumber = 1;
            var done = false;
            while (offset < size) {
                var len = (int) Math.min(partSize, size - offset);
                // uploadId 不编码（对齐 QuarkPan 参考：URL 与 auth_meta 的 resource 保持一致，否则含特殊字符时
                // OSS 按 URL 提取 CanonicalizedResource 与 auth_meta 不一致 → SignatureDoesNotMatch 403）
                var putUrl = ossUrl(bucket, uploadHost, objKey) + "?partNumber=" + partNumber + "&uploadId=" + uploadId;
                var putMeta = putAuthMeta(authInfo, taskId2, mimeOf(localFile.getName()), bucket, objKey, partNumber, uploadId);
                var authJson = callApi("/file/upload/auth", putMeta, null);
                var authKey = authJson.optJSONObject("data") != null
                        ? authJson.optJSONObject("data").optString("auth_key", "") : "";
                if (authKey.isEmpty()) {
                    throw new CloudException(CloudException.Kind.REMOTE, "夸克上传授权失败(part " + partNumber + ")");
                }
                var etag = ossPut(putUrl, localFile, offset, len, mimeOf(localFile.getName()), authKey);
                if (etag.isEmpty()) {
                    throw new CloudException(CloudException.Kind.REMOTE, "夸克分片上传失败(part " + partNumber + ")");
                }
                etags.add(etag);
                offset += len;
                partNumber++;
                if (cb != null) cb.onProgress(taskId, offset, size);
            }

            // 4. 完成分片合并（OSS CompleteMultipartUpload）
            ossComplete(bucket, uploadHost, objKey, uploadId, authInfo, taskId2, etags, callbackObj);

            // 5. 通知夸克服务器完成
            callApi("/file/upload/finish", new JSONObject().put("task_id", taskId2).put("obj_key", objKey), null);
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "夸克上传失败", e);
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
            var parent = pathParent(remote);
            var entry = findEntry(parent, name);
            if (entry == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "夸克文件不存在: " + remotePath);
            }
            var fileId = entry.fileId;
            if (fileId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "夸克缺少文件 id: " + remotePath);
            }
            var dl = downloadUrl(fileId);
            if (dl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "夸克缺少下载地址: " + remotePath);
            }
            var builder = new Request.Builder().url(dl)
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .header("Accept", "application/json, text/plain, */*");
            var ck = cookie();
            if (!ck.isEmpty()) builder.header("Cookie", ck);
            try (var resp = client().newCall(builder.build()).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE, "夸克下载 HTTP " + code);
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "夸克下载空响应");
                }
                try (var out = new FileOutputStream(localPath); var in = body.byteStream()) {
                    var buffer = new byte[BUFFER_SIZE];
                    var read = 0;
                    var written = 0L;
                    var total = body.contentLength();
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        written += read;
                    }
                    if (total > 0 && written != total) {
                        throw new CloudException(CloudException.Kind.REMOTE, "夸克下载不完整: " + written + "/" + total);
                    }
                }
            }
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
            if (entry == null || entry.fileId.isEmpty()) return;
            var ids = new JSONArray();
            ids.put(entry.fileId);
            var body = new JSONObject();
            body.put("action_type", 2);
            body.put("filelist", ids);
            body.put("exclude_fids", new JSONArray());
            callApi("/file/delete", body, null);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 业务 API 封装 ==========

    /** POST JSON API；401 时刷新 __puus 重试一次 */
    private JSONObject callApi(String path, JSONObject data, Map<String, String> extraParams) throws CloudException {
        var resp = httpPost(API_BASE + path, apiHeaders(), asciiJson(data), extraParams);
        if (resp.code == 401) {
            LogHelp.i(TAG, "夸克 401，刷新会话重试: " + path);
            if (refresh()) {
                resp = httpPost(API_BASE + path, apiHeaders(), asciiJson(data), extraParams);
            } else {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "夸克会话失效且刷新失败");
            }
        }
        if (resp.code == 401 || resp.code == 403) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "夸克认证失败 HTTP " + resp.code);
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE, "夸克 API HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            var json = new JSONObject(resp.body);
            var status = json.optInt("status", 0);
            if (status >= 400 || json.optInt("code", 0) != 0) {
                var msg = json.optString("message", "未知错误");
                if (msg.toLowerCase(Locale.ROOT).contains("login") || msg.toLowerCase(Locale.ROOT).contains("auth")) {
                    throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "夸克认证失败: " + msg);
                }
                throw new CloudException(CloudException.Kind.REMOTE, "夸克 API 错误: " + msg);
            }
            return json;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "夸克响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** GET 封装（列表/容量） */
    private JSONObject callApiGet(String path, Map<String, String> params) throws CloudException {
        var resp = httpGet(API_BASE + path, apiHeaders(), params);
        if (resp.code == 401 || resp.code == 403) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "夸克认证失败 HTTP " + resp.code);
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE, "夸克 API HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            var json = new JSONObject(resp.body);
            var status = json.optInt("status", 0);
            if (status >= 400 || json.optInt("code", 0) != 0) {
                throw new CloudException(CloudException.Kind.REMOTE, "夸克 API 错误: " + json.optString("message", "未知错误"));
            }
            return json;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "夸克响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /**
     * 刷新会话（__puus）：用「剥离 __puus 的 Cookie」请求 /config，
     * 服务端会在 Set-Cookie 中重新下发新的 __puus（AList#830）
     */
    @Override
    public boolean refresh() {
        try {
            var stripped = stripPuus(cookie());
            var resp = httpGet(API_BASE + "/config", apiHeaders(stripped), null);
            return resp.code >= 200 && resp.code < 300 && !cookie().isEmpty() && !cookie().equals(stripped);
        } catch (Exception e) {
            LogHelp.w(TAG, "夸克 __puus 刷新失败", e);
            return false;
        }
    }

    // ========== 上传 OSS 细节 ==========

    /** OSS 上传地址：https://{bucket}.{upload_host}/{obj_key} */
    private static String ossUrl(String bucket, String uploadHost, String objKey) {
        var host = uploadHost;
        if (host.startsWith("https://")) host = host.substring(8);
        if (host.startsWith("http://")) host = host.substring(7);
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        var key = objKey == null ? "" : objKey;
        while (key.startsWith("/")) key = key.substring(1);
        return "https://" + bucket + "." + host + "/" + key;
    }

    /** 构造 file/upload/auth 的请求体（PUT 分片授权） */
    private static JSONObject putAuthMeta(String authInfo, String taskId, String mime, String bucket,
                                          String objKey, int partNumber, String uploadId) throws Exception {
        var date = ossDate();
        var authMeta = "PUT\n\n" + mime + "\n" + date + "\n"
                + "x-oss-date:" + date + "\n"
                + "x-oss-user-agent:" + OSS_UA + "\n"
                + "/" + bucket + "/" + objKey + "?partNumber=" + partNumber + "&uploadId=" + uploadId;
        var body = new JSONObject();
        body.put("auth_info", authInfo);
        body.put("auth_meta", authMeta);
        body.put("task_id", taskId);
        return body;
    }

    /** OSS PUT 上传一个分片，返回 ETag（去引号） */
    private String ossPut(String url, File file, long offset, int len, String mime, String authKey) throws Exception {
        var date = ossDate();
        var builder = new Request.Builder().url(url)
                .header("Authorization", authKey)
                .header("Content-Type", mime)
                .header("Referer", REFERER)
                .header("x-oss-date", date)
                .header("x-oss-user-agent", OSS_UA);
        builder.put(new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse(mime);
            }

            @Override
            public long contentLength() {
                return len;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                var buffer = new byte[BUFFER_SIZE];
                var remaining = (long) len;
                try (var in = new FileInputStream(file)) {
                    var skipped = 0L;
                    while (skipped < offset) {
                        var s = in.skip(offset - skipped);
                        if (s <= 0) break;
                        skipped += s;
                    }
                    while (remaining > 0) {
                        var read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read == -1) break;
                        sink.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
        });
        try (var resp = client().newCall(builder.build()).execute()) {
            if (resp.code() != 200) {
                var body = resp.body() != null ? resp.body().string() : "";
                // 完整打印 403（含 OSS 返回的 StringToSign/CanonicalizedResource 调试字段——定位签名差异关键）
                LogHelp.e(TAG, "夸克 OSS PUT 失败 HTTP " + resp.code() + ": " + truncate(body, 1500));
                return "";
            }
            var etag = resp.header("ETag");
            if (etag == null) etag = resp.header("etag");
            if (etag == null) etag = resp.header("Etag");
            if (etag == null) return "";
            return etag.replace("\"", "");
        }
    }

    /** OSS 分片合并：auth(POST) + CompleteMultipartUpload */
    private void ossComplete(String bucket, String uploadHost, String objKey, String uploadId,
                             String authInfo, String taskId, List<String> etags, JSONObject callback) throws Exception {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CompleteMultipartUpload>\n");
        for (var i = 0; i < etags.size(); i++) {
            xml.append("<Part>\n<PartNumber>").append(i + 1).append("</PartNumber>\n")
                    .append("<ETag>\"").append(etags.get(i)).append("\"</ETag>\n</Part>\n");
        }
        xml.append("</CompleteMultipartUpload>");
        var body = xml.toString();

        var md5 = Base64.getEncoder().encodeToString(md5Raw(body));
        var callbackB64 = callback != null
                ? Base64.getEncoder().encodeToString(callbackJson(callback).getBytes(StandardCharsets.UTF_8))
                : "";
        var date = ossDate();
        // 注意：OkHttp RequestBody.create(MediaType, String) 会自动附加 "; charset=utf-8"，
        // 因此 Content-Type 行必须与实际发出的请求头完全一致（否则 OSS 签名不匹配，403 SignatureDoesNotMatch）
        var contentType = "application/xml; charset=utf-8";
        var authMeta = "POST\n" + md5 + "\n" + contentType + "\n" + date + "\n"
                + "x-oss-callback:" + callbackB64 + "\n"
                + "x-oss-date:" + date + "\n"
                + "x-oss-user-agent:" + OSS_UA + "\n"
                + "/" + bucket + "/" + objKey + "?uploadId=" + uploadId;
        var authBody = new JSONObject();
        authBody.put("auth_info", authInfo);
        authBody.put("auth_meta", authMeta);
        authBody.put("task_id", taskId);
        var authJson = callApi("/file/upload/auth", authBody, null);
        var authKey = authJson.optJSONObject("data") != null
                ? authJson.optJSONObject("data").optString("auth_key", "") : "";
        if (authKey.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "夸克完成授权失败");
        }

        var url = ossUrl(bucket, uploadHost, objKey) + "?uploadId=" + urlEncode(uploadId);
        var builder = new Request.Builder().url(url)
                .header("Authorization", authKey)
                .header("Content-MD5", md5)
                .header("Content-Type", contentType)
                .header("Referer", REFERER)
                .header("x-oss-callback", callbackB64)
                .header("x-oss-date", date)
                .header("x-oss-user-agent", OSS_UA)
                .post(RequestBody.create(MediaType.parse(contentType), body));
        try (var resp = client().newCall(builder.build()).execute()) {
            if (resp.code() != 200 && resp.code() != 203) {
                var errBody = resp.body() != null ? resp.body().string() : "";
                // 完整打印 OSS 错误（含 StringToSign/SignatureProvided 调试字段）便于定位签名差异
                LogHelp.e(TAG, "夸克 OSS 合并失败 HTTP " + resp.code() + " body=" + errBody
                        + " | url=" + url + " | authMeta=" + authMeta);
                throw new CloudException(CloudException.Kind.REMOTE,
                        "夸克 OSS 合并失败 HTTP " + resp.code() + ": " + truncate(errBody, 800));
            }
        }
    }

    // ========== 路径解析 ==========

    private List<Item> collectEntries(String parentId) throws CloudException {
        var items = new ArrayList<Item>();
        var pid = parentId == null ? ROOT_ID : parentId;
        var page = 1;
        for (var guard = 0; guard < 200; guard++) {
            var params = new LinkedHashMap<String, String>();
            params.put("pdir_fid", pid);
            params.put("_page", String.valueOf(page));
            params.put("_size", "100");
            params.put("_sort", "file_name:asc");
            params.put("_fetch_total", "1");
            params.put("fetch_all_file", "1");
            var json = callApiGet("/file/sort", params);
            var data = json.optJSONObject("data");
            var arr = data != null ? data.optJSONArray("list") : null;
            if (arr == null || arr.length() == 0) break;
            for (var i = 0; i < arr.length(); i++) {
                var obj = arr.optJSONObject(i);
                if (obj != null) items.add(Item.fromJson(obj));
            }
            if (arr.length() < 100) break;
            page++;
        }
        return items;
    }

    private List<RemoteEntry> listParent(String parentId) throws CloudException {
        var items = collectEntries(parentId);
        var out = new ArrayList<RemoteEntry>(items.size());
        for (var it : items) {
            out.add(new RemoteEntry(it.name, it.size, it.isDir, it.modifiedTime));
        }
        return out;
    }

    private Item findChild(String parentId, String name) throws CloudException {
        for (var it : collectEntries(parentId)) {
            if (it.name.equals(name)) return it;
        }
        return null;
    }

    private String createFolder(String parentId, String name) throws CloudException {
        try {
            var body = new JSONObject();
            body.put("pdir_fid", parentId == null ? ROOT_ID : parentId);
            body.put("file_name", name);
            body.put("dir_init_lock", false);
            body.put("dir_path", "");
            var resp = callApi("/file", body, null);
            // 优先从创建响应取目录 id（部分版本返回 data.fid）
            var data = resp.optJSONObject("data");
            if (data != null) {
                var directId = data.optString("fid", data.optString("id", ""));
                if (!directId.isEmpty()) return directId;
            }
            // 夸克建目录异步生效：轮询查找（AList MakeDir 后 sleep 1s 同因），最多约 3 秒
            for (var i = 0; i < 10; i++) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                var child = findChild(parentId, name);
                if (child != null) return child.fileId;
            }
            throw new CloudException(CloudException.Kind.REMOTE, "夸克建目录缺少 id: " + name);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 解析路径为目录 id；createMissing=true 自动建目录；失败返回 null */
    private String resolvePath(String path, boolean createMissing) throws CloudException {
        var v = trimSlashes(path);
        if (v.isEmpty()) return ROOT_ID;
        var parts = v.split("/");
        var parentId = ROOT_ID;
        for (var part : parts) {
            var name = cleanName(part);
            if (name.isEmpty()) continue;
            var child = findChild(parentId, name);
            if (child == null) {
                if (!createMissing) return null;
                parentId = createFolder(parentId, name);
                continue;
            }
            if (!child.isDir && !createMissing) return null;
            if (!child.isDir) {
                throw new CloudException(CloudException.Kind.REMOTE, "夸克路径非目录: " + name);
            }
            parentId = child.fileId;
            if (parentId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "夸克缺少目录 id: " + name);
            }
        }
        return parentId;
    }

    private Item findEntry(String parentPath, String targetName) throws CloudException {
        var parentId = resolvePath(parentPath, false);
        if (parentId == null) return null;
        for (var it : collectEntries(parentId)) {
            if (it.name.equals(targetName)) return it;
        }
        return null;
    }

    // ========== HTTP 层 ==========

    private static class HttpResponse {
        int code;
        String body = "";
    }

    private HttpResponse httpGet(String url, Map<String, String> headers, Map<String, String> params) throws CloudException {
        var parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null) {
            throw new CloudException(CloudException.Kind.NETWORK, "invalid url: " + url);
        }
        var ub = parsed.newBuilder();
        // 公共参数 + 业务参数
        ub.addQueryParameter("pr", "ucpro").addQueryParameter("fr", "pc");
        if (params != null) {
            for (var e : params.entrySet()) ub.addQueryParameter(e.getKey(), e.getValue());
        }
        var builder = new Request.Builder().url(ub.build());
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        return execute(builder.build());
    }

    private HttpResponse httpPost(String url, Map<String, String> headers, String body, Map<String, String> extraParams) throws CloudException {
        var parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null) {
            throw new CloudException(CloudException.Kind.NETWORK, "invalid url: " + url);
        }
        var ub = parsed.newBuilder();
        ub.addQueryParameter("pr", "ucpro").addQueryParameter("fr", "pc");
        if (extraParams != null) {
            for (var e : extraParams.entrySet()) ub.addQueryParameter(e.getKey(), e.getValue());
        }
        var builder = new Request.Builder().url(ub.build());
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        builder.post(RequestBody.create(JSON, body == null ? "" : body));
        return execute(builder.build());
    }

    /**
     * 执行请求并处理 Set-Cookie 中的 __puus 回写
     * 夸克会在响应 Set-Cookie 中滚动更新会话凭证（有效约 3 小时），实时合并避免会话提前失效
     */
    private synchronized HttpResponse execute(Request request) throws CloudException {
        var resp = new HttpResponse();
        try (var r = client().newCall(request).execute()) {
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            mergePuus(r.headers("Set-Cookie"));
            return resp;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    /** 从 Set-Cookie 中提取新 __puus 并回写加密存储（线程安全由 execute 的 synchronized 保证） */
    private void mergePuus(List<String> setCookies) {
        if (setCookies == null) return;
        for (var sc : setCookies) {
            var idx = sc.indexOf("__puus=");
            if (idx < 0) continue;
            var end = sc.indexOf(';', idx + 7);
            var value = end < 0 ? sc.substring(idx + 7) : sc.substring(idx + 7, end);
            if (!value.isEmpty()) {
                var ck = cookie();
                var updated = replaceCookiePair(ck, "__puus", value);
                if (!updated.equals(ck)) {
                    EncryptedCredStore.put(account.id, "cookie", updated);
                    LogHelp.d(TAG, "夸克 __puus 已刷新");
                }
            }
        }
    }

    /** 替换 cookie 字符串中的某对 k=v；不存在则追加 */
    private static String replaceCookiePair(String cookieStr, String key, String value) {
        var parts = cookieStr.split(";");
        var out = new ArrayList<String>();
        var found = false;
        for (var p : parts) {
            var t = p.trim();
            if (t.startsWith(key + "=")) {
                if (!found) out.add(key + "=" + value);
                found = true;
            } else if (!t.isEmpty()) {
                out.add(t);
            }
        }
        if (!found) out.add(key + "=" + value);
        return String.join("; ", out);
    }

    /** 剥离 __puus 的 Cookie（用于刷新会话，AList#830） */
    private static String stripPuus(String cookieStr) {
        var parts = cookieStr.split(";");
        var out = new ArrayList<String>();
        for (var p : parts) {
            var t = p.trim();
            if (t.isEmpty() || t.startsWith("__puus=")) continue;
            out.add(t);
        }
        return String.join("; ", out);
    }

    private static OkHttpClient sClient;

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (QuarkProvider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    // ========== 请求头 / 工具 ==========

    private Map<String, String> apiHeaders() {
        return apiHeaders(cookie());
    }

    private Map<String, String> apiHeaders(String ck) {
        var h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Content-Type", "application/json");
        h.put("Origin", REFERER);
        h.put("Referer", REFERER + "/");
        h.put("User-Agent", UA);
        if (ck != null && !ck.isEmpty()) h.put("Cookie", ck);
        return h;
    }

    private String cookie() {
        return EncryptedCredStore.get(account.id, "cookie");
    }

    private String downloadUrl(String fileId) throws CloudException {
        try {
            var ids = new JSONArray();
            ids.put(fileId);
            var body = new JSONObject();
            body.put("fids", ids);
            var json = callApi("/file/download", body, null);
            var arr = json.optJSONArray("data");
            if (arr == null || arr.length() == 0) return "";
            var first = arr.optJSONObject(0);
            return first != null ? first.optString("download_url", "") : "";
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    private static String mimeOf(String name) {
        var mime = java.net.URLConnection.guessContentTypeFromName(name == null ? "" : name);
        return mime == null ? "application/octet-stream" : mime;
    }

    private static String md5Hex(File file) throws Exception {
        try (var in = new FileInputStream(file)) {
            return digestHex(in, "MD5");
        }
    }

    private static String sha1Hex(File file) throws Exception {
        try (var in = new FileInputStream(file)) {
            return digestHex(in, "SHA-1");
        }
    }

    private static String digestHex(InputStream in, String algo) throws Exception {
        var md = MessageDigest.getInstance(algo);
        var buffer = new byte[BUFFER_SIZE];
        var read = 0;
        while ((read = in.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
        var bytes = md.digest();
        var out = new StringBuilder(bytes.length * 2);
        for (var b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static byte[] md5Raw(String text) throws Exception {
        return MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 序列化 OSS callback 为固定字段序 JSON：{"callbackUrl":...,"callbackBody":...}
     * 对齐 AList（Go struct 字段序）——字段顺序决定 base64 值，顺序不一致会导致
     * OSS 校验 SignatureDoesNotMatch（服务端按规范序参与签名）
     */
    private static String callbackJson(JSONObject obj) {
        if (obj == null) return "{}";
        var url = obj.optString("callbackUrl", obj.optString("callbackurl", ""));
        var body = obj.optString("callbackBody", obj.optString("callbackbody", ""));
        return "{\"callbackUrl\":\"" + escapeJson(url) + "\",\"callbackBody\":\"" + escapeJson(body) + "\"}";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 转义非 ASCII 字符为 \\uXXXX（对齐服务端签名依赖的 asciiJson） */
    private static String asciiJson(JSONObject obj) {
        if (obj == null) return "";
        var source = obj.toString();
        var out = new StringBuilder(source.length());
        for (var i = 0; i < source.length(); i++) {
            var c = source.charAt(i);
            if (c > 127) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** OSS 签名日期：EEE, dd MMM yyyy HH:mm:ss 'GMT'（UTC） */
    private static String ossDate() {
        var fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt.format(new Date());
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
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
        return i < 0 ? ROOT_ID : v.substring(0, i);
    }

    private static String pathName(String path) {
        var v = trimSlashes(path);
        var i = v.lastIndexOf('/');
        return i < 0 ? v : v.substring(i + 1);
    }

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐光鸭 Provider） */
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

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 目录条目 */
    private static class Item {
        final String fileId;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;

        Item(String fileId, String name, long size, boolean isDir, long modifiedTime) {
            this.fileId = fileId;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
        }

        static Item fromJson(JSONObject obj) {
            var fileId = obj.optString("fid", "");
            var name = obj.optString("file_name", "");
            var isDir = !obj.optBoolean("file", true);
            var modified = obj.optLong("l_updated_at", obj.optLong("updated_at", 0L));
            if (modified == 0L) modified = System.currentTimeMillis();
            return new Item(fileId, name, obj.optLong("size", 0L), isDir, modified);
        }
    }
}
