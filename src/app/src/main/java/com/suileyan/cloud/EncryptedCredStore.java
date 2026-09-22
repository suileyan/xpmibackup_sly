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
 * 因此采用「种子 + 随机文件盐 + PBKDF2 派生 AES 密钥」：
 * - 盐在首次创建文件时随机生成并随文件持久化，两个进程读同一文件得到同一密钥；
 * - PBKDF2(种子, 盐, 600000 迭代) 派生 256-bit 密钥；
 * - v4 起种子不再是编译进 APK 的常量，而是「公开种子 + 设备指纹」（CRIT-01）：
 *   Build.MANUFACTURER/BRAND/DEVICE/BOARD/HARDWARE/PRODUCT/MODEL 在两个宿主进程内一致、
 *   且不存在于源码/APK 中，攻击者仅拿到 creds.json + 公开源码无法离线还原密钥。
 *   只取 ROM 升级不会变化的稳定字段（FINGERPRINT 含 build id/增量号，OTA 后会变，禁止入种）。
 * - 旧格式（v1 无盐 / v2 20000 迭代 / v3 600000 迭代）读取时回退旧参数，任意写操作触发升级重加密。
 * 数据以 AES-GCM 加密落盘，IV 随机且随密文存储，避免明文散落 sdcard。
 *
 * 残余风险（已知且无法在本架构内消除）：凭据文件位于外部存储，能读到该文件的攻击者
 * 同时掌握 APK 时仍可推导密钥——设备指纹只提高离线爆破成本，不等于真实密钥托管。
 * 彻底修复需把凭据移出外部存储（见 README 架构说明）。
 *
 * 跨进程一致性：写操作采用「临时文件 + rename 原子替换」，并尽力获取文件锁，
 * 避免进程崩溃留下截断文件、降低并发读-改-写覆盖丢失的概率。
 */
public final class EncryptedCredStore {

    private static final String TAG = "XpMiBackup";
    /** 凭据文件统一到 /MIUI/backup/sly/creds.json（由 ConfigHelp.ensureSlyLayout 首启自动迁移旧位置） */
    private static final String CRED_FILE = com.suileyan.comm.ConfigHelp.credFile();
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final int KEY_BITS = 256;
    /** 当前 PBKDF2 迭代数（MED-01：OWASP 推荐 ≥600000，原 20000 过弱） */
    private static final int PBKDF2_ITERATIONS = 600000;
    /** 旧版迭代数：v2 数据用此值加密，读取旧数据时用于解密后自动迁移重加密 */
    private static final int LEGACY_PBKDF2_ITERATIONS = 20000;
    /** 公开种子（编译进 APK，项目 MIT 开源 → 必须视为公开值）。
     *  v4 起仅作为 KDF 输入前缀，不再单独决定密钥（CRIT-01） */
    private static final String KEY_SEED = "xp-mibackup-credential-v1";
    private static final String KEY_SALT = "salt";
    private static final String KEY_VERSION = "v";
    private static final String KEY_DATA = "data";
    /**
     * 当前格式版本：
     * 1 = 无盐单次 SHA-256；2 = 盐 + PBKDF2 20000；3 = 盐 + PBKDF2 600000；
     * 4 = 盐 + PBKDF2 600000 + 设备指纹种子（CRIT-01）
     */
    private static final int FORMAT_VERSION = 4;
    /** 引入设备指纹种子的版本号（读旧数据时按此判定使用哪套种子） */
    private static final int BOUND_SEED_VERSION = 4;
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

    /** 派生密钥缓存：文件盐+迭代数+种子类型不变则密钥不变，避免每次加解密都跑一次 PBKDF2 */
    private static volatile SecretKey sDerivedKey;
    private static volatile String sDerivedKeySalt = "";
    private static volatile int sDerivedKeyIterations = 0;
    private static volatile boolean sDerivedKeyBound = false;

    /** 设备指纹种子缓存（Build 字段为静态常量，进程内取一次即可） */
    private static volatile String sDeviceSeed;

    /**
     * 是否检测到凭据密文损坏（MED-03）：GCM 校验失败 / 密钥不匹配（如设备指纹变化）。
     * 调用方据此可区分「未登录」与「凭据损坏」，避免把真实故障降级成空字符串。
     */
    private static volatile boolean sCorruptionDetected = false;

    private EncryptedCredStore() {
    }

    /** 是否出现过解密失败（凭据损坏/密钥不匹配），供诊断与 UI 提示使用 */
    public static boolean corruptionDetected() {
        return sCorruptionDetected;
    }

    public static synchronized void put(String accountId, String key, String value) {
        if (accountId == null || accountId.isEmpty() || key == null || key.isEmpty()) return;
        try {
            var store = loadStore();
            // 旧格式（无盐 / 旧迭代数 / 旧种子）在首次写操作时升级
            ensureLatestFormat(store);
            var dataKey = accountId + ":" + key;
            if (value == null || value.isEmpty()) {
                store.data.remove(dataKey);
            } else {
                // 关键：按 store 实际版本加密（而不是硬编码 v4 参数）。
                // 迁移失败时 store.version 仍是旧值，若此处用 v4 参数写入、save() 又标记为 v4，
                // 未迁移的旧条目会永久无法解密——必须保证"写"与"读"用同一套参数。
                store.data.put(dataKey, encrypt(value, store.salt,
                        iterationsForVersion(store.version), usesBoundSeed(store.version)));
            }
            // 落盘失败时不写进程内缓存：避免"假成功"——本次进程读得到、重启后凭空消失（MED-11）
            if (!save(store)) return;
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

    /**
     * 批量写入（MED-02）：一次 loadStore + 一次 save。
     * 逐条 put 会让每次调用都全量重读重写 creds.json（O(N²) I/O），
     * 同时放大并发读-改-写覆盖窗口——账号列表保存等多条写入场景必须走这里。
     *
     * @param entries 组合键（{@link #compositeKey}）→ 明文值；空值表示删除该条
     */
    public static synchronized void putAll(java.util.Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) return;
        try {
            var store = loadStore();
            ensureLatestFormat(store);
            for (var e : entries.entrySet()) {
                var dataKey = e.getKey();
                if (dataKey == null || dataKey.isEmpty()) continue;
                var value = e.getValue();
                if (value == null || value.isEmpty()) {
                    store.data.remove(dataKey);
                } else {
                    // 同 put()：按 store 实际版本加密，保证读写参数一致
                    store.data.put(dataKey, encrypt(value, store.salt,
                            iterationsForVersion(store.version), usesBoundSeed(store.version)));
                }
            }
            if (!save(store)) return;
            for (var e : entries.entrySet()) {
                var dataKey = e.getKey();
                if (dataKey == null || dataKey.isEmpty()) continue;
                var value = e.getValue();
                if (value == null || value.isEmpty()) {
                    CACHE.remove(dataKey);
                } else {
                    CACHE.put(dataKey, value);
                }
            }
            refreshCacheTs();
            cacheHitAt = System.currentTimeMillis();
        } catch (Exception e) {
            LogHelp.e(TAG, "EncryptedCredStore putAll failed", e);
        }
    }

    /** 组合键（accountId + ":" + key），与内部 data 键一致，供 {@link #putAll} 使用 */
    public static String compositeKey(String accountId, String key) {
        return accountId + ":" + key;
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
            // 按版本选择迭代数与种子读取（不在此同步迁移：600000 次 PBKDF2 会阻塞主线程导致启动黑屏）；
            // 旧格式由后台 warmUp/异步任务执行迁移重加密
            var enc = store.data.optString(dataKey, "");
            var value = enc.isEmpty() ? ""
                    : decrypt(enc, store.salt, iterationsForVersion(store.version), usesBoundSeed(store.version));
            CACHE.put(dataKey, value);
            return value;
        } catch (Exception e) {
            // MED-03：解密失败（GCM 校验失败 = 密文被篡改 / 密钥不匹配 = 设备指纹或格式变化）
            // 不能静默等同「未登录」，否则真实故障被掩盖成「莫名掉登录」
            sCorruptionDetected = true;
            LogHelp.e(TAG, "凭据解密失败（密文损坏或密钥不匹配，请重新登录该账号）: "
                    + accountId + ":" + key + " -> " + e);
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
            if (!save(store)) return;
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

    /** 该版本的数据使用哪套迭代数（v1 无盐走 SHA-256，迭代数无意义） */
    private static int iterationsForVersion(int version) {
        return version >= 3 ? PBKDF2_ITERATIONS : LEGACY_PBKDF2_ITERATIONS;
    }

    /** 该版本的数据是否使用设备指纹种子（v4 起启用） */
    private static boolean usesBoundSeed(int version) {
        return version >= BOUND_SEED_VERSION;
    }

    /**
     * 旧格式升级：生成随机盐（原格式无盐时）+ 用当前密钥（设备指纹种子 + 600000 迭代）重加密所有条目。
     * 覆盖 v1（无盐）/ v2（20000）/ v3（600000，公开种子）→ v4（CRIT-01）。
     * 调用方必须保证已处于 synchronized 块内
     */
    private static void migrateToLatest(Store store) {
        try {
            var oldSalt = store.salt;
            var oldIterations = iterationsForVersion(store.version);
            var oldBound = usesBoundSeed(store.version);
            var newSalt = oldSalt;
            if (newSalt == null || newSalt.isEmpty()) {
                var salt = new byte[16];
                SECURE_RANDOM.nextBytes(salt);
                newSalt = Base64.getEncoder().encodeToString(salt);
            }
            var newData = new JSONObject();
            var keys = store.data.keys();
            while (keys.hasNext()) {
                var k = keys.next();
                var plain = decrypt(store.data.optString(k, ""), oldSalt, oldIterations, oldBound);
                newData.put(k, encrypt(plain, newSalt, PBKDF2_ITERATIONS, true));
            }
            store.salt = newSalt;
            store.data = newData;
            store.version = FORMAT_VERSION;
            LogHelp.i(TAG, "EncryptedCredStore migrated to v" + FORMAT_VERSION
                    + "（设备指纹种子 + " + PBKDF2_ITERATIONS + " 迭代）");
        } catch (Exception e) {
            LogHelp.e(TAG, "migrate creds to latest format failed", e);
        }
    }

    /** 检测并执行存储格式升级（v1/v2/v3 → v4）。调用方须在 synchronized 内 */
    private static void ensureLatestFormat(Store store) {
        if (store.version >= FORMAT_VERSION) return;
        migrateToLatest(store);
    }

    /**
     * 设备指纹种子（CRIT-01）：公开种子 + 设备稳定标识。
     * 只取 ROM OTA 升级不会变化的字段——FINGERPRINT 含 build id/增量号，升级后必变，
     * 一旦入种会让全部凭据在系统升级后无法解密（表现为「莫名掉登录」）。
     */
    private static String deviceSeed() {
        var s = sDeviceSeed;
        if (s != null) return s;
        s = KEY_SEED + "|" + android.os.Build.MANUFACTURER + "|" + android.os.Build.BRAND + "|"
                + android.os.Build.DEVICE + "|" + android.os.Build.BOARD + "|"
                + android.os.Build.HARDWARE + "|" + android.os.Build.PRODUCT + "|"
                + android.os.Build.MODEL;
        sDeviceSeed = s;
        return s;
    }

    /** 派生 AES 密钥：盐非空走 PBKDF2，盐为空（v1 旧格式）回退单次 SHA-256 保证旧数据可读。
     *  iterations：当前格式用 PBKDF2_ITERATIONS，旧 v2 数据读取用 LEGACY_PBKDF2_ITERATIONS（迁移用）；
     *  boundSeed：v4 起为 true，种子附加设备指纹（CRIT-01） */
    private static SecretKey key(String salt, int iterations, boolean boundSeed) throws Exception {
        var effectiveSalt = salt == null ? "" : salt;
        if (sDerivedKey != null && effectiveSalt.equals(sDerivedKeySalt)
                && iterations == sDerivedKeyIterations && boundSeed == sDerivedKeyBound) {
            return sDerivedKey;
        }
        SecretKey derived;
        if (salt == null || salt.isEmpty()) {
            // v1 无盐格式：历史数据只能沿用旧派生，写操作会立刻迁移到 v4
            var digest = MessageDigest.getInstance("SHA-256");
            derived = new SecretKeySpec(digest.digest(KEY_SEED.getBytes(StandardCharsets.UTF_8)), "AES");
        } else {
            var seed = boundSeed ? deviceSeed() : KEY_SEED;
            var spec = new PBEKeySpec(seed.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, KEY_BITS);
            derived = new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        }
        sDerivedKey = derived;
        sDerivedKeySalt = effectiveSalt;
        sDerivedKeyIterations = iterations;
        sDerivedKeyBound = boundSeed;
        return derived;
    }

    private static String encrypt(String plain, String salt, int iterations, boolean boundSeed) throws Exception {
        var cipher = Cipher.getInstance(TRANSFORM);
        var iv = new byte[IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, key(salt, iterations, boundSeed), new GCMParameterSpec(GCM_TAG_BITS, iv));
        var ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        var out = new byte[IV_LENGTH + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LENGTH);
        System.arraycopy(ct, 0, out, IV_LENGTH, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    private static String decrypt(String enc, String salt, int iterations, boolean boundSeed) throws Exception {
        var raw = Base64.getDecoder().decode(enc);
        if (raw.length <= IV_LENGTH) throw new IllegalStateException("bad credential blob");
        var iv = new byte[IV_LENGTH];
        var ct = new byte[raw.length - IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
        System.arraycopy(raw, IV_LENGTH, ct, 0, ct.length);
        var cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key(salt, iterations, boundSeed), new GCMParameterSpec(GCM_TAG_BITS, iv));
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
            // 旧位置凭据文件由 ConfigHelp.ensureSlyLayout 迁入 SLY_ROOT：宿主进程可能
            // 先于配置读取访问凭据，这里兜一次幂等迁移，否则会表现为"凭据全丢/掉登录"
            com.suileyan.comm.ConfigHelp.ensureSlyLayout();
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

    /**
     * 原子写：复用 AtomicFile（临时文件 + rename + 跨进程文件锁）（NEW-L-01）
     *
     * @return 是否真正落盘成功。MED-11：写失败（磁盘满/权限/锁竞争）不能静默——
     * 否则登录流程照常继续、进程内缓存仍留值，重启后凭据丢失，表现为「莫名掉登录」
     */
    private static boolean save(Store store) {
        try {
            var file = new File(CRED_FILE);
            var root = new JSONObject();
            // 写实际版本（而非常量）：迁移失败时 store.version 保持旧值，
            // 标记成新版本会让未迁移的旧条目再也读不出来
            root.put(KEY_VERSION, store.version > 0 ? store.version : FORMAT_VERSION);
            root.put(KEY_SALT, store.salt == null ? "" : store.salt);
            root.put(KEY_DATA, store.data);
            if (!AtomicFile.write(file, root.toString().getBytes(StandardCharsets.UTF_8))) {
                throw new java.io.IOException("atomic write returned false: " + file.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            LogHelp.e(TAG, "save creds failed（凭据未落盘，重启后会丢失）", e);
            return false;
        }
    }

    /** 迁移/预热任务是否已调度（进程内只迁移一次，幂等） */
    private static volatile boolean sMigrationScheduled = false;

    /**
     * 后台预热 + 格式迁移（MED-01 / CRIT-01）：
     * 600000 次 PBKDF2 较重，若在主线程同步执行会阻塞启动造成黑屏。
     * 由 MainActivity 启动时异步调用；旧数据在此用旧参数解密后以 v4 参数重加密（进程内仅一次）。
     * get() 读取期间若尚未迁移完成，会按旧参数读取，保证功能不受影响。
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
                        key(store.salt, PBKDF2_ITERATIONS, true);
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
