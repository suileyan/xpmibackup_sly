package com.suileyan.cloud;

import java.util.Map;

/**
 * 登录结果
 * 阶段一：SMB/WebDAV/脚本凭据直连，无需登录流程，返回 NOT_SUPPORTED
 */
public class LoginState {

    public static final LoginState NOT_SUPPORTED = new LoginState(false, 0L, "login not supported");

    /** 是否登录成功 */
    public final boolean success;
    /** 登录态过期时间戳，0 表示不过期 */
    public final long expiresAt;
    /** 结果描述 */
    public final String message;

    public LoginState(boolean success, long expiresAt, String message) {
        this.success = success;
        this.expiresAt = expiresAt;
        this.message = message;
    }
}
