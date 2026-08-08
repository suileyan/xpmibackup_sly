package com.suileyan.cloud;

import org.json.JSONObject;

import com.suileyan.comm.LogHelp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 云盘账号展示工具
 * - 统一生成「网盘名 · 脱敏账号」的显示文本（列表与备份下拉共用），区分同网盘不同账户
 * - 光鸭 access_token 按 JWT 解码提取账号标识（uid/手机号等），老账号自动回填
 */
public final class AccountDisplay {

    private static final String TAG = "XpMiBackup";

    private AccountDisplay() {
    }

    /** 完整展示文本：网盘名 + 脱敏账号，如「139 云盘 · 138****1234」 */
    public static String display(CloudAccount a) {
        if (a == null) return "";
        var label = providerLabel(a.provider);
        var info = accountInfo(a);
        if (info == null || info.isEmpty()) {
            return label;
        }
        return label + " · " + info;
    }

    /** 脱敏账号信息（仅读取，不修改持久化状态），无账号信息返回空串 */
    public static String accountInfo(CloudAccount a) {
        if (a == null) return "";
        var info = a.account;
        if (info == null || info.isEmpty()) {
            info = uidOnly(a);
        }
        if (info == null || info.isEmpty()) return "";
        return mask(info);
    }

    /**
     * 显式回填老账号 uid：account 字段为空时从加密存储的 access_token 解码并写盘
     * 只在 UI 刷新等明确时机调用，展示函数本身不再触发写盘（HIGH-04）
     */
    public static void healAccountUidIfNeeded(CloudAccount a) {
        if (a == null || !CloudAccount.PROVIDER_GUANGYA.equals(a.provider)) return;
        if (a.account != null && !a.account.isEmpty()) return;
        var uid = uidOnly(a);
        if (!uid.isEmpty()) {
            try {
                CloudAccountStore.add(new CloudAccount(a.id, a.provider, uid, a.name, a.createdAt));
            } catch (Exception e) {
                LogHelp.e(TAG, "heal account uid failed", e);
            }
        }
    }

    /** 只读解析光鸭账号 uid，不写盘 */
    private static String uidOnly(CloudAccount a) {
        if (!CloudAccount.PROVIDER_GUANGYA.equals(a.provider)) return "";
        try {
            var token = EncryptedCredStore.get(a.id, "access_token");
            return decodeUid(token);
        } catch (Exception e) {
            return "";
        }
    }

    /** 网盘类型名 */
    public static String providerLabel(String provider) {
        if (CloudAccount.PROVIDER_139.equals(provider)) return "139 云盘";
        if (CloudAccount.PROVIDER_GUANGYA.equals(provider)) return "光鸭云盘";
        if (CloudAccount.PROVIDER_QUARK.equals(provider)) return "夸克云盘";
        return provider == null ? "" : provider;
    }

    /**
     * 脱敏：手机号/长数字 → 138****1234；一般 id → 首2***尾2；短值（≤3）不脱敏
     */
    public static String mask(String raw) {
        if (raw == null) return "";
        raw = raw.trim();
        if (raw.isEmpty()) return "";
        if (raw.length() <= 3) return raw;
        if (raw.length() >= 7 && raw.matches("\\d{7,}")) {
            return raw.substring(0, 3) + "****" + raw.substring(raw.length() - 4);
        }
        if (raw.length() <= 6) {
            return raw.substring(0, 1) + "***" + raw.substring(raw.length() - 1);
        }
        return raw.substring(0, 2) + "***" + raw.substring(raw.length() - 2);
    }

    /**
     * 从 OAuth access_token 解码账号标识：按 JWT 三段式（header.payload.signature）解析 payload，
     * 提取 uid/sub/userId/phone 等常见字段；非 JWT 返回空
     */
    public static String decodeUid(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) return "";
        try {
            var parts = accessToken.split("\\.");
            if (parts.length < 2) return "";
            var payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            var json = new JSONObject(payload);
            for (var key : new String[]{"uid", "sub", "userId", "user_id", "id", "phone", "mobile", "username"}) {
                var v = json.optString(key, "");
                if (!v.isEmpty()) return v;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 老账号自愈逻辑已移至显式方法 healAccountUidIfNeeded，展示函数不再写盘（HIGH-04） */
}
