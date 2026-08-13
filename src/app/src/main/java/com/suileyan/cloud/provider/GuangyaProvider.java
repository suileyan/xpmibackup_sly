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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 光鸭云盘（guangyapan.com）Provider
 *
 * 实现参考 plugins/mibackup_光鸭.txt：
 * - OAuth2：account.guangyapan.com/v1/auth/token 刷新（client_id=aMe-8VSlkrbQXpUR），401 自动刷新重试
 * - 业务 API：api.guangyapan.com，POST + Bearer + x-client-* 头体系
 * - 上传：get_res_center_token 取阿里云 OSS STS 临时凭证 → 分片直传（init?uploads → PUT part → complete）
 * - OSS 签名：HMAC-SHA1(canonical)，canonical 含 METHOD/MD5/ContentType/Date/x-oss-headers/resource，Date 必须有值
 */
public class GuangyaProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "guangya";
    private static final String API_BASE = "https://api.guangyapan.com";
    private static final String ACCOUNT_BASE = "https://account.guangyapan.com";
    private static final String CLIENT_ID = "aMe-8VSlkrbQXpUR";
    private static final String ROOT_ID = "";
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final int BUFFER_SIZE = 64 * 1024;

    private final CloudAccount account;

    public GuangyaProvider(CloudAccount account) {
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
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "光鸭云盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !EncryptedCredStore.get(id(), "access_token").isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        return LoginState.NOT_SUPPORTED;
    }

    // ========== 目录与列表 ==========

    @Override
    public boolean testConnection() throws CloudException {
        return callApi("/userres/v1/file/get_file_list", listBody(ROOT_ID, 0, 1)) != null;
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
        // 诊断：枚举目录 ID（与上传 token parentId 对比——part 文件曾出现"转正成功但目录看不到"）
        LogHelp.i(TAG, "光鸭 listEntries dir=" + remoteDir + " parentId=" + truncate(parentId, 24));
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
            // 0 字节文件（备份完成标记 end）：光鸭服务端不支持空文件上传，直接 mock 成功（对齐百度/沃盘）
            if (size == 0) {
                LogHelp.i(TAG, "光鸭跳过 0 字节文件（服务端不支持空文件）: " + localFile.getName());
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }
            // 诊断：上传链路（"备份文件损坏"排查——记录上传文件名/大小/OSS objectKey，与下载大小/descript 声明对比）
            LogHelp.i(TAG, "光鸭 upload start name=" + localFile.getName()
                    + " size=" + size + " remoteDir=" + remoteDir
                    + " parentId=" + truncate(parentId, 24));

            // 1. 获取上传凭证（可能返回空 creds = 服务端直接处理/秒传）
            var tokenBody = new JSONObject();
            tokenBody.put("capacity", 2);
            tokenBody.put("name", localFile.getName());
            var res = new JSONObject();
            res.put("fileSize", size);
            tokenBody.put("res", res);
            tokenBody.put("parentId", parentId == null ? ROOT_ID : parentId);
            var tokenJson = callApi("/nd.bizuserres.s/v1/get_res_center_token", tokenBody);
            var td = tokenJson.optJSONObject("data");
            if (td == null || td.isNull("taskId")) {
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭上传凭证解析失败: " + truncate(tokenJson.toString(), 300));
            }
            var taskId2 = td.optString("taskId");
            var credsObj = td.optJSONObject("creds");
            if (credsObj == null) {
                // 服务端直接处理（重复文件复制/秒传），确认任务即可
                callApi("/nd.bizuserres.s/v1/file/get_info_by_task_id", new JSONObject().put("taskId", taskId2));
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }

            var akId = credsObj.optString("accessKeyID", "");
            var akSecret = credsObj.optString("secretAccessKey", "");
            var secToken = credsObj.optString("sessionToken", "").trim();
            var bucket = td.optString("bucketName", "");
            var objectKey = td.optString("objectPath", "");
            var fullEndPoint = td.optString("fullEndPoint", "");
            if (akId.isEmpty() || akSecret.isEmpty() || secToken.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭 OSS 临时凭证不完整");
            }

            // 2. OSS 分片直传（单分片，CloudFileHelp 已切片）
            ossUpload(bucket, objectKey, fullEndPoint, akId, akSecret, secToken, localFile, cb, taskId);
            LogHelp.i(TAG, "光鸭 OSS 上传完成 name=" + localFile.getName()
                    + " size=" + size + " object=" + objectKey);

            // 3. 确认任务（对齐参考 file_upload：上传后轮询等待服务端把 upload_tmp 临时对象转正为正式文件。
            //    光鸭大文件转正是异步的（msg="文件上传中"），不等待会导致文件留在临时区、目录里找不到 → 恢复时报"备份文件损坏"）
            for (var i = 0; i < 3; i++) {
                var info = callApi("/nd.bizuserres.s/v1/file/get_info_by_task_id", new JSONObject().put("taskId", taskId2));
                var msg = info.optString("msg", "");
                // 诊断：打印任务确认响应（区分"文件上传中"与转正成功；part 文件曾出现确认成功但目录缺失）
                LogHelp.i(TAG, "光鸭 确认任务 name=" + localFile.getName()
                        + " taskId=" + truncate(taskId2, 20) + " msg=" + truncate(msg, 40)
                        + " resp=" + truncate(info.toString(), 200));
                if (!"文件上传中".equals(msg) && !"处理中".equals(msg)) {
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "光鸭上传失败", e);
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
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭文件不存在: " + remotePath);
            }
            var fileId = entry.fileId;
            if (fileId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭缺少文件 id: " + remotePath);
            }
            var urlJson = callApi("/nd.bizuserres.s/v1/get_res_download_url", new JSONObject().put("fileId", fileId));
            var data = urlJson.optJSONObject("data");
            // 实测响应字段为 signedURL（签名下载地址），downloadUrl/url 为历史兼容
            var dl = data != null ? data.optString("signedURL", data.optString("downloadUrl", data.optString("url", ""))) : "";
            if (dl.isEmpty()) {
                // 打响应结构定位字段（光鸭可能改接口或对部分文件返回 sign 流程）
                LogHelp.w(TAG, "光鸭 get_res_download_url 缺少下载地址: " + remotePath
                        + " resp=" + truncate(urlJson.toString(), 300));
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭缺少下载地址: " + remotePath);
            }
            var request = new Request.Builder().url(dl).build();
            try (var resp = client().newCall(request).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE, "光鸭下载 HTTP " + code);
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "光鸭下载空响应");
                }
                try (var out = new FileOutputStream(localPath); var in = body.byteStream()) {
                    var buffer = new byte[BUFFER_SIZE];
                    var read = 0;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
            // 诊断：下载完整性——本地大小 vs 云端 size（"备份文件损坏"= 下载截断或上传本身损坏，先区分）
            var localLen = new File(localPath).length();
            LogHelp.i(TAG, "光鸭 download done name=" + name
                    + " local=" + localLen + " remote=" + entry.size
                    + (localLen == entry.size ? "" : " **SIZE-MISMATCH**"));
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
            callApi("/nd.bizuserres.s/v1/file/delete_file", new JSONObject().put("fileIds", ids));
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 业务 API 封装 ==========

    /** POST JSON API；401 时刷新 token 重试一次 */
    private JSONObject callApi(String path, JSONObject data) throws CloudException {
        var resp = httpPost(API_BASE + path, apiHeaders(), asciiJson(data), null);
        if (resp.code == 401) {
            LogHelp.i(TAG, "光鸭 401，刷新 token 重试: " + path);
            refreshAccessToken();
            resp = httpPost(API_BASE + path, apiHeaders(), asciiJson(data), null);
        }
        if (resp.code == 401 || resp.code == 403) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "光鸭认证失败 HTTP " + resp.code);
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE, "光鸭 API HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            return new JSONObject(resp.body);
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "光鸭响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** 刷新锁：OAuth2 refresh_token 单次使用，多线程 401 同时刷新会互相挤掉导致账号锁定（HIGH-06） */
    private static final Object REFRESH_LOCK = new Object();

    /**
     * 刷新 access_token（refresh_token 换新）
     * 整个刷新过程加锁：后到的线程会读取其他线程刷新后写入的新 refresh_token，
     * 避免两个线程用同一个 refresh_token 并发刷新导致 OAuth2 轮换冲突
     */
    private String refreshAccessToken() throws CloudException {
        synchronized (REFRESH_LOCK) {
            var rt = EncryptedCredStore.get(id(), "refresh_token");
            if (rt.isEmpty()) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "光鸭无 refresh_token，需重新登录");
            }
            var body = new JSONObject();
            try {
                body.put("client_id", CLIENT_ID);
                body.put("grant_type", "refresh_token");
                body.put("refresh_token", rt);
            } catch (Exception ignored) {
            }
            var resp = httpPost(ACCOUNT_BASE + "/v1/auth/token", accountHeaders(), asciiJson(body), null);
            if (resp.code < 200 || resp.code >= 300) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "光鸭刷新失败 HTTP " + resp.code + ": " + truncate(resp.body, 200));
            }
            try {
                var j = new JSONObject(resp.body);
                var token = j.optString("access_token", "");
                if (token.isEmpty()) {
                    throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "光鸭刷新响应无 access_token");
                }
                EncryptedCredStore.put(id(), "access_token", token);
                if (j.has("refresh_token")) {
                    EncryptedCredStore.put(id(), "refresh_token", j.optString("refresh_token"));
                }
                return token;
            } catch (CloudException e) {
                throw e;
            } catch (Exception e) {
                throw new CloudException(CloudException.Kind.REMOTE, e);
            }
        }
    }

    // ========== OSS 直传 ==========

    /** OSS 分片上传：init?uploads → PUT part → complete（单分片） */
    private void ossUpload(String bucket, String objectKey, String fullEndPoint, String akId, String akSecret,
                           String secToken, File localFile, ProgressCallback cb, String taskId) throws Exception {
        var baseUrl = joinUrl(fullEndPoint, objectKey);

        // 1. 初始化分片上传（无 Content-MD5；Content-Type 必须与签名一致，否则 SignatureDoesNotMatch）
        var initHeaders = ossSign("POST", bucket, objectKey, akId, akSecret, secToken,
                "text/plain; charset=utf-8", "", Map.of("uploads", "")).headers;
        var initResp = httpPost(baseUrl + "?uploads", initHeaders, "", MediaType.parse("text/plain; charset=utf-8"));
        if (initResp.code < 200 || initResp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE, "光鸭 OSS 初始化失败 HTTP " + initResp.code + ": " + truncate(initResp.body, 200));
        }
        var uploadId = parseUploadId(initResp.body);
        if (uploadId.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "光鸭 OSS 缺少 uploadId: " + truncate(initResp.body, 200));
        }

        // 2. 上传分片（流式 + 进度）
        var partNumber = 1;
        var putHeaders = ossSign("PUT", bucket, objectKey, akId, akSecret, secToken,
                "application/octet-stream", "", Map.of("partNumber", String.valueOf(partNumber), "uploadId", uploadId)).headers;
        var putUrl = baseUrl + "?partNumber=" + partNumber + "&uploadId=" + urlEncode(uploadId);
        var putResp = httpPut(putUrl, putHeaders, localFile, cb, taskId);
        if (putResp.code < 200 || putResp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE, "光鸭 OSS 分片上传失败 HTTP " + putResp.code + ": " + truncate(putResp.body, 200));
        }
        var etag = putResp.headers.getOrDefault("ETag", "");
        if (etag.isEmpty()) etag = putResp.headers.getOrDefault("etag", "");
        if (etag.isEmpty()) etag = putResp.headers.getOrDefault("Etag", "");
        etag = etag.replace("\"", "");
        if (etag.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "光鸭 OSS 分片响应缺少 ETag");
        }

        // 3. 完成上传（含 Content-MD5；Content-Type 与签名一致）
        var xml = "<CompleteMultipartUpload><Part><PartNumber>" + partNumber + "</PartNumber><ETag>" + etag + "</ETag></Part></CompleteMultipartUpload>";
        var completeMd5 = Base64.getEncoder().encodeToString(md5Raw(xml));
        var completeHeaders = ossSign("POST", bucket, objectKey, akId, akSecret, secToken,
                "application/xml; charset=utf-8", completeMd5, Map.of("uploadId", uploadId)).headers;
        var completeResp = httpPost(baseUrl + "?uploadId=" + urlEncode(uploadId), completeHeaders, xml, MediaType.parse("application/xml; charset=utf-8"));
        if (completeResp.code < 200 || completeResp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE, "光鸭 OSS 完成上传失败 HTTP " + completeResp.code + ": " + truncate(completeResp.body, 200));
        }
    }

    /** OSS V1 签名（对齐脚本 ossSignHeaders）：canonical = METHOD\nMD5\nType\nDate\nx-oss-headers\nresource */
    private OssSignResult ossSign(String method, String bucket, String objectKey, String akId, String akSecret,
                                  String secToken, String contentType, String contentMd5, Map<String, String> subRes) throws Exception {
        var date = ossDate();
        var key = objectKey == null ? "" : objectKey;
        while (key.startsWith("/")) key = key.substring(1);
        var resource = "/" + bucket + "/" + key;
        if (subRes != null && !subRes.isEmpty()) {
            var parts = new ArrayList<String>();
            for (var k : new TreeSet<>(subRes.keySet())) {
                var v = subRes.get(k);
                parts.add(v == null || v.isEmpty() ? k : k + "=" + v);
            }
            resource += "?" + String.join("&", parts);
        }
        var canonicalHeaders = new ArrayList<String>();
        canonicalHeaders.add("x-oss-date:" + date);
        if (secToken != null && !secToken.isEmpty()) {
            canonicalHeaders.add("x-oss-security-token:" + secToken);
        }
        Collections.sort(canonicalHeaders);
        var canonical = method.toUpperCase() + "\n"
                + (contentMd5 == null ? "" : contentMd5) + "\n"
                + (contentType == null ? "" : contentType) + "\n"
                + date + "\n"
                + String.join("\n", canonicalHeaders) + "\n"
                + resource;
        var sig = Base64.getEncoder().encodeToString(hmacSha1(akSecret, canonical));
        var headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "OSS " + akId + ":" + sig);
        headers.put("x-oss-date", date);
        if (secToken != null && !secToken.isEmpty()) headers.put("x-oss-security-token", secToken);
        if (contentType != null && !contentType.isEmpty()) headers.put("Content-Type", contentType);
        if (contentMd5 != null && !contentMd5.isEmpty()) headers.put("Content-MD5", contentMd5);
        return new OssSignResult(headers);
    }

    // ========== 路径解析（对齐脚本） ==========

    private List<Item> collectEntries(String parentId) throws CloudException {
        var items = new ArrayList<Item>();
        var pid = parentId == null ? ROOT_ID : parentId;
        var page = 0;
        for (var guard = 0; guard < 200; guard++) {
            var json = callApi("/userres/v1/file/get_file_list", listBody(pid, page, 50));
            var data = json.optJSONObject("data");
            // 诊断：打印 data 全部键 + list/fileList 每项 fileName——定位"响应 total=8 含 part 但解析后只有 5 项"的丢失点
            var keys = new StringBuilder();
            if (data != null) {
                var it = data.keys();
                while (it.hasNext()) keys.append(it.next()).append(',');
            }
            var arrDebug = (JSONArray) null;
            if (data != null) {
                if (data.has("fileList")) arrDebug = data.optJSONArray("fileList");
                else if (data.has("list")) arrDebug = data.optJSONArray("list");
            }
            var names = new StringBuilder();
            if (arrDebug != null) {
                for (var i = 0; i < arrDebug.length(); i++) {
                    var o = arrDebug.optJSONObject(i);
                    if (o != null) {
                        if (names.length() > 0) names.append(',');
                        names.append(o.optString("fileName", "?"));
                    }
                }
            }
            LogHelp.i(TAG, "光鸭 get_file_list parent=" + truncate(pid, 24) + " page=" + page
                    + " dataKeys=" + keys + " arrLen=" + (arrDebug == null ? -1 : arrDebug.length())
                    + " names=" + truncate(names.toString(), 400));
            var arr = (JSONArray) null;
            if (data != null) {
                // 关键：光鸭响应 data 可能同时有 fileList（常规文件）与 list（分片/特殊文件）——必须取"两者并集"
                var arr1 = data.optJSONArray("fileList");
                var arr2 = data.optJSONArray("list");
                if (arr1 != null && arr2 != null) {
                    arr = arr1;
                    // 合并 list 中不在 fileList 的项（part 分片在 list 中）
                    for (var i = 0; i < arr2.length(); i++) {
                        var obj = arr2.optJSONObject(i);
                        if (obj == null) continue;
                        var nm = obj.optString("fileName", "");
                        if (nm.isEmpty()) continue;
                        var dup = false;
                        for (var j = 0; j < arr1.length(); j++) {
                            var o1 = arr1.optJSONObject(j);
                            if (o1 != null && nm.equals(o1.optString("fileName", ""))) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) arr.put(obj);
                    }
                } else if (arr1 != null) {
                    arr = arr1;
                } else if (arr2 != null) {
                    arr = arr2;
                } else if (data.length() > 0) {
                    // data 本身可能是数组（防御）
                    try {
                        arr = new JSONArray(data.toString());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (arr == null || arr.length() == 0) break;
            for (var i = 0; i < arr.length(); i++) {
                var obj = arr.optJSONObject(i);
                if (obj != null) items.add(Item.fromJson(obj));
            }
            if (arr.length() < 50) break;
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
            body.put("dirName", name);
            body.put("parentId", parentId == null ? ROOT_ID : parentId);
            var json = callApi("/nd.bizuserres.s/v1/file/create_dir", body);
            var data = json.opt("data");
            var id = "";
            if (data instanceof String) {
                id = (String) data;
            } else if (data instanceof JSONObject) {
                id = entryId((JSONObject) data);
            }
            if (id.isEmpty()) {
                var child = findChild(parentId, name);
                if (child != null) id = child.fileId;
            }
            // 诊断：create_dir 响应与解析出的 ID（目录 ID 链对比——part 转正目标 vs list 枚举目录）
            LogHelp.i(TAG, "光鸭 create_dir name=" + name + " parent=" + truncate(parentId, 24)
                    + " -> id=" + truncate(id, 24) + " resp=" + truncate(json.toString(), 200));
            if (id.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭建目录缺少 id: " + truncate(json.toString(), 200));
            }
            return id;
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
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭路径非目录: " + name);
            }
            parentId = child.fileId;
            if (parentId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "光鸭缺少目录 id: " + name);
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
        Map<String, String> headers = new LinkedHashMap<>();
    }

    private HttpResponse httpPost(String url, Map<String, String> headers, String body, MediaType type) throws CloudException {
        var builder = new Request.Builder().url(url);
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        var mediaType = type != null ? type : JSON;
        builder.post(RequestBody.create(mediaType, body == null ? "" : body));
        return execute(builder.build());
    }

    private HttpResponse httpPut(String url, Map<String, String> headers, File file, ProgressCallback cb, String taskId) throws CloudException {
        var builder = new Request.Builder().url(url);
        for (var e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        builder.put(new RequestBody() {
            @Override
            public MediaType contentType() {
                return OCTET;
            }

            @Override
            public long contentLength() {
                return file.length();
            }

            @Override
            public void writeTo(okio.BufferedSink sink) throws IOException {
                var buffer = new byte[BUFFER_SIZE];
                var total = file.length();
                var written = 0L;
                var lastReport = 0L;
                try (var in = new FileInputStream(file)) {
                    var read = 0;
                    while ((read = in.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                        written += read;
                        var now = System.currentTimeMillis();
                        if (cb != null && (now - lastReport >= 200 || written == total)) {
                            lastReport = now;
                            cb.onProgress(taskId, written, total);
                        }
                    }
                }
            }
        });
        return execute(builder.build());
    }

    private HttpResponse execute(Request request) throws CloudException {
        var resp = new HttpResponse();
        try (var r = client().newCall(request).execute()) {
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            var headers = r.headers();
            for (var i = 0; i < headers.size(); i++) {
                var name = headers.name(i);
                if (!resp.headers.containsKey(name)) {
                    resp.headers.put(name, headers.value(i));
                }
            }
            return resp;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    private static OkHttpClient sClient;

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (GuangyaProvider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    // ========== 请求头 / 工具 ==========

    private Map<String, String> apiHeaders() {
        var did = deviceId();
        var h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Authorization", "Bearer " + EncryptedCredStore.get(id(), "access_token"));
        h.put("Content-Type", "application/json");
        h.put("did", did);
        h.put("dt", "4");
        h.put("traceparent", "00-" + randomHex(32) + "-" + randomHex(16) + "-01");
        h.put("x-client-id", CLIENT_ID);
        h.put("x-client-version", "0.0.1");
        h.put("x-device-id", did);
        h.put("x-device-model", "chrome%2F147.0.0.0");
        h.put("x-device-name", "PC-Chrome");
        h.put("x-device-sign", "wdi10." + did + randomHex(32));
        h.put("x-net-work-type", "NONE");
        h.put("x-os-version", "MacIntel");
        h.put("x-platform-version", "1");
        h.put("x-protocol-version", "301");
        h.put("x-provider-name", "NONE");
        h.put("x-sdk-version", "9.0.2");
        return h;
    }

    private Map<String, String> accountHeaders() {
        var did = deviceId();
        var h = new LinkedHashMap<String, String>();
        h.put("Accept", "*/*");
        h.put("Content-Type", "application/json");
        h.put("x-client-id", CLIENT_ID);
        h.put("x-client-version", "0.0.1");
        h.put("x-device-id", did);
        h.put("x-device-model", "chrome%2F147.0.0.0");
        h.put("x-device-name", "PC-Chrome");
        h.put("x-device-sign", "wdi10." + did + randomHex(32));
        h.put("x-net-work-type", "NONE");
        h.put("x-os-version", "MacIntel");
        h.put("x-platform-version", "1");
        h.put("x-protocol-version", "301");
        h.put("x-provider-name", "NONE");
        h.put("x-sdk-version", "9.0.2");
        return h;
    }

    private String deviceId() {
        var did = EncryptedCredStore.get(id(), "did");
        if (did.isEmpty()) {
            did = randomHex(32);
            EncryptedCredStore.put(id(), "did", did);
        }
        return did;
    }

    private static JSONObject listBody(String parentId, int page, int pageSize) {
        var body = new JSONObject();
        try {
            body.put("parentId", parentId == null ? ROOT_ID : parentId);
            body.put("page", page);
            body.put("pageSize", pageSize);
            body.put("orderBy", 0);
            body.put("sortType", 0);
        } catch (Exception ignored) {
        }
        return body;
    }

    /** 转义非 ASCII 字符为 \\uXXXX（对齐脚本 asciiJson，服务端签名依赖） */
    private static String asciiJson(JSONObject obj) {
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

    private static String randomHex(int len) {
        var hex = "0123456789abcdef";
        var rnd = new Random();
        var out = new StringBuilder(len);
        for (var i = 0; i < len; i++) {
            out.append(hex.charAt(rnd.nextInt(16)));
        }
        return out.toString();
    }

    /** OSS 签名日期：EEE, dd MMM yyyy HH:mm:ss 'GMT'（UTC） */
    private static String ossDate() {
        var fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt.format(new Date());
    }

    private static byte[] hmacSha1(String key, String data) throws Exception {
        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] md5Raw(String text) throws Exception {
        return MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String parseUploadId(String xml) {
        if (xml == null) return "";
        var tag = "<UploadId>";
        var s = xml.indexOf(tag);
        if (s < 0) return "";
        s += tag.length();
        var e = xml.indexOf("</UploadId>", s);
        return e < 0 ? "" : xml.substring(s, e);
    }

    private static String joinUrl(String endpoint, String objectKey) {
        var base = endpoint == null ? "" : endpoint;
        var key = objectKey == null ? "" : objectKey;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        while (key.startsWith("/")) key = key.substring(1);
        return base + "/" + key;
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

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐脚本 cleanFolderName） */
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

    private static String entryId(JSONObject item) {
        if (item == null) return "";
        for (var key : new String[]{"fileId", "id", "dirId", "folderId"}) {
            if (item.has(key) && !item.isNull(key)) {
                return item.optString(key, "");
            }
        }
        return "";
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
            var fileId = entryId(obj);
            var name = obj.optString("fileName", obj.optString("name", ""));
            // 光鸭条目：目录 dirType=1,resType=2；文件 dirType=1,resType=1——dirType 文件/目录相同，
            // **必须用 resType==2 判目录**（旧逻辑 dirType==1 把所有文件误判为目录 → manifest 不转换、
            // 恢复列表不显示彩信.bak → App 无法恢复彩信 → 报"备份文件损坏"）
            var isDir = obj.optInt("resType", 0) == 2
                    || obj.optBoolean("isDir", false)
                    || "folder".equals(obj.optString("type"));
            var modified = 0L;
            var updated = obj.optString("updatedAt", obj.optString("updateAt", ""));
            if (!updated.isEmpty()) {
                try {
                    modified = java.time.Instant.parse(updated).toEpochMilli();
                } catch (Exception e) {
                    try {
                        modified = java.time.LocalDateTime.parse(updated.replace(" ", "T"))
                                .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (modified == 0L) modified = System.currentTimeMillis();
            return new Item(fileId, name, obj.optLong("fileSize", obj.optLong("size", 0L)), isDir, modified);
        }
    }

    /** OSS 签名结果 */
    private static class OssSignResult {
        final Map<String, String> headers;

        OssSignResult(Map<String, String> headers) {
            this.headers = headers;
        }
    }
}
