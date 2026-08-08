package com.suileyan.cloud.login;

import org.json.JSONObject;

import com.suileyan.comm.LogHelp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * 中国移动云盘（139）登录引擎
 *
 * 关键事实（来自 alist/OpenList 139 驱动的逆向实现）：
 * 1. Authorization 格式为 Basic base64(...)，由登录云盘网页版后获取，业界无"手机号+密码直接换 Authorization"的稳定公开接口；
 * 2. 校验与文件接口签名：mcloud-sign 头 = ts,randStr,sign，
 *    sign = MD5(MD5(base64(sort(encodeURIComponent(body)))) + MD5(ts+":"+randStr)) 大写；
 * 3. token 续期接口：https://aas.caiyun.feixin.10086.cn/tellin/authTokenRefresh.do（未在本次范围）。
 *
 * 本类提供：
 * - validate(authorization)：用签名算法校验 Authorization 是否可用（可靠）
 * - loginWithPassword(mobile, password)：实验性账密自动登录（逆向接口，可能随版本失效；
 *   失效时上层引导用户登录网页版后粘贴 Authorization，此为可靠兜底）
 */
public final class Yun139Login {

    private static final String TAG = "XpMiBackup";
    private static final String API_BASE = "https://yun.139.com";
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static volatile OkHttpClient sClient;

    private Yun139Login() {
    }

    // ========== Authorization 校验（可靠） ==========

    /**
     * 校验 Authorization（Basic 后的值）是否可用：调用 hcy/file/list 并检查响应
     */
    public static boolean validate(String authorization) {
        return validate(authorization, null);
    }

    /**
     * 校验 Authorization（Basic 后的值）是否可用
     *
     * @param host 网页实际使用的 API 主机（如 personal-kd-njs.yun.139.com）；为空时用默认主站。
     *             139 的区域节点域名与主站可能不同，用网页真实请求捕获的主机校验成功率最高。
     */
    public static boolean validate(String authorization, String host) {
        if (authorization == null || authorization.trim().isEmpty()) return false;
        try {
            var body = buildListBody();
            var ts = currentTimestamp();
            var rand = randomString(16);
            var sign = calSign(body, ts, rand);
            var apiBase = (host != null && !host.isEmpty()) ? "https://" + host : API_BASE;
            var request = new Request.Builder()
                    .url(apiBase + "/hcy/file/list")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("CMS-DEVICE", "default")
                    .header("Authorization", "Basic " + authorization.trim())
                    .header("mcloud-channel", "1000101")
                    .header("mcloud-client", "10701")
                    .header("mcloud-sign", ts + "," + rand + "," + sign)
                    .header("mcloud-version", "6.6.0")
                    .header("Origin", "https://yun.139.com")
                    .header("Referer", "https://yun.139.com/w/")
                    .header("x-SvcType", "1")
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .post(RequestBody.create(JSON, body))
                    .build();
            try (var resp = getClient().newCall(request).execute()) {
                var code = resp.code();
                var respBody = resp.body() != null ? resp.body().string() : "";
                // 严格判定：仅 2xx 视为通过；响应必须可解析为 JSON；success 显式为 false 视为不可用
                if (code < 200 || code >= 300) {
                    LogHelp.w(TAG, "139 validate failed: HTTP " + code);
                    return false;
                }
                var json = new JSONObject(respBody);
                if (!json.isNull("success") && !json.optBoolean("success", true)) {
                    LogHelp.w(TAG, "139 validate failed: success=false, " + respBody);
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "139 authorization validate failed", e);
            return false;
        }
    }

    /**
     * 实验性账密自动登录（逆向接口，可能随版本失效）
     * 成功返回 Authorization（Basic 后的值），失败返回 null（上层引导粘贴兜底）
     */
    public static String loginWithPassword(String mobile, String password) {
        if (mobile == null || mobile.trim().isEmpty() || password == null || password.isEmpty()) return null;
        try {
            var body = new JSONObject();
            body.put("uname", mobile.trim());
            body.put("password", password);
            var request = new Request.Builder()
                    .url("https://webapi.139.com/sinaportal/login")
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("Referer", "https://www.139.com/")
                    .header("Origin", "https://www.139.com")
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            try (var resp = getClient().newCall(request).execute()) {
                var respBody = resp.body() != null ? resp.body().string() : "";
                // 尝试从 JSON 响应中提取 authorization/token
                try {
                    var json = new JSONObject(respBody);
                    for (var key : new String[]{"authorization", "Authorization", "token", "authToken", "ssoToken"}) {
                        if (!json.isNull(key)) {
                            var value = json.optString(key, "").trim();
                            if (!value.isEmpty() && validate(value)) return value;
                        }
                    }
                } catch (Exception ignored) {
                }
                return null;
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "139 password login failed", e);
            return null;
        }
    }

    // ========== 签名算法（移植自 alist drivers/139，供本类与 Yun139Provider 共用） ==========

    /** mcloud-sign 签名：body URL 编码→逐字符排序→Base64→MD5，混 ts:rand，再 MD5 大写 */
    public static String calSign(String body, String ts, String randStr) {
        var encoded = encodeURIComponent(body == null ? "" : body);
        var chars = encoded.toCharArray();
        java.util.Arrays.sort(chars);
        var sorted = new String(chars);
        var b64 = Base64.getEncoder().encodeToString(sorted.getBytes(StandardCharsets.UTF_8));
        var md5 = md5Hex(b64);
        var timeMd5 = md5Hex(ts + ":" + randStr);
        return md5Hex(md5 + timeMd5).toUpperCase(Locale.ROOT);
    }

    /** 与 JS encodeURIComponent 对齐（空格→%20，! ' ( ) * 不编码） */
    static String encodeURIComponent(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%2A", "*");
        } catch (Exception e) {
            return value;
        }
    }

    static String md5Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            var bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            var out = new StringBuilder(bytes.length * 2);
            for (var b : bytes) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            // 摘要算法缺失是环境性问题，吞掉会返回错误签名导致接口全部失败（MED-04）
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    public static String currentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }

    /** 加密安全随机源（MED-03），静态复用避免每次调用重新初始化（NEW-L-06） */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 随机串用于签名随机数，改用加密安全随机源（MED-03） */
    public static String randomString(int length) {
        var out = new StringBuilder(length);
        for (var i = 0; i < length; i++) {
            out.append(CHARS.charAt(SECURE_RANDOM.nextInt(CHARS.length())));
        }
        return out.toString();
    }

    /** 列目录请求体（与 yun139.js buildListBody 一致） */
    private static String buildListBody() {
        try {
            var body = new JSONObject();
            body.put("imageThumbnailStyleList", new String[]{"Small", "Large"});
            body.put("orderBy", "updated_at");
            body.put("orderDirection", "DESC");
            var pageInfo = new JSONObject();
            pageInfo.put("pageCursor", "");
            pageInfo.put("pageSize", 1);
            body.put("pageInfo", pageInfo);
            body.put("parentFileId", "/");
            return body.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static OkHttpClient getClient() {
        if (sClient != null) return sClient;
        synchronized (Yun139Login.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }
}
