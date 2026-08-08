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
 * 配置文件位于 /sdcard/MIUI/backup/config.ini，格式为每行 key=value
 * 只保存非敏感全局配置；敏感凭据走 cloud.EncryptedCredStore
 *
 * 性能：进程内缓存文件内容（按 mtime 失效），避免每次 getString 都重读整个文件；
 * 保存：敏感键黑名单过滤 + 原子写，防止密码/token 明文落盘与半截文件。
 */
public class ConfigHelp {

    private static final String TAG = "XpMiBackup";
    public static final String BACKUP_ROOT = "/sdcard/MIUI/backup";
    private static final String CONFIG_PATH = BACKUP_ROOT + "/config.ini";

    /** 明文落盘黑名单：这些键只允许进入 EncryptedCredStore，禁止写入 config.ini */
    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "smb_pass", "webdav_pass", "custom_script_b64",
            "authorization", "access_token", "refresh_token", "cookie", "cookie_token",
            "did", "x-device-sign");

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
            sb.append(key).append('=').append(val != null ? val.toString() : "").append('\n');
        }
        if (AtomicFile.write(file, sb.toString().getBytes(StandardCharsets.UTF_8))) {
            // 保存成功则刷新缓存，避免读到旧值
            sCache = null;
            sCacheTs = Long.MIN_VALUE;
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
        map.put("log_enabled", "false");
        map.put("protocol", "smb");
        map.put("upload_threads", "3");
        map.put("chunk_size_mb", "64");
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
