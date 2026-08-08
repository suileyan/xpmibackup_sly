package com.suileyan.cloud;

import java.util.List;

/**
 * 云端存储统一接口
 * 约定：只处理整文件上传/下载；切片/manifest 由 CloudFileHelp 层统一处理
 */
public interface CloudProvider {

    /** 账号唯一标识 */
    String id();

    /** 类型：smb|webdav|script|baidu|aliyun|quark|yun139|tianyi|onedrive */
    String type();

    /** 展示名称 */
    String displayName();

    boolean testConnection() throws CloudException;

    List<String> listDirs() throws CloudException;

    List<RemoteEntry> listEntries(String remoteDir) throws CloudException;

    /** 递归创建远端目录 */
    void mkdirs(String remoteDir) throws CloudException;

    /** 上传单个普通文件（无进度） */
    String upload(String localPath, String remoteDir) throws CloudException;

    /** 上传单个普通文件并回调进度 */
    void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException;

    /** 下载单个远端文件到本地路径 */
    String downloadFile(String remotePath, String localPath) throws CloudException;

    /** 删除远端目录及其所有内容 */
    void deleteDir(String remoteDir) throws CloudException;

    /** 删除远端单个文件 */
    void deleteFile(String remotePath) throws CloudException;

    /** 登录；阶段一返回 NOT_SUPPORTED */
    default LoginState login(LoginContext ctx) {
        return LoginState.NOT_SUPPORTED;
    }

    /** 静默刷新登录态；不支持返回 false */
    default boolean refresh() {
        return false;
    }

    /** 当前是否已登录（凭据是否可用） */
    default boolean isLoggedIn() {
        return true;
    }
}
