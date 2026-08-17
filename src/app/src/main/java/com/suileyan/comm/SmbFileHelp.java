package com.suileyan.comm;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.share.DiskShare;

import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;

import android.os.Build;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * SMB文件操作工具类
 * 上传、下载、列表、恢复辅助均在此类实现
 */
public class SmbFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final int BUFFER_SIZE = 1048576; // 1MB

    // ========== SMB会话管理 ==========

    /** SMB连接资源封装，支持 try-with-resources 自动关闭 */
    private static class SmbSession implements AutoCloseable {
        final DiskShare share;
        final String backupPath;
        final com.hierynomus.smbj.session.Session session;
        final com.hierynomus.smbj.connection.Connection connection;

        SmbSession(String shareName) {
            var cfg = ConfigHelp.load();
            var server = cfg.optString("smb_server", "");
            var port = cfg.optInt("smb_port", 445);
            var user = cfg.optString("smb_user", "");
            var pass = cfg.optString("smb_pass", "");
            this.backupPath = cfg.optString("backup_path", "");

            try {
                LogHelp.i(TAG, "SMB connecting " + server + ":" + port + " share=" + shareName);
                var config = SmbConfig.builder().build();
                var client = new SMBClient(config);
                this.connection = client.connect(server, port);
                this.session = this.connection.authenticate(new AuthenticationContext(user, pass.toCharArray(), ""));
                this.share = (DiskShare) session.connectShare(shareName);
                LogHelp.i(TAG, "SMB connected " + server + ":" + port + " share=" + shareName);
            } catch (Exception e) {
                // 只记录服务器/端口/共享名，密码与用户名绝不落日志
                LogHelp.e(TAG, "SMB connect failed: " + server + ":" + port + " share=" + shareName, e);
                // 附稳定错误码（ERR_SMB_*），供 UI 层映射为本地化提示（共享不存在/无权限等）
                var code = smbErrorCode(e);
                // Android 17+ 本地网络保护（HIGH-23）：targetSdk 37 进程默认阻止局域网访问。
                // 连接类失败 + 局域网地址时升级为专项错误码，UI 层提示可能被系统拦截（避免误报用"可能"措辞）
                // 注意：错误码仅含字母数字（UI 层按 isLetterOrDigit 截取），不得带下划线
                if ("ERR_SMB_CONNECT".equals(code) && isLocalNetworkBlockPossible(server)) {
                    code = "ERR_SMB_LOCALNET";
                }
                throw new RuntimeException("SMB connect failed: " + code, e);
            }
        }

        /**
         * Android 17+ 本地网络保护检测（HIGH-23）：
         * 仅当 ① 系统为 Android 17+ 且 ② 目标是局域网/环回地址时返回 true。
         * 不做权限强校验（宿主进程权限由小米备份 APK 决定，无法可靠读取），
         * 由 UI 层以"可能"措辞提示，避免服务器真实宕机时误导。
         */
        private static boolean isLocalNetworkBlockPossible(String server) {
            if (Build.VERSION.SDK_INT < 37) return false;
            try {
                var addr = InetAddress.getByName(server);
                return addr.isSiteLocalAddress() || addr.isLoopbackAddress();
            } catch (Exception e) {
                // 主机名解析失败（DNS 异常等）不判定为本地网络受限，避免误报
                return false;
            }
        }

        /** 遍历异常链分类 SMB 连接失败原因，返回稳定错误码（UI 层据此映射多语言提示） */
        private static String smbErrorCode(Throwable t) {
            var cur = t;
            while (cur != null) {
                if (cur instanceof SMBApiException) {
                    var code = ((SMBApiException) cur).getStatusCode();
                    if (code == NtStatus.STATUS_BAD_NETWORK_NAME.getValue()
                            || code == NtStatus.STATUS_BAD_NETWORK_PATH.getValue()) {
                        return "ERR_SMB_SHARE_NOT_FOUND";
                    }
                    if (code == NtStatus.STATUS_ACCESS_DENIED.getValue()) {
                        return "ERR_SMB_SHARE_NO_PERMISSION";
                    }
                    return "ERR_SMB_REJECTED";
                }
                cur = cur.getCause();
            }
            return "ERR_SMB_CONNECT";
        }

        /** 创建远程目录 */
        void mkdir(String path) {
            if (path != null && !path.isEmpty()) {
                try { share.mkdir(path); } catch (Exception ignored) {}
            }
        }

        /** 递归创建远程目录链 */
        void mkdirs(String remoteDir) {
            mkdirsRecursive(backupPath);
            mkdirsRecursive(remoteDir);
        }

        /** 按路径段逐级创建目录 */
        void mkdirsRecursive(String path) {
            if (path == null || path.isEmpty()) {
                return;
            }
            var current = "";
            for (var part : path.replace('\\', '/').split("/")) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                current = current.isEmpty() ? part : current + "/" + part;
                mkdir(current);
            }
        }

        @Override
        public void close() {
            try { share.close(); } catch (Exception ignored) {}
            try { session.close(); } catch (Exception ignored) {}
            try { connection.close(); } catch (Exception ignored) {}
        }
    }

    // ========== 公共方法 ==========

    /** 测试SMB连接是否可达 */
    public static boolean testConnection() throws Exception {
        LogHelp.i(TAG, "SMB test connection start");
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            LogHelp.i(TAG, "SMB test connection OK");
            return true;
        }
    }

    /** 列出backup_path目录中的备份子目录名 */
    public static List<String> listDirs() throws Exception {
        var dirs = new ArrayList<String>();
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            for (var entry : s.share.list(s.backupPath)) {
                var name = entry.getFileName();
                if (!name.equals(".") && !name.equals("..") && (entry.getFileAttributes() & 0x10) != 0) {
                    dirs.add(name);
                }
            }
            LogHelp.i(TAG, "SMB listDirs OK path=" + s.backupPath + " count=" + dirs.size());
        }
        return dirs;
    }

    /** 列出指定SMB目录下的文件条目，路径相对于共享根目录 */
    public static List<RemoteEntry> listEntries(String remoteDir) throws Exception {
        var entries = new ArrayList<RemoteEntry>();
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            for (var entry : s.share.list(remoteDir)) {
                var name = entry.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                var isDir = (entry.getFileAttributes() & 0x10) != 0;
                var size = isDir ? 0L : entry.getEndOfFile();
                var modified = entry.getLastWriteTime() != null
                    ? entry.getLastWriteTime().toEpochMillis()
                    : System.currentTimeMillis();
                entries.add(new RemoteEntry(name, size, isDir, modified));
            }
            LogHelp.i(TAG, "SMB listEntries OK dir=" + remoteDir + " count=" + entries.size());
        }
        return entries;
    }

    /** 递归创建远端目录链（供 Provider 统一 mkdirs 调用） */
    public static void mkdirs(String remoteDir) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            s.mkdirs(remoteDir);
        }
    }

    /** 删除远端单个文件（供 Provider 统一 deleteFile 调用） */
    public static void deleteFile(String remotePath) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            try {
                s.share.rm(remotePath);
            } catch (Exception e) {
                LogHelp.w(TAG, "SMB delete file failed: " + remotePath, e);
            }
        }
    }

    /** 删除远端目录及其所有内容 */
    public static void deleteDir(String remoteDir) throws Exception {
        LogHelp.i(TAG, "SMB delete dir start: " + remoteDir);
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var path = normalizeDeletePath(s.backupPath, remoteDir);
            deleteDirRecursive(s.share, path);
            LogHelp.i(TAG, "SMB delete dir done: " + remoteDir);
        }
    }

    /**
     * 批量删除远端目录（HIGH-10）
     * 复用同一个 SMB Session，避免每个目录都重新握手认证；
     * 单个目录失败只记日志，不影响其余目录删除
     */
    public static void deleteDirs(java.util.List<String> remoteDirs) throws Exception {
        if (remoteDirs == null || remoteDirs.isEmpty()) return;
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            for (var remoteDir : remoteDirs) {
                try {
                    var path = normalizeDeletePath(s.backupPath, remoteDir);
                    deleteDirRecursive(s.share, path);
                } catch (Exception e) {
                    LogHelp.w(TAG, "SMB batch delete failed: " + remoteDir, e);
                }
            }
        }
    }

    /** 递归删除SMB目录
     * 兼容完整云端路径和相对备份目录名两种删除调用
     */
    private static String normalizeDeletePath(String backupPath, String remoteDir) {
        if (remoteDir == null || remoteDir.isEmpty()) {
            return backupPath == null ? "" : backupPath;
        }
        var path = remoteDir.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        // 剔除 . 与 .. 段，防止路径穿越删到共享根之外（MED-07）
        path = cleanPathSegments(path);
        if (backupPath == null || backupPath.isEmpty()) {
            return path;
        }
        // 规范化 backupPath 尾部斜杠（NEW-L-18），保证前缀匹配一致
        var base = backupPath.replace('\\', '/');
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return path;
        }
        if (path.startsWith(base + "/") || path.equals(base)) {
            return path;
        }
        return base + "/" + path;
    }

    /** 剔除路径中的 . 与 .. 段 */
    private static String cleanPathSegments(String path) {
        if (path == null || path.isEmpty()) return "";
        var cleaned = new StringBuilder();
        for (var segment : path.split("/")) {
            if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            if (cleaned.length() > 0) cleaned.append("/");
            cleaned.append(segment);
        }
        return cleaned.toString();
    }

    private static void deleteDirRecursive(DiskShare share, String path) {
        try {
            var entries = share.list(path);
            for (var entry : entries) {
                var name = entry.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                var childPath = path + "/" + name;
                if ((entry.getFileAttributes() & 0x10) != 0) {
                    deleteDirRecursive(share, childPath);
                } else {
                    try { share.rm(childPath); } catch (Exception ignored) {}
                }
            }
            share.rmdir(path, false);
        } catch (Exception e) {
            LogHelp.w(TAG, "SMB recursive delete failed: " + path, e);
        }
    }

    // ========== 上传 ==========

    /** 上传本地文件到SMB共享（无进度回调），失败自动重试3次（统一走 RetryPolicy，NEW-L-07） */
    public static String upload(String localPath, String remoteDir) throws Exception {
        return com.suileyan.cloud.RetryPolicy.retry(3, 2000L, () -> doUpload(localPath, remoteDir));
    }

    private static String doUpload(String localPath, String remoteDir) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var localFile = new File(localPath);
            if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localPath);

            LogHelp.i(TAG, "SMB upload start: " + localPath + " size=" + localFile.length());
            s.mkdirs(remoteDir);
            var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();
            uploadWholeFileToSmb(s.share, localFile, remotePath, null, "", 0L, localFile.length());
            LogHelp.i(TAG, "SMB upload done: " + remotePath + " size=" + localFile.length());
            return "OK: " + remotePath + " (" + localFile.length() + " bytes)";
        }
    }

    /** 上传文件到SMB并实时回调进度（备份用），失败自动重试3次（统一走 RetryPolicy，NEW-L-07） */
    public static void uploadToSmb(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws Exception {
        com.suileyan.cloud.RetryPolicy.retry(3, 2000L, () -> {
            doUploadToSmb(localPath, cb, remoteDir, taskId);
            return null;
        });
    }

    private static void doUploadToSmb(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var localFile = new File(localPath);
            if (!localFile.exists()) throw new FileNotFoundException("file not found: " + localPath);

            var fileSize = localFile.length();
            LogHelp.i(TAG, "SMB backup upload start: " + localPath + " size=" + fileSize);

            s.mkdirs(remoteDir);
            var remotePath = (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + localFile.getName();
            uploadWholeFileToSmb(s.share, localFile, remotePath, cb, taskId, 0L, fileSize);

            LogHelp.i(TAG, "SMB backup upload done: " + remotePath + " size=" + fileSize);
            if (cb != null) cb.onFinish(taskId, 0, "success");
        }
    }

    /**
     * SMB整文件上传，CloudFileHelp会在进入这里前统一处理切片
     */
    private static long uploadWholeFileToSmb(DiskShare share, File localFile, String remotePath, ProgressCallback cb, String taskId, long baseWritten, long totalSize) throws Exception {
        var smbFile = openSmbOutput(share, remotePath);
        try (var fis = new FileInputStream(localFile); var fos = smbFile.getOutputStream()) {
            return streamCopyWithProgress(fis, fos, cb, taskId, baseWritten, totalSize);
        } finally {
            try { smbFile.close(); } catch (Exception ignored) {}
        }
    }

    // ========== 下载 ==========

    /** 下载单个文件从SMB到本地 */
    public static String downloadFile(String remotePath, String localPath) throws Exception {
        try (var s = new SmbSession(ConfigHelp.getString("smb_share", ""))) {
            var localFile = new File(localPath);
            var parent = localFile.getParentFile();
            if (parent != null) parent.mkdirs();

            LogHelp.i(TAG, "SMB download start: " + remotePath + " -> " + localPath);
            var smbFile = s.share.openFile(remotePath,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.SYNCHRONIZE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));

            // try-with-resources保证异常时输入输出流和SMB文件句柄都被关闭
            try (var is = smbFile.getInputStream(); var fos = new FileOutputStream(localFile)) {
                var total = streamCopy(is, fos);
                LogHelp.i(TAG, "SMB download done: " + remotePath + " bytes=" + total);
                return "OK: " + remotePath + " -> " + localPath + " (" + total + " bytes)";
            } finally {
                try { smbFile.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ========== 工具方法 ==========

    /** 流拷贝（不负责关闭流，由调用方用try-with-resources管理） */
    private static long streamCopy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        return streamCopyWithProgress(in, out, null, "", 0L, 0L);
    }

    /** 流拷贝并按时间间隔回调上传进度 */
    private static long streamCopyWithProgress(java.io.InputStream in, java.io.OutputStream out, ProgressCallback cb, String taskId, long baseWritten, long totalSize) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var total = 0L;
        var lastReportTime = 0L;
        var bytesRead = 0;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            out.flush();
            total += bytesRead;
            var current = baseWritten + total;
            var now = System.currentTimeMillis();
            if (cb != null && (now - lastReportTime >= 200 || current == totalSize)) {
                lastReportTime = now;
                cb.onProgress(taskId, current, totalSize);
            }
        }
        return total;
    }

    /** 打开SMB远端文件用于覆盖写入 */
    private static com.hierynomus.smbj.share.File openSmbOutput(DiskShare share, String remotePath) {
        return share.openFile(remotePath,
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.SYNCHRONIZE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }
}
