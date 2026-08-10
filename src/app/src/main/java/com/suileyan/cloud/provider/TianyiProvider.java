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
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 天翼云盘（cloud.189.cn）Provider
 *
 * API 参考 tmp/cloud189-sdk（Node.js TS SDK v1.0.9）：
 * - 认证：WebView 登录捕获 SSON Cookie（存 "sson_cookie"），Provider 内部换会话；
 *   TokenSession{accessToken, refreshToken, sessionKey} 持久化，refresh_token 续期，InvalidAccessToken/SessionKey 自愈重试
 * - 三类鉴权：api.cloud.189.cn 用 accessToken MD5 签名头（Accesstoken 小写 t）；
 *   cloud.189.cn 的 /open 用 AppKey 签名头；upload.cloud.189.cn 用 signatureUpload（AES+RSA+HMAC）
 * - 列表：GET /open/file/listFiles.action（folderId 分页，根 folderId=""，个人根 id=-11）
 * - 上传：initMultiUpload(秒传) → checkTransSecond → getMultiUploadUrls(分片签名URL) → PUT 分片 → commitMultiUploadFile
 * - 下载：GET /open/file/getFileDownloadUrl.action 换直链
 * - 删除：createBatchTask(DELETE) → checkBatchTask 轮询
 */
public class TianyiProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "189";

    private static final String WEB_URL = "https://cloud.189.cn";
    private static final String AUTH_URL = "https://open.e.189.cn";
    private static final String API_URL = "https://api.cloud.189.cn";
    private static final String UPLOAD_URL = "https://upload.cloud.189.cn";

    /** unifyLoginForPC / getSessionForPC / refreshToken 的 clientId */
    private static final String APP_ID = "8025431004";
    private static final String ACCOUNT_TYPE = "02";
    /** unifyLoginForPC */
    private static final String CLIENT_TYPE = "10020";
    private static final String RETURN_URL = "https://m.cloud.189.cn/zhuanti/2020/loginErrorPc/index.html";
    /** getSessionForPC */
    private static final String PC_CLIENT_TYPE = "TELEPC";
    private static final String PC_VERSION = "6.2";
    private static final String CHANNEL_ID = "web_cloud.189.cn";
    /** /open 签名 AppKey（SDK 硬编码） */
    private static final String APP_KEY = "600100422";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/87.0.4280.88 Safari/537.36";

    /** listFiles 根 folderId */
    private static final String ROOT_ID = "";
    /** 个人根 id（createFolder 于根时的 parentFolderId 兜底值，SDK 示例使用） */
    private static final String PERSONAL_ROOT_ID = "-11";
    private static final int PAGE_SIZE = 60;
    private static final int MAX_PAGES = 200;
    /** 分片并发数（对齐 SDK asyncPool(5)） */
    private static final int MAX_PART = 5;
    /** 分片大小基数 10MiB */
    private static final long DEF_SLICE = 10L * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    /** TokenSession 无 expiresIn 字段，SDK 兜底 6 天 */
    private static final long ACCESS_TOKEN_TTL_MS = 6L * 24 * 3600 * 1000;
    /** RSA 公钥默认 TTL（响应 XML 无 expire 字段时兜底，1 小时） */
    private static final long RSA_KEY_TTL_MS = 3600L * 1000;
    /** InvalidAccessToken/InvalidSessionKey 自愈重试上限 */
    private static final int MAX_SESSION_RETRY = 3;

    private final CloudAccount account;
    /** ensureSession 单飞锁：防 refreshToken 一次性轮换并发（对齐 Guangya REFRESH_LOCK 思路） */
    private final Object sessionLock = new Object();
    private volatile String sessionKey = "";
    private volatile String accessToken = "";
    private volatile long accessTokenExpires = 0L;
    /** generateRsaKey 缓存（按 expire 过期） */
    private volatile RsaKey rsaKey;

    private static OkHttpClient sClient;
    /** 上传签名的 uuid 用加密安全随机源（MED-02：java.util.Random 可预测，不应用于签名/HMAC 密钥生成） */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public TianyiProvider(CloudAccount account) {
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
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "天翼云盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !EncryptedCredStore.get(account.id, "sson_cookie").isEmpty()
                || !EncryptedCredStore.get(account.id, "access_token").isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        // 天翼凭据来自 WebView 网页登录捕获 SSON Cookie，无应用内登录流程
        return LoginState.NOT_SUPPORTED;
    }

    // ========== 会话管理 ==========

    /**
     * 确保存在可用会话（sessionKey + accessToken），优先级对齐 SDK getSession()：
     * 内存快路径 → 持久化有效会话 → sessionKey 续 accessToken → accessToken 直登
     * → refreshToken 刷新 → SSON cookie 登录；全部失败抛 AUTH_EXPIRED。
     * 全程 synchronized 单飞，成功后持久化回写加密存储。
     */
    private void ensureSession() throws CloudException {
        if (!sessionKey.isEmpty() && !accessToken.isEmpty()) {
            return;
        }
        synchronized (sessionLock) {
            if (!sessionKey.isEmpty() && !accessToken.isEmpty()) {
                return;
            }
            var sson = EncryptedCredStore.get(account.id, "sson_cookie");
            var at = EncryptedCredStore.get(account.id, "access_token");
            var rt = EncryptedCredStore.get(account.id, "refresh_token");
            var sk = EncryptedCredStore.get(account.id, "session_key");
            var expires = parseLong(EncryptedCredStore.get(account.id, "access_token_expires"), 0L);

            // 1. 持久化会话仍有效
            if (!sk.isEmpty() && !at.isEmpty() && expires > System.currentTimeMillis()) {
                sessionKey = sk;
                accessToken = at;
                accessTokenExpires = expires;
                return;
            }
            // 2. sessionKey 有效 → 用 /open getAccessTokenBySsKey 补/续 accessToken
            //    （getSessionForPC 响应不含 accessToken，这是唯一的补 accessToken 途径，对齐 SDK getAccessToken）
            if (!sk.isEmpty()) {
                try {
                    accessToken = getAccessTokenBySsKey();
                    sessionKey = sk;
                    accessTokenExpires = System.currentTimeMillis() + ACCESS_TOKEN_TTL_MS;
                    persistSession(accessToken, rt, sk, accessTokenExpires, "");
                    return;
                } catch (Exception e) {
                    LogHelp.w(TAG, "189 getAccessTokenBySsKey 失败，降级登录链", e);
                    sk = "";
                }
            }
            // 3. accessToken 直登换全新会话，成功后补 accessToken
            if (!at.isEmpty()) {
                try {
                    var ts = loginByAccessToken(at);
                    sessionKey = ts.sessionKey;
                    replenishAccessToken(rt, ts);
                    return;
                } catch (Exception e) {
                    LogHelp.w(TAG, "189 accessToken 直登失败，降级 refreshToken", e);
                    at = "";
                }
            }
            // 4. refreshToken 刷新后再换会话，成功后补 accessToken
            if (!rt.isEmpty()) {
                try {
                    var rs = refreshToken(rt);
                    var newRt = rs.refreshToken.isEmpty() ? rt : rs.refreshToken;
                    var ts = loginByAccessToken(rs.accessToken);
                    sessionKey = ts.sessionKey;
                    replenishAccessToken(newRt, ts);
                    return;
                } catch (Exception e) {
                    LogHelp.w(TAG, "189 refreshToken 刷新失败，降级 SSON", e);
                    rt = "";
                }
            }
            // 5. SSON cookie 最后兜底，成功后补 accessToken
            if (!sson.isEmpty()) {
                try {
                    var ts = loginBySsoCooike(sson);
                    sessionKey = ts.sessionKey;
                    replenishAccessToken(rt, ts);
                    return;
                } catch (Exception e) {
                    LogHelp.w(TAG, "189 SSON 登录失败", e);
                    throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 会话失效且 SSON 登录失败");
                }
            }
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 无可用会话凭据");
        }
    }

    /** 用 sessionKey 补 accessToken 并持久化（getSessionForPC 不返回 accessToken） */
    private void replenishAccessToken(String rt, TokenSession ts) throws CloudException {
        var newAt = getAccessTokenBySsKey();
        accessToken = newAt;
        accessTokenExpires = System.currentTimeMillis() + ACCESS_TOKEN_TTL_MS;
        persistSession(newAt, ts.refreshToken.isEmpty() ? rt : ts.refreshToken,
                sessionKey, accessTokenExpires, ts.loginName);
    }

    /** 回写加密存储（按 account.id 隔离） */
    private void persistSession(String at, String rt, String sk, long expires, String loginName) {
        try {
            EncryptedCredStore.put(account.id, "access_token", at == null ? "" : at);
            EncryptedCredStore.put(account.id, "refresh_token", rt == null ? "" : rt);
            EncryptedCredStore.put(account.id, "session_key", sk == null ? "" : sk);
            EncryptedCredStore.put(account.id, "access_token_expires", String.valueOf(expires));
            if (loginName != null && !loginName.isEmpty()) {
                EncryptedCredStore.put(account.id, "login_name", loginName);
            }
        } catch (Exception e) {
            LogHelp.w(TAG, "189 持久化会话失败", e);
        }
    }

    /** refresh()：静默续期一次，供 CloudFileHelp 的 AUTH_EXPIRED 重试 */
    @Override
    public boolean refresh() {
        try {
            ensureSession();
            return !sessionKey.isEmpty() && !accessToken.isEmpty();
        } catch (Exception e) {
            LogHelp.w(TAG, "189 会话刷新失败", e);
            return false;
        }
    }

    // ========== 登录链（无签名端点） ==========

    /** SSON cookie 登录：unifyLoginForPC 跟随重定向 → 带 Cookie 再跟随 → getSessionForPC */
    private TokenSession loginBySsoCooike(String sson) throws CloudException {
        var u1 = HttpUrl.parse(WEB_URL + "/api/portal/unifyLoginForPC.action").newBuilder()
                .addQueryParameter("appId", APP_ID)
                .addQueryParameter("clientType", CLIENT_TYPE)
                .addQueryParameter("returnURL", RETURN_URL)
                .addQueryParameter("timeStamp", String.valueOf(System.currentTimeMillis()))
                .build();
        var r1 = execute(new Request.Builder().url(u1).header("User-Agent", UA).build());
        if (r1.finalUrl.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "189 SSON 登录重定向链失败(1)");
        }
        var r2 = execute(new Request.Builder().url(r1.finalUrl)
                .header("Cookie", "SSON=" + sson)
                .header("User-Agent", UA)
                .build());
        if (r2.finalUrl.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "189 SSON 登录重定向链失败(2)");
        }
        return getSessionForPC("redirectURL", r2.finalUrl);
    }

    /** accessToken 直登 */
    private TokenSession loginByAccessToken(String at) throws CloudException {
        return getSessionForPC("accessToken", at);
    }

    /** 换会话（POST 无签名，query 携带参数） */
    private TokenSession getSessionForPC(String key, String value) throws CloudException {
        var url = HttpUrl.parse(API_URL + "/getSessionForPC.action").newBuilder()
                .addQueryParameter("appId", APP_ID)
                .addQueryParameter("clientType", PC_CLIENT_TYPE)
                .addQueryParameter("version", PC_VERSION)
                .addQueryParameter("channelId", CHANNEL_ID)
                .addQueryParameter("rand", String.valueOf(System.currentTimeMillis()))
                .addQueryParameter(key, value)
                .build();
        var resp = execute(new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json;charset=UTF-8")
                .post(RequestBody.create(null, new byte[0]))
                .build());
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 getSessionForPC HTTP " + resp.code);
        }
        try {
            var json = new JSONObject(resp.body);
            // res_code 为字符串错误码（如 "UserInvalidOpenToken"），不能用 optInt 判断（字符串解析为 0 会误判成功）
            var resCode = json.optString("res_code", "0");
            if (!"0".equals(resCode) && !resCode.isEmpty()) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                        "189 会话获取失败: " + json.optString("res_message", resCode));
            }
            var ts = new TokenSession();
            // 字段名兼容：camelCase / snake_case 双解析
            ts.accessToken = firstNonEmpty(json, "accessToken", "access_token");
            ts.refreshToken = firstNonEmpty(json, "refreshToken", "refresh_token");
            ts.sessionKey = firstNonEmpty(json, "sessionKey", "session_key");
            ts.loginName = firstNonEmpty(json, "loginName", "login_name");
            if (ts.accessToken.isEmpty() || ts.sessionKey.isEmpty()) {
                // 诊断日志：仅打印响应键集合与 res_code，不打印 token 值
                var keyList = new ArrayList<String>();
                for (var it = json.keys(); it.hasNext(); ) keyList.add(it.next());
                LogHelp.e(TAG, "189 getSessionForPC 响应缺少 token: keys=" + keyList
                        + " res_code=" + resCode + " res_message=" + json.optString("res_message", ""));
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 会话响应缺少 token");
            }
            return ts;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                    "189 会话响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** refresh_token 换新 token（无签名） */
    private RefreshSession refreshToken(String rt) throws CloudException {
        var form = new FormBody.Builder()
                .add("clientId", APP_ID)
                .add("refreshToken", rt)
                .add("grantType", "refresh_token")
                .add("format", "json")
                .build();
        var resp = execute(new Request.Builder().url(AUTH_URL + "/api/oauth2/refreshToken.do")
                .header("User-Agent", UA)
                .header("Accept", "application/json;charset=UTF-8")
                .post(form)
                .build());
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 refreshToken HTTP " + resp.code);
        }
        try {
            var json = new JSONObject(resp.body);
            if (json.has("result") && json.optInt("result", 0) != 0) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                        "189 refreshToken 失败: " + json.optString("msg", truncate(resp.body, 200)));
            }
            var rs = new RefreshSession();
            rs.accessToken = json.optString("accessToken", "");
            rs.refreshToken = json.optString("refreshToken", "");
            rs.expiresIn = json.optLong("expiresIn", 0L);
            if (rs.accessToken.isEmpty()) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 refreshToken 响应缺少 accessToken");
            }
            return rs;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                    "189 refreshToken 响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** 用 sessionKey 换取新 accessToken（cloud.189.cn /open，AppKey 签名） */
    private String getAccessTokenBySsKey() throws CloudException {
        var time = String.valueOf(System.currentTimeMillis());
        var data = new LinkedHashMap<String, String>();
        data.put("Timestamp", time);
        data.put("AppKey", APP_KEY);
        var url = HttpUrl.parse(WEB_URL + "/api/open/oauth2/getAccessTokenBySsKey.action").newBuilder()
                .addQueryParameter("sessionKey", sessionKey)
                .build();
        var builder = new Request.Builder().url(url);
        for (var h : signedHeaders(data).entrySet()) builder.header(h.getKey(), h.getValue());
        builder.header("User-Agent", UA).header("Referer", WEB_URL + "/web/main/");
        var resp = execute(builder.build());
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 getAccessTokenBySsKey HTTP " + resp.code);
        }
        try {
            var json = new JSONObject(resp.body);
            var at = json.optString("accessToken", "");
            if (at.isEmpty()) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 getAccessTokenBySsKey 响应缺少 token");
            }
            return at;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED,
                    "189 getAccessTokenBySsKey 响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** 获取上传 RSA 公钥（sessionKey 鉴权，按 expire 缓存） */
    private RsaKey ensureRsaKey() throws CloudException {
        if (rsaKey != null && rsaKey.expire > System.currentTimeMillis()) {
            return rsaKey;
        }
        var url = HttpUrl.parse(WEB_URL + "/api/security/generateRsaKey.action").newBuilder()
                .addQueryParameter("sessionKey", sessionKey)
                .build();
        var resp = execute(new Request.Builder().url(url).header("User-Agent", UA).build());
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE, "189 generateRsaKey HTTP " + resp.code);
        }
        try {
            // 真实接口在 sessionKey 有效时返回 XML（<keyPair><pubKey>…</pubKey>…），
            // sessionKey 无效时返回 JSON 错误（{"errorCode":"InvalidSessionKey",…}）；双格式兼容
            var body = resp.body;
            var key = new RsaKey();
            if (body.trim().startsWith("<")) {
                key.pubKey = xmlTag(body, "pubKey");
                key.pkId = xmlTag(body, "pkId");
                key.expire = parseLong(xmlTag(body, "expire"), 0L);
            } else {
                var json = new JSONObject(body);
                key.pubKey = json.optString("pubKey", "");
                key.pkId = json.optString("pkId", "");
                key.expire = json.optLong("expire", 0L);
            }
            // expire 秒→毫秒（毫秒值 > 1e12 则已是毫秒）；缺失时用默认 TTL
            if (key.expire > 0 && key.expire < 1000000000000L) key.expire *= 1000L;
            if (key.expire <= 0) key.expire = System.currentTimeMillis() + RSA_KEY_TTL_MS;
            if (key.pubKey.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "189 generateRsaKey 缺少公钥: " + truncate(body, 120));
            }
            rsaKey = key;
            return key;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "189 generateRsaKey 响应解析失败: " + truncate(resp.body, 200));
        }
    }

    // ========== 目录与列表 ==========

    @Override
    public boolean testConnection() throws CloudException {
        // 根目录列表轻量验证（listFiles 成功即会话可用）
        apiGet("/open/file/listFiles.action", listParams(ROOT_ID, 1, 1));
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

    /**
     * 完整复刻 SDK 上传：initMultiUpload（单分片带 fileMd5/sliceMd5，多分片 lazyCheck=1）
     * → checkTransSecond 秒传 → getMultiUploadUrls 分片签名 URL → PUT 分片（并发 5）→ commitMultiUploadFile
     */
    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException {
        var localFile = new File(localPath);
        if (!localFile.exists()) {
            throw new CloudException(CloudException.Kind.LOCAL, "file not found: " + localPath);
        }
        try {
            var parentId = resolvePath(remoteDir, true);
            if (parentId == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "189 无法解析上传目录: " + remoteDir);
            }
            if (cb != null) cb.onStart(taskId);
            var size = localFile.length();
            var sliceSize = partSize(size);
            var md5s = fileAndChunkMd5(localFile, sliceSize);
            // fileName 按 encodeURIComponent 语义编码后再入 AES params（SDK 一致，空格为 %20 而非 +）
            var fileName = uriComponent(localFile.getName());

            if (md5s.chunkMd5s.size() <= 1) {
                // ---- 单分片（含小文件与秒传） ----
                var params = new LinkedHashMap<String, String>();
                params.put("parentFolderId", parentId);
                params.put("fileName", fileName);
                params.put("fileSize", String.valueOf(size));
                params.put("sliceSize", String.valueOf(sliceSize));
                params.put("fileMd5", md5s.fileMd5);
                params.put("sliceMd5", md5s.fileMd5);
                var init = uploadGet("/person/initMultiUpload", params);
                checkUploadCode(init);
                var data = init.optJSONObject("data");
                if (data == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 initMultiUpload 缺少 data");
                }
                var uploadFileId = data.optString("uploadFileId", "");
                if (uploadFileId.isEmpty()) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 initMultiUpload 缺少 uploadFileId");
                }
                if (dataExists(data)) {
                    if (cb != null) {
                        cb.onProgress(taskId, size, size);
                        cb.onFinish(taskId, 0, "success");
                    }
                    return;
                }
                uploadPart(1, "1-" + base64Hex(md5s.fileMd5), uploadFileId, localFile, 0, (int) size);
                var commit = new LinkedHashMap<String, String>();
                commit.put("fileMd5", md5s.fileMd5);
                commit.put("sliceMd5", md5s.fileMd5);
                commit.put("uploadFileId", uploadFileId);
                checkUploadCode(uploadGet("/person/commitMultiUploadFile", commit));
            } else {
                // ---- 多分片 ----
                var sliceMd5 = md5Hex(String.join("\n", md5s.chunkMd5s));
                var params = new LinkedHashMap<String, String>();
                params.put("parentFolderId", parentId);
                params.put("fileName", fileName);
                params.put("fileSize", String.valueOf(size));
                params.put("sliceSize", String.valueOf(sliceSize));
                params.put("lazyCheck", "1");
                var init = uploadGet("/person/initMultiUpload", params);
                checkUploadCode(init);
                var data = init.optJSONObject("data");
                if (data == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 initMultiUpload 缺少 data");
                }
                var uploadFileId = data.optString("uploadFileId", "");
                if (uploadFileId.isEmpty()) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 initMultiUpload 缺少 uploadFileId");
                }
                var check = new LinkedHashMap<String, String>();
                check.put("fileMd5", md5s.fileMd5);
                check.put("sliceMd5", sliceMd5);
                check.put("uploadFileId", uploadFileId);
                var sec = uploadGet("/person/checkTransSecond", check);
                checkUploadCode(sec);
                var secData = sec.optJSONObject("data");
                if (secData != null && dataExists(secData)) {
                    if (cb != null) {
                        cb.onProgress(taskId, size, size);
                        cb.onFinish(taskId, 0, "success");
                    }
                    return;
                }
                var uploaded = new AtomicLong(0L);
                var chunkCount = md5s.chunkMd5s.size();
                var executor = Executors.newFixedThreadPool(MAX_PART);
                try {
                    var futures = new ArrayList<Future<?>>();
                    for (var i = 0; i < chunkCount; i++) {
                        final var idx = i;
                        futures.add(executor.submit(() -> {
                            var offset = (long) idx * sliceSize;
                            var len = (int) Math.min(sliceSize, size - offset);
                            uploadPart(idx + 1, (idx + 1) + "-" + base64Hex(md5s.chunkMd5s.get(idx)),
                                    uploadFileId, localFile, offset, len);
                            var done = uploaded.addAndGet(len);
                            if (cb != null) cb.onProgress(taskId, done, size);
                            return null;
                        }));
                    }
                    for (var f : futures) f.get();
                } finally {
                    executor.shutdownNow();
                }
                var commit = new LinkedHashMap<String, String>();
                commit.put("fileMd5", md5s.fileMd5);
                commit.put("sliceMd5", sliceMd5);
                commit.put("uploadFileId", uploadFileId);
                commit.put("lazyCheck", "1");
                checkUploadCode(uploadGet("/person/commitMultiUploadFile", commit));
            }
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            var ce = findCloudException(e);
            if (ce != null) {
                if (cb != null) cb.onFinish(taskId, -1, ce.getMessage());
                throw ce;
            }
            LogHelp.e(TAG, "189 上传失败", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 单分片 PUT 直传（requestHeader 由服务端签发，逐项透传不覆盖） */
    private void uploadPart(int partNumber, String partInfo, String uploadFileId, File file, long offset, int len) throws CloudException {
        var params = new LinkedHashMap<String, String>();
        params.put("partInfo", partInfo);
        params.put("uploadFileId", uploadFileId);
        var json = uploadGet("/person/getMultiUploadUrls", params);
        checkUploadCode(json);
        var urls = json.optJSONObject("uploadUrls");
        if (urls == null) {
            throw new CloudException(CloudException.Kind.REMOTE, "189 getMultiUploadUrls 缺少 uploadUrls");
        }
        var meta = urls.optJSONObject("partNumber_" + partNumber);
        if (meta == null) {
            throw new CloudException(CloudException.Kind.REMOTE, "189 getMultiUploadUrls 缺少 partNumber_" + partNumber);
        }
        var requestURL = meta.optString("requestURL", "");
        var requestHeader = meta.optString("requestHeader", "");
        if (requestURL.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "189 分片上传缺少 requestURL");
        }
        var builder = new Request.Builder().url(requestURL);
        for (var pair : requestHeader.split("&")) {
            var t = pair.trim();
            if (t.isEmpty()) continue;
            var eq = t.indexOf('=');
            if (eq <= 0) continue;
            builder.header(t.substring(0, eq), t.substring(eq + 1));
        }
        builder.put(partBody(file, offset, len));
        var resp = execute(builder.build());
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "189 分片 PUT HTTP " + resp.code + ": " + truncate(resp.body, 300));
        }
    }

    /** 流式读文件区间作为 PUT body */
    private static RequestBody partBody(File file, long offset, int len) {
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return null;
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
    }

    // ========== 下载 ==========

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "189 文件不存在: " + remotePath);
            }
            if (entry.isDir) {
                throw new CloudException(CloudException.Kind.REMOTE, "189 目标为目录: " + remotePath);
            }
            var dl = downloadUrl(entry.id);
            if (dl.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "189 缺少下载地址: " + remotePath);
            }
            var builder = new Request.Builder().url(dl)
                    .header("User-Agent", UA)
                    .header("Referer", WEB_URL + "/web/main/")
                    .header("Accept", "application/json, text/plain, */*");
            try (var resp = client().newCall(builder.build()).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 下载 HTTP " + code);
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 下载空响应");
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
                                "189 下载不完整: " + written + "/" + total);
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

    private String downloadUrl(String fileId) throws CloudException {
        var p = new LinkedHashMap<String, String>();
        p.put("fileId", fileId);
        var json = apiGet("/open/file/getFileDownloadUrl.action", p);
        return json.optString("fileDownloadUrl", "");
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

    /** 批量任务删除：createBatchTask(DELETE) → checkBatchTask 轮询 */
    private void deletePath(String remotePath) throws CloudException {
        var remote = trimSlashes(remotePath);
        if (remote.isEmpty()) return;
        try {
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null) return;
            var taskInfos = new JSONArray();
            var item = new JSONObject();
            item.put("fileId", entry.id);
            item.put("isFolder", entry.isDir ? 1 : 0);
            taskInfos.put(item);
            var form = new LinkedHashMap<String, String>();
            form.put("type", "DELETE");
            form.put("taskInfos", taskInfos.toString());
            var created = apiPostForm("/open/batch/createBatchTask.action", form);
            var taskId = created.optString("taskId", "");
            if (taskId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "189 删除任务缺少 taskId");
            }
            for (var i = 0; i < 60; i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                var check = new LinkedHashMap<String, String>();
                check.put("type", "DELETE");
                check.put("taskId", taskId);
                var res = apiPostForm("/open/batch/checkBatchTask.action", check);
                var status = res.optInt("taskStatus", -99);
                if (status == 4) return;
                if (status == -1) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 删除任务异常");
                }
                if (status == 2) {
                    throw new CloudException(CloudException.Kind.REMOTE, "189 删除任务重名冲突");
                }
            }
            throw new CloudException(CloudException.Kind.REMOTE, "189 删除任务超时: " + taskId);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 业务 API 封装 ==========

    @FunctionalInterface
    private interface ApiCall {
        HttpResponse run() throws CloudException;
    }

    /**
     * 签名请求统一入口：先确保会话，遇 HTTP 400 且 errorCode 为
     * InvalidAccessToken/InvalidSessionKey 时清空对应 token 后重试（自愈），上限 MAX_SESSION_RETRY 次
     */
    private JSONObject withRetry(ApiCall call) throws CloudException {
        var last = new HttpResponse();
        for (var attempt = 0; attempt <= MAX_SESSION_RETRY; attempt++) {
            ensureSession();
            last = call.run();
            if (selfHeal(last) != null) continue;
            return parseApiResponse(last);
        }
        throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 认证失效(重试耗尽)");
    }

    /** 400 自愈：识别 InvalidAccessToken/InvalidSessionKey 并清空对应 token */
    private String selfHeal(HttpResponse resp) {
        if (resp.code != 400) return null;
        String ec = null;
        try {
            ec = new JSONObject(resp.body).optString("errorCode", "");
        } catch (Exception ignored) {
        }
        if ("InvalidAccessToken".equals(ec)) {
            accessToken = "";
            EncryptedCredStore.put(account.id, "access_token", "");
            LogHelp.i(TAG, "189 InvalidAccessToken，清空 accessToken 重试");
            return ec;
        }
        if ("InvalidSessionKey".equals(ec)) {
            sessionKey = "";
            EncryptedCredStore.put(account.id, "session_key", "");
            LogHelp.i(TAG, "189 InvalidSessionKey，清空 sessionKey 重试");
            return ec;
        }
        return null;
    }

    private JSONObject parseApiResponse(HttpResponse resp) throws CloudException {
        if (resp.code == 401) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "189 认证失败 HTTP 401");
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "189 API HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            var json = new JSONObject(resp.body);
            if (json.has("errorCode")) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "189 API 错误: " + json.optString("errorMsg", json.optString("errorCode", "")));
            }
            // res_code 可能是数字(0)或字符串("0"/错误码)：非 "0" 一律视为失败（对齐 getSessionForPC 修复）
            if (json.has("res_code")) {
                var rc = json.opt("res_code");
                if (rc != null && !"0".equals(String.valueOf(rc))) {
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "189 API 错误: " + json.optString("res_message", truncate(resp.body, 200)));
                }
            }
            return json;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "189 响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** api.cloud.189.cn GET（accessToken MD5 签名头） */
    private JSONObject apiGet(String path, Map<String, String> params) throws CloudException {
        return withRetry(() -> {
            var time = String.valueOf(System.currentTimeMillis());
            var data = new LinkedHashMap<String, String>(params);
            data.put("Timestamp", time);
            data.put("AccessToken", accessToken);
            var url = HttpUrl.parse(API_URL + path).newBuilder();
            for (var e : params.entrySet()) url.addQueryParameter(e.getKey(), e.getValue());
            var builder = new Request.Builder().url(url.build());
            for (var h : signedHeaders(data).entrySet()) builder.header(h.getKey(), h.getValue());
            builder.header("User-Agent", UA)
                    .header("Referer", WEB_URL + "/web/main/")
                    .header("Accept", "application/json;charset=UTF-8");
            return execute(builder.build());
        });
    }

    /** api.cloud.189.cn POST form（accessToken MD5 签名头） */
    private JSONObject apiPostForm(String path, Map<String, String> form) throws CloudException {
        return withRetry(() -> {
            var time = String.valueOf(System.currentTimeMillis());
            var data = new LinkedHashMap<String, String>(form);
            data.put("Timestamp", time);
            data.put("AccessToken", accessToken);
            var fb = new FormBody.Builder();
            for (var e : form.entrySet()) fb.add(e.getKey(), e.getValue());
            var builder = new Request.Builder().url(API_URL + path);
            for (var h : signedHeaders(data).entrySet()) builder.header(h.getKey(), h.getValue());
            builder.header("User-Agent", UA)
                    .header("Referer", WEB_URL + "/web/main/")
                    .header("Accept", "application/json;charset=UTF-8");
            builder.post(fb.build());
            return execute(builder.build());
        });
    }

    /** upload.cloud.189.cn GET（signatureUpload：AES 参数 + RSA 加密 uuid + HMAC 签名） */
    private JSONObject uploadGet(String path, LinkedHashMap<String, String> params) throws CloudException {
        return withRetry(() -> {
            var time = String.valueOf(System.currentTimeMillis());
            var uuid = genUuid();
            var aes = aesEcbHex(joinParams(params), uuid.substring(0, 16));
            var key = ensureRsaKey();
            var requestId = UUID.randomUUID().toString();
            var data = "SessionKey=" + sessionKey
                    + "&Operate=GET"
                    + "&RequestURI=" + path
                    + "&Date=" + time
                    + "&params=" + aes;
            var sig = hmacSha1Hex(data, uuid);
            var encText = rsaEncryptBase64(key.pubKey, uuid);
            var url = HttpUrl.parse(UPLOAD_URL + path).newBuilder()
                    .addQueryParameter("params", aes)
                    .build();
            var builder = new Request.Builder().url(url)
                    .header("X-Request-Date", time)
                    .header("X-Request-ID", requestId)
                    .header("SessionKey", sessionKey)
                    .header("EncryptionText", encText)
                    .header("PkId", key.pkId)
                    .header("Signature", sig)
                    .header("User-Agent", UA)
                    .header("Referer", WEB_URL + "/web/main/")
                    .header("Accept", "application/json;charset=UTF-8");
            return execute(builder.build());
        });
    }

    /** 上传接口 code 字段非 0 判定失败（code 可能为数字或字符串，统一转字符串判断） */
    private void checkUploadCode(JSONObject json) throws CloudException {
        if (json.has("code")) {
            var code = json.opt("code");
            if (code != null && !"0".equals(String.valueOf(code))) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "189 上传接口错误 code=" + code
                                + ": " + json.optString("message", json.optString("errorMsg", "")));
            }
        }
    }

    /** fileDataExists 非 0 即秒传命中 */
    private static boolean dataExists(JSONObject data) {
        return data.optInt("fileDataExists", 0) != 0 || data.optBoolean("fileDataExists", false);
    }

    // ========== 签名算法 ==========

    /** 参数条目 k=v 按整串字典序排序后 & 连接（对齐 SDK sortParameter） */
    private static String sortParameters(Map<String, String> data) {
        var list = new ArrayList<String>();
        for (var e : data.entrySet()) list.add(e.getKey() + "=" + e.getValue());
        list.sort(Comparator.naturalOrder());
        return String.join("&", list);
    }

    /** accessToken/AppKey 共用的 MD5 签名头构造（header 名 Accesstoken 小写 t，与 SDK 逐字一致） */
    private static Map<String, String> signedHeaders(Map<String, String> data) {
        var sig = md5Hex(sortParameters(data));
        var h = new LinkedHashMap<String, String>();
        h.put("Sign-Type", "1");
        h.put("Signature", sig);
        h.put("Timestamp", data.get("Timestamp"));
        if (data.containsKey("AccessToken")) {
            h.put("Accesstoken", data.get("AccessToken"));
        } else {
            h.put("AppKey", data.get("AppKey"));
        }
        return h;
    }

    /** uuid：模板 xxxx…4xxxy… 生成后取 16~31 位随机长度；AES 密钥=前 16 字符，HMAC 密钥=整个 uuid */
    private static String genUuid() {
        var tpl = "xxxxxxxxxxxx4xxxyxxxxxxxxxxxxxxx";
        var sb = new StringBuilder(tpl.length());
        for (var i = 0; i < tpl.length(); i++) {
            var c = tpl.charAt(i);
            if (c == 'x') {
                sb.append(Integer.toHexString(SECURE_RANDOM.nextInt(16)));
            } else if (c == 'y') {
                sb.append(Integer.toHexString((3 & SECURE_RANDOM.nextInt(16)) | 8));
            } else {
                sb.append(c);
            }
        }
        var len = 16 + SECURE_RANDOM.nextInt(16);
        return sb.substring(0, len);
    }

    /** AES-128-ECB(PKCS7) 加密，密钥 = key16 UTF-8 前 16 字节，输出小写 hex */
    private static String aesEcbHex(String plain, String key16) {
        try {
            var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16.getBytes(StandardCharsets.UTF_8), "AES"));
            return hexLower(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("AES 加密失败", e);
        }
    }

    /** HMAC-SHA1，密钥 = 整个 uuid，输出小写 hex */
    private static String hmacSha1Hex(String data, String key) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return hexLower(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** RSA/ECB/PKCS1 加密明文，输出 base64（对齐 Node publicEncrypt RSA_PKCS1_PADDING） */
    private static String rsaEncryptBase64(String pubKeyB64, String plain) {
        try {
            var der = Base64.getDecoder().decode(pubKeyB64);
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("RSA 加密失败", e);
        }
    }

    /** 分片大小三段式（对齐 SDK util.ts partSize） */
    private static long partSize(long size) {
        if (size > DEF_SLICE * 2 * 999) {
            var chunk = size / 1999.0;
            var mult = Math.max((long) Math.ceil(chunk / DEF_SLICE), 5L);
            return mult * DEF_SLICE;
        }
        if (size > DEF_SLICE * 999) return DEF_SLICE * 2;
        return DEF_SLICE;
    }

    /** 全文件 MD5（小写 hex）+ 每分片 MD5（大写 hex），分块大小 = sliceSize */
    private static Md5Result fileAndChunkMd5(File file, long sliceSize) throws Exception {
        var fileHash = MessageDigest.getInstance("MD5");
        var chunks = new ArrayList<String>();
        try (var in = new FileInputStream(file)) {
            var chunkHash = MessageDigest.getInstance("MD5");
            var inChunk = 0L;
            var buffer = new byte[BUFFER_SIZE];
            var read = 0;
            while ((read = in.read(buffer)) != -1) {
                fileHash.update(buffer, 0, read);
                chunkHash.update(buffer, 0, read);
                inChunk += read;
                if (inChunk >= sliceSize) {
                    chunks.add(hexLower(chunkHash.digest()).toUpperCase(Locale.ROOT));
                    chunkHash = MessageDigest.getInstance("MD5");
                    inChunk = 0L;
                }
            }
            if (inChunk > 0) {
                chunks.add(hexLower(chunkHash.digest()).toUpperCase(Locale.ROOT));
            }
        }
        var out = new Md5Result();
        out.fileMd5 = hexLower(fileHash.digest());
        out.chunkMd5s = chunks;
        return out;
    }

    // ========== 路径解析 ==========

    private List<Item> collectEntries(String parentId) throws CloudException {
        var out = new ArrayList<Item>();
        var pid = parentId == null || parentId.isEmpty() ? ROOT_ID : parentId;
        var page = 1;
        var total = Integer.MAX_VALUE;
        while (out.size() < total && page <= MAX_PAGES) {
            var json = apiGet("/open/file/listFiles.action", listParams(pid, page, PAGE_SIZE));
            var ao = json.optJSONObject("fileListAO");
            if (ao == null) break;
            total = ao.optInt("count", total);
            var folders = ao.optJSONArray("folderList");
            var got = 0;
            if (folders != null) {
                for (var i = 0; i < folders.length(); i++) {
                    var o = folders.optJSONObject(i);
                    if (o != null) out.add(Item.fromJson(o));
                }
                got += folders.length();
            }
            var files = ao.optJSONArray("fileList");
            if (files != null) {
                for (var i = 0; i < files.length(); i++) {
                    var o = files.optJSONObject(i);
                    if (o != null) out.add(Item.fromJson(o));
                }
                got += files.length();
            }
            if (got < PAGE_SIZE) break;
            page++;
        }
        return out;
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
            if (name.equals(it.name)) return it;
        }
        return null;
    }

    private Item findEntry(String parentPath, String targetName) throws CloudException {
        var parentId = resolvePath(parentPath, false);
        if (parentId == null) return null;
        return findChild(parentId, targetName);
    }

    private String createFolder(String parentId, String name) throws CloudException {
        var form = new LinkedHashMap<String, String>();
        form.put("parentFolderId", parentId == null || parentId.isEmpty() ? PERSONAL_ROOT_ID : parentId);
        form.put("folderName", name);
        var json = apiPostForm("/open/file/createFolder.action", form);
        var id = json.optString("id", "");
        if (id.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "189 建目录缺少 id: " + name);
        }
        return id;
    }

    /** 解析路径为目录 id；createMissing=true 自动建目录；缺失返回 null */
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
            if (!child.isDir) {
                if (!createMissing) return null;
                throw new CloudException(CloudException.Kind.REMOTE, "189 路径非目录: " + name);
            }
            parentId = child.id;
            if (parentId.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "189 缺少目录 id: " + name);
            }
        }
        return parentId;
    }

    private static LinkedHashMap<String, String> listParams(String folderId, int pageNum, int pageSize) {
        var p = new LinkedHashMap<String, String>();
        p.put("pageNum", String.valueOf(pageNum));
        p.put("pageSize", String.valueOf(pageSize));
        p.put("mediaType", "0");
        p.put("orderBy", "3");
        p.put("descending", "true");
        p.put("folderId", folderId == null ? "" : folderId);
        p.put("iconOption", "5");
        return p;
    }

    // ========== HTTP 层 ==========

    private static class HttpResponse {
        int code;
        String body = "";
        String finalUrl = "";
    }

    private HttpResponse execute(Request request) throws CloudException {
        var resp = new HttpResponse();
        try (var r = client().newCall(request).execute()) {
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            resp.finalUrl = r.request().url().toString();
            return resp;
        } catch (IOException e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (TianyiProvider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    // ========== 工具 ==========

    /** 依次取 JSON 字段，返回第一个非空字符串（camelCase / snake_case 兼容） */
    private static String firstNonEmpty(JSONObject json, String... keys) {
        for (var key : keys) {
            var v = json.optString(key, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String hexLower(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String md5Hex(String text) {
        try {
            return hexLower(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    /** hex 字符串转 base64（对齐 SDK hexToBase64） */
    private static String base64Hex(String hex) {
        var bytes = new byte[hex.length() / 2];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 插入序 k=v&k=v 拼接（对齐 SDK Object.entries join） */
    private static String joinParams(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** encodeURIComponent 语义：UTF-8 字节百分比编码，保留 A-Za-z0-9-_.~（空格 %20 而非 +） */
    private static String uriComponent(String s) {
        var sb = new StringBuilder();
        for (var b : s.getBytes(StandardCharsets.UTF_8)) {
            var c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                // %02X 只接受整数类型，char 需显式转 int，否则中文/特殊字符触发 IllegalFormatConversionException
                sb.append('%').append(String.format(Locale.ROOT, "%02X", (int) c));
            }
        }
        return sb.toString();
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    /** 简单 XML 标签取值（generateRsaKey 的 keyPair 结构，值不含尖括号） */
    private static String xmlTag(String xml, String tag) {
        if (xml == null || xml.isEmpty()) return "";
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 在异常链中查找 CloudException（并发分片 Future.get 会把受检异常包成 ExecutionException） */
    private static CloudException findCloudException(Throwable t) {
        for (var cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof CloudException) return (CloudException) cur;
        }
        return null;
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

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐 Quark Provider） */
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

    // ========== 内部模型 ==========

    private static class TokenSession {
        String accessToken = "";
        String refreshToken = "";
        String sessionKey = "";
        String loginName = "";
    }

    private static class RefreshSession {
        String accessToken = "";
        String refreshToken = "";
        long expiresIn = 0L;
    }

    private static class RsaKey {
        String pubKey = "";
        String pkId = "";
        long expire = 0L;
    }

    private static class Md5Result {
        String fileMd5 = "";
        List<String> chunkMd5s = new ArrayList<>();
    }

    /** 目录/文件条目 */
    private static class Item {
        final String id;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;

        Item(String id, String name, long size, boolean isDir, long modifiedTime) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
        }

        static Item fromJson(JSONObject obj) {
            var id = obj.optString("id", "");
            var name = obj.optString("name", "");
            var isDir = obj.has("fileCount");
            return new Item(id, name, obj.optLong("size", 0L), isDir, parseTime(obj.optString("lastOpTime", "")));
        }

        static long parseTime(String s) {
            if (s == null || s.isEmpty()) return System.currentTimeMillis();
            try {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).parse(s).getTime();
            } catch (Exception e) {
                return System.currentTimeMillis();
            }
        }
    }
}
