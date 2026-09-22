package com.suileyan.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.comm.AtomicFile;
import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * NAS 配置方案列表持久化
 * 存 /sdcard/MIUI/backup/profiles.json，仅存非敏感元数据；敏感凭据走 EncryptedCredStore（按方案 id 隔离）
 * 首次读取时自动把旧 config.ini 迁移为一条默认方案并设为激活
 */
public final class ProfileStore {

    private static final String TAG = "XpMiBackup";
    /** 方案文件统一到 /MIUI/backup/sly/profiles.json（由 ConfigHelp.ensureSlyLayout 首启自动迁移旧位置） */
    private static final String PROFILE_FILE = com.suileyan.comm.ConfigHelp.profileFile();
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE = "activeId";

    private static volatile boolean migrated = false;

    private ProfileStore() {
    }

    public static synchronized List<Profile> list() {
        ensureMigrated();
        return load().profiles;
    }

    /**
     * 方案文件版本戳（mtime，文件不存在返回 0）。
     *
     * 供 ProviderRegistry 做跨进程缓存失效比对：模块 UI 进程与宿主进程（com.miui.backup）
     * 各持一份静态 Provider 缓存，in-process 的 invalidate() 传不过去——UI 侧改了方案
     * （例如把「电脑备份」从局域网切到 USB 通道）后，宿主会继续用旧地址直到进程重启。
     * 一次 stat 即可判定，不读文件、无解析开销。
     */
    public static long stamp() {
        try {
            com.suileyan.comm.ConfigHelp.ensureSlyLayout();
            var file = new File(PROFILE_FILE);
            if (!file.exists()) return 0L;
            // mtime 之外再掺入文件长度：/sdcard（FAT/FUSE）的 mtime 粒度可达 1~2s，
            // 同一秒内两次保存方案时 mtime 可能不变，跨进程失效就会漏掉（宿主继续用旧 Provider）
            return file.lastModified() * 31L + file.length();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static synchronized Profile get(String id) {
        if (id == null || id.isEmpty()) return null;
        ensureMigrated();
        for (var p : load().profiles) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public static synchronized Profile getActive() {
        ensureMigrated();
        var holder = load();
        if (holder.activeId == null || holder.activeId.isEmpty()) return null;
        for (var p : holder.profiles) {
            if (holder.activeId.equals(p.id)) return p;
        }
        return null;
    }

    public static synchronized String getActiveId() {
        var active = getActive();
        return active != null ? active.id : "";
    }

    public static synchronized void add(Profile profile) {
        if (profile == null || profile.id == null || profile.id.isEmpty()) return;
        var holder = load();
        holder.profiles.removeIf(p -> p.id.equals(profile.id));
        holder.profiles.add(profile);
        if (holder.activeId == null || holder.activeId.isEmpty()) {
            holder.activeId = profile.id;
        }
        save(holder);
        ProviderRegistry.invalidate(profile.id);
    }

    /** 按名称更新方案（名称存在时覆盖原方案，返回最终使用的方案） */
    public static synchronized Profile upsertByName(String name, Profile profile) {
        if (name == null || name.isEmpty() || profile == null) return profile;
        var holder = load();
        for (var p : holder.profiles) {
            if (name.equals(p.name) && !p.id.equals(profile.id)) {
                // 复用已有名称的 id，保留其敏感凭据位置，参数覆盖为新值
                var merged = new Profile(p.id, name, profile.type, p.createdAt, profile.params);
                holder.profiles.removeIf(x -> x.id.equals(p.id));
                holder.profiles.add(merged);
                if (holder.activeId == null || holder.activeId.isEmpty()) {
                    holder.activeId = merged.id;
                }
                save(holder);
                ProviderRegistry.invalidate(merged.id);
                return merged;
            }
        }
        add(profile);
        return profile;
    }

    public static synchronized void remove(String id) {
        if (id == null || id.isEmpty()) return;
        var holder = load();
        holder.profiles.removeIf(p -> p.id.equals(id));
        if (id.equals(holder.activeId)) {
            holder.activeId = holder.profiles.isEmpty() ? "" : holder.profiles.get(holder.profiles.size() - 1).id;
        }
        save(holder);
        EncryptedCredStore.removeAccount(id);
        ProviderRegistry.invalidate(id);
    }

    public static synchronized void setActive(String id) {
        ensureMigrated();
        var holder = load();
        for (var p : holder.profiles) {
            if (id.equals(p.id)) {
                holder.activeId = id;
                save(holder);
                return;
            }
        }
    }

    // ========== 持久化 ==========

    private static class Holder {
        final List<Profile> profiles = new ArrayList<>();
        String activeId = "";
    }

    private static Holder load() {
        var holder = new Holder();
        try {
            var file = new File(PROFILE_FILE);
            if (!file.exists()) return holder;
            var root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            holder.activeId = root.optString(KEY_ACTIVE, "");
            var arr = root.optJSONArray(KEY_PROFILES);
            if (arr != null) {
                for (var i = 0; i < arr.length(); i++) {
                    var obj = arr.optJSONObject(i);
                    if (obj != null) holder.profiles.add(Profile.fromJson(obj));
                }
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "load profiles failed", e);
        }
        return holder;
    }

    private static void save(Holder holder) {
        try {
            var file = new File(PROFILE_FILE);
            var dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            var root = new JSONObject();
            root.put(KEY_ACTIVE, holder.activeId == null ? "" : holder.activeId);
            var arr = new JSONArray();
            for (var p : holder.profiles) {
                arr.put(p.toJson());
            }
            root.put(KEY_PROFILES, arr);
            // MED-11：写失败（磁盘满/权限/锁竞争）不再静默——否则"保存成功"的提示是假的，重启后方案丢失
            if (!AtomicFile.write(file, root.toString().getBytes(StandardCharsets.UTF_8))) {
                LogHelp.e(TAG, "save profiles failed: atomic write returned false: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "save profiles failed", e);
        }
    }

    // ========== 旧配置迁移 ==========

    private static void ensureMigrated() {
        if (migrated) return;
        synchronized (ProfileStore.class) {
            if (migrated) return;
            try {
                migrateFromLegacy();
            } catch (Exception e) {
                LogHelp.e(TAG, "migrate legacy config failed", e);
            }
            migrated = true;
        }
    }

    /** 检测到旧 config.ini 有协议配置且无方案时，自动迁移为一条默认方案并设为激活 */
    private static void migrateFromLegacy() {
        var holder = load();
        if (!holder.profiles.isEmpty()) return;

        var cfg = ConfigHelp.load();
        var protocol = cfg.optString("protocol", "");
        var params = new LinkedHashMap<String, String>();
        var id = "legacy";

        if (Profile.TYPE_SMB.equals(protocol)) {
            params.put("smb_server", cfg.optString("smb_server", ""));
            params.put("smb_port", cfg.optString("smb_port", "445"));
            params.put("smb_share", cfg.optString("smb_share", ""));
            params.put("smb_user", cfg.optString("smb_user", ""));
            EncryptedCredStore.put(id, "smb_pass", cfg.optString("smb_pass", ""));
        } else if (Profile.TYPE_WEBDAV.equals(protocol)) {
            params.put("webdav_url", cfg.optString("webdav_url", ""));
            params.put("webdav_user", cfg.optString("webdav_user", ""));
            EncryptedCredStore.put(id, "webdav_pass", cfg.optString("webdav_pass", ""));
        } else if (Profile.TYPE_SCRIPT.equals(protocol)) {
            EncryptedCredStore.put(id, "custom_script_b64", cfg.optString("custom_script_b64", ""));
        } else {
            return;
        }

        holder.profiles.add(new Profile(id, defaultName(protocol), protocol, System.currentTimeMillis(), params));
        holder.activeId = id;
        save(holder);
        LogHelp.i(TAG, "migrated legacy config to profile [type=" + protocol + "]");
    }

    private static String defaultName(String type) {
        if (Profile.TYPE_SMB.equals(type)) return "默认 SMB";
        if (Profile.TYPE_WEBDAV.equals(type)) return "默认 WebDAV";
        if (Profile.TYPE_SCRIPT.equals(type)) return "默认脚本";
        return type;
    }
}
