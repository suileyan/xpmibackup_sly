package com.suileyan.cloud.provider;

import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.Profile;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.comm.SmbFileHelp;

import java.util.List;
import java.util.Map;

/**
 * SMB 存储 Provider
 * 委托 SmbFileHelp，补齐 mkdirs/deleteFile 公开能力
 */
public class SmbProvider extends AbstractCloudProvider implements CloudProvider {

    public static final String TYPE = "smb";

    public SmbProvider(Profile profile) {
        super(profile);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    protected Map<String, String> sensitiveKeyMapping() {
        return Map.of("smb_pass", "smb_pass");
    }

    @Override
    public boolean testConnection() throws CloudException {
        return withAccount(() -> SmbFileHelp.testConnection());
    }

    @Override
    public List<String> listDirs() throws CloudException {
        return withAccount(() -> SmbFileHelp.listDirs());
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        return withAccount(() -> SmbFileHelp.listEntries(remoteDir));
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
        withAccountVoid(() -> SmbFileHelp.mkdirs(remoteDir));
    }

    @Override
    public String upload(String localPath, String remoteDir) throws CloudException {
        return withAccount(() -> SmbFileHelp.upload(localPath, remoteDir));
    }

    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException {
        withAccountVoid(() -> SmbFileHelp.uploadToSmb(localPath, cb, remoteDir, taskId));
    }

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        return withAccount(() -> SmbFileHelp.downloadFile(remotePath, localPath));
    }

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        withAccountVoid(() -> SmbFileHelp.deleteDir(remoteDir));
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        withAccountVoid(() -> SmbFileHelp.deleteFile(remotePath));
    }
}
