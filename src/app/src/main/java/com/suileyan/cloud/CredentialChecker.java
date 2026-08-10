package com.suileyan.cloud;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 云盘账号凭证有效性检查（凭证预处理）
 *
 * 在云盘账号页对每个账号做连通性校验，把"备份时才暴露的登录失效"提前到列表页可见：
 * - 缺少必要凭据 → INVALID（从未登录 / 凭据被清）
 * - 连接成功 → VALID
 * - AUTH_EXPIRED（401 且刷新失败）→ INVALID（需重新登录）
 * - 光鸭：连接成功但缺 refresh_token → WEAK（access 过期后无法续期，建议重新登录）
 * - 网络/远端异常 → ERROR（不判定为凭证失效，避免误报）
 *
 * 结果带 60s TTL 缓存，避免每次进页面都打网络请求；登录成功/删除账号时显式失效。
 */
public final class CredentialChecker {

    private static final long CACHE_TTL_MS = 60 * 1000L;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private CredentialChecker() {
    }

    /** 凭证状态 */
    public enum Status {
        /** 检查中 */
        CHECKING,
        /** 凭证有效 */
        VALID,
        /** 可连接但缺刷新凭据，建议重新登录 */
        WEAK,
        /** 凭证失效/缺失，需重新登录 */
        INVALID,
        /** 网络或服务器异常，无法验证 */
        ERROR
    }

    /**
     * 检查账号凭证状态（含缓存）
     */
    public static Status check(CloudAccount account) {
        if (account == null || account.id == null || account.id.isEmpty()) {
            return Status.ERROR;
        }
        var cached = CACHE.get(account.id);
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_TTL_MS) {
            return cached.status;
        }
        var status = doCheck(account);
        CACHE.put(account.id, new Cached(status, System.currentTimeMillis()));
        return status;
    }

    /** 失效单个账号缓存（重新登录/删除后调用） */
    public static void invalidate(String accountId) {
        if (accountId != null) {
            CACHE.remove(accountId);
        }
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    private static Status doCheck(CloudAccount account) {
        var provider = ProviderRegistry.forAccount(account);
        if (provider == null) {
            return Status.ERROR;
        }
        // 必要凭据缺失：未登录或凭据被清
        if (!provider.isLoggedIn()) {
            return Status.INVALID;
        }
        var conn = test(provider);
        if (conn != Status.VALID) {
            return conn;
        }
        // 光鸭：access_token 有效但缺 refresh_token，过期后将无法自动续期
        if (CloudAccount.PROVIDER_GUANGYA.equals(account.provider)
                && EncryptedCredStore.get(account.id, "refresh_token").isEmpty()) {
            return Status.WEAK;
        }
        // 天翼：refresh_token 与 SSON 同时缺失才无法续期（任一存在都能自动换会话）
        if (CloudAccount.PROVIDER_189.equals(account.provider)
                && EncryptedCredStore.get(account.id, "refresh_token").isEmpty()
                && EncryptedCredStore.get(account.id, "sson_cookie").isEmpty()) {
            return Status.WEAK;
        }
        return Status.VALID;
    }

    /** 连通性测试：成功=VALID；认证过期=INVALID；其余异常=ERROR */
    private static Status test(CloudProvider provider) {
        try {
            return provider.testConnection() ? Status.VALID : Status.INVALID;
        } catch (CloudException e) {
            return e.isAuthExpired() ? Status.INVALID : Status.ERROR;
        } catch (Exception e) {
            return Status.ERROR;
        }
    }

    private static class Cached {
        final Status status;
        final long at;

        Cached(Status status, long at) {
            this.status = status;
            this.at = at;
        }
    }
}
