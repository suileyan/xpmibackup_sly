package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudAccountStore;
import com.suileyan.cloud.Profile;
import com.suileyan.cloud.ProfileStore;
import com.suileyan.cloud.ProviderRegistry;
import com.suileyan.comm.ConfigHelp;
import com.suileyan.xpmibackup.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 备份页面
 * 备份方式：NAS 备份（选择已保存的方案）/ 云盘备份（选择已登录云盘，传输待开发）
 */
public class BackupFragment extends Fragment {

    private RadioGroup rgBackupMethod;
    private RadioButton rbNas, rbCloud;
    private LinearLayout panelNas, panelCloud;
    private Spinner profileSpinner, cloudSpinner;
    private Button btnStartBackup;

    private List<Profile> profiles = new ArrayList<>();
    private List<CloudAccount> cloudAccounts = new ArrayList<>();

    /**
     * 初始化界面：绑定备份方式选择与开始备份按钮
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var t0 = System.currentTimeMillis();
        var view = inflater.inflate(R.layout.fragment_backup, container, false);

        rgBackupMethod = view.findViewById(R.id.rg_backup_method);
        rbNas = view.findViewById(R.id.rb_nas);
        rbCloud = view.findViewById(R.id.rb_cloud);
        panelNas = view.findViewById(R.id.panel_nas);
        panelCloud = view.findViewById(R.id.panel_cloud);
        profileSpinner = view.findViewById(R.id.backup_profile_spinner);
        cloudSpinner = view.findViewById(R.id.cloud_account_spinner);
        btnStartBackup = view.findViewById(R.id.btn_start_backup);

        loadProfiles();
        loadCloudAccounts(this::restoreLastState);

        // listener 必须先注册，restoreLastState 里的 setChecked 才能触发面板切换
        rgBackupMethod.setOnCheckedChangeListener((group, checkedId) -> {
            var nasSelected = checkedId == R.id.rb_nas;
            panelNas.setVisibility(nasSelected ? View.VISIBLE : View.GONE);
            panelCloud.setVisibility(nasSelected ? View.GONE : View.VISIBLE);
        });

        btnStartBackup.setOnClickListener(v -> startBackup());
        com.suileyan.comm.LogHelp.i("XpMiBackup", "STARTUP BackupFragment onCreateView: " + (System.currentTimeMillis() - t0) + "ms");
        return view;
    }

    /**
     * 重新加载云盘账号并恢复上次选择（常驻 Tab 页 onCreateView 只执行一次，
     * 添加新账号后由 MainActivity 切换到本 Tab 时调用）
     */
    public void refresh() {
        if (getView() == null) return;
        loadCloudAccounts(this::restoreLastState);
    }

    /**
     * 加载已保存的 NAS 配置方案到下拉，默认选中激活方案
     */
    private void loadProfiles() {
        profiles = ProfileStore.list();
        var names = new ArrayList<String>();
        for (var p : profiles) {
            names.add(p.name != null && !p.name.isEmpty() ? p.name : typeLabel(p.type));
        }
        var adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);

        var activeId = ProfileStore.getActiveId();
        for (var i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(activeId)) {
                profileSpinner.setSelection(i);
                break;
            }
        }
    }

    /**
     * 加载已登录云盘账号到下拉（显示「网盘名 · 脱敏账号」区分同网盘不同账户）。
     * 账号列表涉及凭据解密（PBKDF2 600000 迭代，首次约 2 秒），后台加载避免主线程阻塞（启动黑屏）；
     * 加载完成回调 onLoaded（用于恢复上次选择等依赖账号列表的逻辑）
     */
    private void loadCloudAccounts(Runnable onLoaded) {
        com.suileyan.comm.Async.run("backup-load-cloud", () -> {
            var accounts = CloudAccountStore.list();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                // 115 网盘已撤销支持：从目标选择列表隐藏已登录的 115 账号
                var visible = new ArrayList<CloudAccount>();
                for (var a : accounts) {
                    if (com.suileyan.cloud.CloudAccount.PROVIDER_115.equals(a.provider)) continue;
                    visible.add(a);
                }
                cloudAccounts = visible;
                var names = new ArrayList<String>();
                for (var a : cloudAccounts) {
                    names.add(com.suileyan.cloud.AccountDisplay.display(a));
                }
                var adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, names);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                cloudSpinner.setAdapter(adapter);
                if (onLoaded != null) onLoaded.run();
            });
        });
    }

    /**
     * 恢复上次备份方式与目标选择（记忆功能）
     * config.ini 保存 backup_method / last_profile_id / last_cloud_account_id
     */
    private void restoreLastState() {
        try {
            var cfg = com.suileyan.comm.ConfigHelp.load();
            var method = cfg.optString("backup_method", "nas");
            if ("cloud".equals(method)) {
                rbCloud.setChecked(true);
                // 显式同步面板（不依赖 setChecked 触发 listener 的时序）
                panelNas.setVisibility(View.GONE);
                panelCloud.setVisibility(View.VISIBLE);
                var lastCloud = cfg.optString("last_cloud_account_id", "");
                for (var i = 0; i < cloudAccounts.size(); i++) {
                    if (cloudAccounts.get(i).id.equals(lastCloud)) {
                        cloudSpinner.setSelection(i);
                        break;
                    }
                }
            } else {
                rbNas.setChecked(true);
                panelNas.setVisibility(View.VISIBLE);
                panelCloud.setVisibility(View.GONE);
                var lastProfile = cfg.optString("last_profile_id", "");
                for (var i = 0; i < profiles.size(); i++) {
                    if (profiles.get(i).id.equals(lastProfile)) {
                        profileSpinner.setSelection(i);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "restore backup state failed", e);
        }
    }

    /**
     * 开始备份：
     * NAS 方式：设置激活所选方案并跳转小米智能存储备份页
     * 云盘方式：设置云盘备份目标（ProviderRegistry 分发到该云盘）并跳转小米智能存储备份页
     */
    private void startBackup() {
        if (rbCloud.isChecked()) {
            if (cloudAccounts.isEmpty()) {
                Toast.makeText(getActivity(), R.string.toast_no_cloud_account, Toast.LENGTH_LONG).show();
                return;
            }
            var index = cloudSpinner.getSelectedItemPosition();
            if (index < 0 || index >= cloudAccounts.size()) {
                Toast.makeText(getActivity(), R.string.toast_no_cloud_account, Toast.LENGTH_LONG).show();
                return;
            }
            var account = cloudAccounts.get(index);
            rememberState("cloud", "", account.id);
            // 云盘备份目标：ProviderRegistry 分发到该云盘账号
            ProviderRegistry.setCloudTarget(account.id);
            ProviderRegistry.invalidateAll();
            com.suileyan.comm.LogHelp.i("XpMiBackup", "backup target set to cloud account: " + account.id + " (" + account.name + ")");
            launchBackupApp();
            return;
        }
        var index = profileSpinner.getSelectedItemPosition();
        if (index < 0 || index >= profiles.size()) {
            Toast.makeText(getActivity(), R.string.toast_no_profile, Toast.LENGTH_LONG).show();
            return;
        }
        var profile = profiles.get(index);
        rememberState("nas", profile.id, "");
        ProfileStore.setActive(profile.id);
        ProviderRegistry.clearCloudTarget();
        ProviderRegistry.invalidateAll();
        launchBackupApp();
    }

    /** 持久化备份方式与目标选择（记忆功能） */
    private void rememberState(String method, String profileId, String cloudAccountId) {
        try {
            var cfg = com.suileyan.comm.ConfigHelp.load();
            cfg.put("backup_method", method);
            cfg.put("last_profile_id", profileId == null ? "" : profileId);
            cfg.put("last_cloud_account_id", cloudAccountId == null ? "" : cloudAccountId);
            com.suileyan.comm.ConfigHelp.save(cfg);
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "remember backup state failed", e);
        }
    }

    /** 跳转小米智能存储备份页（云盘/NAS 共用） */
    private void launchBackupApp() {
        var deviceId = ConfigHelp.getString("device_id", "");
        if (deviceId.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_device_id_required, Toast.LENGTH_LONG).show();
            return;
        }
        var deviceName = ConfigHelp.getString("device_name", "");
        var intent = new Intent("miui.intent.backup.NAS_HOME_ACTIVITY");
        intent.putExtra("deviceId", deviceId);
        intent.putExtra("deviceName", deviceName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getActivity(), R.string.toast_backup_app_missing, Toast.LENGTH_LONG).show();
        }
    }

    private String typeLabel(String type) {
        if (Profile.TYPE_SMB.equals(type)) return getString(R.string.account_type_smb);
        if (Profile.TYPE_WEBDAV.equals(type)) return getString(R.string.account_type_webdav);
        if (Profile.TYPE_SCRIPT.equals(type)) return getString(R.string.account_type_custom);
        return type;
    }
}
