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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 115 网盘（115.com）Provider —— Cookie 直连官方 API
 *
 * 认证：WebView 登录捕获完整 Cookie 串（存 "cookie" 键，含 UID/CID/SEID/KID），直连 115 API。
 * 参考 tmp/115-apis：
 * - 列表/建目录/删除：gaoyb7/115drive-webdav（api.go，纯 Cookie 无签名）
 * - 上传：p115client（deerwinter）upload_sample_init —— 网页端上传初始化（明文，无秒传）
 *   + 阿里云 OSS 直传（_upload.py oss_upload_sign，HMAC-SHA1 签名）
 * - 下载：115drive-webdav downurl（crypto.go，RSA+XOR 加密请求/响应）
 *
 * - 列表：GET webapi.115.com/files?cid=...&show_dir=1（分页 limit=1000）
 * - 路径→cid：GET webapi.115.com/files/getid?path=...（全路径一次查询，0=根）
 * - 建目录：POST webapi.115.com/files/add（pid+cname）；errno 20004 已存在幂等
 * - 上传：POST uplb.115.com/3.0/sampleinitupload.php（filename+target=U_1_<cid>）→
 *   getuploadinfo 取 endpoint/gettokenurl → gettoken 取 OSS 临时凭证 → PUT OSS（x-oss-callback 注册）
 * - 下载：POST proapi.115.com/app/chrome/downurl（data=RSA加密 {pickcode}）→ 解密取 url → 流式下载
 * - 删除：POST webapi.115.com/rb/delete（fid[0]+pid，目录传 cid；递归子项）
 * - Cookie 无刷新机制：HTTP 401 / check/sso user_id==0 → AUTH_EXPIRED，引导重新 WebView 登录
 */
public class Pan115Provider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "115";

    private static final String API_SSO = "https://passportapi.115.com/app/1.0/web/1.0/check/sso";
    private static final String API_FILES = "https://webapi.115.com/files";
    private static final String API_GET_ID = "https://webapi.115.com/files/getid";
    private static final String API_ADD_DIR = "https://webapi.115.com/files/add";
    private static final String API_DELETE = "https://webapi.115.com/rb/delete";
    private static final String API_DOWN_URL = "https://proapi.115.com/app/chrome/downurl";
    private static final String API_SAMPLE_INIT = "https://uplb.115.com/3.0/sampleinitupload.php";
    private static final String API_UPLOAD_INFO = "https://uplb.115.com/3.0/getuploadinfo.php";

    private static final String UA = "Mozilla/5.0 115Browser/23.9.3.2";
    private static final String OSS_UA = "aliyun-sdk-android/2.9.1";
    private static final String REFERER = "https://115.com/";
    private static final String ROOT_CID = "0";
    /** OSS POST 直传 file 字段 content-type */
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    private static final int PAGE_SIZE = 1000;
    /** HTTP 429/5xx 退避重试上限 */
    private static final int MAX_RETRY = 3;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final CloudAccount account;
    /** 路径→cid 缓存（LRU，减少 getid 调用）
     * static：云盘 Provider 每次上传/下载任务 new 实例，实例级缓存每次失效——
     * 恢复列表遍历每个备份目录都重新 getid（实测 405 风控拦截页），键含 accountId 防跨账号冲突 */
    private static final Map<String, String> cidCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 512;
        }
    };

    private static OkHttpClient sClient;

    public Pan115Provider(CloudAccount account) {
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
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "115网盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !cookie().isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        // 115 凭据来自 WebView 网页登录捕获 Cookie（UID/SEID 等），无应用内登录流程
        return LoginState.NOT_SUPPORTED;
    }

    @Override
    public boolean refresh() {
        // Cookie 无刷新机制，失效只能重新网页登录
        return false;
    }

    // ========== 目录与列表（115 按 cid 树，路径→cid 一次 getid） ==========

    @Override
    public boolean testConnection() throws CloudException {
        var json = executeGet(API_SSO);
        var data = json.optJSONObject("data");
        var userId = data != null ? data.optLong("user_id", -1L) : -1L;
        if (userId <= 0) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "115 未登录或 Cookie 已失效");
        }
        LogHelp.i(TAG, "115 check/sso user_id=" + userId + " (account " + account.id + ")");
        return true;
    }

    @Override
    public List<String> listDirs() throws CloudException {
        var out = new ArrayList<String>();
        for (var e : listByCid(ROOT_CID)) {
            if (e.directory) out.add(e.name);
        }
        return out;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        var cid = resolveCid(remoteDir, false);
        if (cid == null) return new ArrayList<>();
        return listByCid(cid);
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
        resolveCid(remoteDir, true);
    }

    /** 列出 cid 目录下全部条目（分页 limit=1000，offset 翻页直到 count） */
    private List<RemoteEntry> listByCid(String cid) throws CloudException {
        var out = new ArrayList<RemoteEntry>();
        var offset = 0;
        while (true) {
            var params = new LinkedHashMap<String, String>();
            params.put("aid", "1");
            params.put("cid", cid);
            params.put("o", "user_ptime");
            params.put("asc", "0");
            params.put("offset", String.valueOf(offset));
            params.put("show_dir", "1");
            params.put("limit", String.valueOf(PAGE_SIZE));
            params.put("snap", "0");
            params.put("record_open_time", "1");
            params.put("format", "json");
            params.put("fc_mix", "0");
            var json = executeGet(API_FILES, params);
            var arr = json.optJSONArray("data");
            if (arr != null) {
                for (var i = 0; i < arr.length(); i++) {
                    var o = arr.optJSONObject(i);
                    if (o == null) continue;
                    var fid = o.optString("fid", "");
                    var name = o.optString("n", "");
                    if (name.isEmpty()) continue;
                    var isDir = fid.isEmpty();
                    out.add(new RemoteEntry(name, o.optLong("s", 0L), isDir, o.optLong("t", 0L)));
                }
            }
            var count = json.optInt("count", 0);
            offset += PAGE_SIZE;
            if (offset >= count || arr == null || arr.length() == 0) break;
        }
        return out;
    }

    /** 远端路径拆分：/a/b → [父路径, 名字] */
    private static String[] splitPath(String remotePath) {
        var v = trimSlashes(remotePath);
        if (v.isEmpty()) return new String[]{"", ""};
        var idx = v.lastIndexOf('/');
        if (idx < 0) return new String[]{"", v};
        return new String[]{v.substring(0, idx), v.substring(idx + 1)};
    }

    /** 在父目录下列表内按名字定位条目（文件含 fid/pickcode/sha；目录含 cid） */
    private JSONObject findEntry(String remotePath) throws CloudException {
        var parts = splitPath(remotePath);
        if (parts[1].isEmpty()) return null;
        var parentCid = resolveCid(parts[0], false);
        if (parentCid == null) return null;
        var params = new LinkedHashMap<String, String>();
        params.put("aid", "1");
        params.put("cid", parentCid);
        params.put("o", "user_ptime");
        params.put("asc", "0");
        params.put("offset", "0");
        params.put("show_dir", "1");
        params.put("limit", String.valueOf(PAGE_SIZE));
        params.put("snap", "0");
        params.put("record_open_time", "1");
        params.put("format", "json");
        params.put("fc_mix", "0");
        var json = executeGet(API_FILES, params);
        var arr = json.optJSONArray("data");
        if (arr == null) return null;
        for (var i = 0; i < arr.length(); i++) {
            var o = arr.optJSONObject(i);
            if (o != null && parts[1].equals(o.optString("n", ""))) {
                // 诊断：打印匹配条目的全部字段（115 新版列表可能精简字段，pickcode 名须实证）
                var ks = new java.util.ArrayList<String>();
                var kit = o.keys();
                while (kit.hasNext()) ks.add(kit.next());
                LogHelp.i(TAG, "115 findEntry match: " + parts[1]
                        + " keys=" + ks + " resp=" + truncate(o.toString(), 400));
                return o;
            }
        }
        return null;
    }

    /** 路径→cid：一次 getid（全路径）；不存在且 createMissing=true 时逐段创建 */
    private String resolveCid(String path, boolean createMissing) throws CloudException {
        var v = trimSlashes(path);
        if (v.isEmpty()) return ROOT_CID;
        synchronized (cidCache) {
            var hit = cidCache.get(cidKey(v));
            if (hit != null) return hit;
        }
        var cid = getIdByPath(v);
        if (cid != null) {
            synchronized (cidCache) {
                cidCache.put(cidKey(v), cid);
            }
            return cid;
        }
        if (!createMissing) return null;
        // 逐段创建：从根开始找/建每一段
        var parts = v.split("/");
        var cur = ROOT_CID;
        var curPath = "";
        for (var part : parts) {
            var name = cleanName(part);
            if (name.isEmpty()) continue;
            curPath = curPath.isEmpty() ? name : curPath + "/" + name;
            synchronized (cidCache) {
                var hit = cidCache.get(cidKey(curPath));
                if (hit != null) {
                    cur = hit;
                    continue;
                }
            }
            var c = getIdByPath(curPath);
            if (c != null) {
                synchronized (cidCache) {
                    cidCache.put(cidKey(curPath), c);
                }
                cur = c;
                continue;
            }
            cur = createDir(cur, name, curPath);
        }
        return cur;
    }

    /** getid 查询（目录不存在返回 null）；path 为相对路径（去前导 /） */
    private String getIdByPath(String relPath) throws CloudException {
        if (inRiskWindow()) {
            // 风控冷却期：跳过请求（listEntries 会因此枚举失败，但避免持续 405 刷屏/加重风控）
            return null;
        }
        var params = new LinkedHashMap<String, String>();
        params.put("path", relPath);
        var json = executeGet(API_GET_ID, params);
        // 115 getid 成功响应字段为 id（2026-08 PC 实测 {"state":true,"errno":0,"id":"...","is_private":"0"}，
        // 非 category_id——曾误读导致已存在目录永远无法复用）
        var cid = json.optString("id", "0");
        return "0".equals(cid) ? null : cid;
    }

    /** 建目录；errno 20004（已存在）幂等：重查 getid 拿 cid */
    private String createDir(String pid, String name, String fullPath) throws CloudException {
        var form = new LinkedHashMap<String, String>();
        form.put("pid", pid);
        form.put("cname", name);
        var json = executePost(API_ADD_DIR, form);
        var cid = json.optString("cid", "");
        if (cid.isEmpty()) {
            // 已存在（errno 20004 已在 parseApiResponse 特判返回）→ 重查
            var c = getIdByPath(fullPath);
            if (c != null) cid = c;
        }
        if (cid.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "115 建目录失败: " + fullPath);
        }
        synchronized (cidCache) {
            cidCache.put(cidKey(fullPath), cid);
        }
        return cid;
    }

    // ========== 上传（sampleinitupload + OSS 直传，无秒传） ==========

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
            var cid = resolveCid(remoteDir, true);
            if (cid == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "115 无法解析上传目录: " + remoteDir);
            }
            if (cb != null) cb.onStart(taskId);
            var size = localFile.length();
            // 1) sampleinitupload（明文）：返回 OSS POST 直传凭证
            //    {object, accessid, policy, signature, host, expire, callback(base64)}——
            //    callback 是 base64 字符串（{"callbackUrl":"http://uplb.115.com/..."}），
            //    作为 POST 表单字段随文件直传，OSS 上传成功后回调 115 注册文件到目录
            var init = sampleInit(localFile.getName(), cid);
            var object = init.optString("object", "");
            var host = init.optString("host", "");
            var policy = init.optString("policy", "");
            var accessid = init.optString("accessid", "");
            var signature = init.optString("signature", "");
            var callbackB64 = init.optString("callback", "");
            if (object.isEmpty() || host.isEmpty() || policy.isEmpty() || accessid.isEmpty() || signature.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "115 sampleinitupload 缺 POST 直传凭证: " + truncate(init.toString(), 300));
            }
            // 2) OSS POST 表单直传（key/policy/OSSAccessKeyId/signature/callback + file）
            ossPost(host, object, policy, accessid, signature, callbackB64, localFile, cb, taskId, size);
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            LogHelp.e(TAG, "115 上传失败", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** sampleinitupload：明文 POST filename + target=U_1_<cid>，返回服务端下发的上传配置 */
    private JSONObject sampleInit(String filename, String cid) throws CloudException {
        var form = new LinkedHashMap<String, String>();
        form.put("filename", filename);
        form.put("target", "U_1_" + cid);
        var json = executePost(API_SAMPLE_INIT, form);
        // 兼容两种响应：{bucket,object,callback} 或 {status:1,statuscode:0,bucket,object,callback} 包在 data
        if (!json.has("bucket") && json.has("data")) {
            var d = json.optJSONObject("data");
            if (d != null) json = d;
        }
        // 诊断：打印初始化响应的关键字段（status 2=秒传命中无 OSS 上传；1=走 OSS；bucket 可能从 host 解析）
        LogHelp.i(TAG, "115 sampleinitupload status=" + json.optInt("status", -1)
                + " statuscode=" + json.optInt("statuscode", -1)
                + " hasBucket=" + json.has("bucket") + " hasCallback=" + json.has("callback")
                + " hasCallbackVar=" + json.has("callback_var")
                + " hasAccessid=" + json.has("accessid") + " hasPolicy=" + json.has("policy")
                + " hasSignature=" + json.has("signature")
                + " object=" + truncate(json.optString("object", ""), 40)
                + " host=" + truncate(json.optString("host", ""), 60)
                + " resp=" + truncate(json.toString(), 400));
        return json;
    }

    /** OSS POST 表单直传（浏览器模式，实测 sampleinitupload 返回 POST 直传凭证）：
     * POST {host}/ 表单：key=object + policy + OSSAccessKeyId + signature + callback(base64) + file；
     * callback 作为表单字段，OSS 上传成功后回调 115 注册文件到目录（PUT+header 模式不注册，恢复列表 entries=0）
     */
    private void ossPost(String host, String object, String policy, String accessid, String signature,
                         String callbackB64, File file, ProgressCallback cb, String taskId, long size) throws CloudException {
        var fileBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return OCTET;
            }

            @Override
            public long contentLength() {
                return size;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                var buffer = new byte[BUFFER_SIZE];
                var written = 0L;
                try (var in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                        written += read;
                        if (cb != null) cb.onProgress(taskId, written, size);
                    }
                }
            }
        };
        var mb = new MultipartBody.Builder().setType(MultipartBody.FORM);
        mb.addFormDataPart("key", object);
        mb.addFormDataPart("policy", policy);
        mb.addFormDataPart("OSSAccessKeyId", accessid);
        mb.addFormDataPart("signature", signature);
        if (callbackB64 != null && !callbackB64.isEmpty()) {
            mb.addFormDataPart("callback", callbackB64);
        }
        mb.addFormDataPart("file", file.getName(), fileBody);
        var req = new Request.Builder().url(host)
                .header("User-Agent", OSS_UA)
                .post(mb.build())
                .build();
        var resp = execute(req);
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "115 OSS POST 上传 HTTP " + resp.code + ": " + truncate(resp.body, 300));
        }
        if (resp.code == 203) {
            // OSS 203 = 文件已保存但 callback 未注册成功（115 目录无此文件）→ 恢复列表 entries=0
            LogHelp.e(TAG, "115 OSS callback 注册失败(203): " + truncate(resp.body, 400));
            throw new CloudException(CloudException.Kind.REMOTE,
                    "115 OSS callback 注册失败(203): " + truncate(resp.body, 200));
        }
        LogHelp.i(TAG, "115 OSS 上传完成: " + object + " size=" + size + " code=" + resp.code);
    }

    // ========== 下载（downurl RSA+XOR 加密） ==========

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            var entry = findEntry(remotePath);
            if (entry == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "115 文件不存在: " + remotePath);
            }
            if (!entry.has("fid")) {
                throw new CloudException(CloudException.Kind.REMOTE, "115 目标为目录: " + remotePath);
            }
            var pickCode = entry.optString("pickcode", "");
            if (pickCode.isEmpty()) {
                // 115 列表条目 pickcode 字段名实测为 "pc"（2026-08：keys 含 fid/cid/n/s/.../pc）——多字段兼容
                pickCode = entry.optString("pick_code", "");
            }
            if (pickCode.isEmpty()) {
                pickCode = entry.optString("pc", "");
            }
            if (pickCode.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "115 缺少 pickcode: " + remotePath);
            }
            var dlUrl = getDownloadUrl(pickCode);
            if (dlUrl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "115 缺少下载地址: " + remotePath);
            }
            var builder = new Request.Builder().url(dlUrl)
                    .header("Cookie", cookie())
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .header("Accept", "*/*");
            try (var resp = client().newCall(builder.build()).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    // 诊断：下载 403 打印 body（XML/JSON 错误码定位——签名/风控/域变化）+ URL host（不打印完整 URL 防敏感）
                    var body = resp.body() != null ? resp.body().string() : "";
                    LogHelp.e(TAG, "115 下载 HTTP " + code + " host=" + hostOf(dlUrl)
                            + " body=" + truncate(body, 300));
                    throw new CloudException(CloudException.Kind.REMOTE, "115 下载 HTTP " + code);
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "115 下载空响应");
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
                        throw new CloudException(CloudException.Kind.REMOTE,
                                "115 下载不完整: " + written + "/" + total);
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

    /** downurl：请求体 data=RSA 加密的 {"pickcode":...}，响应 data 用同一 key 解密后取 url */
    private String getDownloadUrl(String pickCode) throws CloudException {
        var key = new byte[16];
        new SecureRandom().nextBytes(key);
        var payload = "{\"pickcode\":\"" + pickCode + "\"}";
        var encoded = encode115(payload.getBytes(StandardCharsets.UTF_8), key);
        var form = new FormBody.Builder().add("data", encoded).build();
        var url = HttpUrl.parse(API_DOWN_URL).newBuilder()
                .addQueryParameter("t", String.valueOf(System.currentTimeMillis() / 1000))
                .build();
        var builder = new Request.Builder().url(url)
                .header("Cookie", cookie())
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .post(form);
        var resp = execute(builder.build());
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "115 downurl HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            var json = new JSONObject(resp.body);
            var data = json.optString("data", "");
            if (data.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "115 downurl 缺 data: " + truncate(resp.body, 200));
            }
            var decrypted = decode115(data, key);
            var plain = new String(decrypted, StandardCharsets.UTF_8);
            var u = parseDownloadUrl(plain);
            if (!u.isEmpty()) {
                LogHelp.i(TAG, "115 downurl ok pick=" + truncate(pickCode, 12)
                        + " dataLen=" + data.length() + " urlLen=" + u.length());
                return u;
            }
            return "";
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            // 诊断：打印响应 body（密文 data 长度）与异常，定位 RSA+XOR 解密失败原因
            LogHelp.e(TAG, "115 downurl 解析失败 pick=" + truncate(pickCode, 12)
                    + " resp=" + truncate(resp.body, 300) + " err=" + e);
            // 服务端可能直接返回明文 JSON（加密未启用时），探测兼容
            try {
                var json = new JSONObject(resp.body);
                if (json.has("data")) {
                    var arr = new JSONArray(json.optString("data", "[]"));
                    if (arr.length() > 0) {
                        var first = arr.optJSONObject(0);
                        if (first != null && !first.optString("url", "").isEmpty()) {
                            return first.optString("url");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            throw new CloudException(CloudException.Kind.REMOTE, "115 downurl 解析失败", e);
        }
    }
    // ========== 删除（rb/delete；目录递归子项） ==========

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        deletePath(remoteDir);
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        deletePath(remotePath);
    }

    /** 删除路径：定位条目 → 目录先递归子项 → rb/delete（目录传 cid，文件传 fid，均带 pid） */
    private void deletePath(String remotePath) throws CloudException {
        var parts = splitPath(remotePath);
        if (parts[1].isEmpty()) return;
        try {
            var entry = findEntry(remotePath);
            if (entry == null) return;
            var parentCid = resolveCid(parts[0], false);
            var pid = parentCid != null ? parentCid : ROOT_CID;
            var fid = entry.optString("fid", "");
            if (fid.isEmpty()) {
                // 目录：递归删除子项后删目录自身（115 目录删除是否级联待真机确认，保守递归）
                var cid = entry.optString("cid", "");
                if (!cid.isEmpty()) {
                    for (var child : listByCid(cid)) {
                        deletePath(parts[0].isEmpty() ? child.name : parts[0] + "/" + child.name);
                    }
                    deleteById(cid, pid);
                }
            } else {
                deleteById(fid, pid);
            }
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** rb/delete：fid[0]=id + pid + ignore_warn=1 */
    private void deleteById(String id, String pid) throws CloudException {
        var form = new LinkedHashMap<String, String>();
        form.put("fid[0]", id);
        form.put("pid", pid);
        form.put("ignore_warn", "1");
        executePost(API_DELETE, form);
    }

    // ========== 请求层 ==========

    private static class HttpResponse {
        int code;
        String body = "";
    }

    private String cookie() {
        return EncryptedCredStore.get(account.id, "cookie");
    }

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (Pan115Provider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    private HttpResponse execute(Request request) throws CloudException {
        var resp = new HttpResponse();
        try (var r = client().newCall(request).execute()) {
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            return resp;
        } catch (IOException e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    @FunctionalInterface
    private interface ApiCall {
        HttpResponse run() throws CloudException;
    }

    /** 请求统一出口：HTTP 429/5xx 退避重试；最终 parseApiResponse 映射错误 */
    private JSONObject withRetry(ApiCall call) throws CloudException {
        // 风控冷却期：所有 115 API 请求短路（getid/files/downurl 等）——避免反复请求加重风控、延长风控时长
        if (inRiskWindow()) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "115 风控冷却中（请等待数分钟，或网页端登录 115.com 验证后重试）");
        }
        var last = new HttpResponse();
        for (var attempt = 0; attempt <= MAX_RETRY; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ignored) {
                }
            }
            last = call.run();
            if (last.code == 429 || last.code >= 500) {
                continue;
            }
            return parseApiResponse(last);
        }
        throw new CloudException(CloudException.Kind.REMOTE, "115 API 重试耗尽 HTTP " + last.code);
    }

    /** GET 无参（如 check/sso、getuploadinfo） */
    private JSONObject executeGet(String url) throws CloudException {
        return withRetry(() -> {
            var builder = new Request.Builder().url(url)
                    .header("Cookie", cookie())
                    .header("User-Agent", UA);
            return execute(builder.build());
        });
    }

    /** GET 带 query 参数（列表/getid/gettokenurl） */
    private JSONObject executeGet(String url, Map<String, String> params) throws CloudException {
        var builder = HttpUrl.parse(url).newBuilder();
        for (var e : params.entrySet()) {
            builder.addQueryParameter(e.getKey(), e.getValue());
        }
        var finalUrl = builder.build();
        return withRetry(() -> {
            var req = new Request.Builder().url(finalUrl)
                    .header("Cookie", cookie())
                    .header("User-Agent", UA);
            return execute(req.build());
        });
    }

    /** POST form（add/delete/sampleinitupload） */
    private JSONObject executePost(String url, Map<String, String> form) throws CloudException {
        var fb = new FormBody.Builder();
        for (var e : form.entrySet()) {
            fb.add(e.getKey(), e.getValue());
        }
        var body = fb.build();
        return withRetry(() -> {
            var req = new Request.Builder().url(url)
                    .header("Cookie", cookie())
                    .header("User-Agent", UA)
                    .post(body);
            return execute(req.build());
        });
    }

    /**
     * 错误映射（单出口）：
     * - HTTP 401/403 → AUTH_EXPIRED
     * - errno 20004 → 返回 JSON（建目录已存在幂等）
     * - errno 40100 → AUTH_EXPIRED（未登录）
     * - 其余 errno≠0 / state=false → REMOTE（错误码用 opt()+String 判断，防字符串错误码）
     */
    private JSONObject parseApiResponse(HttpResponse resp) throws CloudException {
        if (resp.code == 401 || resp.code == 403) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "115 未登录或 Cookie 已失效 (HTTP " + resp.code + ")");
        }
        if (resp.code < 200 || resp.code >= 300) {
            // 405 + HTML 拦截页（<!doctypehtml> / <title>405</title>）= 115 WAF 风控拦截（实测 13:47 起高频请求触发），
            // 非接口错误：需等待冷却或网页端验证解除，明确提示避免误判为 bug
            if (resp.code == 405 && resp.body.contains("<!doctype")) {
                markRiskWindow();
                throw new CloudException(CloudException.Kind.REMOTE,
                        "115 请求触发风控拦截(HTTP 405)，请等待数分钟或网页端登录 115.com 验证后重试");
            }
            throw new CloudException(CloudException.Kind.REMOTE,
                    "115 API HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            var json = new JSONObject(resp.body);
            // 登录检查接口：data.user_id == 0 表示未登录（testConnection 显式判定，此处兜底）
            if (json.has("data")) {
                var d = json.optJSONObject("data");
                if (d != null && d.has("user_id") && d.optLong("user_id", -1L) == 0L) {
                    throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "115 未登录或 Cookie 已失效");
                }
            }
            var errnoVal = json.opt("errno");
            var errnoStr = errnoVal == null || JSONObject.NULL.equals(errnoVal)
                    ? "" : String.valueOf(errnoVal).trim();
            // 115 成功响应 errno 常为空字符串 ""（约定 state:true 即成功）；
            // 仅当 errno 非空且非 "0"，或 state 明确 false 时才判失败
            var stateObj = json.opt("state");
            var stateFalse = Boolean.FALSE.equals(stateObj)
                    || "false".equalsIgnoreCase(String.valueOf(stateObj));
            if (!errnoStr.isEmpty() && !"0".equals(errnoStr)) {
                if ("40100".equals(errnoStr)) {
                    throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "115 未登录或 Cookie 已失效 (errno=" + errnoStr + ")");
                }
                if ("20004".equals(errnoStr)) {
                    // 建目录已存在：幂等返回，调用方重查 cid
                    return json;
                }
                if (!stateFalse) {
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "115 API 错误 errno=" + errnoStr + ": " + truncate(resp.body, 200));
                }
            }
            if (stateFalse) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "115 API 错误 state=false: " + truncate(resp.body, 200));
            }
            return json;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "115 响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    // ========== 115 下载加密（移植 115drive-webdav/115/crypto.go，逐字节对齐） ==========

    /** RSA 公钥（1024-bit PKIX） */
    private static final String RSA_PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n"
            + "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCGhpgMD1okxLnUMCDNLCJwP/P0\n"
            + "UHVlKQWLHPiPCbhgITZHcZim4mgxSWWb0SLDNZL9ta1HlErR6k02xrFyqtYzjDu2\n"
            + "rGInUC0BCZOsln0a7wDwyOA43i5NO8LsNory6fEKbx7aT3Ji8TZCDAfDMbhxvxOf\n"
            + "dPMBDjxP5X3zr7cWgwIDAQAB\n"
            + "-----END PUBLIC KEY-----";

    /** xorKeySeed（crypto.go L15-34，160 字节） */
    private static final byte[] XOR_SEED = {
            (byte) 0xf0, (byte) 0xe5, (byte) 0x69, (byte) 0xae, (byte) 0xbf, (byte) 0xdc, (byte) 0xbf, (byte) 0x8a,
            (byte) 0x1a, (byte) 0x45, (byte) 0xe8, (byte) 0xbe, (byte) 0x7d, (byte) 0xa6, (byte) 0x73, (byte) 0xb8,
            (byte) 0xde, (byte) 0x8f, (byte) 0xe7, (byte) 0xc4, (byte) 0x45, (byte) 0xda, (byte) 0x86, (byte) 0xc4,
            (byte) 0x9b, (byte) 0x64, (byte) 0x8b, (byte) 0x14, (byte) 0x6a, (byte) 0xb4, (byte) 0xf1, (byte) 0xaa,
            (byte) 0x38, (byte) 0x01, (byte) 0x35, (byte) 0x9e, (byte) 0x26, (byte) 0x69, (byte) 0x2c, (byte) 0x86,
            (byte) 0x00, (byte) 0x6b, (byte) 0x4f, (byte) 0xa5, (byte) 0x36, (byte) 0x34, (byte) 0x62, (byte) 0xa6,
            (byte) 0x2a, (byte) 0x96, (byte) 0x68, (byte) 0x18, (byte) 0xf2, (byte) 0x4a, (byte) 0xfd, (byte) 0xbd,
            (byte) 0x6b, (byte) 0x97, (byte) 0x8f, (byte) 0x4d, (byte) 0x8f, (byte) 0x89, (byte) 0x13, (byte) 0xb7,
            (byte) 0x6c, (byte) 0x8e, (byte) 0x93, (byte) 0xed, (byte) 0x0e, (byte) 0x0d, (byte) 0x48, (byte) 0x3e,
            (byte) 0xd7, (byte) 0x2f, (byte) 0x88, (byte) 0xd8, (byte) 0xfe, (byte) 0xfe, (byte) 0x7e, (byte) 0x86,
            (byte) 0x50, (byte) 0x95, (byte) 0x4f, (byte) 0xd1, (byte) 0xeb, (byte) 0x83, (byte) 0x26, (byte) 0x34,
            (byte) 0xdb, (byte) 0x66, (byte) 0x7b, (byte) 0x9c, (byte) 0x7e, (byte) 0x9d, (byte) 0x7a, (byte) 0x81,
            (byte) 0x32, (byte) 0xea, (byte) 0xb6, (byte) 0x33, (byte) 0xde, (byte) 0x3a, (byte) 0xa9, (byte) 0x59,
            (byte) 0x34, (byte) 0x66, (byte) 0x3b, (byte) 0xaa, (byte) 0xba, (byte) 0x81, (byte) 0x60, (byte) 0x48,
            (byte) 0xb9, (byte) 0xd5, (byte) 0x81, (byte) 0x9c, (byte) 0xf8, (byte) 0x6c, (byte) 0x84, (byte) 0x77,
            (byte) 0xff, (byte) 0x54, (byte) 0x78, (byte) 0x26, (byte) 0x5f, (byte) 0xbe, (byte) 0xe8, (byte) 0x1e,
            (byte) 0x36, (byte) 0x9f, (byte) 0x34, (byte) 0x80, (byte) 0x5c, (byte) 0x45, (byte) 0x2c, (byte) 0x9b,
            (byte) 0x76, (byte) 0xd5, (byte) 0x1b, (byte) 0x8f, (byte) 0xcc, (byte) 0xc3, (byte) 0xb8, (byte) 0xf5
    };

    /** xorClientKey（crypto.go L36-39，12 字节） */
    private static final byte[] XOR_CLIENT_KEY = {
            (byte) 0x78, (byte) 0x06, (byte) 0xad, (byte) 0x4c, (byte) 0x33, (byte) 0x86,
            (byte) 0x5d, (byte) 0x18, (byte) 0x4c, (byte) 0x01, (byte) 0x3f, (byte) 0x46
    };

    private static java.security.PublicKey sRsaPublicKey;

    private static java.security.PublicKey rsaPublicKey() throws Exception {
        if (sRsaPublicKey != null) return sRsaPublicKey;
        var pem = RSA_PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        var der = Base64.getDecoder().decode(pem);
        sRsaPublicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        return sRsaPublicKey;
    }

    /** Encode（crypto.go L65-74）：16 字节随机 key + XOR(keyS) + reverse + XOR(clientKey) + RSA-PKCS1v15 + base64 */
    static String encode115(byte[] input, byte[] key) {
        var buf = new byte[16 + input.length];
        System.arraycopy(key, 0, buf, 0, 16);
        System.arraycopy(input, 0, buf, 16, input.length);
        xorTransform(buf, 16, buf.length - 16, xorDeriveKey(key, 4));
        reverseBytes(buf, 16, buf.length);
        xorTransform(buf, 16, buf.length - 16, XOR_CLIENT_KEY);
        var enc = rsaEncrypt(buf);
        return Base64.getEncoder().encodeToString(enc);
    }

    /** Decode（crypto.go L76-88）：RSA 伪解密（公钥 E 模幂）→ XOR(keyL) → reverse → XOR(keyS) */
    static byte[] decode115(String input, byte[] key) throws Exception {
        var data = Base64.getDecoder().decode(input);
        var plain = rsaDecode(data);
        if (plain.length <= 16) {
            throw new CloudException(CloudException.Kind.REMOTE, "115 downurl 解密结果过短");
        }
        var randKey = Arrays.copyOfRange(plain, 0, 16);
        var out = Arrays.copyOfRange(plain, 16, plain.length);
        xorTransform(out, 0, out.length, xorDeriveKey(randKey, 12));
        reverseBytes(out, 0, out.length);
        xorTransform(out, 0, out.length, xorDeriveKey(key, 4));
        return out;
    }

    /** xorDeriveKey（crypto.go L90-97） */
    private static byte[] xorDeriveKey(byte[] seed, int size) {
        var key = new byte[size];
        for (var i = 0; i < size; i++) {
            key[i] = (byte) ((seed[i] + XOR_SEED[size * i]) & 0xff);
            key[i] ^= XOR_SEED[size * (size - i - 1)];
        }
        return key;
    }

    /** xorTransform（crypto.go L99-110）：先 mod 前字节用 key[i%k]，之后用 key[(i-mod)%k] */
    private static void xorTransform(byte[] data, int offset, int len, byte[] key) {
        var mod = len % 4;
        var kLen = key.length;
        for (var i = 0; i < mod; i++) {
            data[offset + i] ^= key[i % kLen];
        }
        for (var i = mod; i < len; i++) {
            data[offset + i] ^= key[(i - mod) % kLen];
        }
    }

    private static void reverseBytes(byte[] data, int from, int to) {
        for (int i = from, j = to - 1; i < j; i++, j--) {
            var t = data[i];
            data[i] = data[j];
            data[j] = t;
        }
    }

    /** RSA-PKCS1v15 逐块加密（块 117 → 128） */
    private static byte[] rsaEncrypt(byte[] input) {
        try {
            var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey());
            var block = 117;
            var out = new ByteArrayOutputStream();
            for (var offset = 0; offset < input.length; offset += block) {
                var size = Math.min(block, input.length - offset);
                out.write(cipher.doFinal(input, offset, size));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("115 RSA 加密失败", e);
        }
    }

    /** RSA 伪解密（crypto.go L127-146）：逐 128 块 c^E mod N，取第一个 0x00 后段（公钥即可，服务端私钥加密）。
     * block 必须用 (bitLength+7)/8 而非 n.toByteArray().length：1024-bit 模数最高位为 1 时
     * toByteArray() 返回 129 字节（Java 补符号位），导致多块密文错位 → 模幂结果无 0x00 → 解密失败
     * （实测：descript.xml 下载地址 >117 字节明文 = 2 块，第二块从 129 偏移错位） */
    private static byte[] rsaDecode(byte[] input) throws Exception {
        var publicKey = (java.security.interfaces.RSAPublicKey) rsaPublicKey();
        var e = publicKey.getPublicExponent();
        var n = publicKey.getModulus();
        var block = (n.bitLength() + 7) / 8;
        var out = new ByteArrayOutputStream();
        for (var offset = 0; offset < input.length; offset += block) {
            var size = Math.min(block, input.length - offset);
            var m = new BigInteger(1, Arrays.copyOfRange(input, offset, offset + size)).modPow(e, n);
            var b = m.toByteArray();
            if (b.length > 0 && b[0] == 0) {
                b = Arrays.copyOfRange(b, 1, b.length); // 去符号位，对齐 Go Bytes()
            }
            var idx = indexOfZero(b);
            if (idx < 0) {
                throw new IllegalStateException("115 RSA 伪解密失败（未找到 0x00）");
            }
            out.write(b, idx + 1, b.length - idx - 1);
        }
        return out.toByteArray();
    }

    private static int indexOfZero(byte[] b) {
        for (var i = 0; i < b.length; i++) {
            if (b[i] == 0) return i;
        }
        return -1;
    }

    /**
     * 解析 downurl 解密后的下载地址 JSON（对齐 115drive-webdav types.go）：
     * 顶层为 map&lt;fid, DownloadInfo&gt;（实测 {"<fid>":{"file_name":...,"url":{"url":"https://...","client":...}}}），
     * 真正地址在 url.url。兼容旧格式（顶层数组 / url 为字符串）。
     */
    private static String parseDownloadUrl(String plain) {
        try {
            // 1) 顶层对象：fid → info → url{url}
            var obj = new JSONObject(plain);
            var keys = obj.keys();
            if (keys.hasNext()) {
                var info = obj.optJSONObject(keys.next());
                if (info != null) {
                    var u = info.optString("url", "");
                    if (!u.isEmpty() && u.startsWith("http")) return u;
                    var urlObj = info.optJSONObject("url");
                    if (urlObj != null) {
                        u = urlObj.optString("url", "");
                        if (u.startsWith("http")) return u;
                    }
                }
            }
        } catch (Exception ignored) {
            // 非对象，继续尝试数组/其他
        }
        try {
            // 2) 顶层数组（旧格式）
            var arr = new JSONArray(plain);
            if (arr.length() > 0) {
                var first = arr.optJSONObject(0);
                if (first != null) {
                    var u = first.optString("url", "");
                    if (u.startsWith("http")) return u;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    // ========== OSS 签名辅助 ==========

    /** base64(HMAC-SHA1(secret, data)) */
    private static String hmacSha1Base64(String secret, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("OSS HMAC-SHA1 签名失败", e);
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 剥离 http(s):// 前缀与尾部 /（OSS endpoint 可能带协议前缀，拼接域名前必须去除） */
    private static String stripProtocol(String s) {
        if (s == null) return "";
        var v = s.trim();
        if (v.startsWith("http://")) {
            v = v.substring(7);
        } else if (v.startsWith("https://")) {
            v = v.substring(8);
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    /** RFC1123 GMT（如 "Mon, 10 Aug 2026 02:00:00 GMT"），OSS 签名与请求头共用 */
    private static String httpDate() {
        var fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt.format(new Date());
    }

    // ========== 工具 ==========

    /** 缓存键：accountId + path（static 缓存跨实例共享，防止多账号 cid 冲突） */
    private String cidKey(String path) {
        return account.id + "\0" + path;
    }

    /** 风控冷却：115 WAF 拦截（405 HTML 页）后 5 分钟内不再发起任何 API 请求（实测风控持续数分钟，60s 冷却不够） */
    private static volatile long sRiskWindowUntil = 0L;

    private static void markRiskWindow() {
        sRiskWindowUntil = System.currentTimeMillis() + 300_000L;
    }

    private static boolean inRiskWindow() {
        return System.currentTimeMillis() < sRiskWindowUntil;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 提取 URL host（仅用于诊断，不打印完整 URL 防敏感） */
    private static String hostOf(String url) {
        try {
            var u = new java.net.URL(url);
            return u.getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    /** 去首尾 /，保留内部结构（"" 表示根） */
    private static String trimSlashes(String path) {
        if (path == null) return "";
        var v = path.trim();
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String cleanName(String name) {
        return name == null ? "" : name.trim();
    }
}
