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
                } catch (Exception ignored) {
                    lockChannel.close();
                    lockChannel = null;
                }
            } catch (Exception ignored) {
            }

            tmpFile = new File(dir, target.getName() + ".tmp");
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
}
