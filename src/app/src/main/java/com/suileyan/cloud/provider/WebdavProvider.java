package com.suileyan.cloud.provider;

import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.Profile;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.comm.WebdavFileHelp;

import java.util.List;
import java.util.Map;

/**
 * WebDAV 存储 Provider
 * 委托 WebdavFileHelp
 */
public class WebdavProvider extends AbstractCloudProvider implements CloudProvider {

    public static final String TYPE = "webdav";

    public WebdavProvider(Profile profile) {
        super(profile);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    protected Map<String, String> sensitiveKeyMapping() {
        return Map.of("webdav_pass", "webdav_pass");
    }

    @Override
    public boolean testConnection() throws CloudException {
        return withAccount(() -> WebdavFileHelp.testConnection());
    }

    @Override
    public List<String> listDirs() throws CloudException {
        return withAccount(() -> WebdavFileHelp.listDirs());
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        return withAccount(() -> WebdavFileHelp.listEntries(remoteDir));
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
        withAccountVoid(() -> WebdavFileHelp.mkdirs(remoteDir));
    }

    @Override
    public String upload(String localPath, String remoteDir) throws CloudException {
        return withAccount(() -> WebdavFileHelp.upload(localPath, remoteDir));
    }

    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException {
        withAccountVoid(() -> WebdavFileHelp.uploadToWebdav(localPath, cb, remoteDir, taskId));
    }

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        return withAccount(() -> WebdavFileHelp.downloadFile(remotePath, localPath));
    }

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        withAccountVoid(() -> WebdavFileHelp.deleteDir(remoteDir));
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        withAccountVoid(() -> WebdavFileHelp.deleteFile(remotePath));
    }
}
