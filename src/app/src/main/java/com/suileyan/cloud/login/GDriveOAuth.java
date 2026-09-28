package com.suileyan.cloud.login;

import com.suileyan.cloud.CloudException;
import com.suileyan.comm.LogHelp;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Google OAuth 2.0 凭据换取（Drive v3 REST 路线的凭据层）
 *
 * - 授权 URL：https://accounts.google.com/o/oauth2/auth（Desktop 类型 client，
 *   redirect_uri 用 http://127.0.0.1:<port>，OOB 流已下线不可用）
 * - 换取/刷新：https://oauth2.googleapis.com/token（form 表单）
 * - scope 用 drive.file（sensitive 级，免 restricted 审核流程；只见本应用创建的文件）
 * - refresh_token 不轮换（撤销前长期有效）；access_token 约 1h
 *
 * 日志纪律：token 一律掩码输出，不落明文。
 */
public final class GDriveOAuth {

    private static final String TAG = "XpMiBackup";
    public static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/auth";
    public static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    public static final String SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";

    /**
     * 内置 OAuth client（评审决策 Q1：内置默认 + 用户可覆盖）。
     *
     * 取值来自构建期注入：根目录 `gdrive.properties`（`clientId` / `clientSecret`）
     * → `buildConfigField` → `BuildConfig.GDRIVE_CLIENT_ID/SECRET`，见 `app/build.gradle`。
     * 该属性文件已在 `.gitignore` 中，凭据不进入 git 历史；未配置时为空串，
     * 登录页回退为「要求用户自填自己的 GCP 凭据」。
     *
     * 风险提示：Google 对「桌面应用」类型的 client_secret 不视为机密（已安装应用无法保密），
     * 但公开分发意味着任何人都能用这个 client_id 发起授权——消耗的是本项目配额，
     * 滥用还可能让 client 被停用（rclone 的共享 client 已宣布 2026 年退役）。
     * 需要换成自己的项目时改 `gdrive.properties` 重新构建即可，无需改代码。
     */
    public static final String BUILTIN_CLIENT_ID = com.suileyan.xpmibackup.BuildConfig.GDRIVE_CLIENT_ID;
    public static final String BUILTIN_CLIENT_SECRET = com.suileyan.xpmibackup.BuildConfig.GDRIVE_CLIENT_SECRET;

    /** 是否内置了可用的 OAuth 客户端：决定登录页是「一键授权」还是「要求自填凭据」 */
    public static boolean hasBuiltinClient() {
        return !BUILTIN_CLIENT_ID.isEmpty() && !BUILTIN_CLIENT_SECRET.isEmpty();
    }

    private GDriveOAuth() {
    }

    /** token 换取结果（expiresAt 为本地时钟毫秒） */
    public static final class TokenSet {
        public final String accessToken;
        public final String refreshToken; // 可能为空（刷新响应未带新值时沿用旧值）
        public final long expiresAt;

        TokenSet(String accessToken, String refreshToken, long expiresAt) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
        }
    }

    /** 构造授权页 URL（redirect_uri 必须与 loopback 服务器端口一致） */
    public static String buildAuthUrl(String clientId, String redirectUri, String state) {
        return AUTH_ENDPOINT
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPE_DRIVE_FILE)
                // access_type=offline：必须，否则不下发 refresh_token
                + "&access_type=offline"
                // prompt=consent：必须，保证重授权场景仍发 refresh_token
                + "&prompt=consent"
                + "&state=" + enc(state);
    }

    /** 授权码换 token（登录时一次性） */
    public static TokenSet exchange(String clientId, String clientSecret,
                                    String redirectUri, String code) throws CloudException {
        var form = new FormBody.Builder()
                .add("code", code)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
                .build();
        return tokenPost(form, "换取");
    }

    /** refresh_token 换新 access_token */
    public static TokenSet refresh(String clientId, String clientSecret,
                                   String refreshToken) throws CloudException {
        var form = new FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("grant_type", "refresh_token")
                .build();
        return tokenPost(form, "刷新");
    }

    private static TokenSet tokenPost(RequestBody form, String action) throws CloudException {
        var request = new Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(form)
                .build();
        try (var resp = httpClient().newCall(request).execute()) {
            var body = resp.body() != null ? resp.body().string() : "";
            var json = new JSONObject(body.isEmpty() ? "{}" : body);
            if (resp.code() != 200 || json.has("error")) {
                // Google 错误体形如 {"error":"invalid_grant","error_description":"..."}
                var error = json.optString("error", "HTTP " + resp.code());
                var desc = json.optString("error_description", "");
                var kind = "invalid_grant".equals(error)
                        ? CloudException.Kind.AUTH_EXPIRED : CloudException.Kind.REMOTE;
                throw new CloudException(kind,
                        "Google 凭据" + action + "失败: " + error
                                + (desc.isEmpty() ? "" : " - " + desc));
            }
            var access = json.optString("access_token", "");
            if (access.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE,
                        "Google 凭据" + action + "响应缺少 access_token");
            }
            var refresh = json.optString("refresh_token", "");
            var expiresInSeconds = json.optLong("expires_in", 3600L);
            LogHelp.i(TAG, "Google 凭据" + action + "成功 access=" + mask(access)
                    + (refresh.isEmpty() ? " refresh=<沿用旧值>" : " refresh=" + mask(refresh)));
            return new TokenSet(access, refresh,
                    System.currentTimeMillis() + expiresInSeconds * 1000L);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    /** 生成授权 state（128bit 随机，防 CSRF） */
    public static String newState() {
        var bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** token 掩码：仅保留前 4 位与长度，日志脱敏用 */
    public static String mask(String token) {
        if (token == null || token.isEmpty()) return "<empty>";
        return token.substring(0, Math.min(4, token.length())) + "***len=" + token.length();
    }

    private static OkHttpClient sClient;

    private static synchronized OkHttpClient httpClient() {
        if (sClient != null) return sClient;
        sClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        return sClient;
    }

    private static String enc(String value) {
        try {
            // 注意：URLEncoder.encode(String, Charset) 是 API 33 才有的重载，minSdk 30 会 NoSuchMethodError
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
