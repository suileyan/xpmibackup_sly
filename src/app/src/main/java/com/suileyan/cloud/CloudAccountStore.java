package com.suileyan.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.comm.AtomicFile;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 云盘账号列表持久化
 * 存 /sdcard/MIUI/backup/cloud_accounts.json，仅存非敏感元数据；
 * Authorization 等敏感凭据走 EncryptedCredStore（按账号 id 隔离）
 */
public final class CloudAccountStore {

    private static final String TAG = "XpMiBackup";
    private static final String ACCOUNT_FILE = "/sdcard/MIUI/backup/cloud_accounts.json";
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
            for (var a : list) {
                // CRIT-02：account 明文写入加密存储（account_label 键），文件内置空
                if (a.account != null && !a.account.isEmpty()) {
                    EncryptedCredStore.put(a.id, "account_label", a.account);
                }
                arr.put(a.toJson());
            }
            root.put(KEY_ACCOUNTS, arr);
            AtomicFile.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LogHelp.e(TAG, "save cloud accounts failed", e);
        }
    }
}
