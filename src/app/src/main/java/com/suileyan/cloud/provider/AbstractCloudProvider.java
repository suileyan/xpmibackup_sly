package com.suileyan.cloud.provider;

import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.Profile;
import com.suileyan.comm.ConfigHelp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider 公共基类
 * 携带配置方案（Profile）：非敏感参数来自 profile.params，
 * 敏感凭据从 EncryptedCredStore 按 profile.id 读取；
 * 用 ConfigHelp.withAccount() 的 ThreadLocal 参数覆盖注入，
 * 让底层 SmbFileHelp/WebdavFileHelp/CustomHttpFileHelp 读到正确参数，多方案互不串扰
 */
public abstract class AbstractCloudProvider {

    protected final Profile profile;

    protected AbstractCloudProvider(Profile profile) {
        this.profile = profile;
    }

    public String id() {
        return profile != null ? profile.id : "";
    }

    public String displayName() {
        return profile != null && profile.name != null && !profile.name.isEmpty() ? profile.name : type();
    }

    /** 协议类型：smb | webdav | script | 未来网盘类型 */
    public abstract String type();

    /** 敏感凭据 key → config 覆盖 key 的映射；由子类声明 */
    protected abstract Map<String, String> sensitiveKeyMapping();

    /**
     * 组装参数覆盖表：profile 非敏感参数 + 加密存储敏感凭据
     */
    public Map<String, String> overrides() {
        var map = new LinkedHashMap<String, String>();
        if (profile != null) {
            map.putAll(profile.params);
        }
        for (var entry : sensitiveKeyMapping().entrySet()) {
            var value = EncryptedCredStore.get(id(), entry.getKey());
            if (value != null && !value.isEmpty()) {
                map.put(entry.getValue(), value);
            }
        }
        return map;
    }

    /** 在参数覆盖下执行动作 */
    protected <T> T withAccount(CloudCallable<T> action) throws CloudException {
        try {
            return ConfigHelp.withAccount(overrides(), action::call);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** 在参数覆盖下执行无返回值动作 */
    protected void withAccountVoid(CloudVoid action) throws CloudException {
        withAccount(() -> {
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    protected interface CloudCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    protected interface CloudVoid {
        void run() throws Exception;
    }
}
