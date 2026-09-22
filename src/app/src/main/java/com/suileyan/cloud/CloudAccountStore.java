package com.suileyan.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.comm.AtomicFile;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 云盘账号列表持久化
 * 存 /sdcard/MIUI/backup/cloud_accounts.json，仅存非敏感元数据；
 * Authorization 等敏感凭据走 EncryptedCredStore（按账号 id 隔离）
 */
public final class CloudAccountStore {

    private static final String TAG = "XpMiBackup";
    /** 账号文件统一到 /MIUI/backup/sly/cloud_accounts.json（由 ConfigHelp.ensureSlyLayout 首启自动迁移旧位置） */
    private static final String ACCOUNT_FILE = com.suileyan.comm.ConfigHelp.cloudAccountFile();
    private static final String KEY_ACCOUNTS = "accounts";

    private CloudAccountStore() {
    }

    public static synchronized List<CloudAccount> list() {
        return load();
    }

    public static synchronized CloudAccount get(String id) {
        if (id == null || id.isEmpty()) return null;
        for (var a : load()) {
            if (id.equals(a.id)) return a;
        }
        return null;
    }

    public static synchronized void add(CloudAccount account) {
        if (account == null || account.id == null || account.id.isEmpty()) return;
        var list = load();
        list.removeIf(a -> a.id.equals(account.id));
        list.add(account);
        save(list);
    }

    public static synchronized void remove(String id) {
        if (id == null || id.isEmpty()) return;
        var list = load();
        list.removeIf(a -> a.id.equals(id));
        save(list);
        EncryptedCredStore.removeAccount(id);
    }

    // ========== 持久化 ==========

    private static List<CloudAccount> load() {
        var list = new ArrayList<CloudAccount>();
        var t0 = System.currentTimeMillis();
        try {
            // 旧位置（/MIUI/backup/cloud_accounts.json）由 ConfigHelp.ensureSlyLayout 首启迁入
            // SLY_ROOT；宿主进程可能在读完配置前就取账号列表，这里先兜一次幂等迁移
            com.suileyan.comm.ConfigHelp.ensureSlyLayout();
            var file = new File(ACCOUNT_FILE);
            if (!file.exists()) return list;
            var root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            var arr = root.optJSONArray(KEY_ACCOUNTS);
            if (arr != null) {
                for (var i = 0; i < arr.length(); i++) {
                    var obj = arr.optJSONObject(i);
                    if (obj != null) {
                        var a = CloudAccount.fromJson(obj);
                        // CRIT-02：account（手机号 PII）明文不入文件，从加密存储回填
                        if (a.account == null || a.account.isEmpty()) {
                            a = new CloudAccount(a.id, a.provider,
                                    EncryptedCredStore.get(a.id, "account_label"),
                                    a.name, a.createdAt);
                        }
                        list.add(a);
                    }
                }
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "load cloud accounts failed", e);
        }
        LogHelp.i(TAG, "STARTUP CloudAccountStore.load: " + list.size() + " accounts, " + (System.currentTimeMillis() - t0) + "ms");
        return list;
    }

    private static void save(List<CloudAccount> list) {
        try {
            var file = new File(ACCOUNT_FILE);
            var dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            var root = new JSONObject();
            var arr = new JSONArray();
            // CRIT-02：account 明文写入加密存储（account_label 键），文件内置空。
            // MED-02：标签先收集再一次性批量写入——逐条 put 每次都全量重读重写 creds.json
            // （O(N²) I/O），并把并发读-改-写覆盖窗口放大 N 倍
            var labels = new LinkedHashMap<String, String>();
            for (var a : list) {
                if (a.account != null && !a.account.isEmpty()) {
                    labels.put(EncryptedCredStore.compositeKey(a.id, "account_label"), a.account);
                }
                arr.put(a.toJson());
            }
            EncryptedCredStore.putAll(labels);
            root.put(KEY_ACCOUNTS, arr);
            if (!AtomicFile.write(file, root.toString().getBytes(StandardCharsets.UTF_8))) {
                // MED-11：写失败不再静默（磁盘满/权限/锁竞争），否则账号列表"假成功"、重启后消失
                LogHelp.e(TAG, "save cloud accounts failed: atomic write returned false: " + file.getAbsolutePath());
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "save cloud accounts failed", e);
        }
    }
}
