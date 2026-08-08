package com.suileyan.cloud.provider;

import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.Profile;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.comm.CustomHttpFileHelp;

import java.util.List;
import java.util.Map;

/**
 * 自定义 HTTP 脚本 Provider
 * 委托 CustomHttpFileHelp；执行前设置脚本账号 ThreadLocal（按方案 id），
 * 使脚本内 stateGet/stateSet 按方案隔离存取
 */
public class ScriptProvider extends AbstractCloudProvider implements CloudProvider {

    public static final String TYPE = "script";

    public ScriptProvider(Profile profile) {
        super(profile);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    protected Map<String, String> sensitiveKeyMapping() {
        return Map.of("custom_script_b64", "custom_script_b64");
    }

    @Override
    public boolean testConnection() throws CloudException {
        return withScriptAccount(() -> CustomHttpFileHelp.testConnection());
    }

    @Override
    public List<String> listDirs() throws CloudException {
        return withScriptAccount(() -> CustomHttpFileHelp.listDirs());
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        return withScriptAccount(() -> CustomHttpFileHelp.listEntries(remoteDir));
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
        withScriptAccountVoid(() -> CustomHttpFileHelp.mkdirs(remoteDir));
    }

    @Override
    public String upload(String localPath, String remoteDir) throws CloudException {
        return withScriptAccount(() -> CustomHttpFileHelp.upload(localPath, remoteDir));
    }

    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException {
        withScriptAccountVoid(() -> CustomHttpFileHelp.uploadWithProgress(localPath, cb, remoteDir, taskId));
    }

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        return withScriptAccount(() -> CustomHttpFileHelp.downloadFile(remotePath, localPath));
    }

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        withScriptAccountVoid(() -> CustomHttpFileHelp.deleteDir(remoteDir));
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        // 脚本 deletePath 对文件/目录通用，复用 deleteDir 委托
        withScriptAccountVoid(() -> CustomHttpFileHelp.deleteDir(remotePath));
    }

    /** 执行时绑定脚本方案 id，供 stateGet/stateSet 按方案隔离 */
    private <T> T withScriptAccount(CloudCallable<T> action) throws CloudException {
        CustomHttpFileHelp.setScriptAccountId(id());
        try {
            return withAccount(action);
        } finally {
            CustomHttpFileHelp.setScriptAccountId(null);
        }
    }

    private void withScriptAccountVoid(CloudVoid action) throws CloudException {
        withScriptAccount(() -> {
            action.run();
            return null;
        });
    }
}
