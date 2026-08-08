package com.suileyan.cloud;

/**
 * 云端存储统一异常
 * 区分认证过期/网络/远端/本地错误，供上层做重试与提示
 */
public class CloudException extends Exception {

    public enum Kind {
        /** 登录态过期，可尝试 refresh() 后重试 */
        AUTH_EXPIRED,
        /** 网络层错误 */
        NETWORK,
        /** 远端服务器错误 */
        REMOTE,
        /** 本地文件或参数错误 */
        LOCAL
    }

    private final Kind kind;

    public CloudException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CloudException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public CloudException(Kind kind, Throwable cause) {
        // cause 的 message 可能为 null（如 UnknownHostException），回退为异常类名（LOW-17）
        super(cause != null && cause.getMessage() != null ? cause.getMessage()
                : (cause != null ? cause.getClass().getSimpleName() : "unknown error"), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isAuthExpired() {
        return kind == Kind.AUTH_EXPIRED;
    }
}
