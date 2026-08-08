package com.suileyan.cloud;

import com.suileyan.cloud.provider.GuangyaProvider;
import com.suileyan.cloud.provider.Pan123Provider;
import com.suileyan.cloud.provider.QuarkProvider;
import com.suileyan.cloud.provider.ScriptProvider;
import com.suileyan.cloud.provider.SmbProvider;
import com.suileyan.cloud.provider.WebdavProvider;
import com.suileyan.cloud.provider.Yun139Provider;
import com.suileyan.comm.LogHelp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider 实例注册表
 * 分发规则：优先「当前云盘备份目标」（备份页云盘方式设置的云盘账号，经 BackupTarget 跨进程持久化）；
 * 否则按配置方案（Profile）分发；激活目标/方案变更时失效缓存
 */
public final class ProviderRegistry {

    private static final String TAG = "XpMiBackup";
    private static final Map<String, CloudProvider> CACHE = new ConcurrentHashMap<>();

    private ProviderRegistry() {
    }

    /** 设置云盘备份目标（备份页选择云盘账号后调用，持久化供 backup 进程读取） */
    public static void setCloudTarget(String cloudAccountId) {
        if (cloudAccountId == null || cloudAccountId.isEmpty()) {
            clearCloudTarget();
            return;
        }
        BackupTarget.setCloud(cloudAccountId);
        CACHE.remove("cloud:" + cloudAccountId);
        LogHelp.i(TAG, "cloud target set: " + cloudAccountId);
    }

    /** 清除云盘备份目标，回到 NAS 方案模式 */
    public static void clearCloudTarget() {
        BackupTarget.clear();
    }

    /** 当前是否为云盘备份模式 */
    public static boolean isCloudTarget() {
        return BackupTarget.isCloud();
    }

    /** 当前生效目标类型（云盘账号 or 激活方案） */
    public static String activeType() {
        var cloudId = BackupTarget.cloudAccountId();
        if (cloudId != null) {
            var account = CloudAccountStore.get(cloudId);
            if (account != null && CloudAccount.PROVIDER_GUANGYA.equals(account.provider)) {
                return GuangyaProvider.TYPE;
            }
            if (account != null && CloudAccount.PROVIDER_139.equals(account.provider)) {
                return Yun139Provider.TYPE;
            }
            if (account != null && CloudAccount.PROVIDER_QUARK.equals(account.provider)) {
                return QuarkProvider.TYPE;
            }
            if (account != null && CloudAccount.PROVIDER_123.equals(account.provider)) {
                return Pan123Provider.TYPE;
            }
            // 未知/已删除账号：返回空串，由调用方回退
            return "";
        }
        var profile = ProfileStore.getActive();
        return profile != null ? profile.type : "";
    }

    /**
     * 获取当前备份目标的 Provider：优先云盘账号，其次激活方案
     * getter 不修改持久化状态（HIGH-04）：云盘账号缺失时不再隐式清除备份目标，
     * 而是记录日志并回退到激活方案，由上层决定是否清理目标
     */
    public static CloudProvider active() {
        var cloudId = BackupTarget.cloudAccountId();
        if (cloudId != null) {
            var provider = cloudProvider(cloudId);
            if (provider != null) return provider;
            LogHelp.w(TAG, "cloud account missing (id=" + cloudId + "), fallback to active profile");
        }
        var profile = ProfileStore.getActive();
        if (profile == null) {
            throw new IllegalStateException("no active profile");
        }
        return get(profile.id);
    }

    /** 按方案 id 获取 Provider，缓存未命中时构造 */
    public static CloudProvider get(String profileId) {
        if (profileId == null || profileId.isEmpty()) {
            throw new IllegalStateException("empty profile id");
        }
        var cached = CACHE.get(profileId);
        if (cached != null) return cached;

        var profile = ProfileStore.get(profileId);
        if (profile == null) {
            throw new IllegalStateException("profile not found: " + profileId);
        }
        CloudProvider provider;
        switch (profile.type) {
            case SmbProvider.TYPE:
                provider = new SmbProvider(profile);
                break;
            case WebdavProvider.TYPE:
                provider = new WebdavProvider(profile);
                break;
            case ScriptProvider.TYPE:
                provider = new ScriptProvider(profile);
                break;
            default:
                throw new IllegalStateException("unsupported profile type: " + profile.type);
        }
        CACHE.put(profileId, provider);
        return provider;
    }

    /** 按云盘账号 id 获取 Provider（凭据/节点从加密存储读取）。
     * 不缓存：云盘账号可能重新登录（Authorization/host 变化），每次重新构造读取最新凭据 */
    private static CloudProvider cloudProvider(String cloudAccountId) {
        var account = CloudAccountStore.get(cloudAccountId);
        if (account == null) {
            LogHelp.e(TAG, "cloud account not found: " + cloudAccountId);
            return null;
        }
        return forAccount(account);
    }

    /** 按云盘账号构造 Provider（校验/测试用，每次新构造读取最新凭据，不缓存） */
    public static CloudProvider forAccount(CloudAccount account) {
        if (account == null) {
            return null;
        }
        if (CloudAccount.PROVIDER_139.equals(account.provider)) {
            return new Yun139Provider(account);
        }
        if (CloudAccount.PROVIDER_GUANGYA.equals(account.provider)) {
            return new GuangyaProvider(account);
        }
        if (CloudAccount.PROVIDER_QUARK.equals(account.provider)) {
            return new QuarkProvider(account);
        }
        if (CloudAccount.PROVIDER_123.equals(account.provider)) {
            return new Pan123Provider(account);
        }
        LogHelp.e(TAG, "unsupported cloud provider: " + account.provider);
        return null;
    }

    /** 方案参数或凭据变更后失效缓存 */
    public static void invalidate(String profileId) {
        if (profileId != null) {
            CACHE.remove(profileId);
        }
    }

    public static void invalidateAll() {
        CACHE.clear();
    }
}
