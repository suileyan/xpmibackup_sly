package com.suileyan.cloud;

import org.json.JSONObject;

import com.suileyan.comm.AtomicFile;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭据加密存储
 * Cookie/Token/密码不再明文落盘，按 accountId 隔离存取
 *
 * 密钥方案说明：
 * 本模块通过 Xposed 注入 com.android.settings 与 com.miui.backup 两个不同 UID 的进程，
 * Android Keystore 的密钥按调用进程 UID 隔离，无法跨进程共享解密。
 * 因此采用「固定种子 + 随机文件盐 + PBKDF2 派生 AES 密钥」：
 * - 盐在首次创建文件时随机生成并随文件持久化，两个进程读同一文件得到同一密钥；
 * - PBKDF2(种子, 盐, 20000 迭代) 派生 256-bit 密钥，远强于旧版单次 SHA-256；
 * - 旧版无盐格式自动兼容：读取时回退旧派生，任意写操作触发升级重加密。
 * 数据以 AES-GCM 加密落盘，IV 随机且随密文存储，避免明文散落 sdcard。
 *
 * 跨进程一致性：写操作采用「临时文件 + rename 原子替换」，并尽力获取文件锁，
 * 避免进程崩溃留下截断文件、降低并发读-改-写覆盖丢失的概率。
 */
public final class EncryptedCredStore {

    private static final String TAG = "XpMiBackup";
    private static final String CRED_FILE = "/sdcard/MIUI/backup/creds.json";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final int KEY_BITS = 256;
    /** 当前 PBKDF2 迭代数（MED-01：OWASP 推荐 ≥600000，原 20000 过弱） */
    private static final int PBKDF2_ITERATIONS = 600000;
    /** 旧版迭代数：v2 数据用此值加密，读取旧数据时用于解密后自动迁移重加密 */
    private static final int LEGACY_PBKDF2_ITERATIONS = 20000;
    private static final String KEY_SEED = "xp-mibackup-credential-v1";
    private static final String KEY_SALT = "salt";
    private static final String KEY_VERSION = "v";
    private static final String KEY_DATA = "data";
    /** 当前格式版本：v3 = PBKDF2 600000 迭代（MED-01） */
    private static final int FORMAT_VERSION = 3;
    /** 解密缓存 TTL：超过该时长强制重新读盘，防止明文在进程内永久驻留 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 加密安全随机源：静态复用避免每次加解密重新初始化（VRF-L-01） */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 单配置模式下敏感凭据的统一键 */
    public static final String DEFAULT_KEY = "default";

    /** 进程内解密缓存：避免每次访问都读文件+派生密钥+AES解密（NAS 页一次加载会访问多次） */
    private static final java.util.Map<String, String> CACHE = new java.util.HashMap<>();
    /** 缓存对应的文件修改时间戳+大小；跨进程写入后组合变化会自动失效重读 */
    private static long cacheFileTs = -1L;
    private static long cacheFileSize = -1L;
    /** 缓存命中时间戳，用于 TTL 过期强制重读 */
    private static long cacheHitAt = 0L;

    /** 派生密钥缓存：文件盐+迭代数不变则密钥不变，避免每次加解密都跑一次 PBKDF2 */
    private static volatile SecretKey sDerivedKey;
    private static volatile String sDerivedKeySalt = "";
    private static volatile int sDerivedKeyIterations = 0;

    private EncryptedCredStore() {
    }

    public static synchronized void put(String accountId, String key, String value) {
        if (accountId == null || accountId.isEmpty() || key == null || key.isEmpty()) return;
        try {
            var store = loadStore();
            // 旧格式（无盐 / 旧迭代数）在首次写操作时升级
            ensureLatestFormat(store);
            var dataKey = accountId + ":" + key;
            if (value == null || value.isEmpty()) {
                store.data.remove(dataKey);
            } else {
                store.data.put(dataKey, encrypt(value, store.salt, PBKDF2_ITERATIONS));
            }
            save(store);
            refreshCacheTs();
            if (value == null || value.isEmpty()) {
                CACHE.remove(dataKey);
            } else {
                CACHE.put(dataKey, value);
            }
            cacheHitAt = System.currentTimeMillis();
        } catch (Exception e) {
            LogHelp.e(TAG, "EncryptedCredStore put failed", e);
        }
    }

    public static synchronized String get(String accountId, String key) {
        if (accountId == null || accountId.isEmpty() || key == null || key.isEmpty()) return "";
        try {
            var dataKey = accountId + ":" + key;
            // 文件 mtime/size 变化（跨进程写入）时清缓存；TTL 超时也强制重读
            var file = new File(CRED_FILE);
            var ts = file.exists() ? file.lastModified() : 0L;
            var size = file.exists() ? file.length() : 0L;
            if (ts != cacheFileTs || size != cacheFileSize) {
                CACHE.clear();
                cacheFileTs = ts;
                cacheFileSize = size;
                cacheHitAt = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - cacheHitAt > CACHE_TTL_MS) {
                CACHE.clear();
                cacheHitAt = System.currentTimeMillis();
            }
            if (CACHE.containsKey(dataKey)) {
                return CACHE.get(dataKey);
            }
            var store = loadStore();
            // 按版本选择迭代数读取（不在此同步迁移：600000 次 PBKDF2 会阻塞主线程导致启动黑屏）；
            // 旧格式由后台 warmUp/异步任务执行迁移重加密
            var iterations = store.version >= FORMAT_VERSION ? PBKDF2_ITERATIONS : LEGACY_PBKDF2_ITERATIONS;
            var t = System.currentTimeMillis();
            var enc = store.data.optString(dataKey, "");
            var value = enc.isEmpty() ? "" : decrypt(enc, store.salt, iterations);
            var cost = System.currentTimeMillis() - t;
            if (cost > 200) {
                LogHelp.i(TAG, "STARTUP cred get slow: key=" + dataKey + " iter=" + iterations + " cost=" + cost + "ms");
            }
            CACHE.put(dataKey, value);
            return value;
        } catch (Exception e) {
            LogHelp.e(TAG, "EncryptedCredStore get failed", e);
            return "";
        }
    }

    public static synchronized void removeAccount(String accountId) {
        if (accountId == null || accountId.isEmpty()) return;
        try {
            var store = loadStore();
            ensureLatestFormat(store);
            var prefix = accountId + ":";
            var keys = store.data.keys();
            var toRemove = new ArrayList<String>();
            while (keys.hasNext()) {
                var k = keys.next();
                if (k.startsWith(prefix)) toRemove.add(k);
            }
            for (var k : toRemove) store.data.remove(k);
            save(store);
            refreshCacheTs();
            CACHE.keySet().removeIf(k -> k.startsWith(prefix));
            cacheHitAt = System.currentTimeMillis();
        } catch (Exception e) {
            LogHelp.e(TAG, "EncryptedCredStore removeAccount failed", e);
        }
    }

    /** 保存后刷新缓存时间戳并保留本进程缓存，避免立即失效重读 */
    private static void refreshCacheTs() {
        try {
            var file = new File(CRED_FILE);
            cacheFileTs = file.exists() ? file.lastModified() : 0L;
            cacheFileSize = file.exists() ? file.length() : 0L;
        } catch (Exception ignored) {
        }
    }

    /**
     * 旧格式（无盐）升级：生成随机盐，用新密钥重加密所有既有条目
     * 调用方必须保证已处于 synchronized 块内
     */
    private static void upgradeToSalted(Store store) {
        try {
            var salt = new byte[16];
            SECURE_RANDOM.nextBytes(salt);
            var saltB64 = Base64.getEncoder().encodeToString(salt);
            var newData = new JSONObject();
            var keys = store.data.keys();
            while (keys.hasNext()) {
                var k = keys.next();
                var plain = decrypt(store.data.optString(k, ""), null, 0);
                newData.put(k, encrypt(plain, saltB64, PBKDF2_ITERATIONS));
            }
            store.salt = saltB64;
            store.data = newData;
            store.version = FORMAT_VERSION;
            LogHelp.i(TAG, "EncryptedCredStore upgraded to salted format");
        } catch (Exception e) {
            LogHelp.e(TAG, "upgrade creds to salted format failed", e);
        }
    }

    /**
     * 旧 v2（PBKDF2 20000 迭代）→ v3（600000 迭代）自动迁移：
     * 用旧迭代数解密所有条目，再以新迭代数重加密，一次完成后格式版本升级（MED-01）
     * 调用方必须保证已处于 synchronized 块内
     */
    private static void upgradeIterations(Store store) {
        try {
            var newData = new JSONObject();
            var keys = store.data.keys();
            while (keys.hasNext()) {
                var k = keys.next();
                var plain = decrypt(store.data.optString(k, ""), store.salt, LEGACY_PBKDF2_ITERATIONS);
                newData.put(k, encrypt(plain, store.salt, PBKDF2_ITERATIONS));
            }
            store.data = newData;
            store.version = FORMAT_VERSION;
            LogHelp.i(TAG, "EncryptedCredStore upgraded iterations " + LEGACY_PBKDF2_ITERATIONS
                    + " -> " + PBKDF2_ITERATIONS);
        } catch (Exception e) {
            LogHelp.e(TAG, "upgrade creds iterations failed", e);
        }
    }

    /** 检测并执行存储格式升级（无盐→加盐；v2 迭代数→v3）。调用方须在 synchronized 内 */
    private static void ensureLatestFormat(Store store) {
        if (store.salt == null) {
            upgradeToSalted(store);
        } else if (store.version < FORMAT_VERSION) {
            upgradeIterations(store);
        }
    }

    /** 派生 AES 密钥：盐非空走 PBKDF2，盐为空（旧格式）回退单次 SHA-256 保证旧数据可读。
     *  iterations：当前格式用 PBKDF2_ITERATIONS，旧 v2 数据读取用 LEGACY_PBKDF2_ITERATIONS（迁移用） */
    private static SecretKey key(String salt, int iterations) throws Exception {
        var effectiveSalt = salt == null ? "" : salt;
        if (sDerivedKey != null && effectiveSalt.equals(sDerivedKeySalt) && iterations == sDerivedKeyIterations) {
            return sDerivedKey;
        }
        SecretKey derived;
        if (salt == null || salt.isEmpty()) {
            var digest = MessageDigest.getInstance("SHA-256");
            derived = new SecretKeySpec(digest.digest(KEY_SEED.getBytes(StandardCharsets.UTF_8)), "AES");
        } else {
            var spec = new PBEKeySpec(KEY_SEED.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, KEY_BITS);
            derived = new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        }
        sDerivedKey = derived;
        sDerivedKeySalt = effectiveSalt;
        sDerivedKeyIterations = iterations;
        return derived;
    }

    private static String encrypt(String plain, String salt, int iterations) throws Exception {
        var cipher = Cipher.getInstance(TRANSFORM);
        var iv = new byte[IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key(salt, iterations), new GCMParameterSpec(GCM_TAG_BITS, iv));
        var ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        var out = new byte[IV_LENGTH + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LENGTH);
        System.arraycopy(ct, 0, out, IV_LENGTH, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    private static String decrypt(String enc, String salt, int iterations) throws Exception {
        var raw = Base64.getDecoder().decode(enc);
        if (raw.length <= IV_LENGTH) throw new IllegalStateException("bad credential blob");
        var iv = new byte[IV_LENGTH];
        var ct = new byte[raw.length - IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
        System.arraycopy(raw, IV_LENGTH, ct, 0, ct.length);
        var cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key(salt, iterations), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** 存储容器：data 为条目 JSON，salt 为空表示旧版无盐格式，version 为格式版本 */
    private static class Store {
        JSONObject data;
        String salt;
        int version;

        Store(JSONObject data, String salt, int version) {
            this.data = data;
            this.salt = salt;
            this.version = version;
        }
    }

    private static Store loadStore() {
        try {
            var file = new File(CRED_FILE);
            if (!file.exists()) return new Store(new JSONObject(), null, 0);
            var root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (root.has(KEY_SALT)) {
                var salt = root.optString(KEY_SALT, "");
                var data = root.optJSONObject(KEY_DATA);
                var version = root.optInt(KEY_VERSION, 2);
                return new Store(data != null ? data : new JSONObject(), salt, version);
            }
            // 旧格式：整个 root 就是条目（无盐 v1）
            return new Store(root, null, 1);
        } catch (Exception e) {
            LogHelp.e(TAG, "load creds failed", e);
            return new Store(new JSONObject(), null, 0);
        }
    }

    /** 原子写：复用 AtomicFile（临时文件 + rename + 跨进程文件锁）（NEW-L-01） */
    private static void save(Store store) {
        try {
            var file = new File(CRED_FILE);
            var root = new JSONObject();
            root.put(KEY_VERSION, FORMAT_VERSION);
            root.put(KEY_SALT, store.salt == null ? "" : store.salt);
            root.put(KEY_DATA, store.data);
            AtomicFile.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LogHelp.e(TAG, "save creds failed", e);
        }
    }

    /** 迁移/预热任务是否已调度（进程内只迁移一次，幂等） */
    private static volatile boolean sMigrationScheduled = false;

    /**
     * 后台预热 + 格式迁移（MED-01）：
     * 600000 次 PBKDF2 较重，若在主线程同步执行会阻塞启动造成黑屏。
     * 由 MainActivity 启动时异步调用；旧 v2 数据在此用旧迭代解密后以新迭代重加密（进程内仅一次）。
     * get() 读取期间若尚未迁移完成，会按旧迭代数读取，保证功能不受影响。
     */
    public static void warmUp() {
        if (sMigrationScheduled) return;
        sMigrationScheduled = true;
        com.suileyan.comm.Async.run("cred-migrate", () -> {
            synchronized (EncryptedCredStore.class) {
                try {
                    var t0 = System.currentTimeMillis();
                    var store = loadStore();
                    if (store.version >= FORMAT_VERSION) {
                        // 已是最新格式：仍预热派生密钥（否则主线程首次 get 会触发 600000 迭代派生，
                        // 阻塞 2 秒造成启动黑屏——日志实测 iter=600000 cost=2062ms）
                        key(store.salt, PBKDF2_ITERATIONS);
                        LogHelp.i(TAG, "EncryptedCredStore warmUp key cached, cost=" + (System.currentTimeMillis() - t0) + "ms");
                        return;
                    }
                    ensureLatestFormat(store);
                    save(store);
                    LogHelp.i(TAG, "EncryptedCredStore warmUp/migration done, version=" + FORMAT_VERSION
                            + " cost=" + (System.currentTimeMillis() - t0) + "ms");
                } catch (Exception e) {
                    LogHelp.e(TAG, "EncryptedCredStore warmUp failed", e);
                }
            }
        });
    }
}
