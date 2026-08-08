package com.suileyan.cloud;

/**
 * 通用重试策略
 * SMB/WebDAV/脚本共用的重试入口（SmbFileHelp 已接入）
 *
 * 修复：只对可恢复错误重试（网络抖动/5xx/429），
 * 认证过期（AUTH_EXPIRED）与本地错误（LOCAL）立即抛出，
 * 避免对认证端点重复无效请求触发风控/锁定；等待时间采用指数退避 + 抖动（带溢出保护）。
 */
public final class RetryPolicy {

    private RetryPolicy() {
    }

    /** 429 状态码精确匹配（NEW-L-02）：避免 "4290" 等包含串误判 */
    private static final java.util.regex.Pattern HTTP_429_PATTERN =
            java.util.regex.Pattern.compile("\\b429\\b");
    /** 5xx 状态码匹配：HTTP 500-599 */
    private static final java.util.regex.Pattern HTTP_5XX_PATTERN =
            java.util.regex.Pattern.compile("\\bHTTP (?:[5-9]\\d\\d)\\b");
    /** 退避位移上限：防止 attempt 过大时 1L << n 溢出（NEW-L-03） */
    private static final int MAX_BACKOFF_SHIFT = 16;

    @FunctionalInterface
    public interface Retryable<T> {
        T run() throws Exception;
    }

    /**
     * 是否允许对该异常重试
     * 网络错误与远端 5xx/429 可重试；认证过期/本地错误/参数错误不可重试
     */
    private static boolean retryable(Exception e) {
        if (e instanceof CloudException) {
            var kind = ((CloudException) e).kind();
            if (kind == CloudException.Kind.NETWORK) return true;
            if (kind == CloudException.Kind.REMOTE) {
                return hasRetryableHttpCode(e.getMessage());
            }
            return false;
        }
        // 非 CloudException：网络/IO 类可重试，本地文件类不重试
        if (hasRetryableHttpCode(e.getMessage())) {
            return true;
        }
        // 遍历 cause 链：SMB 等把 IOException 包装成 RuntimeException（NEW-L-07）
        var t = (Throwable) e;
        while (t != null) {
            if (t instanceof java.io.IOException || t instanceof java.net.SocketException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 消息中是否含 429 或 5xx HTTP 状态码（精确词边界匹配） */
    private static boolean hasRetryableHttpCode(String message) {
        if (message == null) return false;
        var m = message.toUpperCase(java.util.Locale.ROOT);
        return HTTP_429_PATTERN.matcher(m).find() || HTTP_5XX_PATTERN.matcher(m).find();
    }

    public static <T> T retry(int maxAttempts, long baseSleepMillis, Retryable<T> action) throws Exception {
        var attempts = Math.max(1, maxAttempts);
        var lastError = (Exception) null;
        for (var attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.run();
            } catch (Exception e) {
                lastError = e;
                if (!retryable(e)) {
                    throw e;
                }
                if (attempt < attempts && baseSleepMillis > 0) {
                    // 指数退避 + 抖动：base * 2^(attempt-1) ± 20%，位移封顶防溢出
                    var shift = Math.min(attempt - 1, MAX_BACKOFF_SHIFT);
                    var exp = Math.min(baseSleepMillis * (1L << shift), 30_000L);
                    var jitter = exp / 5;
                    var sleep = exp + (long) (Math.random() * 2 * jitter - jitter);
                    try {
                        Thread.sleep(Math.max(0, Math.min(sleep, 30_000L)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        // maxAttempts 至少为 1，此处不可能为 null
        throw lastError;
    }
}
