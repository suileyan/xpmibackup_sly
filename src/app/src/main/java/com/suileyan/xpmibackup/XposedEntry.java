package com.suileyan.xpmibackup;

import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.LogHelp;
import com.suileyan.xpmibackup.hook.AIDLHook;
import com.suileyan.xpmibackup.hook.AutoBackupHook;
import com.suileyan.xpmibackup.hook.BackupHook;
import com.suileyan.xpmibackup.hook.SettingsHook;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;

/**
 * Xposed模块入口
 * 设置应用负责显示配置入口，小米备份负责接入DFS AIDL重定向
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "XpMiBackup";
    private static final String TEMP_ROOT = ConfigHelp.BACKUP_ROOT + "/AllBackupTemp";

    /**
     * 根据加载的包名安装对应Hook
     */
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        if ("com.android.settings".equals(lpparam.packageName)) {
            new SettingsHook().hook(lpparam);
        } else if ("com.miui.backup".equals(lpparam.packageName)) {
            new BackupHook().hook(lpparam);
            new AutoBackupHook().hook(lpparam);
            new AIDLHook().hook(lpparam);
            // 清理临时目录放到后台守护线程池，避免文件 IO 阻塞目标进程主线程（MED-20 / NEW-H-01）
            com.suileyan.comm.Async.run("cleanup-temp", () -> cleanupTempDirs());
        }
    }

    /**
     * 清理AllBackupTemp下面上次会话残留的所有临时内容
     */
    private void cleanupTempDirs() {
        try {
            var root = new File(TEMP_ROOT);
            if (!root.exists()) {
                root.mkdirs();
                return;
            }
            if (!root.isDirectory()) {
                return;
            }
            deleteChildren(root);
        } catch (Throwable e) {
            LogHelp.e(TAG, "cleanup temp dirs failed: " + e.getMessage(), e);
        }
    }

    /**
     * 删除目录下所有子项，保留目录自身
     */
    private void deleteChildren(File dir) {
        var children = dir.listFiles();
        if (children != null) {
            for (var child : children) {
                deleteRecursively(child);
            }
        }
    }

    /**
     * 递归删除临时文件或目录
     * 不跟随符号链接（MED-10）：符号链接只删除链接本身，避免穿越到目录外删除真实文件
     */
    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        // 符号链接直接删除链接本身，不递归进入目标
        if (file.isDirectory() && !isSymlink(file)) {
            deleteChildren(file);
        }
        if (!file.delete()) {
            LogHelp.e(TAG, "delete temp path failed: " + file.getAbsolutePath());
        }
    }

    /**
     * 判断文件是否为符号链接（Java 7+ 标准 API，Android API 28+ 可用）
     */
    private boolean isSymlink(File file) {
        try {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        } catch (Exception e) {
            return false;
        }
    }
}
