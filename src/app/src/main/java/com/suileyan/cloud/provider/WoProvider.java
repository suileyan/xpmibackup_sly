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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 联通沃盘（pan.wo.cn）Provider
 *
 * 实现参考 tmp/wopan/wopan-sdk-go-openlist（Go SDK v0.2.2，协议权威）+ woopen 应用层：
 * - 认证：WebView 网页登录捕获 dispatcher 请求的 `Accesstoken` 请求头（UUID 形，Web 端约 7 天有效），
 *   可选 localStorage 兜底扫 refresh_token；无 refresh 时过期需重新登录（与 123/光鸭同构）
 * - 请求：POST https://panservice.mail.wo.cn/{channel}/dispatcher
 *   - 请求头签名 sign = md5(方法名 + resTime + reqSeq + channel + "")（header.go 逐字对齐）
 *   - body.param = AES-128-CBC 加密（api-user 通道 key=clientSecret；wohome 通道 key=accessToken 前 16 字符，
 *     IV 固定 "wNSOYIB1k1DjY5lA"）Base64；其余字段按接口不同：{secret:true}/{clientId,secret:true}/{key:true}
 *   - 成功判据 STATUS=="200" && RSP_CODE=="0000"；RSP_CODE==9999（wohome）有 refresh 时刷新重试，
 *     9999/8005/1001 → AUTH_EXPIRED
 * - 上传：无秒传/预申请，POST {zone}/openapi/client/upload2C 逐分片直传 multipart
 *   （8MiB/片，partIndex 从 1 起，psToken 固定 "undefined"，响应 code=="0000" 且 data.fid 即文件 fid），
 *   上传域由 GetZoneInfo 动态下发（旧 gxupload 域已下线），分片失败重试 2 次
 * - 目录/文件寻址：目录条目用 id（fid 恒为 "0"），文件条目用 fid（下载直链/删除均用它）
 * - 下载：GetDownloadUrlV2 → downloadUrl 直链（fid 参数需 URL 规范化）→ 流式写盘
 * - 删除：DeleteFile，dirList 传目录 id、fileList 传文件 fid（字符串数组）
 */
public class WoProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "wo";

    /** 联通沃盘固定客户端凭据（consts.go，官方 SDK 硬编码） */
    private static final String CLIENT_ID = "1001000021";
    private static final String CLIENT_SECRET = "XFmi9GS2hzk98jGX";
    private static final String APP_ID = "10000001";
    private static final String BASE_URL = "https://panservice.mail.wo.cn";
    /** 上传域兜底（GetZoneInfo 失败时；旧域 gxupload.pan.wo.cn:8443 已下线） */
    private static final String DEFAULT_ZONE_URL = "https://tjupload.pan.wo.cn";
    /** AES-128-CBC 固定 IV（crypto.go） */
    private static final String IV = "wNSOYIB1k1DjY5lA";
    /** 官方 SDK DefaultUA（consts.go） */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.37";
    /** 分片大小：8MiB（consts.go DefaultPartSize） */
    private static final long PART_SIZE = 8L * 1024 * 1024;
    private static final String ROOT_ID = "0";
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 200;
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final String CH_API_USER = "api-user";
    private static final String CH_WOHOME = "wohome";

    private static final String M_APP_QUERY_USER = "AppQueryUser";
    private static final String M_APP_REFRESH_TOKEN = "AppRefreshToken";
    private static final String M_QUERY_ALL_FILES = "QueryAllFiles";
    private static final String M_CREATE_DIRECTORY = "CreateDirectory";
    private static final String M_GET_DOWNLOAD_URL_V2 = "GetDownloadUrlV2";
    private static final String M_DELETE_FILE = "DeleteFile";
    private static final String M_GET_ZONE_INFO = "GetZoneInfo";
    private static final String M_CLASSIFY_RULE = "ClassifyRule";

    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    private final CloudAccount account;

    /** GetZoneInfo 会话缓存（动态上传域，volatile + 单飞锁） */
    private volatile String zoneUrl = "";
    private final Object zoneLock = new Object();
    /** ClassifyRule 缓存：扩展名(小写) → type（"1".."5"） */
    private volatile Map<String, String> fileTypes;
    private final Object fileTypesLock = new Object();
    /** AppQueryUser 缓存的账号标识（可选展示用，不持久化） */
    private volatile String cachedUserId = "";

    private static OkHttpClient sClient;

    public WoProvider(CloudAccount account) {
        this.account = account;
    }

    // ========== 基础接口 ==========

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
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "联通沃盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !accessToken().isEmpty();
    }

    // ========== 凭据 ==========

    /** 沃盘 access_token（UUID 形）；wohome 通道 AES key 取其前 16 字符 */
    private String accessToken() {
        return EncryptedCredStore.get(account.id, "access_token");
    }

    private String refreshToken() {
        return EncryptedCredStore.get(account.id, "refresh_token");
    }

    /** wohome 通道 AES key = accessToken 前 16 字符；不足 16 视为登录态失效（crypto.go SetAccessToken） */
    private String wohomeKey() throws CloudException {
        var at = accessToken();
        if (at == null || at.length() < 16) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "沃盘 access_token 缺失或过短");
        }
        return at.substring(0, 16);
    }

    /** 通道加密 key：api-user 用 clientSecret，其余通道用 accessToken 前 16 字符（crypto.go） */
    private String channelKey(String channel) throws CloudException {
        return CH_API_USER.equals(channel) ? CLIENT_SECRET : wohomeKey();
    }

    // ========== AES 加解密（AES/CBC/PKCS5Padding，16 字节块下等价 Go PKCS7） ==========

    private static String aesEncrypt(String plain, String key16) throws CloudException {
        try {
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key16.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "沃盘 AES 加密失败", e);
        }
    }

    private static String aesDecrypt(String b64, String key16) throws CloudException {
        try {
            var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key16.getBytes(StandardCharsets.UTF_8), "AES"),
                    new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(b64)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "沃盘 AES 解密失败", e);
        }
    }

    // ========== 签名与请求 ==========

    /** 小写 hex（byte 级，防 %02X+char 中文崩溃，ADD-PROVIDER.md §6 #1） */
    private static String md5Hex(String input) throws CloudException {
        try {
            var md = MessageDigest.getInstance("MD5");
            var out = new StringBuilder();
            for (var b : md.digest(input.getBytes(StandardCharsets.UTF_8))) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "沃盘签名 MD5 失败", e);
        }
    }

    /** dispatcher 请求头：sign = md5(key + resTime + reqSeq + channel + "")，reqSeq 100000..109998（header.go） */
    private static JSONObject calHeader(String channel, String method) throws CloudException {
        try {
            var resTime = System.currentTimeMillis();
            var reqSeq = 100000 + new Random().nextInt(8999);
            var version = "";
            var sign = md5Hex(method + resTime + reqSeq + channel + version);
            var h = new JSONObject();
            h.put("key", method);
            h.put("resTime", resTime);
            h.put("reqSeq", reqSeq);
            h.put("channel", channel);
            h.put("sign", sign);
            h.put("version", version);
            return h;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "沃盘签名构造失败", e);
        }
    }

    /** dispatcher 统一入口：POST {BASE}/{channel}/dispatcher（含 9999 刷新重试与 HTTP 退避） */
    private JSONObject dispatcher(String channel, String method, JSONObject param,
                                  JSONObject bodyOther, int retryToken) throws CloudException {
        var header = calHeader(channel, method);
        var body = new JSONObject();
        try {
            body.put("param", aesEncrypt(param != null ? param.toString() : "{}", channelKey(channel)));
            if (bodyOther != null) {
                var keys = bodyOther.keys();
                while (keys.hasNext()) {
                    var k = keys.next();
                    body.put(k, bodyOther.get(k));
                }
            }
            var root = new JSONObject();
            root.put("header", header);
            root.put("body", body);

            var builder = new Request.Builder().url(BASE_URL + "/" + channel + "/dispatcher")
                    .header("Origin", "https://pan.wo.cn")
                    .header("Referer", "https://pan.wo.cn/")
                    .header("User-Agent", UA)
                    .header("Content-Type", "application/json;charset=UTF-8");
            var at = accessToken();
            if (at != null && !at.isEmpty()) {
                builder.header("Accesstoken", at); // 非空才带（request.go:14-16）
            }
            var resp = executeWithRetry(builder.post(RequestBody.create(JSON, asciiJson(root))).build());
            if (resp.code < 200 || resp.code >= 300) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "沃盘 dispatcher HTTP " + resp.code + " channel=" + channel + " method=" + method
                                + ": " + truncate(resp.body, 200));
            }
            try {
                return parseRsp(resp.body, channel);
            } catch (CloudException e) {
                // RSP_CODE==9999 且非 api-user 且未重试过且有 refresh_token → 刷新后重试一次（request.go:42-47）
                var msg = e.getMessage();
                if (e.isAuthExpired() && retryToken == 0 && !CH_API_USER.equals(channel)
                        && msg != null && msg.contains("9999")) {
                    var rt = refreshToken();
                    if (rt != null && !rt.isEmpty() && refresh()) {
                        return dispatcher(channel, method, param, bodyOther, 1);
                    }
                }
                throw e;
            }
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 解析 dispatcher 响应：STATUS=="200" && RSP_CODE=="0000"；DATA 为密文串时先解密（request.go:52-58） */
    private JSONObject parseRsp(String body, String channel) throws CloudException {
        JSONObject json;
        try {
            json = new JSONObject(body);
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "沃盘响应非 JSON: " + truncate(body, 200));
        }
        if (!"200".equals(json.optString("STATUS"))) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "沃盘 STATUS=" + json.optString("STATUS") + " MSG=" + truncate(json.optString("MSG", ""), 200));
        }
        var rsp = json.optJSONObject("RSP");
        if (rsp == null) {
            throw new CloudException(CloudException.Kind.REMOTE, "沃盘响应缺 RSP: " + truncate(body, 200));
        }
        var code = rsp.optString("RSP_CODE", "");
        var desc = rsp.optString("RSP_DESC", "");
        if (!"0000".equals(code) && !"0".equals(code)) {
            // 错误码是字符串（坑 #2）：optString 判断，不用 optInt
            if ("9999".equals(code) || "8005".equals(code) || "1001".equals(code)) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "沃盘登录态失效 RSP_CODE=" + code + " " + desc);
            }
            throw new CloudException(CloudException.Kind.REMOTE, "沃盘 RSP_CODE=" + code + " " + desc);
        }
        var data = rsp.opt("DATA");
        if (data instanceof String) {
            var s = (String) data;
            if (!s.isEmpty()) {
                try {
                    return new JSONObject(aesDecrypt(s, channelKey(channel)));
                } catch (CloudException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CloudException(CloudException.Kind.REMOTE, "沃盘 DATA 解密失败", e);
                }
            }
            return new JSONObject();
        }
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        return new JSONObject(); // 空 DATA（删除/重命名等）
    }

    /** 业务封装：wohome 文件操作 bodyOther={secret:true}（vars.go JsonSecret） */
    private JSONObject wohome(String method, JSONObject param) throws CloudException {
        var other = new JSONObject();
        try {
            other.put("secret", true);
        } catch (Exception ignored) {
        }
        return dispatcher(CH_WOHOME, method, param, other, 0);
    }

    /** 业务封装：api-user 通道 bodyOther={clientId, secret:true}（vars.go JsonClientIDSecret） */
    private JSONObject apiUser(String method, JSONObject param) throws CloudException {
        var other = new JSONObject();
        try {
            other.put("clientId", CLIENT_ID);
            other.put("secret", true);
        } catch (Exception ignored) {
        }
        return dispatcher(CH_API_USER, method, param, other, 0);
    }

    /** 动态上传域（GetZoneInfo，bodyOther={key:true}）；会话缓存 + 单飞，失败回落默认域 */
    private String getZoneInfoCached() throws CloudException {
        if (!zoneUrl.isEmpty()) {
            return zoneUrl;
        }
        synchronized (zoneLock) {
            if (!zoneUrl.isEmpty()) {
                return zoneUrl;
            }
            try {
                var param = new JSONObject();
                param.put("appId", APP_ID);
                var other = new JSONObject();
                other.put("key", true);
                var data = dispatcher(CH_WOHOME, M_GET_ZONE_INFO, param, other, 0);
                var url = data.optString("url", "");
                if (!url.isEmpty()) {
                    zoneUrl = url;
                    LogHelp.i(TAG, "沃盘上传域: " + url);
                }
            } catch (Exception e) {
                LogHelp.w(TAG, "沃盘 GetZoneInfo 失败，回落默认域: " + e.getMessage());
            }
            if (zoneUrl.isEmpty()) {
                zoneUrl = DEFAULT_ZONE_URL;
            }
            return zoneUrl;
        }
    }

    /** 扩展名 → fileType 映射（ClassifyRule，bodyOther={key:true}）；失败保持空映射，fileTypeOf 回落 "5" */
    private void ensureFileTypes() {
        if (fileTypes != null) {
            return;
        }
        synchronized (fileTypesLock) {
            if (fileTypes != null) {
                return;
            }
            var map = new HashMap<String, String>();
            try {
                var param = new JSONObject();
                var other = new JSONObject();
                other.put("key", true);
                var data = dispatcher(CH_WOHOME, M_CLASSIFY_RULE, param, other, 0);
                var ft = data.optJSONObject("fileTypes");
                if (ft != null) {
                    var keys = ft.keys();
                    while (keys.hasNext()) {
                        var ext = keys.next();
                        var v = ft.optJSONObject(ext);
                        if (v != null) {
                            map.put(ext.toLowerCase(Locale.ROOT), v.optString("type", "5"));
                        }
                    }
                }
                LogHelp.i(TAG, "沃盘 ClassifyRule 加载: " + map.size() + " 类型");
            } catch (Exception e) {
                LogHelp.w(TAG, "沃盘 ClassifyRule 失败(忽略): " + e.getMessage());
            }
            fileTypes = map;
        }
    }

    /** 文件扩展名 → type（client.go GetFileType：无扩展名/未命中/加载失败均 "5"） */
    private String fileTypeOf(String fileName) {
        ensureFileTypes();
        var idx = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (idx < 0) {
            return "5";
        }
        var ext = fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
        var t = fileTypes.get(ext);
        return t == null || t.isEmpty() ? "5" : t;
    }

    // ========== 连接与目录 ==========

    @Override
    public boolean testConnection() throws CloudException {
        var at = accessToken();
        if (at == null || at.isEmpty()) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "沃盘缺少 access_token");
        }
        var param = new JSONObject();
        try {
            param.put("accessToken", at);
        } catch (Exception ignored) {
        }
        var data = apiUser(M_APP_QUERY_USER, param);
        var uid = data.optString("userId", data.optString("userName", ""));
        if (!uid.isEmpty()) {
            cachedUserId = uid;
        }
        return true;
    }

    @Override
    public List<String> listDirs() throws CloudException {
        var out = new ArrayList<String>();
        for (var e : queryAll(ROOT_ID)) {
            if (e.isDir) {
                out.add(e.name);
            }
        }
        return out;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        var parentId = resolvePath(remoteDir, false);
        if (parentId == null) {
            return new ArrayList<>();
        }
        var items = queryAll(parentId);
        var out = new ArrayList<RemoteEntry>(items.size());
        for (var it : items) {
            out.add(new RemoteEntry(it.name, it.size, it.isDir, it.modifiedTime));
        }
        return out;
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
            var directoryId = resolvePath(remoteDir, true);
            if (cb != null) {
                cb.onStart(taskId);
            }
            var size = localFile.length();
            var zone = getZoneInfoCached();
            var uploadUrl = zone + "/openapi/client/upload2C";
            // totalPart 整除后至少 1（upload.go:65-68）；末片取余
            var totalPart = Math.max(1L, size / PART_SIZE);
            var batchNo = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
            var fileType = fileTypeOf(localFile.getName());
            // fileInfo 加密（wohome key）：spaceType/directoryId/batchNo/fileName/fileSize/fileType
            var info = new JSONObject();
            info.put("spaceType", "0");
            info.put("directoryId", directoryId);
            info.put("batchNo", batchNo);
            info.put("fileName", localFile.getName());
            info.put("fileSize", size);
            info.put("fileType", fileType);
            var fileInfoB64 = aesEncrypt(info.toString(), wohomeKey());

            String fid = "";
            long finished = 0L;
            for (var partIndex = 1L; partIndex <= totalPart; partIndex++) {
                var partSize = partIndex == totalPart ? (size - finished) : PART_SIZE;
                var ok = false;
                // 分片级失败重试 2 次（upload.go:102-117；每次重发从本片偏移读，等价 Seek 回滚）
                for (var attempt = 0; attempt <= 2 && !ok; attempt++) {
                    try {
                        var resp = postUploadPart(uploadUrl, localFile, directoryId, size,
                                totalPart, partIndex, partSize, fileInfoB64, localFile.getName());
                        var code = resp.optString("code", "");
                        var data = resp.optJSONObject("data");
                        if ("0000".equals(code)) {
                            if (data != null) {
                                var f = data.optString("fid", "");
                                if (!f.isEmpty()) {
                                    fid = f;
                                }
                            }
                            ok = true;
                        } else {
                            LogHelp.w(TAG, "沃盘分片 " + partIndex + " code=" + code
                                    + " msg=" + resp.optString("msg", "") + " len=" + partSize);
                        }
                    } catch (Exception e) {
                        LogHelp.w(TAG, "沃盘分片 " + partIndex + " 异常: " + e.getMessage() + " len=" + partSize);
                    }
                }
                if (!ok) {
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "沃盘分片上传失败 partIndex=" + partIndex + " fileName=" + localFile.getName());
                }
                finished += partSize;
                if (cb != null) {
                    cb.onProgress(taskId, finished, size);
                }
            }
            if (fid.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘上传未返回 fid: " + localFile.getName());
            }
            LogHelp.i(TAG, "沃盘上传完成: " + localFile.getName() + " size=" + size);
            if (cb != null) {
                cb.onFinish(taskId, 0, "success");
            }
        } catch (CloudException e) {
            if (cb != null) {
                cb.onFinish(taskId, -1, e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "沃盘上传失败", e);
            if (cb != null) {
                cb.onFinish(taskId, -1, e.getMessage());
            }
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 单片直传 multipart（upload.go uploadPart）：字段逐字对齐 SDK */
    private JSONObject postUploadPart(String uploadUrl, File file, String directoryId, long size,
                                      long totalPart, long partIndex, long partSize,
                                      String fileInfoB64, String fileName) throws CloudException {
        var at = accessToken();
        var uniqueId = System.currentTimeMillis() + "_" + randomChars(6);
        var builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        builder.addFormDataPart("uniqueId", uniqueId);
        builder.addFormDataPart("accessToken", at == null ? "" : at);
        builder.addFormDataPart("fileName", fileName);
        builder.addFormDataPart("psToken", "undefined");
        builder.addFormDataPart("fileSize", String.valueOf(size));
        builder.addFormDataPart("totalPart", String.valueOf(totalPart));
        builder.addFormDataPart("partSize", String.valueOf(partSize));
        builder.addFormDataPart("partIndex", String.valueOf(partIndex));
        builder.addFormDataPart("channel", "wocloud");
        builder.addFormDataPart("directoryId", directoryId);
        builder.addFormDataPart("fileInfo", fileInfoB64);
        builder.addFormDataPart("file", fileName, partBody(file, (partIndex - 1) * PART_SIZE, partSize));
        var req = new Request.Builder().url(uploadUrl)
                .header("Origin", "https://pan.wo.cn")
                .header("Referer", "https://pan.wo.cn/")
                .header("User-Agent", UA)
                .header("Accept", "application/json;charset=UTF-8")
                .post(builder.build())
                .build();
        var resp = executeOnce(req);
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "沃盘上传 HTTP " + resp.code + " part=" + partIndex);
        }
        try {
            return new JSONObject(resp.body);
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "沃盘上传响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** 流式读文件区间（skip + 限长），避免整块载入内存（对齐 123/Tianyi putPart） */
    private static RequestBody partBody(File file, long offset, long len) {
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return OCTET;
            }

            @Override
            public long contentLength() {
                return len;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                var buffer = new byte[BUFFER_SIZE];
                var remaining = len;
                try (var in = new FileInputStream(file)) {
                    var skipped = 0L;
                    while (skipped < offset) {
                        var s = in.skip(offset - skipped);
                        if (s <= 0) {
                            break;
                        }
                        skipped += s;
                    }
                    while (remaining > 0) {
                        var read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read == -1) {
                            break;
                        }
                        sink.write(buffer, 0, read);
                        remaining -= read;
                    }
                }
            }
        };
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
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘文件不存在: " + remotePath);
            }
            if (entry.isDir) {
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘目标为目录: " + remotePath);
            }
            if (entry.fid.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘缺少文件 fid: " + remotePath);
            }
            // GetDownloadUrlV2 → DATA.list[0].downloadUrl
            var param = new JSONObject();
            param.put("type", "1");
            var fidList = new JSONArray();
            fidList.put(entry.fid);
            param.put("fidList", fidList);
            param.put("clientId", CLIENT_ID);
            var data = wohome(M_GET_DOWNLOAD_URL_V2, param);
            var list = data.optJSONArray("list");
            String url = "";
            if (list != null && list.length() > 0) {
                var first = list.optJSONObject(0);
                if (first != null) {
                    url = first.optString("downloadUrl", "");
                }
            }
            if (url.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘缺少下载直链: " + remotePath);
            }
            url = sanitizeDownloadUrl(url);
            var builder = new Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://pan.wo.cn/")
                    .header("Accept", "*/*");
            try (var r = client().newCall(builder.build()).execute()) {
                var code = r.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE, "沃盘下载 HTTP " + code);
                }
                var body = r.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "沃盘下载空响应");
                }
                var parentFile = new File(localPath).getParentFile();
                if (parentFile != null) {
                    parentFile.mkdirs();
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
                        throw new CloudException(CloudException.Kind.REMOTE, "沃盘下载不完整: " + written + "/" + total);
                    }
                }
            }
            LogHelp.i(TAG, "沃盘下载完成: " + remotePath);
            return "OK: " + remotePath + " -> " + localPath;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /**
     * 下载直链 fid 参数规范化（woopen sanitizeDownloadURL）：
     * fid（base64）可能含 '+'，未编码会被中间层当空格；先 +→%2B 再整体 percent-encode 规范化
     */
    private static String sanitizeDownloadUrl(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            var qIdx = raw.indexOf('?');
            if (qIdx < 0) {
                return raw;
            }
            var query = raw.substring(qIdx + 1);
            if (query.isEmpty()) {
                return raw;
            }
            var parts = query.split("&");
            var changed = false;
            for (var i = 0; i < parts.length; i++) {
                if (!parts[i].startsWith("fid=")) {
                    continue;
                }
                var val = parts[i].substring(4);
                if (val.isEmpty()) {
                    continue;
                }
                var normalized = val.replace("+", "%2B");
                var decoded = normalized;
                try {
                    decoded = java.net.URLDecoder.decode(normalized, "UTF-8");
                } catch (Exception ignored) {
                }
                var encoded = percentEncode(decoded);
                if (!encoded.equals(val)) {
                    parts[i] = "fid=" + encoded;
                    changed = true;
                }
            }
            if (!changed) {
                return raw;
            }
            return raw.substring(0, qIdx) + "?" + String.join("&", parts);
        } catch (Exception e) {
            return raw;
        }
    }

    /** RFC 3986 unreserved 之外的字符百分号编码（对齐 Go url.QueryEscape 语义） */
    private static String percentEncode(String s) {
        var sb = new StringBuilder();
        for (var b : s.getBytes(StandardCharsets.UTF_8)) {
            var c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append(String.format(Locale.ROOT, "%%%02x", b & 0xff));
            }
        }
        return sb.toString();
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

    /** 删除（DeleteFile，dirList 目录 id / fileList 文件 fid 字符串数组）；不存在幂等成功 */
    private void deletePath(String remotePath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            if (remote.isEmpty()) {
                return;
            }
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null) {
                return; // 目标不存在按成功处理，便于清理旧备份重试（幂等）
            }
            var param = new JSONObject();
            param.put("spaceType", "0");
            param.put("vipLevel", "0");
            var dirList = new JSONArray();
            var fileList = new JSONArray();
            if (entry.isDir) {
                dirList.put(entry.id);
            } else {
                fileList.put(entry.fid);
            }
            param.put("dirList", dirList);
            param.put("fileList", fileList);
            param.put("clientId", CLIENT_ID);
            wohome(M_DELETE_FILE, param);
            LogHelp.i(TAG, "沃盘已删除: " + remotePath + (entry.isDir ? " (dir)" : " (file)"));
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 登录态 ==========

    /** 静默刷新：有 refresh_token 时 AppRefreshToken 换新并持久化（AppRefreshToken param 不加密语义同 api-user） */
    @Override
    public boolean refresh() {
        var rt = refreshToken();
        if (rt == null || rt.isEmpty()) {
            return false;
        }
        try {
            var param = new JSONObject();
            param.put("refreshToken", rt);
            param.put("clientSecret", CLIENT_SECRET);
            var data = apiUser(M_APP_REFRESH_TOKEN, param);
            var at = data.optString("access_token", "");
            var newRt = data.optString("refresh_token", "");
            if (at.isEmpty()) {
                return false;
            }
            EncryptedCredStore.put(account.id, "access_token", at);
            if (!newRt.isEmpty()) {
                EncryptedCredStore.put(account.id, "refresh_token", newRt);
            }
            LogHelp.i(TAG, "沃盘 token 已刷新: " + account.id + " accessLen=" + at.length());
            return true;
        } catch (Exception e) {
            LogHelp.w(TAG, "沃盘 token 刷新失败: " + e.getMessage());
            return false;
        }
    }

    // ========== 路径解析与条目 ==========

    /** 分页收集目录下所有条目（pageNum 从 0 起，files 不足 pageSize 停止，guard 上限防死循环） */
    private List<Item> queryAll(String parentId) throws CloudException {
        var out = new ArrayList<Item>();
        var pageNum = 0;
        for (var guard = 0; guard < MAX_PAGES; guard++) {
            var param = new JSONObject();
            try {
                param.put("spaceType", "0");
                param.put("parentDirectoryId", parentId == null || parentId.isEmpty() ? ROOT_ID : parentId);
                param.put("pageNum", pageNum);
                param.put("pageSize", PAGE_SIZE);
                param.put("sortRule", 0);
                param.put("clientId", CLIENT_ID);
            } catch (Exception ignored) {
            }
            var data = wohome(M_QUERY_ALL_FILES, param);
            var files = data.optJSONArray("files");
            if (files == null || files.length() == 0) {
                break;
            }
            for (var i = 0; i < files.length(); i++) {
                var o = files.optJSONObject(i);
                if (o != null) {
                    out.add(Item.fromJson(o));
                }
            }
            if (files.length() < PAGE_SIZE) {
                break;
            }
            pageNum++;
        }
        return out;
    }

    private Item findChild(String parentId, String name) throws CloudException {
        for (var it : queryAll(parentId)) {
            if (it.name.equals(name)) {
                return it;
            }
        }
        return null;
    }

    /** 建目录（CreateDirectory → DATA.id）；不存在则创建 */
    private String createDirectory(String parentId, String name) throws CloudException {
        try {
            var param = new JSONObject();
            param.put("spaceType", "0");
            param.put("familyId", "");
            param.put("parentDirectoryId", parentId == null || parentId.isEmpty() ? ROOT_ID : parentId);
            param.put("directoryName", name);
            param.put("clientId", CLIENT_ID);
            var data = wohome(M_CREATE_DIRECTORY, param);
            var id = data.optString("id", "");
            if (id.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘建目录缺少 id: " + name);
            }
            return id;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 解析路径为目录 id（createMissing=true 自动建目录）；缺失且不建返回 null */
    private String resolvePath(String path, boolean createMissing) throws CloudException {
        var v = trimSlashes(path);
        if (v.isEmpty()) {
            return ROOT_ID;
        }
        var parts = v.split("/");
        var parentId = ROOT_ID;
        for (var part : parts) {
            var name = cleanName(part);
            if (name.isEmpty()) {
                continue;
            }
            var child = findChild(parentId, name);
            if (child == null) {
                if (!createMissing) {
                    return null;
                }
                parentId = createDirectory(parentId, name);
                continue;
            }
            if (!child.isDir) {
                if (!createMissing) {
                    return null;
                }
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘路径非目录: " + name);
            }
            parentId = child.id;
            if (parentId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "沃盘缺少目录 id: " + name);
            }
        }
        return parentId;
    }

    private Item findEntry(String parentPath, String targetName) throws CloudException {
        var parentId = resolvePath(parentPath, false);
        if (parentId == null) {
            return null;
        }
        for (var it : queryAll(parentId)) {
            if (it.name.equals(targetName)) {
                return it;
            }
        }
        return null;
    }

    // ========== HTTP 层 ==========

    private static class HttpResponse {
        int code;
        String body = "";
    }

    private static OkHttpClient client() {
        if (sClient != null) {
            return sClient;
        }
        synchronized (WoProvider.class) {
            if (sClient != null) {
                return sClient;
            }
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    /** 单次请求执行（网络异常 → NETWORK） */
    private HttpResponse executeOnce(Request request) throws CloudException {
        try (var r = client().newCall(request).execute()) {
            var resp = new HttpResponse();
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            return resp;
        } catch (IOException e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    /** HTTP 429/5xx 退避重试（500/1500ms，最多 2 次；对齐 123/Tianyi execute） */
    private HttpResponse executeWithRetry(Request request) throws CloudException {
        for (var attempt = 0; ; attempt++) {
            var resp = executeOnce(request);
            if (resp.code >= 200 && resp.code < 300) {
                return resp;
            }
            if ((resp.code == 429 || resp.code >= 500) && attempt < 2) {
                LogHelp.w(TAG, "沃盘 HTTP " + resp.code + " 重试 " + (attempt + 1));
                sleep(attempt == 0 ? 500 : 1500);
                continue;
            }
            return resp;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ========== 工具 ==========

    /** 随机字母串（大小写混合，对齐 SDK randomChars） */
    private static String randomChars(int length) {
        final String charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        var rnd = new Random();
        var sb = new StringBuilder(length);
        for (var i = 0; i < length; i++) {
            sb.append(charset.charAt(rnd.nextInt(charset.length())));
        }
        return sb.toString();
    }

    /** 非 ASCII 字符转 \\uXXXX（对齐 Python/Go json.Marshal 的 UTF-8 语义差异兜底，防服务端一致性校验） */
    private static String asciiJson(JSONObject obj) {
        if (obj == null) {
            return "";
        }
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

    /** yyyyMMddHHmmss → epoch ms（client_list.go time.Parse("20060102150405")） */
    private static long parseTime(String s) {
        if (s == null || s.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            return new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).parse(s).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static String trimSlashes(String path) {
        var v = path == null ? "" : path.replace('\\', '/');
        while (v.startsWith("/")) {
            v = v.substring(1);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
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

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐其他 Provider） */
    private static String cleanName(String name) {
        if (name == null) {
            return "";
        }
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
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 沃盘文件条目：目录用 id（fid 恒为 "0"）、文件用 fid（下载/删除） */
    private static class Item {
        final String id;
        final String fid;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;

        Item(String id, String fid, String name, long size, boolean isDir, long modifiedTime) {
            this.id = id;
            this.fid = fid;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
        }

        /** Type==0 为目录（client_list.go）；type 缺失时按 fid=="0" 兜底 */
        static Item fromJson(JSONObject o) {
            var fid = o.optString("fid", "");
            var id = o.optString("id", "");
            boolean isDir;
            if (o.has("type")) {
                isDir = o.optInt("type", -1) == 0;
            } else {
                isDir = "0".equals(fid) || "0".equals(o.optString("fileType", ""));
            }
            return new Item(id, fid, o.optString("name", ""), o.optLong("size", 0L), isDir,
                    parseTime(o.optString("createTime", "")));
        }
    }
}
