package com.suileyan.comm;

import org.json.JSONObject;

import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.ListenerProgressCallback;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.ProviderRegistry;
import com.suileyan.cloud.RemoteEntry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 云端文件访问门面
 * 按当前活跃账号从 ProviderRegistry 取 Provider 实例，把文件操作分发到对应实现
 * 切片/manifest 逻辑统一在此层处理，Provider 只负责整文件传输
 */
public class CloudFileHelp {

    private static final String TAG = "XpMiBackup";
    private static final int BUFFER_SIZE = 1048576;
    private static final int DEFAULT_CHUNK_SIZE_MB = 64;
    private static final int MIN_CHUNK_SIZE_MB = 1;
    private static final int MAX_CHUNK_SIZE_MB = 1024;
    private static final String MANIFEST_SUFFIX = ".mibak.json";
    /** 远端 manifest 可信上限：分片数不超过 100000，单文件不超过 1TB（HIGH-09） */
    private static final long MAX_CHUNK_PARTS = 100000L;
    private static final long MAX_CHUNK_FILE_SIZE = 1024L * 1024L * 1024L * 1024L;

    /**
     * 测试当前活跃账号的远端存储是否可连接
     */
    public static boolean testConnection() {
        try {
            return currentProvider().testConnection();
        } catch (Exception e) {
            logError("testConnection failed [type=" + currentType() + "]", e);
            return false;
        }
    }

    /**
     * 列出backup_path下面的备份目录
     */
    public static List<String> listDirs() {
        try {
            return currentProvider().listDirs();
        } catch (Exception e) {
            logError("listDirs failed [type=" + currentType() + "]", e);
            return List.of();
        }
    }

    /**
     * 列出指定远端目录下的文件和文件夹
     */
    public static List<RemoteEntry> listEntries(String remoteDir) {
        try {
            var entries = currentProvider().listEntries(remoteDir);
            return normalizeChunkEntries(entries);
        } catch (Exception e) {
            logError("listEntries failed [type=" + currentType() + ", dir=" + remoteDir + "]", e);
            return List.of();
        }
    }

    /**
     * 上传本地文件，不回调小米DFS进度
     */
    public static String upload(String localPath, String remoteDir) {
        try {
            var localFile = new File(localPath);
            if (shouldChunk(localFile.length())) {
                return uploadChunked(localFile, remoteDir, null, "");
            }
            return uploadSingle(localPath, remoteDir);
        } catch (Exception e) {
            logError("upload failed [type=" + currentType() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 上传备份文件，并回调小米DFS传输进度（{@link #uploadWithProgress(String, Object, String, String, long)} 的便捷入口）
     */
    public static boolean uploadWithProgress(String localPath, Object progressListener, String remoteDir, String taskId) {
        return uploadWithProgress(localPath, progressListener, remoteDir, taskId,
                android.os.SystemClock.elapsedRealtime());
    }

    /**
     * 上传备份文件并回调进度，返回是否上传成功。
     *
     * 调用方必须按返回值决定能否删除本地源文件：原实现返回 void 且内部吞掉异常，
     * 上传失败（或取消）后调用方仍当成功处理 —— 实测取消备份后本地微信备份被 "--auto_delete_local" 删掉。
     *
     * @param requestAt 宿主发起本次上传请求的时刻。取消备份（{@link com.suileyan.comm.BackupCancel}）后，
     *                  早于取消时刻的请求不再产生新的上传动作并在分片间隙尽快中止
     */
    public static boolean uploadWithProgress(String localPath, Object progressListener, String remoteDir,
                                             String taskId, long requestAt) {
        if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
            LogHelp.i(TAG, "upload skipped (backup cancelled): " + localPath);
            return false;
        }
        var cb = progressListener != null ? new ListenerProgressCallback(progressListener) : null;
        try {
            var localFile = new File(localPath);
            if (shouldChunk(localFile.length())) {
                var result = uploadChunked(localFile, remoteDir, cb, taskId, requestAt);
                if (result != null && result.startsWith(CANCELLED_RESULT)) {
                    return false; // 中途被取消：不报完成，也不当成功
                }
                if (cb != null) cb.onFinish(taskId, 0, "success");
            } else if (!uploadSingleWithProgress(localPath, cb, remoteDir, taskId, requestAt)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
                LogHelp.i(TAG, "upload aborted by cancel: " + safeMsg(e));
                return false;
            }
            logError("uploadWithProgress failed [type=" + currentType() + "]", e);
            if (cb != null) cb.onFinish(taskId, -1, safeMsg(e));
            return false;
        }
    }

    /**
     * 下载单个远端文件到本地路径
     */
    public static String downloadFile(String remotePath, String localPath) {
        try {
            var result = downloadChunked(remotePath, localPath);
            if (result != null) {
                return result;
            }
            return downloadSingle(remotePath, localPath);
        } catch (Exception e) {
            logError("downloadFile failed [type=" + currentType() + "]", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * 按恢复侧本地路径推导远端路径并下载文件
     */
    public static void downloadFromCloud(String localPath) {
        try {
            var remotePath = remotePathFromRestoreLocal(localPath);
            if (remotePath != null && !remotePath.isEmpty()) {
                downloadFile(remotePath, localPath);
            }
        } catch (Exception e) {
            logError("downloadFromCloud failed [type=" + currentType() + "]", e);
        }
    }

    /**
     * 枚举备份目录（backup_path 下的直接子目录；backup_path 为空时用云端根目录子目录）。
     * 只保留备份目录格式（\d{8}_\d{6}），跳过云盘根下其他用户文件夹——
     * 备份实际存放在 backup_path 之下，listDirs() 返回的是云端根目录子目录，两者不能混用
     * （否则 backup_path 嵌套时枚举 0 个备份目录，descript.xml 零预下载，恢复列表为空）。
     */
    private static List<String> listBackupDirs() {
        var backupPath = ConfigHelp.getString("backup_path", "");
        List<String> dirs;
        if (backupPath == null || backupPath.isEmpty()) {
            dirs = listDirs();
        } else {
            dirs = new java.util.ArrayList<String>();
            for (var e : listEntries(backupPath)) {
                if (e.directory) dirs.add(e.name);
            }
        }
        var result = new java.util.ArrayList<String>(dirs.size());
        for (var dirName : dirs) {
            if (dirName != null && dirName.matches("\\d{8}_\\d{6}")) {
                result.add(dirName);
            }
        }
        return result;
    }

    /** 全量 descript 遍历节流间隔：App/UI 可能每秒多次触发 readBackupXmls/listAndDownloadXml
     * （恢复页 getDeviceList/设备状态回调 + 账号列表刷新），每次都全量遍历 19 个备份目录并 findEntry，
     * 并发叠加会引发 list 请求风暴（实测 60-140 list/s）挤占上传连接。10s 内只执行一次。 */
    private static final long LIST_XML_THROTTLE_MS = 10_000L;
    private static volatile long sLastListXmlAt = 0L;

    /** 节流闸门：10s 内已跑过返回 false（同一进程内生效；跨进程由各调用方独立节流） */
    private static boolean allowListXmlRun() {
        var now = System.currentTimeMillis();
        synchronized (CloudFileHelp.class) {
            if (now - sLastListXmlAt < LIST_XML_THROTTLE_MS) {
                return false;
            }
            sLastListXmlAt = now;
            return true;
        }
    }

    /**
     * 读取所有备份目录里的descript.xml内容
     */
    public static List<String> readBackupXmls() {
        if (!allowListXmlRun()) {
            return List.of();
        }
        try {
            var xmlList = new java.util.ArrayList<String>();
            var backupPath = ConfigHelp.getString("backup_path", "");
            for (var dirName : listBackupDirs()) {
                var local = File.createTempFile("descript", ".xml");
                try {
                    var result = downloadFile(remotePath(backupDirPath(backupPath, dirName), "descript.xml"), local.getAbsolutePath());
                    if (result != null && !result.startsWith("ERROR:")) {
                        var xml = new String(java.nio.file.Files.readAllBytes(local.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                        xmlList.add(dirName + "|" + xml);
                    }
                } catch (Exception e) {
                    logError("readBackupXmls descript failed [dir=" + dirName + "]", e);
                } finally {
                    deleteTempFile(local);
                }
            }
            return xmlList;
        } catch (Exception e) {
            logError("readBackupXmls failed [type=" + currentType() + "]", e);
            return List.of();
        }
    }

    /**
     * 列出备份目录，并把每个descript.xml下载到本地临时目录。
     * 只处理备份目录（\d{8}_\d{6}），跳过云盘根下其他用户文件夹——否则每连接一次就遍历全部文件夹
     * （139 等网盘根目录含照片/文档等大量文件夹，逐个 findEntry 触发 list 请求风暴、卡住连接）
     * 并行下载（固定 3 线程）：备份目录多时串行遍历会累积成秒级延迟（实测 25 目录 4-5 秒），
     * 并行后 1-2 秒；139 同 host 并发上限 5，3 线程留余量避免与上传争抢
     */
    public static String listAndDownloadXml(String localTempPath) {
        if (!allowListXmlRun()) {
            return "";
        }
        var backupPath = ConfigHelp.getString("backup_path", "");
        var dirs = listBackupDirs();
        var result = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
            var t = new Thread(r, "list-xml-dl");
            t.setDaemon(true);
            return t;
        });
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>(dirs.size());
            for (var dirName : dirs) {
                futures.add(executor.submit(() -> {
                    try {
                        var localDir = new File(localTempPath, dirName);
                        localDir.mkdirs();
                        var localFile = new File(localDir, "descript.xml");
                        var downloaded = downloadFile(remotePath(backupDirPath(backupPath, dirName), "descript.xml"), localFile.getAbsolutePath());
                        if (downloaded != null && !downloaded.startsWith("ERROR:")) {
                            var rstFile = new File(localDir, "restoring");
                            if (!rstFile.exists()) rstFile.createNewFile();
                            result.add(dirName);
                        } else {
                            logError("listAndDownloadXml descript failed [dir=" + dirName + ", result=" + downloaded + "]", new IllegalStateException("download descript.xml failed"));
                        }
                    } catch (Exception e) {
                        logError("listAndDownloadXml descript failed [dir=" + dirName + "]", e);
                    }
                }));
            }
            for (var f : futures) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logError("listAndDownloadXml failed [type=" + currentType() + "]", e);
            return "ERROR: " + e.getMessage();
        } finally {
            executor.shutdownNow();
        }
        return String.join(",", result);
    }

    /**
     * 删除远端目录及其所有内容
     */
    public static void deleteRemoteDir(String remoteDir) {
        try {
            currentProvider().deleteDir(remoteDir);
        } catch (Exception e) {
            logError("deleteRemoteDir failed [type=" + currentType() + "]", e);
        }
    }

    /**
     * 只保留backup_max个最新备份目录；backup_max小于等于0表示不限制
     */
    public static void cleanupOldBackups() {
        try {
            var max = ConfigHelp.getInt("backup_max", 5);
            if (max <= 0) {
                return;
            }
            // 只处理备份目录（\d{8}_\d{6}）：listDirs() 返回云端根目录全部文件夹，
            // 若直接排序删除会误删根下用户自己的文件夹（HIGH：139 根含照片/文档等）
            var dirs = new java.util.ArrayList<>(listBackupDirs());
            if (dirs.size() <= max) {
                return;
            }
            java.util.Collections.sort(dirs);
            var toDelete = dirs.subList(0, dirs.size() - max);
            var backupPath = ConfigHelp.getString("backup_path", "");
            var targets = new java.util.ArrayList<String>(toDelete.size());
            for (var dir : toDelete) {
                targets.add(backupDirPath(backupPath, dir));
            }
            deleteRemoteDirs(targets);
        } catch (Exception e) {
            logError("cleanupOldBackups failed", e);
        }
    }

    /**
     * 批量删除远端目录
     * SMB 复用单个 Session 批量删除，避免每个目录重新握手（HIGH-10）；
     * 其余协议逐个删除
     */
    public static void deleteRemoteDirs(java.util.List<String> remoteDirs) {
        if (remoteDirs == null || remoteDirs.isEmpty()) return;
        try {
            var provider = currentProvider();
            if (provider instanceof com.suileyan.cloud.provider.SmbProvider) {
                com.suileyan.comm.SmbFileHelp.deleteDirs(remoteDirs);
                return;
            }
            for (var dir : remoteDirs) {
                try {
                    provider.deleteDir(dir);
                } catch (Exception e) {
                    logError("deleteRemoteDir failed [dir=" + dir + "]", e);
                    // 百度 errno=132 安全验证是账号级风控（实测 12:53 日志连续 132）：
                    // 后续目录删除必然同样被拦，停止循环避免反复触发风控
                    var msg = String.valueOf(e.getMessage());
                    if (msg.contains("安全验证")) {
                        com.suileyan.comm.LogHelp.w("CloudFileHelp",
                                "删除触发账号级安全验证，停止清理旧备份（可在网页端手动处理）");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logError("deleteRemoteDirs failed [type=" + currentType() + "]", e);
        }
    }

    /**
     * 兼容旧调用：返回当前激活方案的协议类型
     */
    public static String getProtocol() {
        return currentType();
    }

    // ========== Provider 获取 ==========

    /** 获取当前激活方案对应的 Provider */
    private static CloudProvider currentProvider() {
        return ProviderRegistry.active();
    }

    /** 当前备份目标类型（云盘账号 or 激活方案） */
    private static String currentType() {
        return ProviderRegistry.activeType();
    }

    // ========== 通用切片 ==========

    /** 分片上传被取消时的结果标记（调用方据此判定"没传成功"，绝不能当成功删本地源文件） */
    public static final String CANCELLED_RESULT = "CANCELLED: ";

    /**
     * 通用切片上传：Cloud层生成part和manifest，底层协议只负责上传普通文件
     * 任一分片失败时清理已上传的远端分片与 manifest，避免下次重试把旧分片当有效数据（HIGH-11）
     */
    private static String uploadChunked(File localFile, String remoteDir, ProgressCallback cb, String taskId) throws Exception {
        return uploadChunked(localFile, remoteDir, cb, taskId, android.os.SystemClock.elapsedRealtime());
    }

    /**
     * 带取消除检查的分片上传。
     *
     * 宿主的取消只会收掉它自己的任务，这里必须自己看取消标记，否则取消后仍会把剩余分片
     * 全部传完（实测微信 8.75GB 取消后继续传了二十多片，直到云端目录被删、PUT 报 500）。
     * 检查点：造分片前、每个分片任务真正开始上传前、等分片时、写 manifest 前。
     *
     * @param requestAt 宿主发起本次上传请求的时刻
     * @return 正常完成返回结果串；被取消返回以 {@link #CANCELLED_RESULT} 开头
     */
    private static String uploadChunked(File localFile, String remoteDir, ProgressCallback cb, String taskId,
                                        long requestAt) throws Exception {
        if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
            return CANCELLED_RESULT + "before chunking";
        }
        if (!localFile.exists()) {
            throw new java.io.FileNotFoundException("file not found: " + localFile.getAbsolutePath());
        }
        sweepOrphanPartsOnce();
        var fileSize = localFile.length();
        var chunkSize = chunkSizeBytes();
        var totalParts = Math.max(1L, (fileSize + chunkSize - 1L) / chunkSize);
        var remotePath = remotePath(remoteDir, localFile.getName());
        // 分片落盘到模块自己的目录，不写宿主 AllBackupTemp 数据目录：
        // 宿主在项结束后会清空自己的目录，分片写在那里会被连带清掉；独立目录也便于异常中断后清理残留
        var tempDir = chunkTempDir();
        if (!tempDir.exists() && !tempDir.mkdirs() && !tempDir.exists()) {
            throw new java.io.IOException("create chunk temp dir failed: " + tempDir.getAbsolutePath());
        }
        var buffer = new byte[BUFFER_SIZE];
        var totalWritten = 0L;

        // 分片并行上传（默认 8 线程；serial_upload=on 时钳到 1，分片也串行，宿主才能逐项 100% 推进）。
        // 沃盘等云盘对单请求限速（实测沃盘上传域当前 ~70KB/s 总吞吐，5 连接共享；并发提到 8 试每连接限速的剩余空间）
        var partThreads = "on".equals(com.suileyan.comm.ConfigHelp.getString("serial_upload", "off"))
                ? 1
                : 8;
        // 背压：在途分片（已切好、尚未传完）最多 partThreads + 2 片。
        // 原实现把线程池的无界队列当缓冲，而切分只是本地盘读写（数百 MB/s）、上传要过网络（几十 MB/s），
        // 于是 8.75GB 的项会在几十秒内被整份切成 140 片堆在盘上等上传 —— 手机端峰值占用凭空多出整整一份文件。
        // 限流后磁盘上的分片稳定在 (partThreads+2)×chunkSize 以内（默认 10×64MiB≈640MB；逐项模式 3×64MiB）。
        var inFlight = new java.util.concurrent.Semaphore(partThreads + 2);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(partThreads);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        // 本轮切出的分片文件清单：分片任务被 cancel 时它自己的 finally 不会执行，
        // 必须靠这份清单兜底删除（否则失败后会在盘上留下排队中的整批分片）
        var partFiles = new java.util.ArrayList<File>();
        var uploaded = new java.util.concurrent.atomic.AtomicLong(0L);
        try (var fis = new FileInputStream(localFile)) {
            for (var i = 0L; i < totalParts; i++) {
                // 取消后的请求：不再造分片、不再提交（已提交的在分片任务里自行放弃）
                if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
                    LogHelp.i(TAG, "chunked upload cancelled at part " + i + "/" + totalParts
                            + ": " + localFile.getName());
                    return CANCELLED_RESULT + "part " + i + "/" + totalParts;
                }
                // 背压等待期间同样响应取消，否则取消后要等一整批分片传完才能退出
                if (!acquirePartSlot(inFlight, requestAt)) {
                    LogHelp.i(TAG, "chunked upload cancelled while waiting part slot " + i + "/" + totalParts
                            + ": " + localFile.getName());
                    return CANCELLED_RESULT + "part " + i + "/" + totalParts;
                }
                var partFileName = localFile.getName() + ".part" + partName(i);
                var partFile = new File(tempDir, partFileName);
                partFiles.add(partFile);
                var submitted = false;
                try {
                    var remaining = Math.min(chunkSize, fileSize - totalWritten);
                    try (var fos = new FileOutputStream(partFile)) {
                        while (remaining > 0) {
                            var read = fis.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (read == -1) {
                                break;
                            }
                            fos.write(buffer, 0, read);
                            remaining -= read;
                            totalWritten += read;
                        }
                    }
                    final var partSize = partFile.length();
                    futures.add(executor.submit(() -> {
                        try {
                            // 排队期间备份被取消：直接放弃这一片（此时上传只会白跑，云端目录正在被删）
                            if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
                                return;
                            }
                            var uploadResult = uploadSingle(partFile.getAbsolutePath(), remoteDir);
                            if (uploadResult != null && uploadResult.startsWith("ERROR:")) {
                                throw new IllegalStateException(uploadResult);
                            }
                            if (cb != null) cb.onProgress(taskId, uploaded.addAndGet(partSize), fileSize);
                        } catch (Exception ex) {
                            // submit(Runnable) 不能抛检查异常，包装为 CompletionException，外层 f.get() 解包
                            throw new java.util.concurrent.CompletionException(ex);
                        } finally {
                            // 分片传完（或放弃）立刻删除：磁盘占用只跟"在途分片数"有关，不跟文件大小有关
                            deleteTempFile(partFile);
                            inFlight.release();
                        }
                    }));
                    submitted = true;
                } finally {
                    // 切分或提交失败：这一片没进线程池，自己删掉并归还背压额度
                    if (!submitted) {
                        deleteTempFile(partFile);
                        inFlight.release();
                    }
                }
            }
        } catch (Exception e) {
            // 切分/提交异常：取消未完成分片并清理已上传的远端分片
            for (var f : futures) {
                f.cancel(true);
            }
            deletePartFiles(partFiles);
            // 取消场景下云端目录正在被整体删除，逐片清理只会刷 404，跳过
            if (!com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
                cleanupRemoteChunks(remoteDir, localFile.getName(), totalParts);
            }
            throw e;
        } finally {
            executor.shutdown();
        }
        try {
            // 等待全部分片上传完成（任一失败 → 清理已上传远端分片后抛给上层）
            for (var f : futures) {
                f.get();
            }
        } catch (Exception e) {
            deletePartFiles(partFiles);
            if (!com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
                cleanupRemoteChunks(remoteDir, localFile.getName(), totalParts);
            }
            if (e instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null) {
                var cause = ee.getCause();
                while (cause instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) {
                    cause = ce.getCause();
                }
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new IllegalStateException(cause);
            }
            throw new IllegalStateException(e);
        }

        // 取消后不再补 manifest（云端目录正在被删，补了也是残留）
        if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
            return CANCELLED_RESULT + "before manifest";
        }

        var manifest = new JSONObject();
        manifest.put("version", 1);
        manifest.put("name", localFile.getName());
        manifest.put("size", fileSize);
        manifest.put("chunkSize", chunkSize);
        manifest.put("parts", totalParts);
        manifest.put("createdTime", System.currentTimeMillis());

        var manifestFile = new File(tempDir, localFile.getName() + MANIFEST_SUFFIX);
        try {
            try (var fos = new FileOutputStream(manifestFile)) {
                fos.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            var uploadResult = uploadSingle(manifestFile.getAbsolutePath(), remoteDir);
            if (uploadResult != null && uploadResult.startsWith("ERROR:")) {
                throw new IllegalStateException(uploadResult);
            }
        } catch (Exception e) {
            cleanupRemoteChunks(remoteDir, localFile.getName(), totalParts);
            throw e;
        } finally {
            deleteTempFile(manifestFile);
            // 刻意不清理空的分片目录：并发上传多项时，"本项刚删完最后一片"与"另一项正要写 manifest"
            // 之间存在窗口，删目录会让对方写文件失败。留一个空目录无成本，下次上传直接复用。
        }
        return "OK: " + remotePath + " (" + fileSize + " bytes, chunked)";
    }

    /** 清理已上传的远端分片与 manifest（尽力而为，失败仅记日志） */
    private static void cleanupRemoteChunks(String remoteDir, String fileName, long totalParts) {
        try {
            var provider = currentProvider();
            for (var i = 0L; i < totalParts; i++) {
                try {
                    provider.deleteFile(remotePath(remoteDir, fileName + ".part" + partName(i)));
                } catch (Exception ignored) {
                }
            }
            try {
                provider.deleteFile(remotePath(remoteDir, fileName + MANIFEST_SUFFIX));
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            logError("cleanup remote chunks failed", e);
        }
    }

    /**
     * 若远端存在manifest，则按分片顺序下载并合并；不存在时返回null
     * 校验 manifest 的 parts/size/chunkSize 交叉一致性并设上限（HIGH-09），
     * 下载失败时删除半成品 localFile（HIGH-11）
     */
    private static String downloadChunked(String remotePath, String localPath) throws Exception {
        var localFile = new File(localPath);
        var parent = localFile.getParentFile();
        if (parent != null) parent.mkdirs();

        var manifestFile = File.createTempFile("mibak_manifest", ".json", parent);
        try {
            var manifestResult = "";
            try {
                manifestResult = downloadSingle(remotePath + MANIFEST_SUFFIX, manifestFile.getAbsolutePath());
            } catch (Exception e) {
                LogHelp.d(TAG, "chunk manifest not found, fallback to single file: " + remotePath + MANIFEST_SUFFIX);
                return null;
            }
            if (manifestResult == null || manifestResult.startsWith("ERROR:")) {
                return null;
            }
            var manifest = (JSONObject) null;
            try {
                manifest = new JSONObject(new String(java.nio.file.Files.readAllBytes(manifestFile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                LogHelp.d(TAG, "invalid chunk manifest, fallback to single file: " + remotePath + MANIFEST_SUFFIX);
                return null;
            }
            var parts = manifest.optLong("parts", 0L);
            var declaredSize = manifest.optLong("size", 0L);
            var chunkSize = manifest.optLong("chunkSize", 0L);
            // 交叉校验：parts 必须等于 ceil(size/chunkSize)，且均在合理上限内
            if (parts <= 0 || parts > MAX_CHUNK_PARTS || declaredSize < 0 || declaredSize > MAX_CHUNK_FILE_SIZE) {
                LogHelp.w(TAG, "chunk manifest rejected (out of range): parts=" + parts + " size=" + declaredSize);
                return null;
            }
            if (declaredSize > 0 && chunkSize > 0) {
                var expectedParts = (declaredSize + chunkSize - 1L) / chunkSize;
                if (expectedParts != parts) {
                    LogHelp.w(TAG, "chunk manifest inconsistent: parts=" + parts + " expected=" + expectedParts);
                    return null;
                }
            }
            // 分片并行下载（3 线程）：云盘 dlink 限速若为每连接级，并行可线性加速（百度实测单连接 ~30KB/s；
            // 账号级限速则总吞吐不变，但并行至少不劣化）。按序合并保证文件完整性
            var partFiles = new java.util.ArrayList<File>((int) parts);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(3);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>((int) parts);
                for (var i = 0L; i < parts; i++) {
                    var partFile = File.createTempFile("mibak_part", ".tmp", parent);
                    partFiles.add(partFile);
                    final var idx = i;
                    futures.add(executor.submit(() -> {
                        try {
                            var partResult = downloadSingle(remotePath + ".part" + partName(idx), partFile.getAbsolutePath());
                            if (partResult == null || partResult.startsWith("ERROR:")) {
                                throw new IllegalStateException(partResult);
                            }
                        } catch (Exception e) {
                            throw new java.util.concurrent.CompletionException(e);
                        }
                    }));
                }
                for (var f : futures) {
                    try {
                        f.get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        var cause = e.getCause();
                        if (cause instanceof Exception ex) {
                            throw ex;
                        }
                        throw new IllegalStateException(cause);
                    }
                }
                try (var out = new FileOutputStream(localFile)) {
                    for (var pf : partFiles) {
                        try (var in = new FileInputStream(pf)) {
                            streamCopy(in, out);
                        }
                    }
                }
            } finally {
                executor.shutdownNow();
                for (var pf : partFiles) {
                    deleteTempFile(pf);
                }
            }
            // 最终大小校验：与声明 size 不一致（超出或截断）都视为数据异常，删除半成品（HIGH-09 / NEW-L-05）
            if (declaredSize > 0 && localFile.length() != declaredSize) {
                LogHelp.e(TAG, "chunk download size mismatch: local=" + localFile.length() + " declared=" + declaredSize);
                deleteTempFile(localFile);
                throw new IllegalStateException("chunk download size mismatch");
            }
            return "OK: " + remotePath + " -> " + localPath + " (chunked)";
        } catch (Exception e) {
            // 下载失败：删除半成品本地文件，避免下次合并旧分片损坏数据（HIGH-11）
            deleteTempFile(localFile);
            throw e;
        } finally {
            deleteTempFile(manifestFile);
        }
    }

    /** 隐藏part文件的正则（预编译，避免每次列表都重新编译） */
    private static final java.util.regex.Pattern PART_FILE_PATTERN = java.util.regex.Pattern.compile(".*\\.part\\d{5,}$");

    /**
     * 隐藏part文件，并将manifest展示成原文件名，避免恢复列表看到内部文件
     */
    private static List<RemoteEntry> normalizeChunkEntries(List<RemoteEntry> entries) {
        var normalized = new java.util.ArrayList<RemoteEntry>();
        var visibleNames = new java.util.HashSet<String>();
        var partPattern = PART_FILE_PATTERN;
        for (var entry : entries) {
            if (entry.name == null || entry.name.isEmpty()) continue;
            if (partPattern.matcher(entry.name).matches()) continue;
            if (!entry.directory && entry.name.endsWith(MANIFEST_SUFFIX)) {
                var name = entry.name.substring(0, entry.name.length() - MANIFEST_SUFFIX.length());
                if (visibleNames.add(name)) {
                    normalized.add(new RemoteEntry(name, entry.size, false, entry.modifiedTime));
                }
                continue;
            }
            if (visibleNames.add(entry.name)) {
                normalized.add(entry);
            }
        }
        return normalized;
    }

    /** 上传单个普通文件，不在Cloud层做切片；登录态过期时 refresh 后重试一次 */
    private static String uploadSingle(String localPath, String remoteDir) throws Exception {
        var provider = currentProvider();
        try {
            return provider.upload(localPath, remoteDir);
        } catch (CloudException e) {
            if (e.isAuthExpired() && provider.refresh()) {
                return provider.upload(localPath, remoteDir);
            }
            throw e;
        }
    }

    /**
     * 上传单个普通文件，并交给底层协议回调细粒度进度；登录态过期时 refresh 后重试一次。
     * 返回是否上传成功（取消后返回 false，调用方不得据此删除本地源文件）。
     */
    private static boolean uploadSingleWithProgress(String localPath, ProgressCallback cb, String remoteDir,
                                                    String taskId, long requestAt) throws Exception {
        if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
            LogHelp.i(TAG, "single upload skipped (backup cancelled): " + localPath);
            return false;
        }
        var provider = currentProvider();
        try {
            provider.uploadWithProgress(localPath, cb, remoteDir, taskId);
            return true;
        } catch (CloudException e) {
            if (e.isAuthExpired() && provider.refresh()) {
                provider.uploadWithProgress(localPath, cb, remoteDir, taskId);
                return true;
            }
            throw e;
        }
    }

    /** 下载单个普通远端文件，不在Cloud层处理manifest；登录态过期时 refresh 后重试一次 */
    private static String downloadSingle(String remotePath, String localPath) throws Exception {
        var provider = currentProvider();
        try {
            return provider.downloadFile(remotePath, localPath);
        } catch (CloudException e) {
            if (e.isAuthExpired() && provider.refresh()) {
                return provider.downloadFile(remotePath, localPath);
            }
            throw e;
        }
    }

    /** 根据恢复侧本地文件推导云端路径 */
    private static String remotePathFromRestoreLocal(String localPath) throws Exception {
        var localFile = new File(localPath);
        var parent = localFile.getParentFile();
        if (parent == null) {
            return null;
        }
        var dirName = parent.getName();
        var fileName = localFile.getName();
        var backupPath = ConfigHelp.getString("backup_path", "");
        var remoteDir = backupDirPath(backupPath, dirName);
        if (!fileName.equals("restoring")) {
            return remotePath(remoteDir, fileName);
        }

        var descriptFile = new File(parent, "descript.xml");
        if (!descriptFile.exists()) {
            return null;
        }
        var content = new String(java.nio.file.Files.readAllBytes(descriptFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        var idx = content.indexOf("<bakFile>");
        if (idx < 0) {
            return null;
        }
        var endIdx = content.indexOf("</bakFile>", idx);
        if (endIdx <= 0) {
            return null;
        }
        var bakFileName = content.substring(idx + 9, endIdx);
        return remotePath(remoteDir, bakFileName);
    }

    /** 合成备份目录路径（MED-05：剔除目录名中的路径分隔与 .. 段，防路径遍历） */
    private static String backupDirPath(String backupPath, String dirName) {
        var safeDir = sanitizeDirName(dirName);
        return backupPath == null || backupPath.isEmpty() ? safeDir : backupPath + "/" + safeDir;
    }

    /** 剔除目录名中的路径分隔符与 .. /. 段 */
    private static String sanitizeDirName(String dirName) {
        if (dirName == null) return "";
        var cleaned = new StringBuilder();
        for (var segment : dirName.replace('\\', '/').split("/")) {
            if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                continue;
            }
            if (cleaned.length() > 0) cleaned.append("/");
            cleaned.append(segment);
        }
        return cleaned.toString();
    }

    /** 判断文件是否需要切片；0表示关闭切片 */
    private static boolean shouldChunk(long fileSize) {
        var chunkSize = chunkSizeBytes();
        return chunkSize > 0 && fileSize > chunkSize;
    }

    /** 读取并限制切片大小；0表示不切片，负数按默认值处理 */
    private static long chunkSizeBytes() {
        var mb = ConfigHelp.getInt("chunk_size_mb", DEFAULT_CHUNK_SIZE_MB);
        if (mb == 0) return 0L;
        if (mb < MIN_CHUNK_SIZE_MB) {
            mb = DEFAULT_CHUNK_SIZE_MB;
        } else if (mb > MAX_CHUNK_SIZE_MB) {
            mb = MAX_CHUNK_SIZE_MB;
        }
        return mb * 1024L * 1024L;
    }

    /** 合成远端文件路径 */
    private static String remotePath(String remoteDir, String fileName) {
        return (remoteDir != null && !remoteDir.isEmpty() ? remoteDir + "/" : "") + fileName;
    }

    /** 分片编号至少5位，确保字典序和上传顺序一致 */
    private static String partName(long index) {
        return String.format(java.util.Locale.ROOT, "%05d", index);
    }

    /**
     * 分片落盘目录：{@code <AllBackupTemp>/miback/}（模块自管，与宿主数据目录分离）。
     * 分片是"边切边传、传完即删"的，放在这里既不会被宿主清理自己的备份目录时误删，
     * 也便于上传异常中断后统一清理残留分片。
     */
    private static File chunkTempDir() {
        return new File(new File(com.suileyan.comm.LocalBackupFileHelp.TEMP_BACKUP_ROOT), "miback");
    }

    /** 遗留分片判定阈值：6 小时没被动过才算孤儿（正常一次上传最长也就几十分钟） */
    private static final long ORPHAN_PART_IDLE_MS = 6L * 60 * 60 * 1000L;
    private static volatile boolean orphanSwept = false;

    /**
     * 清理上次异常中断（进程被杀 / 上传失败）遗留的分片与临时清单。
     *
     * 分片是"边切边传、传完即删"的，正常路径不残留；但进程被杀时磁盘上会留下一整批分片
     * （实测取消一次微信备份残留 97 片 ≈ 6.36GB，直接把手机端可用空间吃掉一大块）。
     * 这里只删 6 小时以上没动过的 {@code *.partNNNNN} / {@code *.mibak.json}，
     * 绝不会碰到正在上传的分片；每个进程生命周期最多全量扫一次。
     */
    private static void sweepOrphanPartsOnce() {
        if (orphanSwept) {
            return;
        }
        orphanSwept = true;
        try {
            var cutoff = System.currentTimeMillis() - ORPHAN_PART_IDLE_MS;
            var root = new File(com.suileyan.comm.LocalBackupFileHelp.TEMP_BACKUP_ROOT);
            var count = sweepOrphanParts(root, cutoff, false);
            // 顺带收掉被清空的分片目录（miback 空了就删，不会碰到宿主目录）
            com.suileyan.comm.LocalBackupFileHelp.deleteEmptyDirsUntilTempRoot(new File(root, "miback"));
            if (count > 0) {
                LogHelp.i(TAG, "清理遗留分片/临时文件 " + count + " 个（上次异常中断残留）");
            }
        } catch (Exception e) {
            LogHelp.w(TAG, "sweep orphan parts failed", e);
        }
    }

    /**
     * 递归清理孤儿临时文件，返回删除个数；目录不可读时静默跳过。
     *
     * @param moduleOwned 当前目录是否位于模块专属临时区（{@code AllBackupTemp/miback}）之下。
     *                    模块目录里的文件都是上传中间产物（分片、fd 落盘副本），超时一律可删；
     *                    其余位置只认分片/manifest 的固定命名，避免误删宿主的备份数据
     */
    private static int sweepOrphanParts(File dir, long cutoff, boolean moduleOwned) {
        var children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        var removed = 0;
        for (var child : children) {
            if (child.isDirectory()) {
                removed += sweepOrphanParts(child, cutoff, moduleOwned || "miback".equals(child.getName()));
                continue;
            }
            var name = child.getName();
            // 模块专属目录内都是上传中间产物（分片、fd 落盘副本），超时即可删；
            // 模块目录之外只认「本模块生成过的确切命名」，避免误删宿主的备份数据：
            //   - 分片：<源文件名>.partNNNNN，源文件必然是宿主的 *.bak 备份文件
            //   - manifest：<源文件名>.mibak.json（".mibak" 是本模块专有后缀）
            // 单靠 "*.partNNNNN" 不够：宿主自己也可能有同风格临时文件，6h 阈值挡不住
            // "宿主一个跑了一晚上的大项"，所以额外要求带 .bak 源文件标记。
            var orphan = moduleOwned
                    || (PART_FILE_PATTERN.matcher(name).matches() && name.contains(".bak.part"))
                    || name.endsWith(MANIFEST_SUFFIX);
            if (orphan && child.lastModified() < cutoff && child.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 取一个「在途分片」额度（背压），取不到就等；等待期间响应备份取消。
     *
     * @return true 已取到额度；false 表示备份已取消或线程被中断，调用方应立刻停止切分
     */
    private static boolean acquirePartSlot(java.util.concurrent.Semaphore slots, long requestAt) {
        try {
            while (!slots.tryAcquire(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (com.suileyan.comm.BackupCancel.isCancelledFor(requestAt)) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 拷贝流内容，调用方负责关闭输入输出流 */
    private static void streamCopy(java.io.InputStream in, java.io.OutputStream out) throws Exception {
        var buffer = new byte[BUFFER_SIZE];
        var len = 0;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    /**
     * 删除本轮切出的分片文件（失败/取消兜底）。
     *
     * 分片任务正常结束时会在自己的 finally 里删片，但被 {@code Future.cancel} 取消、
     * 尚未开始执行的任务不会走 finally —— 依赖它就会像旧实现一样在盘上留下整批分片。
     * 已经删掉的文件这里再删是 no-op。
     */
    private static void deletePartFiles(java.util.List<File> partFiles) {
        if (partFiles == null || partFiles.isEmpty()) {
            return;
        }
        for (var file : partFiles) {
            deleteTempFile(file);
        }
        partFiles.clear();
    }

    /** 删除临时切片文件，失败时只记录日志 */
    private static void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logError("delete temp chunk file failed", new IllegalStateException(file.getAbsolutePath()));
        }
    }

    private static String safeMsg(Exception e) {
        return e == null || e.getMessage() == null ? "" : e.getMessage();
    }

    /**
     * 统一记录云端文件操作异常
     */
    private static void logError(String message, Exception e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
