package com.suileyan.comm;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * 应用更新检查工具（多源降级）
 *
 * 数据源优先级：
 * 1. config.ini `update_url`（用户显式配置的完整检查 URL，最高优先级，通常是国内可达的端点）
 * 2. GitHub 官方 `https://api.github.com/repos/suileyan/xpmibackup_sly/releases/latest`
 * 3. GitHub API 镜像（config.ini `update_mirror` 指定前缀，默认 ghproxy.net；空串禁用镜像）
 *
 * 任一源成功即返回；全部失败返回 ok=false。404 仅在「主源」（官方/自定义）视为"仓库无已发布 release → 无新版"，
 * 镜像 404 不可靠，归为失败继续降级，避免误报"已是最新"。
 *
 * 版本比较：tag_name 去 "v" 前缀后与当前 versionName 按 x.y.z 逐段数字比较；
 * 防误报：tag 解析不出数字 → 视为该源解析失败（不报新版）；latest < current → 按已是最新。
 *
 * 国内网络说明：api.github.com 与 github.com 页面在国内常不可达，因此内置镜像降级，
 * 并在 AboutFragment 提供「镜像下载」动作（用 update_mirror 前缀拼 asset 直链打开浏览器下载）。
 *
 * 注意：本类方法为同步阻塞，必须在后台线程调用（调用方用 Async.run 包裹）。
 */
public final class UpdateChecker {

    private static final String TAG = "XpMiBackup";
    private static final String GITHUB_LATEST_URL =
            "https://api.github.com/repos/suileyan/xpmibackup_sly/releases/latest";
    private static final String RELEASE_PAGE_BASE =
            "https://github.com/suileyan/xpmibackup_sly/releases";
    /**
     * API 检查降级镜像（仅用于 check() 内部请求 GitHub API，应用内静默，无浏览器警报问题）。
     * 注意：与「下载镜像」分离——下载走浏览器，公共代理域名（ghproxy 系）会被 Chrome Safe Browsing
     * 标记为危险，默认不用于下载；下载镜像仅当用户显式配置 config.ini update_mirror 时启用。
     */
    private static final String[] CHECK_MIRRORS = {
            "https://ghproxy.net/",
            "https://gh-proxy.com/"
    };

    /** 检查结果 */
    public static final class Result {
        /** 请求+解析+比较全部成功（ok=false 表示检查失败，调用方按失败提示） */
        public final boolean ok;
        /** 最新版本号（tag 去 "v" 前缀），如 "0.2.1" */
        public final String latestVersion;
        /** GitHub release 页 URL */
        public final String htmlUrl;
        /** 最新 APK 直链（browser_download_url），供镜像下载 */
        public final String downloadUrl;
        /** 是否检测到新版本 */
        public final boolean hasNew;

        Result(boolean ok, String latestVersion, String htmlUrl, String downloadUrl, boolean hasNew) {
            this.ok = ok;
            this.latestVersion = latestVersion;
            this.htmlUrl = htmlUrl;
            this.downloadUrl = downloadUrl;
            this.hasNew = hasNew;
        }
    }

    private UpdateChecker() {
    }

    /**
     * 下载镜像前缀（config.ini `update_mirror`，默认空 = 使用 GitHub 官方直链）。
     * 仅当用户显式配置时启用镜像下载——公共代理域名（如 ghproxy.net）会被 Chrome Safe Browsing
     * 标记为危险，默认不拼接，避免浏览器安全警报。
     */
    public static String downloadMirrorPrefix() {
        return ConfigHelp.getString("update_mirror", "");
    }

    /**
     * 组装下载 URL：配置了 update_mirror 则拼前缀（用户自担风险的镜像），否则返回 GitHub 官方直链
     */
    public static String downloadUrlWithMirror(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isEmpty()) return "";
        var prefix = downloadMirrorPrefix();
        return prefix.isEmpty() ? downloadUrl : prefix + downloadUrl;
    }

    /**
     * 检查最新版本（同步阻塞，必须在后台线程调用）
     *
     * @param currentVersion 当前 versionName，如 "0.2.1"
     */
    public static Result check(String currentVersion) {
        var safeCurrent = norm(currentVersion);

        // 1. 用户自定义 update_url（覆盖内置链，失败则继续降级）
        var custom = ConfigHelp.getString("update_url", "");
        if (!custom.isEmpty()) {
            var r = tryFetch(custom, true, safeCurrent);
            if (r != null) return r;
        }

        // 2. GitHub 官方
        var r1 = tryFetch(GITHUB_LATEST_URL, true, safeCurrent);
        if (r1 != null) return r1;

        // 3. GitHub API 镜像（应用内静默请求，无浏览器警报问题）
        for (var mirror : CHECK_MIRRORS) {
            var r2 = tryFetch(mirror + GITHUB_LATEST_URL, false, safeCurrent);
            if (r2 != null) return r2;
        }

        return new Result(false, "", "", "", false);
    }

    /** 尝试单个源；成功返回 Result，失败/不可用返回 null 由上层继续降级 */
    private static Result tryFetch(String url, boolean primary, String currentVersion) {
        try {
            var request = new Request.Builder().url(url)
                    // GitHub API 对无 UA 请求返回 403；显式 UA 也有助于限流判定稳定
                    .header("User-Agent", "XpMiBackup/" + safeUa(currentVersion)
                            + " (+https://github.com/suileyan/xpmibackup_sly)")
                    .header("Accept", "application/vnd.github+json")
                    .build();
            try (var resp = client().newCall(request).execute()) {
                var code = resp.code();
                if (code == 404) {
                    // 主源 404 = 仓库无已发布 release → 视为无新版；镜像 404 不可靠，降级
                    if (primary) {
                        LogHelp.w(TAG, "update check 404 (no release) from " + hostOf(url));
                        return new Result(true, "", "", "", false);
                    }
                    LogHelp.d(TAG, "update mirror 404, fallback: " + hostOf(url));
                    return null;
                }
                if (code != 200) {
                    LogHelp.d(TAG, "update check HTTP " + code + " from " + hostOf(url));
                    return null;
                }
                var body = resp.body() != null ? resp.body().string() : "";
                return parseBody(url, body, currentVersion);
            }
        } catch (Exception e) {
            LogHelp.d(TAG, "update check failed: " + hostOf(url) + " " + e.getMessage());
            return null;
        }
    }

    /** 解析 releases/latest 响应；字段缺失/版本无法解析视为该源失败 */
    private static Result parseBody(String url, String body, String currentVersion) {
        try {
            var json = new JSONObject(body);
            var tag = json.optString("tag_name", "");
            if (tag.isEmpty()) {
                LogHelp.d(TAG, "update check missing tag_name from " + hostOf(url));
                return null;
            }
            var version = norm(tag);
            // 防误报：tag 解析不出数字（如 "latest"/"alpha"）→ 该源解析失败
            if (!hasNumericPart(version)) {
                LogHelp.w(TAG, "update tag not numeric, ignore: " + tag);
                return null;
            }
            var html = json.optString("html_url", "");
            if (html.isEmpty()) {
                html = RELEASE_PAGE_BASE + "/tag/" + tag;
            }
            var dl = "";
            var assets = json.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                dl = assets.optJSONObject(0).optString("browser_download_url", "");
            }
            var hasNew = compareVersions(version, currentVersion) > 0;
            return new Result(true, version, html, dl, hasNew);
        } catch (Exception e) {
            LogHelp.d(TAG, "update check parse failed: " + hostOf(url) + " " + e.getMessage());
            return null;
        }
    }

    // ========== 版本比较 ==========

    /** x.y.z 逐段数字比较：a<b→-1，a>b→1，相等→0；缺段补 0，非数字段按 0 */
    static int compareVersions(String a, String b) {
        var pa = norm(a).split("\\.");
        var pb = norm(b).split("\\.");
        var n = Math.max(pa.length, pb.length);
        for (var i = 0; i < n; i++) {
            var va = partAt(pa, i);
            var vb = partAt(pb, i);
            if (va < vb) return -1;
            if (va > vb) return 1;
        }
        return 0;
    }

    /** 归一化版本号：空→"0"，去 "v"/"V" 前缀，trim */
    private static String norm(String v) {
        if (v == null) return "0";
        var s = v.trim();
        if (s.length() > 1 && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            s = s.substring(1);
        }
        return s.isEmpty() ? "0" : s;
    }

    private static long partAt(String[] parts, int i) {
        if (i >= parts.length || parts[i] == null) return 0L;
        try {
            return Long.parseLong(parts[i].trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 是否至少有一个数字段（用于防误报校验） */
    private static boolean hasNumericPart(String version) {
        for (var part : version.split("\\.")) {
            try {
                Long.parseLong(part.trim());
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** UA 中的版本号安全化（去空白与换行） */
    private static String safeUa(String version) {
        var v = version == null ? "" : version;
        return v.replaceAll("[\\s\\r\\n/]", "");
    }

    /** 日志只打 host，避免完整 URL 过长 */
    private static String hostOf(String url) {
        try {
            var u = java.net.URI.create(url);
            return u.getHost() != null ? u.getHost() : url;
        } catch (Exception e) {
            return url.length() > 80 ? url.substring(0, 80) + "..." : url;
        }
    }

    // ========== OkHttp 单例 ==========

    private static volatile OkHttpClient sClient;

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (UpdateChecker.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }
}
