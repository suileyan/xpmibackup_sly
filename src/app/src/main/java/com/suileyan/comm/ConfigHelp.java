package com.suileyan.comm;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 配置文件读写工具
 * 配置文件位于 /sdcard/MIUI/backup/sly/config.ini，格式为每行 key=value
 * 只保存非敏感全局配置；敏感凭据走 cloud.EncryptedCredStore
 *
 * 性能：进程内缓存文件内容（按 mtime 失效），避免每次 getString 都重读整个文件；
 * 保存：敏感键黑名单过滤 + 原子写，防止密码/token 明文落盘与半截文件。
 */
public class ConfigHelp {

    private static final String TAG = "XpMiBackup";
    /** 宿主备份引擎的固定根目录（宿主 App 读写，勿动） */
    public static final String BACKUP_ROOT = "/sdcard/MIUI/backup";
    /**
     * 模块自身配置/数据根目录：统一收纳到 BACKUP_ROOT/sly/ 下，避免与宿主引擎的
     * 备份目录（MIUI/backup/20260915_xxxxxx 等）、AllBackupTemp、logs 等混在同一层。
     * 首次使用自动把旧位置的 config.ini / logs / creds.json / cloud_accounts.json /
     * profiles.json 迁移过来（幂等 + 跨进程锁，见 {@link #ensureSlyLayout()}）。
     */
    public static final String SLY_ROOT = BACKUP_ROOT + "/sly";
    private static final String CONFIG_PATH = SLY_ROOT + "/config.ini";

    /** 明文落盘黑名单：这些键只允许进入 EncryptedCredStore，禁止写入 config.ini */
    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "smb_pass", "webdav_pass", "custom_script_b64",
            "authorization", "access_token", "refresh_token", "cookie", "cookie_token",
            "did", "x-device-sign");

    /** URL 内嵌凭据（scheme://user:pass@host）：保存时剥离 userinfo，防密码明文落盘（MED-04） */
    private static final java.util.regex.Pattern URL_USERINFO =
            java.util.regex.Pattern.compile("(?i)^((?:https?|ftp)://)[^/@\\s]+@");

    /** 账号级参数覆盖：Provider 执行时把当前账号连接参数临时注入，底层 FileHelp 零改动读取 */
    private static final ThreadLocal<Map<String, String>> ACCOUNT_OVERRIDE = new ThreadLocal<>();

    /** 文件内容缓存（未应用 ThreadLocal 覆盖），按 mtime 失效 */
    private static volatile long sCacheTs = Long.MIN_VALUE;
    private static volatile java.util.Map<String, String> sCache = null;

    /**
     * 在账号参数覆盖下执行动作（无返回值）
     * 覆盖只在当前线程生效，结束后自动还原
     */
    public static void withAccount(Map<String, String> params, Runnable action) {
        if (action == null) return;
        if (params == null || params.isEmpty()) {
            action.run();
            return;
        }
        var prev = ACCOUNT_OVERRIDE.get();
        ACCOUNT_OVERRIDE.set(params);
        try {
            action.run();
        } finally {
            ACCOUNT_OVERRIDE.set(prev);
        }
    }

    /**
     * 在账号参数覆盖下执行动作（有返回值）
     */
    public static <T> T withAccount(Map<String, String> params, Callable<T> action) throws Exception {
        if (action == null) return null;
        if (params == null || params.isEmpty()) {
            return action.call();
        }
        var prev = ACCOUNT_OVERRIDE.get();
        ACCOUNT_OVERRIDE.set(params);
        try {
            return action.call();
        } finally {
            ACCOUNT_OVERRIDE.set(prev);
        }
    }

    /**
     * 加载配置并补齐默认值
     * 文件不存在或部分 key 缺失时，调用方仍能拿到完整配置
     */
    public static JSONObject load() {
        var map = readFileWithCache();
        var defaults = defaultMap();
        for (var entry : defaults.entrySet()) {
            if (!map.containsKey(entry.getKey())) {
                map.put(entry.getKey(), entry.getValue());
            }
        }

        // 应用当前线程的账号参数覆盖，优先级最高
        var override = ACCOUNT_OVERRIDE.get();
        if (override != null && !override.isEmpty()) {
            for (var entry : override.entrySet()) {
                if (entry.getKey() != null) {
                    map.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
                }
            }
        }

        var json = new JSONObject();
        for (var entry : map.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LogHelp.e(TAG, "put config value failed: " + entry.getKey(), e);
            }
        }
        return json;
    }

    /** 读取配置文件内容并缓存（按 mtime 失效）；不应用默认值与覆盖 */
    private static java.util.Map<String, String> readFileWithCache() {
        ensureSlyLayout();
        var file = new File(CONFIG_PATH);
        var ts = file.exists() ? file.lastModified() : 0L;
        var cached = sCache;
        if (cached != null && ts == sCacheTs) {
            // 关键：返回副本而非共享实例（NEW-C-01）
            // load() 会向该 Map 写入 defaults 与账号覆盖（含 smb_pass/webdav_pass 等凭据），
            // 若返回共享缓存，凭据会跨线程泄露且并发写会抛 ConcurrentModificationException
            return new LinkedHashMap<>(cached);
        }
        var map = new LinkedHashMap<String, String>();
        if (file.exists()) {
            try (var reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                var line = reader.readLine();
                while (line != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                        var idx = line.indexOf('=');
                        map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                    }
                    line = reader.readLine();
                }
            } catch (Exception e) {
                LogHelp.e(TAG, "load config failed: " + e.getMessage(), e);
            }
        }
        sCache = map;
        sCacheTs = ts;
        return new LinkedHashMap<>(map);
    }

    /**
     * 保存配置为 INI 风格文本
     * 先创建父目录再打开文件，避免首次保存时 FileWriter 因目录不存在而失败；
     * 敏感键做黑名单过滤，绝不把密码/token 明文写入外部存储
     */
    public static void save(JSONObject json) {
        if (json == null) return;
        ensureSlyLayout();
        var file = new File(CONFIG_PATH);
        var dir = file.getParentFile();
        try {
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "create config dir failed: " + e.getMessage(), e);
            return;
        }

        var sb = new StringBuilder();
        var keys = json.keys();
        while (keys.hasNext()) {
            var key = keys.next();
            if (SENSITIVE_KEYS.contains(key)) {
                LogHelp.w(TAG, "ConfigHelp.save: skip sensitive key " + key + " (must use EncryptedCredStore)");
                continue;
            }
            var val = json.opt(key);
            var text = val != null ? val.toString() : "";
            // MED-04：黑名单是按 key 名过滤，挡不住"值里内嵌凭据"——webdav_url 若写成
            // http://user:pass@host/dav，账号密码会明文落进 config.ini（外部存储）。
            // 这里对任何带 scheme 的值剥掉 userinfo，并提示改用独立凭据键。
            var userinfo = URL_USERINFO.matcher(text.trim());
            if (userinfo.find()) {
                LogHelp.w(TAG, "ConfigHelp.save: " + key + " 的值含内嵌账号密码，已剥离"
                        + "（请改用 webdav_user / webdav_pass 等独立凭据键）");
                text = userinfo.replaceFirst("$1");
            }
            sb.append(key).append('=').append(text).append('\n');
        }
        if (AtomicFile.write(file, sb.toString().getBytes(StandardCharsets.UTF_8))) {
            // 保存成功则刷新缓存，避免读到旧值
            sCache = null;
            sCacheTs = Long.MIN_VALUE;
        }
    }

    /** 模块自身文件路径（统一收纳在 SLY_ROOT 下；由各 store 类复用，避免散落硬编码） */
    public static String configPath() { return CONFIG_PATH; }
    public static String slyRoot() { return SLY_ROOT; }
    /** 模块日志目录（LogHelp 用）：SLY_ROOT/logs */
    public static String logDir() { return SLY_ROOT + "/logs"; }
    /** 凭据加密存储：SLY_ROOT/creds.json */
    public static String credFile() { return SLY_ROOT + "/creds.json"; }
    /** 云盘账号元数据：SLY_ROOT/cloud_accounts.json */
    public static String cloudAccountFile() { return SLY_ROOT + "/cloud_accounts.json"; }
    /** NAS 方案元数据：SLY_ROOT/profiles.json */
    public static String profileFile() { return SLY_ROOT + "/profiles.json"; }

    /** 迁移是否已处理过（进程内只跑一次；跨进程靠 migrateFile 的"旧文件不存在即跳过"自然幂等） */
    private static volatile boolean sSlyLayoutDone = false;

    /**
     * 确保 SLY_ROOT 目录与迁移完成（首次使用自动把旧位置文件迁到 sly/）。
     * 幂等 + 跨进程安全：两个宿主进程并发首启时，各自在进程内锁下执行；
     * 文件层面靠「旧文件存在且新文件不存在才迁」的判定天然互斥——
     * 抢先把旧文件 rename 走的进程完成迁移，后到的进程 oldFile.isFile()==false 直接跳过。
     * 旧位置：BACKUP_ROOT/{config.ini, logs/, creds.json, cloud_accounts.json, profiles.json}
     * 新位置：SLY_ROOT 下同名文件/目录。
     * 迁移策略：rename（同分区 O(1)）优先；失败则复制+校验大小+删旧；目录递归搬入。
     */
    public static void ensureSlyLayout() {
        if (sSlyLayoutDone) return;
        synchronized (ConfigHelp.class) {
            if (sSlyLayoutDone) return;
            migrateLegacyConfigFiles();
            sSlyLayoutDone = true;
        }
    }

    private static void migrateLegacyConfigFiles() {
        var slyDir = new File(SLY_ROOT);
        if (!slyDir.exists() && !slyDir.mkdirs()) {
            LogHelp.w(TAG, "ensureSlyLayout: 无法创建 " + SLY_ROOT + "，跳过迁移");
            return;
        }
        var moved = new StringBuilder();
        // 1) 固定文件：逐个迁移（旧存在 + 新不存在才动，幂等）
        migrateFile(new File(BACKUP_ROOT, "config.ini"), new File(SLY_ROOT, "config.ini"), moved);
        migrateFile(new File(BACKUP_ROOT, "creds.json"), new File(SLY_ROOT, "creds.json"), moved);
        migrateFile(new File(BACKUP_ROOT, "cloud_accounts.json"), new File(SLY_ROOT, "cloud_accounts.json"), moved);
        migrateFile(new File(BACKUP_ROOT, "profiles.json"), new File(SLY_ROOT, "profiles.json"), moved);
        // backup_target.json（MED-14）：此前漏在清单外，是唯一散落在 BACKUP_ROOT 的模块文件
        migrateFile(new File(BACKUP_ROOT, "backup_target.json"), new File(SLY_ROOT, "backup_target.json"), moved);
        // 2) logs/ 目录递归搬入（新目录已有同名文件则跳过该文件，避免覆盖新写的日志）
        migrateDir(new File(BACKUP_ROOT, "logs"), new File(SLY_ROOT, "logs"), moved);
        if (moved.length() > 0) {
            LogHelp.i(TAG, "ensureSlyLayout: 已迁移模块旧配置到 " + SLY_ROOT + " -> " + moved);
        }
    }

    /** 单文件迁移：rename 优先，失败复制+校验+删旧。目标已存在则不动（幂等）。 */
    private static void migrateFile(File oldFile, File newFile, StringBuilder log) {
        if (!oldFile.isFile() || newFile.exists()) return;
        var ok = oldFile.renameTo(newFile);
        if (!ok) {
            try {
                var bytes = java.nio.file.Files.readAllBytes(oldFile.toPath());
                java.nio.file.Files.write(newFile.toPath(), bytes);
                // 校验大小一致才删旧，避免数据丢失
                if (newFile.length() == oldFile.length() && oldFile.delete()) {
                    ok = true;
                }
            } catch (Exception e) {
                LogHelp.e(TAG, "migrate " + oldFile.getName() + " 失败", e);
            }
        }
        if (ok) log.append(oldFile.getName()).append(' ');
    }

    /** 目录递归迁移：新目录已有同名文件不覆盖；rename 优先，失败递归复制。 */
    private static void migrateDir(File oldDir, File newDir, StringBuilder log) {
        if (!oldDir.isDirectory()) return;
        var children = oldDir.listFiles();
        if (children == null) return;
        if (!newDir.exists() && !newDir.mkdirs()) {
            log.append(oldDir.getName()).append("(mkdir-fail) ");
            return;
        }
        for (var c : children) {
            var dest = new File(newDir, c.getName());
            if (c.isDirectory()) {
                migrateDir(c, dest, log);
            } else {
                migrateFile(c, dest, log);
            }
        }
        // 旧 logs 清空后尝试删除（保留 BACKUP_ROOT 本身，只删子目录）
        var remaining = oldDir.listFiles();
        if (remaining != null && remaining.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            oldDir.delete();
        }
    }

    /**
     * 读取字符串配置
     */
    public static String getString(String key, String def) {
        return load().optString(key, def);
    }

    /**
     * 读取整数配置，解析失败时使用调用方提供的默认值
     */
    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(load().optString(key, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 默认配置值
     */
    private static LinkedHashMap<String, String> defaultMap() {
        var map = new LinkedHashMap<String, String>();
        map.put("device_id", "miback");
        map.put("device_name", isChineseLocale() ? "云端备份设备" : "Cloud backup device");
        map.put("device_describe", isChineseLocale() ? "我的云端备份设备" : "My cloud backup device");
        map.put("backup_path", "MIUI/backup");
        map.put("backup_max", "5");
        map.put("log_enabled", "true");
        map.put("protocol", "smb");
        map.put("upload_threads", "3");
        map.put("chunk_size_mb", "64");
        map.put("auto_delete_local", "off");
        map.put("serial_upload", "off");
        map.put("smb_server", "192.168.68.1");
        map.put("smb_port", "445");
        map.put("smb_share", isChineseLocale() ? "备份数据" : "BackupData");
        map.put("smb_user", "");
        map.put("webdav_url", "https://192.168.1.1:8080/dav");
        map.put("webdav_user", "");
        return map;
    }

    private static boolean isChineseLocale() {
        return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
    }
}
