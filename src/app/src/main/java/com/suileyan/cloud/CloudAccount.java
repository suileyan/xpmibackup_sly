package com.suileyan.cloud;

import org.json.JSONObject;

/**
 * 云盘账号模型
 * 仅存非敏感元数据；Authorization/token 等敏感凭据走 EncryptedCredStore（按账号 id 隔离）
 */
public class CloudAccount {

    /** 支持的云盘提供方 */
    public static final String PROVIDER_139 = "139";
    public static final String PROVIDER_GUANGYA = "guangya";
    public static final String PROVIDER_QUARK = "quark";

    public final String id;
    public final String provider;
    /** 登录账号（139 为手机号） */
    public final String account;
    /** 显示名称 */
    public final String name;
    public final long createdAt;

    public CloudAccount(String id, String provider, String account, String name, long createdAt) {
        this.id = id;
        this.provider = provider;
        this.account = account;
        this.name = name;
        this.createdAt = createdAt;
    }

    public static CloudAccount fromJson(JSONObject obj) {
        return new CloudAccount(
                obj.optString("id", ""),
                obj.optString("provider", ""),
                obj.optString("account", ""),
                obj.optString("name", ""),
                obj.optLong("createdAt", System.currentTimeMillis()));
    }

    public JSONObject toJson() {
        try {
            var obj = new JSONObject();
            obj.put("id", id);
            obj.put("provider", provider);
            obj.put("account", account);
            obj.put("name", name);
            obj.put("createdAt", createdAt);
            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
