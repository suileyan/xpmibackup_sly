package com.suileyan.cloud;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NAS 配置方案模型
 * 仅存非敏感元数据与非敏感参数；密码/Cookie/Token 等敏感凭据走 EncryptedCredStore（按方案 id 隔离）
 */
public class Profile {

    public static final String TYPE_SMB = "smb";
    public static final String TYPE_WEBDAV = "webdav";
    public static final String TYPE_SCRIPT = "script";

    public final String id;
    /** 配置名称，由用户命名 */
    public final String name;
    /** 协议类型：smb | webdav | script | 未来网盘类型 */
    public final String type;
    public final long createdAt;
    /** 非敏感连接参数，如 smb_server/webdav_url 等 */
    public final Map<String, String> params;

    public Profile(String id, String name, String type, long createdAt, Map<String, String> params) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
        this.params = params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
    }

    public static Profile fromJson(JSONObject obj) {
        var params = new LinkedHashMap<String, String>();
        var paramObj = obj.optJSONObject("params");
        if (paramObj != null) {
            var keys = paramObj.keys();
            while (keys.hasNext()) {
                var k = keys.next();
                params.put(k, paramObj.optString(k, ""));
            }
        }
        return new Profile(
                obj.optString("id", ""),
                obj.optString("name", ""),
                obj.optString("type", ""),
                obj.optLong("createdAt", System.currentTimeMillis()),
                params);
    }

    public JSONObject toJson() {
        try {
            var obj = new JSONObject();
            obj.put("id", id);
            obj.put("name", name);
            obj.put("type", type);
            obj.put("createdAt", createdAt);
            var paramObj = new JSONObject();
            for (var e : params.entrySet()) {
                paramObj.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
            obj.put("params", paramObj);
            return obj;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
