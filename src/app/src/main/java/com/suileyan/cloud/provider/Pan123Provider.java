package com.suileyan.cloud.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.EncryptedCredStore;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 123云盘（123pan.com）Provider
 *
 * 实现参考 tmp/123pan（Python/JS 参考仓库）：
 * - 认证：WebView 网页登录捕获 `Authorization: Bearer <token>`（无 OAuth，无 refresh 机制，过期需重登）
 * - 签名：仅对 5 个接口做 URL query 签名，key=CRC32(12字母串) 的十进制值 h，value=`o-a-g`
 *   （a=随机数, o=UTC秒, e=接口路径, n="web", r="3"；g=CRC32("o|a|e|n|r|h")）
 * - 列目录：GET /b/api/file/list/new（code=0 成功；业务 code=403 为 IP 限流，参考实现 sleep 20s）
 * - 上传：upload_request（MD5 秒传）→ 5MB 分块 s3_repare_upload_parts_batch 预签名 PUT → complete → upload_complete
 * - 下载：download_info → DownloadUrl 中提取 params base64 → 解码 GET → data.redirect_url 最终直链
 * - 删除：/a/api/file/trash（进回收站，不签名，fileTrashInfoList 需提交列表原始条目）
 *
 * 坑点：登录响应 code=200 而业务成功 code=0（本项目不调 sign_in）；s3_repare body 用 StorageNode（大写），
 * s3_list/complete 用 storageNode（小写）；同名文件业务 code=5060（本项目按用户策略 duplicate=1 覆盖重试）。
 */
public class Pan123Provider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "123";
    /**
     * API 主机：2026 新版前端（user.123pan.cn 登录 / yun.123pan.cn 主站）实际调用 api.123278.com，
     * 参考仓库的 www.123pan.com 已不再承载 /b/api 接口（返回 SPA 页面 404），必须用新主机。
     */
    private static final String API_BASE = "https://api.123278.com";
    private static final String ROOT_ID = "0";
    private static final String REFERER = "https://www.123pan.com/";
    /** 客户端固定设备指纹（参考仓库硬编码常量） */
    private static final String LOGIN_UUID = "z-uk_yT8HwR4raGX1gqGk";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");
    private static final int BUFFER_SIZE = 64 * 1024;
    /** 分块大小：5MB（参考实现 5242880） */
    private static final int PART_SIZE = 5 * 1024 * 1024;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 200;
    /** 秒传阈值以上收尾需等待服务端合并（参考实现 >64MB sleep 3s） */
    private static final long COMPLETE_WAIT_SIZE = 64L * 1024 * 1024;

    /**
     * 签名 26 字母表（JS/Python 原样）：d 的 12 位数字逐位映射（0→a,1→d,2→e,3→f,4→g,5→h,6→l,7→m,8→y,9→i）
     */
    private static final String[] SIGN_LETTERS = {
            "a", "d", "e", "f", "g", "h", "l", "m", "y", "i", "j", "n",
            "o", "p", "k", "q", "r", "s", "t", "u", "b", "c", "v", "w", "s", "z"
    };

    private final CloudAccount account;

    public Pan123Provider(CloudAccount account) {
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
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "123云盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !auth().isEmpty();
    }

    /** 认证头值（EncryptedCredStore 已存 "Bearer <token>" 完整值） */
    private String auth() {
        return EncryptedCredStore.get(account.id, "authorization");
    }

    // ========== 签名 ==========

    /**
     * 123 云盘签名：返回 [key, value]，作为 URL query 参数（key=首个 CRC32 值 h，value="o-a-g"）
     * 算法与 tmp/123pan/sign_py.py、demo.js 逐字节对齐：
     * - a = 随机 0..9999999
     * - o = UTC 秒（round，参考 JS Math.round(ms/1000)）
     * - d = o 对应 Asia/Shanghai 本地时间 yyyyMMddHHmm（12 位数字）
     * - 字母串 = d 逐位经 SIGN_LETTERS 映射
     * - h = CRC32(字母串) 十进制；g = CRC32("o|a|e|n|r|h") 十进制
     * Java java.util.zip.CRC32 为标准 IEEE CRC-32，getValue() 无符号十进制 == JS ((-1^a)>>>0) == Python (~a)&0xFFFFFFFF
     */
    private static String[] sign(String endpoint) {
        var a = new Random().nextInt(10000000);
        var o = (System.currentTimeMillis() + 500) / 1000L;
        // 参考 Python 用设备本地时区、JS 用浏览器时区；目标设备均为国内 MIUI（Asia/Shanghai）。
        // 服务端签名校验必然按 +8 计算，这里固定 Asia/Shanghai 消除设备时区差异风险。
        var fmt = new SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        var d = fmt.format(new Date(o * 1000L));
        var sb = new StringBuilder(12);
        for (var i = 0; i < d.length(); i++) {
            sb.append(SIGN_LETTERS[d.charAt(i) - '0']);
        }
        var h = crc32(sb.toString());
        var g = crc32(o + "|" + a + "|" + endpoint + "|web|3|" + h);
        return new String[]{h, o + "-" + a + "-" + g};
    }

    private static String crc32(String s) {
        var crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return Long.toString(crc.getValue());
    }

    // ========== 目录与列表 ==========

    @Override
    public boolean testConnection() throws CloudException {
        callGetList("/b/api/file/list/new", listParams(ROOT_ID, 1));
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
            var etag = md5Hex(localFile);

            // 1. upload_request（签名）：第一次 form 编码，duplicate=0（同名时服务端返回 code=5060）
            var form = new LinkedHashMap<String, String>();
            form.put("driveId", "0");
            form.put("etag", etag);
            form.put("fileName", localFile.getName());
            form.put("parentFileId", parentId == null || parentId.isEmpty() ? ROOT_ID : parentId);
            form.put("size", String.valueOf(size));
            form.put("type", "0");
            form.put("duplicate", "0");
            var resp = postSigned("/b/api/file/upload_request", false, null, form);
            var json = parse(resp, "upload_request");
            var code = json.optInt("code", -1);
            if (code == 5060) {
                // 同名文件：按用户策略 duplicate=1 覆盖重试（JSON 编码，数值型字段用数字，对齐参考）
                LogHelp.i(TAG, "123云盘同名文件 code=5060，覆盖重试: " + localFile.getName());
                var dup = new JSONObject();
                dup.put("driveId", 0);
                dup.put("etag", etag);
                dup.put("fileName", localFile.getName());
                dup.put("parentFileId", longOrString(parentId));
                dup.put("size", size);
                dup.put("type", 0);
                dup.put("duplicate", 1);
                resp = postSigned("/b/api/file/upload_request", true, dup, null);
                json = parse(resp, "upload_request");
                code = json.optInt("code", -1);
            }
            requireOk(json, "upload_request");
            var data = json.optJSONObject("data");
            if (data == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘 upload_request 缺 data: " + truncate(json.toString(), 300));
            }
            // 秒传：MD5 命中，服务端直接复用
            if (data.optBoolean("Reuse", false)) {
                LogHelp.i(TAG, "123云盘秒传命中: " + localFile.getName());
                if (cb != null) cb.onProgress(taskId, size, size);
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }
            var bucket = data.optString("Bucket", "");
            var storageNode = data.optString("StorageNode", "");
            var key = data.optString("Key", "");
            var uploadId = data.optString("UploadId", "");
            var upFileId = data.optString("FileId", "");
            if (bucket.isEmpty() || storageNode.isEmpty() || key.isEmpty() || uploadId.isEmpty() || upFileId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘上传参数不完整: " + truncate(json.toString(), 300));
            }

            // 2.（对齐参考）先查询已上传分块，失败不阻塞
            try {
                requireOk(parse(postUnsigned("/b/api/file/s3_list_upload_parts",
                        partBody(bucket, key, uploadId, storageNode, false), null), "s3_list_pre"), "s3_list_pre");
            } catch (Exception e) {
                LogHelp.w(TAG, "123云盘 s3_list 预检失败(忽略): " + e.getMessage());
            }

            // 3. 5MB 分块上传：每块先取预签名 URL 再 PUT（进度按块上报）
            var offset = 0L;
            var partNumber = 1;
            while (offset < size) {
                var len = (int) Math.min(PART_SIZE, size - offset);
                var link = preparePart(bucket, key, uploadId, storageNode, partNumber);
                if (link.isEmpty()) {
                    throw new CloudException(CloudException.Kind.REMOTE, "123云盘分片链接为空 part=" + partNumber);
                }
                putPart(link, localFile, offset, len);
                offset += len;
                partNumber++;
                if (cb != null) cb.onProgress(taskId, offset, size);
            }

            // 4. 收尾：s3_list（小写 storageNode）→ complete → 大文件等待 → upload_complete
            var completeBody = partBody(bucket, key, uploadId, storageNode, false);
            requireOk(parse(postUnsigned("/b/api/file/s3_list_upload_parts", completeBody, null), "s3_list"), "s3_list");
            requireOk(parse(postUnsigned("/b/api/file/s3_complete_multipart_upload", completeBody, null), "s3_complete"), "s3_complete");
            if (size > COMPLETE_WAIT_SIZE) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
            }
            var finishBody = new JSONObject();
            finishBody.put("fileId", upFileId);
            requireOk(parse(postUnsigned("/b/api/file/upload_complete", finishBody, null), "upload_complete"), "upload_complete");
            LogHelp.i(TAG, "123云盘上传完成: " + localFile.getName() + " size=" + size);
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "123云盘上传失败", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 获取单个分片的预签名上传 URL（s3_repare_upload_parts_batch，StorageNode 大写） */
    private String preparePart(String bucket, String key, String uploadId, String storageNode, int partNumber) throws CloudException {
        var body = new JSONObject();
        try {
            body.put("bucket", bucket);
            body.put("key", key);
            body.put("partNumberEnd", partNumber + 1);
            body.put("partNumberStart", partNumber);
            body.put("uploadId", uploadId);
            body.put("StorageNode", storageNode);
        } catch (Exception ignored) {
        }
        var resp = postUnsigned("/b/api/file/s3_repare_upload_parts_batch", body, null);
        var json = requireOk(parse(resp, "s3_repare"), "s3_repare");
        var data = json.optJSONObject("data");
        if (data == null) return "";
        var urls = data.optJSONObject("presignedUrls");
        return urls != null ? urls.optString(String.valueOf(partNumber), "") : "";
    }

    /** 预签名 URL PUT 分片：优先带 Content-Type，403 时去掉 Content-Type 重试一次（对齐参考裸 PUT） */
    private void putPart(String url, File file, long offset, int len) throws Exception {
        if (!putOnce(url, file, offset, len, true)) {
            LogHelp.w(TAG, "123云盘分片 PUT 403（带 Content-Type），去 Content-Type 重试");
            if (!putOnce(url, file, offset, len, false)) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘分片 PUT 失败: " + truncate(url, 120));
            }
        }
    }

    private boolean putOnce(String url, File file, long offset, int len, boolean withType) throws Exception {
        var body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return withType ? OCTET : null;
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
        };
        var request = new Request.Builder().url(url).put(body).build();
        try (var resp = client().newCall(request).execute()) {
            if (resp.code() >= 200 && resp.code() < 300) return true;
            LogHelp.w(TAG, "123云盘分片 PUT HTTP " + resp.code()
                    + (withType ? " (带 Content-Type)" : " (无 Content-Type)") + " len=" + len);
            return false;
        } catch (Exception e) {
            LogHelp.w(TAG, "123云盘分片 PUT 异常", e);
            return false;
        }
    }

    /** s3_list/s3_complete 共用 body；s3_repare 用 StorageNode 大写，s3_list/complete 用 storageNode 小写 */
    private static JSONObject partBody(String bucket, String key, String uploadId, String storageNode, boolean capital) {
        var body = new JSONObject();
        try {
            body.put("bucket", bucket);
            body.put("key", key);
            body.put("uploadId", uploadId);
            body.put(capital ? "StorageNode" : "storageNode", storageNode);
        } catch (Exception ignored) {
        }
        return body;
    }

    // ========== 下载 ==========

    /** 从 DownloadUrl 提取 params base64 的正则（参考 re.findall("params=(.*)&")，用非贪婪 [^&]* 更稳） */
    private static final java.util.regex.Pattern PARAMS_PATTERN = java.util.regex.Pattern.compile("params=([^&]*)");

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            var name = pathName(remote);
            var parent = pathParent(remote);
            var entry = findEntry(parent, name);
            if (entry == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "123文件不存在: " + remotePath);
            }
            // download_info（签名）：换临时下载地址
            var form = new LinkedHashMap<String, String>();
            form.put("driveId", "0");
            form.put("etag", entry.etag);
            form.put("fileId", entry.fileId);
            form.put("s3keyFlag", entry.s3keyFlag);
            form.put("type", entry.isDir ? "1" : "0");
            form.put("fileName", entry.name);
            form.put("size", String.valueOf(entry.size));
            var resp = postSigned("/a/api/file/download_info", false, null, form);
            var json = requireOk(parse(resp, "download_info"), "download_info");
            var data = json.optJSONObject("data");
            var downloadUrl = data != null ? data.optString("DownloadUrl", "") : "";
            if (downloadUrl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘缺少下载地址: " + remotePath);
            }
            var m = PARAMS_PATTERN.matcher(downloadUrl);
            var b64 = m.find() ? m.group(1) : "";
            if (b64.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘下载链接缺 params: " + truncate(downloadUrl, 200));
            }
            var decodedUrl = decodeParams(b64);
            if (decodedUrl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘下载链接 params 解码失败");
            }
            // 中间 URL 返回 JSON，取 redirect_url 为最终直链
            var midJson = getJsonText(decodedUrl);
            var midData = midJson != null ? midJson.optJSONObject("data") : null;
            var redirectUrl = midData != null ? midData.optString("redirect_url", "") : "";
            if (redirectUrl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "123云盘缺少重定向地址: " + truncate(midJson != null ? midJson.toString() : "", 200));
            }
            // 最终直链流式写盘（带 UA/Referer）
            var builder = new Request.Builder().url(redirectUrl)
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .header("Accept", "*/*");
            try (var r = client().newCall(builder.build()).execute()) {
                var code = r.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE, "123云盘下载 HTTP " + code);
                }
                var body = r.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "123云盘下载空响应");
                }
                var parentFile = new File(localPath).getParentFile();
                if (parentFile != null) parentFile.mkdirs();
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
                        throw new CloudException(CloudException.Kind.REMOTE, "123云盘下载不完整: " + written + "/" + total);
                    }
                }
            }
            LogHelp.i(TAG, "123云盘下载完成: " + remotePath);
            return "OK: " + remotePath + " -> " + localPath;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** GET 中间 URL 并解析 JSON（下载换链第二步） */
    private JSONObject getJsonText(String url) throws Exception {
        var builder = new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*");
        try (var r = client().newCall(builder.build()).execute()) {
            if (r.code() < 200 || r.code() >= 300) return null;
            var body = r.body() != null ? r.body().string() : "";
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** 解码 DownloadUrl 的 params base64：参考直接 b64decode；若含 %xx 先 URLDecoder 再解码 */
    private static String decodeParams(String b64) {
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                var decoded = java.net.URLDecoder.decode(b64, "UTF-8");
                return new String(Base64.getDecoder().decode(decoded), StandardCharsets.UTF_8);
            } catch (Exception e2) {
                return "";
            }
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

    /** 删除（移入回收站）：fileTrashInfoList 必须提交列表原始条目对象（对齐参考） */
    private void deletePath(String remotePath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            if (remote.isEmpty()) return;
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null || entry.fileId.isEmpty()) {
                // 目标不存在按成功处理，便于清理旧备份重试（幂等）
                return;
            }
            var body = new JSONObject();
            body.put("driveId", 0);
            body.put("fileTrashInfoList", entry.raw);
            body.put("operation", true);
            var resp = postUnsigned("/a/api/file/trash", body, null);
            requireOk(parse(resp, "trash"), "trash");
            LogHelp.i(TAG, "123云盘已移入回收站: " + remotePath + " (fileId=" + entry.fileId + ")");
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 路径解析 ==========

    /** 构造列目录参数（对齐 2026 新版前端 api.123278.com 实际请求：orderBy=update_time + event 等） */
    private static Map<String, String> listParams(String parentId, int page) {
        var params = new LinkedHashMap<String, String>();
        params.put("driveId", "0");
        params.put("limit", String.valueOf(PAGE_SIZE));
        params.put("next", "0");
        params.put("orderBy", "update_time");
        params.put("orderDirection", "desc");
        params.put("parentFileId", parentId == null || parentId.isEmpty() ? ROOT_ID : parentId);
        params.put("trashed", "false");
        params.put("SearchData", "");
        params.put("Page", String.valueOf(page));
        params.put("OnlyLookAbnormalFile", "0");
        params.put("event", "homeListFile");
        params.put("operateType", "1");
        params.put("inDirectSpace", "false");
        params.put("fileCategory", "0");
        params.put("isSearchOrder", "false");
        return params;
    }

    /** 分页收集目录下所有条目（list/new 分页，guard 上限防死循环） */
    private List<Item> collectEntries(String parentId) throws CloudException {
        var items = new ArrayList<Item>();
        var page = 1;
        for (var guard = 0; guard < MAX_PAGES; guard++) {
            var json = callGetList("/b/api/file/list/new", listParams(parentId, page));
            var data = json.optJSONObject("data");
            if (data == null) break;
            var arr = data.optJSONArray("InfoList");
            if (arr == null || arr.length() == 0) break;
            for (var i = 0; i < arr.length(); i++) {
                var obj = arr.optJSONObject(i);
                if (obj != null) items.add(Item.fromJson(obj));
            }
            var total = data.optLong("Total", 0L);
            if (arr.length() < PAGE_SIZE || (total > 0 && items.size() >= total)) break;
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

    /** 建目录：POST /a/api/file/upload_request（注意 /a/ 前缀，签名），返回新目录 FileId */
    private String createFolder(String parentId, String name) throws CloudException {
        try {
            var body = new JSONObject();
            body.put("driveId", 0);
            body.put("etag", "");
            body.put("fileName", name);
            body.put("parentFileId", longOrString(parentId));
            body.put("size", 0);
            body.put("type", 1);
            body.put("duplicate", 1);
            body.put("NotReuse", true);
            body.put("event", "newCreateFolder");
            body.put("operateType", 1);
            var resp = postSigned("/a/api/file/upload_request", true, body, null);
            var json = requireOk(parse(resp, "createFolder"), "createFolder");
            var data = json.optJSONObject("data");
            var info = data != null ? data.optJSONObject("Info") : null;
            var id = info != null ? info.optString("FileId", "") : "";
            if (id.isEmpty() && data != null) {
                id = data.optString("FileId", data.optString("id", ""));
            }
            if (id.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘建目录缺少 FileId: " + truncate(json.toString(), 200));
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
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘路径非目录: " + name);
            }
            parentId = child.fileId;
            if (parentId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "123云盘缺少目录 id: " + name);
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

    private static OkHttpClient sClient;

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (Pan123Provider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    /** 登录后的业务请求头（App-Version/LoginUuid 为 123pan 前端必带） */
    private Map<String, String> apiHeaders() {
        var h = new LinkedHashMap<String, String>();
        h.put("Accept", "*/*");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("App-Version", "3");
        h.put("Authorization", auth());
        h.put("Cache-Control", "no-cache");
        h.put("Connection", "keep-alive");
        h.put("LoginUuid", LOGIN_UUID);
        h.put("Pragma", "no-cache");
        h.put("Referer", REFERER);
        h.put("User-Agent", UA);
        h.put("platform", "web");
        return h;
    }

    /** 执行请求；HTTP 403（限流/CDN 风控）最多重试 2 次（1s/2s 退避），仍失败原样返回 */
    private HttpResponse execute(Request request) throws CloudException {
        for (var attempt = 0; ; attempt++) {
            try (var r = client().newCall(request).execute()) {
                var resp = new HttpResponse();
                resp.code = r.code();
                resp.body = r.body() != null ? r.body().string() : "";
                if (resp.code == 403 && attempt < 2) {
                    LogHelp.w(TAG, "123云盘 HTTP 403，重试 " + (attempt + 1));
                    sleep(attempt == 0 ? 1000 : 2000);
                    continue;
                }
                return resp;
            } catch (Exception e) {
                throw new CloudException(CloudException.Kind.NETWORK, e);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** HTTP 错误映射 + JSON 解析（不判业务 code；业务 code 由 requireOk/caller 处理） */
    private JSONObject parse(HttpResponse resp, String path) throws CloudException {
        if (resp.code == 401) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "123云盘认证失败 HTTP 401");
        }
        if (resp.code == 403) {
            throw new CloudException(CloudException.Kind.REMOTE, "123云盘 IP 限流或风控 HTTP 403: " + truncate(resp.body, 200));
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "123云盘 API HTTP " + resp.code + " path=" + path + ": " + truncate(resp.body, 200));
        }
        try {
            return new JSONObject(resp.body);
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "123云盘响应非 JSON path=" + path + ": " + truncate(resp.body, 200));
        }
    }

    /**
     * 业务码校验：code==0 通过；401/认证类 message → AUTH_EXPIRED；
     * 403（业务码）→ REMOTE（列表路径在 callGetList 已做限流重试）；其余 → REMOTE。
     * 注意：123 云盘登录响应 code=200、业务成功 code=0，本项目只调业务接口，统一按 code==0 判成功。
     */
    private JSONObject requireOk(JSONObject json, String context) throws CloudException {
        if (json == null) {
            throw new CloudException(CloudException.Kind.REMOTE, "123云盘空响应: " + context);
        }
        var code = json.optInt("code", -1);
        if (code == 0) return json;
        var msg = json.optString("message", "unknown");
        if (code == 401) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "123云盘认证失败: " + msg);
        }
        if (code == 403) {
            throw new CloudException(CloudException.Kind.REMOTE, "123云盘 IP 限流(code=403): " + msg);
        }
        var lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("login") || lower.contains("auth") || lower.contains("token")
                || msg.contains("登录") || msg.contains("过期") || msg.contains("凭证")) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "123云盘登录态失效: " + msg);
        }
        throw new CloudException(CloudException.Kind.REMOTE,
                "123云盘业务错误 code=" + code + " context=" + context + " msg=" + msg);
    }

    /** 签名 GET：业务 code==403（列表限流）最多重试 2 次（对齐参考 get_dir 的 403 处理，但用短退避） */
    private JSONObject callGetList(String path, Map<String, String> params) throws CloudException {
        for (var attempt = 0; ; attempt++) {
            var resp = getSigned(path, params);
            var json = parse(resp, path);
            if (json.optInt("code", -1) == 403 && attempt < 2) {
                LogHelp.w(TAG, "123云盘列表限流 code=403，重试 " + (attempt + 1));
                sleep(attempt == 0 ? 1000 : 2000);
                continue;
            }
            return requireOk(json, path);
        }
    }

    /** 签名 GET（query 携带 h=o-a-g） */
    private HttpResponse getSigned(String path, Map<String, String> params) throws CloudException {
        var sign = sign(path);
        var ub = okhttp3.HttpUrl.parse(API_BASE + path).newBuilder();
        ub.addQueryParameter(sign[0], sign[1]);
        if (params != null) {
            for (var e : params.entrySet()) ub.addQueryParameter(e.getKey(), e.getValue());
        }
        var builder = new Request.Builder().url(ub.build());
        for (var e : apiHeaders().entrySet()) builder.header(e.getKey(), e.getValue());
        return execute(builder.build());
    }

    /** 签名 POST：jsonBody=true 发 JSON，否则发 form */
    private HttpResponse postSigned(String path, boolean jsonBody, JSONObject json, Map<String, String> form) throws CloudException {
        var sign = sign(path);
        var ub = okhttp3.HttpUrl.parse(API_BASE + path).newBuilder();
        ub.addQueryParameter(sign[0], sign[1]);
        var builder = new Request.Builder().url(ub.build());
        for (var e : apiHeaders().entrySet()) builder.header(e.getKey(), e.getValue());
        if (jsonBody && json != null) {
            builder.post(RequestBody.create(JSON, asciiJson(json)));
        } else if (form != null) {
            var fb = new FormBody.Builder();
            for (var e : form.entrySet()) fb.add(e.getKey(), e.getValue());
            builder.post(fb.build());
        }
        return execute(builder.build());
    }

    /** 不签名 POST（s3_*、trash、upload_complete），JSON body */
    private HttpResponse postUnsigned(String path, JSONObject body, Map<String, String> extraForm) throws CloudException {
        var builder = new Request.Builder().url(API_BASE + path);
        for (var e : apiHeaders().entrySet()) builder.header(e.getKey(), e.getValue());
        if (body != null) {
            builder.post(RequestBody.create(JSON, asciiJson(body)));
        } else if (extraForm != null) {
            var fb = new FormBody.Builder();
            for (var e : extraForm.entrySet()) fb.add(e.getKey(), e.getValue());
            builder.post(fb.build());
        }
        return execute(builder.build());
    }

    // ========== 工具 ==========

    /** 全文件 MD5（秒传 etag） */
    private static String md5Hex(File file) throws IOException {
        try (var in = new FileInputStream(file)) {
            var md = MessageDigest.getInstance("MD5");
            var buffer = new byte[BUFFER_SIZE];
            var read = 0;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            var out = new StringBuilder();
            for (var b : md.digest()) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IOException("md5 failed", e);
        }
    }

    /** JSON 数字化：可解析的长整型转数字，否则保留字符串（对齐参考 int/long 语义） */
    private static Object longOrString(String value) {
        if (value == null || value.isEmpty()) return 0L;
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return value;
        }
    }

    /** 非 ASCII 字符转 \\uXXXX（对齐 Python json.dumps ensure_ascii，服务端可能参与一致性校验） */
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

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐其他 Provider） */
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

    /** 123 云盘目录条目（保留 raw 供 trash 原样提交） */
    private static class Item {
        final String fileId;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;
        final String etag;
        final String s3keyFlag;
        final JSONObject raw;

        Item(String fileId, String name, long size, boolean isDir, long modifiedTime,
             String etag, String s3keyFlag, JSONObject raw) {
            this.fileId = fileId;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
            this.etag = etag;
            this.s3keyFlag = s3keyFlag;
            this.raw = raw;
        }

        /** 字段大小写敏感（FileId/FileName/Size/Type/Etag/S3KeyFlag），Type==1 为文件夹 */
        static Item fromJson(JSONObject obj) {
            var fileId = obj.optString("FileId", obj.optString("id", ""));
            var name = obj.optString("FileName", obj.optString("name", ""));
            var isDir = obj.optInt("Type", 0) == 1 || obj.optInt("type", 0) == 1;
            return new Item(fileId, name, obj.optLong("Size", 0L), isDir, System.currentTimeMillis(),
                    obj.optString("Etag", ""), obj.optString("S3KeyFlag", ""), obj);
        }
    }
}
