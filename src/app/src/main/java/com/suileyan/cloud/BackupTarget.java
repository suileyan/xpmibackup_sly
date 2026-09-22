package com.suileyan.cloud;

import org.json.JSONObject;

import com.suileyan.comm.AtomicFile;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 当前备份目标（跨进程持久化）
 *
 * Xposed 模块注入两个进程：com.android.settings（UI 设置目标）与 com.miui.backup（DFS 劫持读取目标）。
 * 静态内存变量不跨进程共享（NAS 模式依赖 ProfileStore 持久化因此正常），
 * 云盘目标必须同样落盘，settings 进程写入、backup 进程读取。
 */
public final class BackupTarget {

    private static final String TAG = "XpMiBackup";
    /**
     * MED-14：与其它模块文件统一收纳在 SLY_ROOT 下。
     * 此前这里是唯一没走 SLY_ROOT 的模块文件（仍写 /MIUI/backup/backup_target.json），
     * 迁移清单也不含它——清理旧目录即丢备份目标，配置散落两处。
     * 旧位置由 ConfigHelp.ensureSlyLayout 首次启动时自动迁入。
     */
    private static final String TARGET_FILE = com.suileyan.comm.ConfigHelp.slyRoot() + "/backup_target.json";
    private static final String KEY_MODE = "mode";
    private static final String KEY_ID = "id";

    public static final String MODE_CLOUD = "cloud";
    public static final String MODE_PROFILE = "profile";

    private BackupTarget() {
    }

    /** 设置为云盘备份目标 */
    public static void setCloud(String cloudAccountId) {
        save(MODE_CLOUD, cloudAccountId);
    }

    /** 设置为 NAS 方案备份目标 */
    public static void setProfile(String profileId) {
        save(MODE_PROFILE, profileId);
    }

    /** 清除备份目标（回到无目标状态，active() 回退读激活方案） */
    public static void clear() {
        try {
            var file = new File(TARGET_FILE);
            if (file.exists()) {
                Files.delete(file.toPath());
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "clear backup target failed", e);
        }
    }

    /** 当前云盘备份目标账号 id；非云盘模式返回 null */
    public static String cloudAccountId() {
        var holder = load();
        return MODE_CLOUD.equals(holder.mode) ? holder.id : null;
    }

    /** 当前是否为云盘备份模式 */
    public static boolean isCloud() {
        return MODE_CLOUD.equals(load().mode);
    }

    private static void save(String mode, String id) {
        try {
            com.suileyan.comm.ConfigHelp.ensureSlyLayout();
            var file = new File(TARGET_FILE);
            var dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            var root = new JSONObject();
            root.put(KEY_MODE, mode == null ? "" : mode);
            root.put(KEY_ID, id == null ? "" : id);
            // MED-11：写失败不再静默——否则备份目标"设置成功"是假的，宿主读到的仍是旧目标
            if (!AtomicFile.write(file, root.toString().getBytes(StandardCharsets.UTF_8))) {
                LogHelp.e(TAG, "save backup target failed: atomic write returned false: " + file.getAbsolutePath());
                return;
            }
            LogHelp.i(TAG, "backup target saved: mode=" + mode + " id=" + id);
        } catch (Exception e) {
            LogHelp.e(TAG, "save backup target failed", e);
        }
    }

    private static Holder load() {
        var holder = new Holder();
        try {
            // 跨进程顺序陷阱：TARGET_FILE 指向 SLY_ROOT，而旧位置文件由
            // ConfigHelp.ensureSlyLayout 迁移过来。若本进程先读目标再读配置（宿主启动早期
            // 就可能这样），迁移没跑 = 读到空目标 = 回退到激活方案，备份被写到别处。
            // 这里先兜一次幂等迁移（进程内只跑一次，代价是一次 boolean 读）。
            com.suileyan.comm.ConfigHelp.ensureSlyLayout();
            var file = new File(TARGET_FILE);
            if (!file.exists()) return holder;
            var root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            holder.mode = root.optString(KEY_MODE, "");
            holder.id = root.optString(KEY_ID, "");
        } catch (Exception e) {
            LogHelp.e(TAG, "load backup target failed", e);
        }
        return holder;
    }

    private static class Holder {
        String mode = "";
        String id = "";
    }
}
