package com.suileyan.comm;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 跨进程安全的原子文件写入工具
 *
 * Xposed 模块注入 com.android.settings（写）与 com.miui.backup（读）两个进程，
 * 进程内 synchronized 无法跨进程互斥。本工具采用：
 * 1. 写前尽力获取同目录 .lock 文件的 FileChannel 锁（跨进程互斥，sdcard FUSE 不支持时自动降级）；
 * 2. 临时文件 + rename 原子替换，进程崩溃不会留下截断/损坏的目标文件。
 */
public final class AtomicFile {

    private AtomicFile() {
    }

    /**
     * 原子写入文件内容
     *
     * @param target  目标文件
     * @param content 完整文件内容
     * @return 是否写入成功
     */
    public static boolean write(File target, byte[] content) {
        if (target == null) return false;
        var dir = target.getParentFile();
        // parent 为 null（相对路径文件）时退回当前目录（NEW-L-17）
        if (dir == null) {
            dir = new File(".");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            LogHelp.e("XpMiBackup", "create dir failed for atomic write: " + dir.getAbsolutePath());
            return false;
        }
        var lockChannel = (FileChannel) null;
        var tmpFile = (File) null;
        try {
            // 尽力获取跨进程文件锁；不支持时降级为无锁
            try {
                var lockFile = new File(dir, target.getName() + ".lock");
                var raf = new RandomAccessFile(lockFile, "rw");
                lockChannel = raf.getChannel();
                try {
                    lockChannel.lock();
                } catch (Exception e) {
                    // MED-01：锁不可得（sdcard/FUSE 常见）时不再静默——降级为无锁写入，
                    // 此时唯一的安全保障就是下面的"唯一临时文件名"，必须留痕便于排查并发覆盖
                    LogHelp.d("XpMiBackup", "atomic write: lock unavailable for "
                            + target.getName() + "，降级为无锁写入（" + e.getMessage() + "）");
                    lockChannel.close();
                    lockChannel = null;
                }
            } catch (Exception e) {
                // 不静默：拿不到锁文件（目录不可写/文件被占）也会走到这里，
                // 此时只靠"唯一临时文件名"兜底，必须留痕才能解释并发覆盖类问题
                LogHelp.d("XpMiBackup", "atomic write: lock file unavailable for "
                        + target.getName() + "，降级为无锁写入（" + e.getMessage() + "）");
            }
            // MED-01：临时文件名必须唯一（进程 + 线程 + 纳秒）。
            // 固定用 target + ".tmp" 时，两个进程同写同一目标会交叉写同一个 tmp 文件、
            // 各自 rename，产出的是两份内容混合后的损坏文件（比"更新丢失"更严重）。
            tmpFile = new File(dir, target.getName() + "." + tmpSuffix() + ".tmp");
            Files.write(tmpFile.toPath(), content);
            Files.move(tmpFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tmpFile = null;
            return true;
        } catch (Exception e) {
            LogHelp.e("XpMiBackup", "atomic write failed: " + target.getAbsolutePath(), e);
            return false;
        } finally {
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                } catch (Exception ignored) {
                }
            }
            if (tmpFile != null && tmpFile.exists()) {
                try {
                    Files.delete(tmpFile.toPath());
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 进程内自增序号 + 纳秒时间戳，保证同目录下并发写入的临时文件名互不冲突 */
    private static final java.util.concurrent.atomic.AtomicLong TMP_SEQ =
            new java.util.concurrent.atomic.AtomicLong(1);

    private static String tmpSuffix() {
        return android.os.Process.myPid() + "-" + Thread.currentThread().getId()
                + "-" + TMP_SEQ.getAndIncrement() + "-" + Long.toHexString(System.nanoTime());
    }
}
