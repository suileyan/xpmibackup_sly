package com.suileyan.xpmibackup.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.os.Build;
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
import android.widget.TextView;
import android.widget.Toast;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudAccountStore;
import com.suileyan.cloud.Profile;
import com.suileyan.cloud.ProfileStore;
import com.suileyan.cloud.ProviderRegistry;
import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.PcDiscovery;
import com.suileyan.comm.PcPair;
import com.suileyan.xpmibackup.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 备份页面
 * 备份方式：NAS 备份（选择已保存的方案）/ 云盘备份（选择已登录云盘）/ 备份至 PC（自动发现连接的电脑）
 *
 * 备份至 PC：进入本页自动扫描局域网（UDP 广播）与 USB 通道（adb reverse），
 * 发现 mibackpc 后弹窗询问是否连接；用户点「连接」→ 电脑端弹窗确认 → 配对成功后
 * 本方式选项出现。全程无需手动填写地址/账号。
 */
public class BackupFragment extends Fragment {

    /** PC 端在手机里落盘的 WebDAV 方案名（与 ProfileStore.upsertByName 同名复用） */
    private static final String PC_PROFILE_NAME = "电脑备份";
    /** 自动扫描节流间隔：切回本 tab 频繁触发 refresh，不重复扫 */
    private static final long SCAN_THROTTLE_MS = 5000;

    private RadioGroup rgBackupMethod;
    private RadioButton rbNas, rbCloud, rbPc;
    private TextView rbPcHint;
    private LinearLayout panelNas, panelCloud, panelPc;
    private TextView tvPcStatus;
    private Spinner profileSpinner, cloudSpinner;
    private Button btnStartBackup;
    private android.widget.CheckBox cbRootModules;
    private android.widget.CheckBox cbAutoDeleteLocal;
    private android.widget.CheckBox cbSerialUpload;

    private List<Profile> profiles = new ArrayList<>();
    private List<CloudAccount> cloudAccounts = new ArrayList<>();

    // PC 自动发现状态（仅本 tab 生命周期内）
    private List<PcDiscovery.PcInfo> pcFound = new ArrayList<>();
    private final Set<String> pcIgnored = new HashSet<>(); // 本次会话内用户忽略的 host:port
    private boolean pcDialogShowing;
    private boolean pcScanning;
    private long pcLastScanAt;
    private AlertDialog pcPairingDialog;
    private final AtomicBoolean pcPairCancel = new AtomicBoolean(false);
    private long pcCredCheckAt; // 上次凭据验证时间戳，避免每轮扫描都检测

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
        rbPc = view.findViewById(R.id.rb_pc);
        rbPcHint = view.findViewById(R.id.rb_pc_hint);
        panelNas = view.findViewById(R.id.panel_nas);
        panelCloud = view.findViewById(R.id.panel_cloud);
        panelPc = view.findViewById(R.id.panel_pc);
        tvPcStatus = view.findViewById(R.id.tv_pc_status);
        profileSpinner = view.findViewById(R.id.backup_profile_spinner);
        cloudSpinner = view.findViewById(R.id.cloud_account_spinner);
        btnStartBackup = view.findViewById(R.id.btn_start_backup);

        loadProfiles();
        loadCloudAccounts(this::restoreLastState);
        updatePcOption();

        // listener 必须先注册，restoreLastState 里的 setChecked 才能触发面板切换
        rgBackupMethod.setOnCheckedChangeListener((group, checkedId) -> syncPanels(checkedId));

        btnStartBackup.setOnClickListener(v -> startBackup());

        var btnPcRescan = view.findViewById(R.id.btn_pc_rescan);
        btnPcRescan.setOnClickListener(v -> forceRescanPc());

        // 恢复 Root 模块：从 Transfer 快照解包回 /data/adb（二期，独立确认 UI）
        Button btnRestoreModules = view.findViewById(R.id.btn_restore_modules);
        btnRestoreModules.setOnClickListener(v -> showRestoreModulesDialog());

        // Root 模块备份开关（Magisk/KernelSU/APatch，随备份打包到云端/电脑）
        cbRootModules = view.findViewById(R.id.cb_root_modules);
        cbRootModules.setChecked("on".equals(ConfigHelp.getString("root_modules_backup", "on")));
        cbRootModules.setOnCheckedChangeListener((b, isChecked) -> {
            // 取消勾选：直接保存，无前置条件
            if (!isChecked) {
                saveRootModulesToggle(false);
                return;
            }
            // 勾选：先检测 Root 可用性（首次会弹管理器授权窗），无 Root 不勾选
            b.setEnabled(false);
            Toast.makeText(getActivity(), R.string.su_checking, Toast.LENGTH_SHORT).show();
            com.suileyan.comm.Async.run("su-check", () -> {
                var ok = com.suileyan.comm.RootModulesHelp.hasSu();
                var activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    b.setEnabled(true);
                    if (ok) {
                        saveRootModulesToggle(true);
                    } else {
                        // 无 Root：回退勾选并提示
                        b.setOnCheckedChangeListener(null);
                        b.setChecked(false);
                        b.setOnCheckedChangeListener((bb, c) -> cbRootModulesListener(bb, c));
                        saveRootModulesToggle(false);
                        Toast.makeText(getActivity(), R.string.su_missing, Toast.LENGTH_LONG).show();
                    }
                });
            });
        });

        // 自动删除本地已上传备份文件（上传成功后删 AllBackupTemp 源文件，节省存储空间）
        cbAutoDeleteLocal = view.findViewById(R.id.cb_auto_delete_local);
        cbAutoDeleteLocal.setChecked("on".equals(ConfigHelp.getString("auto_delete_local", "off")));
        cbAutoDeleteLocal.setOnCheckedChangeListener((b, isChecked) -> saveAutoDeleteToggle(isChecked));

        // 逐项备份（一项 100% 后再下一项，而非多项并行 30% 补全）
        cbSerialUpload = view.findViewById(R.id.cb_serial_upload);
        cbSerialUpload.setChecked("on".equals(ConfigHelp.getString("serial_upload", "off")));
        cbSerialUpload.setOnCheckedChangeListener((b, isChecked) -> saveSerialUploadToggle(isChecked));
        com.suileyan.comm.LogHelp.i("XpMiBackup", "STARTUP BackupFragment onCreateView: " + (System.currentTimeMillis() - t0) + "ms");
        return view;
    }

    /**
     * 重新加载云盘账号并恢复上次选择（常驻 Tab 页 onCreateView 只执行一次，
     * 添加新账号后由 MainActivity 切换到本 Tab 时调用）。
     * 同时刷新 PC 选项可见性并触发一次自动扫描（节流），实现"处于备份页即自动发现"。
     */
    public void refresh() {
        if (getView() == null) return;
        loadCloudAccounts(this::restoreLastState);
        updatePcOption();
        autoScanPc();
    }

    /** 三种备份方式的面板互斥切换 */
    private void syncPanels(int checkedId) {
        panelNas.setVisibility(checkedId == R.id.rb_nas ? View.VISIBLE : View.GONE);
        panelCloud.setVisibility(checkedId == R.id.rb_cloud ? View.VISIBLE : View.GONE);
        panelPc.setVisibility(checkedId == R.id.rb_pc ? View.VISIBLE : View.GONE);
    }

    // ---------- 备份至 PC：自动发现 + 配对 ----------

    /** 「备份至 PC」选项仅在存在电脑方案（曾经配对成功）后出现 */
    private void updatePcOption() {
        var visible = findPcProfile() != null;
        rbPc.setVisibility(visible ? View.VISIBLE : View.GONE);
        rbPcHint.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private Profile findPcProfile() {
        for (var p : ProfileStore.list()) {
            if (PC_PROFILE_NAME.equals(p.name)) return p;
        }
        return null;
    }

    /**
     * 进入本 tab 自动扫描（refresh 触发，节流）。三路探测见 PcDiscovery。
     */
    private void autoScanPc() {
        if (getView() == null || pcScanning || pcDialogShowing) return;
        var now = android.os.SystemClock.elapsedRealtime();
        if (now - pcLastScanAt < SCAN_THROTTLE_MS) return;
        pcLastScanAt = now;
        pcScanning = true;
        updatePcStatusText(null);
        com.suileyan.comm.Async.run("pc-scan", () -> {
            var found = PcDiscovery.scan();
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                pcScanning = false;
                if (!isAdded()) return;
                handlePcFound(found);
            });
        });
    }

    private void forceRescanPc() {
        pcLastScanAt = 0;
        autoScanPc();
    }

    /** 扫描结果落地：已配对电脑在场 → 验证凭据有效性 → 静默刷新状态；否则对未忽略的电脑弹连接询问 */
    private void handlePcFound(List<PcDiscovery.PcInfo> found) {
        pcFound = found;
        if (found.isEmpty()) {
            updatePcStatusText(null);
            return;
        }
        var pairedAddr = ConfigHelp.getString("pc_paired_addr", "");
        for (var f : found) {
            if ((f.host + ":" + f.port).equals(pairedAddr)) {
                updatePcStatusText(f); // 已配对的电脑在场，静默确认在线
                // 每 60s 验证一次凭据有效性；401 时自动清除配对，触发重新配对
                if (System.currentTimeMillis() - pcCredCheckAt > 60_000) {
                    pcCredCheckAt = System.currentTimeMillis();
                    verifyPcCredOrRePair(f);
                }
                return;
            }
        }
        for (var f : found) {
            var addr = f.host + ":" + f.port;
            if (pcIgnored.contains(addr)) continue;
            updatePcStatusText(null);
            showPcConnectDialog(f);
            return;
        }
        updatePcStatusText(found.get(0));
    }

    /** 后台验证已配对 PC 的 WebDAV 凭据；401 时清除配对状态并触发重新配对 */
    private void verifyPcCredOrRePair(PcDiscovery.PcInfo pc) {
        com.suileyan.comm.Async.run("pc-cred-check", () -> {
            var profile = findPcProfile();
            if (profile == null) return;
            var pass = com.suileyan.cloud.EncryptedCredStore.get(profile.id, "webdav_pass");
            var params = new java.util.LinkedHashMap<>(profile.params);
            params.put("webdav_pass", pass != null ? pass : "");
            try {
                var ok = com.suileyan.comm.ConfigHelp.withAccount(params,
                        (java.util.concurrent.Callable<Boolean>) com.suileyan.comm.WebdavFileHelp::testConnection);
                if (Boolean.TRUE.equals(ok)) return;
            } catch (Exception ignored) {
            }
            // 凭据失效：清除配对状态
            com.suileyan.comm.LogHelp.w("XpMiBackup", "PC 凭据已失效（401），清除配对状态以触发重新配对");
            clearPcPairing();
            var activity = getActivity();
            if (activity == null || !isAdded()) return;
            activity.runOnUiThread(() -> {
                updatePcOption();
                updatePcStatusText(null);
                // 重新扫描，下一轮 handlePcFound 会走新 PC 分支弹连接询问
                forceRescanPc();
            });
        });
    }

    /** 清除 PC 配对信息（config.ini + profile + 凭据） */
    private void clearPcPairing() {
        try {
            var cfg = ConfigHelp.load();
            cfg.put("pc_paired_addr", "");
            cfg.put("pc_paired_name", "");
            ConfigHelp.save(cfg);
        } catch (Exception ignored) {
        }
        var profile = findPcProfile();
        if (profile != null) {
            ProfileStore.remove(profile.id);
        }
    }

    /** 发现未配对电脑：弹窗询问是否连接（忽略则本会话不再弹） */
    private void showPcConnectDialog(PcDiscovery.PcInfo pc) {
        var ctx = getActivity();
        if (ctx == null || pcDialogShowing) return;
        pcDialogShowing = true;
        var addr = pc.host + ":" + pc.port;
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.pc_found_title)
                .setMessage(getString(R.string.pc_found_msg, pc.name, addr))
                .setPositiveButton(R.string.pc_connect, (d, w) -> startPcPairing(pc))
                .setNegativeButton(R.string.pc_ignore, (d, w) -> pcIgnored.add(addr))
                .setOnCancelListener(d -> pcIgnored.add(addr))
                .setOnDismissListener(d -> pcDialogShowing = false)
                .show();
    }

    /** 发起配对：手机请求 → 电脑端弹窗 → 等待确认（可取消） */
    private void startPcPairing(PcDiscovery.PcInfo pc) {
        var ctx = getActivity();
        if (ctx == null) return;
        pcPairCancel.set(false);
        pcPairingDialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.pc_pairing_title)
                .setMessage(R.string.pc_pairing_msg)
                .setOnCancelListener(d -> pcPairCancel.set(true))
                .show();
        var deviceName = deviceDisplayName();
        com.suileyan.comm.Async.run("pc-pair", () -> {
            var r = PcPair.pair(pc.host, pc.port, deviceName, pcPairCancel);
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                dismissPairingDialog();
                if (r.status == PcPair.Result.Status.APPROVED) {
                    onPcPaired(pc, r);
                } else if (r.status == PcPair.Result.Status.DENIED) {
                    pcIgnored.add(pc.host + ":" + pc.port);
                    Toast.makeText(getActivity(), R.string.pc_pair_denied, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getActivity(), R.string.pc_pair_timeout, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void dismissPairingDialog() {
        if (pcPairingDialog != null) {
            try {
                pcPairingDialog.dismiss();
            } catch (Exception ignored) {
            }
            pcPairingDialog = null;
        }
    }

    /** 配对成功：落盘电脑方案（凭据入 EncryptedCredStore）→ 出现并选中「备份至 PC」 */
    private void onPcPaired(PcDiscovery.PcInfo pc, PcPair.Result r) {
        savePcProfile(pc.davUrl(), r.user, r.pass);
        try {
            var cfg = ConfigHelp.load();
            cfg.put("pc_paired_addr", pc.host + ":" + r.port);
            cfg.put("pc_paired_name", pc.name);
            ConfigHelp.save(cfg);
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "save pc paired addr failed", e);
        }
        updatePcOption();
        rbPc.setChecked(true); // listener 同步面板到 panel_pc
        updatePcStatusText(new PcDiscovery.PcInfo(pc.host, r.port, pc.name, pc.usb));
        Toast.makeText(getActivity(), getString(R.string.pc_paired_ok, pc.name), Toast.LENGTH_LONG).show();
        com.suileyan.comm.LogHelp.i("XpMiBackup", "PC paired: " + pc.host + ":" + r.port + " (" + pc.name + ")");
    }

    /** 更新 panel_pc 内的连接状态描述 */
    private void updatePcStatusText(PcDiscovery.PcInfo connected) {
        if (tvPcStatus == null || getView() == null) return;
        if (connected != null) {
            var via = connected.usb ? " · USB" : "";
            tvPcStatus.setText(getString(R.string.pc_status_connected)
                    + "：" + connected.name + "（" + connected.host + ":" + connected.port + via + "）");
        } else if (pcScanning) {
            tvPcStatus.setText(R.string.pc_status_scanning);
        } else if (pcFound.isEmpty()) {
            tvPcStatus.setText(R.string.pc_status_none);
        } else {
            tvPcStatus.setText(getString(R.string.pc_status_ignored, pcFound.get(0).name));
        }
    }

    /** 配对请求里展示的手机名（电脑弹窗与控制页显示） */
    private String deviceDisplayName() {
        var m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        var model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (m.isEmpty()) return model;
        if (model.startsWith(m)) return model;
        return m + " " + model;
    }

    // ---------- 备份流程 ----------

    /**
     * 开始备份。开启 Root 模块备份时先 su 打包 /data/adb 模块目录到 Transfer/
     * （成功后宿主 hook 在备份列表注入对应条目；失败仅提示并继续普通备份），再走原流程。
     */
    private void startBackup() {
        if (cbRootModules == null || !cbRootModules.isChecked()) {
            proceedStartBackup();
            return;
        }
        Toast.makeText(getActivity(), R.string.root_modules_packing, Toast.LENGTH_SHORT).show();
        com.suileyan.comm.Async.run("root-modules-tar", () -> {
            // 本 App 可见的管理器包名 → 推断「SukiSU/KernelSU/APatch/Magisk」动态命名
            var managers = new java.util.ArrayList<String>();
            for (var pair : com.suileyan.comm.RootModulesHelp.MANAGER_PACKAGES) {
                try {
                    getActivity().getPackageManager().getPackageInfo(pair[0], 0);
                    managers.add(pair[0]);
                } catch (Exception ignored) {
                }
            }
            var err = com.suileyan.comm.RootModulesHelp.createTarViaSu(
                    com.suileyan.comm.RootModulesHelp.modulesDir(), managers);
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (err != null) {
                    Toast.makeText(getActivity(),
                            getString(R.string.root_modules_pack_fail) + "\n" + err,
                            Toast.LENGTH_LONG).show();
                } else {
                    // 应用内新建的快照更新指纹，避免 onResume 误判为"原生还原"而弹提示
                    ModuleRestoreUi.markSeen(activity);
                }
                proceedStartBackup();
            });
        });
    }

    /** 原开始备份流程：NAS 方式 / 云盘方式 / 备份至 PC 设置目标后跳转小米智能存储备份页 */
    private void proceedStartBackup() {
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
        if (rbPc.isChecked()) {
            var pcProfile = findPcProfile();
            if (pcProfile == null) {
                Toast.makeText(getActivity(), R.string.pc_need_connect, Toast.LENGTH_LONG).show();
                return;
            }
            // 备份至 PC 复用 NAS 引擎链路：激活电脑方案，宿主 hook 按 profile 类型分发到 WebDAV
            rememberState("pc", pcProfile.id, "");
            ProfileStore.setActive(pcProfile.id);
            ProviderRegistry.clearCloudTarget();
            ProviderRegistry.invalidateAll();
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

    /** 跳转小米智能存储备份页（云盘/NAS/PC 共用） */
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

    /**
     * Root 模块备份开关的勾选检测（供勾选失败回退时重挂监听使用）
     */
    private void cbRootModulesListener(android.widget.CompoundButton b, boolean isChecked) {
        if (!isChecked) {
            saveRootModulesToggle(false);
            return;
        }
        b.setEnabled(false);
        Toast.makeText(getActivity(), R.string.su_checking, Toast.LENGTH_SHORT).show();
        com.suileyan.comm.Async.run("su-check", () -> {
            var ok = com.suileyan.comm.RootModulesHelp.hasSu();
            var activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (!isAdded()) return;
                b.setEnabled(true);
                if (ok) {
                    saveRootModulesToggle(true);
                } else {
                    b.setOnCheckedChangeListener(null);
                    b.setChecked(false);
                    b.setOnCheckedChangeListener(this::cbRootModulesListener);
                    saveRootModulesToggle(false);
                    Toast.makeText(getActivity(), R.string.su_missing, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void saveRootModulesToggle(boolean on) {
        try {
            var cfg = ConfigHelp.load();
            cfg.put("root_modules_backup", on ? "on" : "off");
            ConfigHelp.save(cfg);
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "save root modules toggle failed", e);
        }
    }

    /** 保存「上传完成后自动删除本地备份文件」开关（auto_delete_local=on/off） */
    private void saveAutoDeleteToggle(boolean on) {
        try {
            var cfg = ConfigHelp.load();
            cfg.put("auto_delete_local", on ? "on" : "off");
            ConfigHelp.save(cfg);
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "save auto delete toggle failed", e);
        }
    }

    /** 保存「逐项备份」开关（serial_upload=on 时上传线程池钳到 1，宿主按一项 100% 推进） */
    private void saveSerialUploadToggle(boolean on) {
        try {
            var cfg = ConfigHelp.load();
            cfg.put("serial_upload", on ? "on" : "off");
            ConfigHelp.save(cfg);
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "save serial upload toggle failed", e);
        }
    }

    /** 恢复 Root 模块：选择快照 → 二次确认 → su 解包回 /data/adb */
    private void showRestoreModulesDialog() {
        var activity = getActivity();
        if (activity == null) return;
        ModuleRestoreUi.pickAndRestore(activity);
    }

    /**
     * 恢复上次备份方式与目标选择（记忆功能）
     * config.ini 保存 backup_method / last_profile_id / last_cloud_account_id
     */
    private void restoreLastState() {
        try {
            var cfg = com.suileyan.comm.ConfigHelp.load();
            // 123 云盘已撤销支持：历史备份目标若仍指向 123 账号，清除并回退，
            // 避免跨进程 BackupTarget 继续驱动引擎向不可用的 123 上传
            var tId = com.suileyan.cloud.BackupTarget.cloudAccountId();
            if (tId != null) {
                var tAcc = com.suileyan.cloud.CloudAccountStore.get(tId);
                if (tAcc != null && com.suileyan.cloud.CloudAccount.PROVIDER_123.equals(tAcc.provider)) {
                    com.suileyan.cloud.ProviderRegistry.clearCloudTarget();
                    com.suileyan.comm.LogHelp.w("XpMiBackup",
                            "backup target was 123 (withdrawn), cleared to fallback");
                }
            }
            var method = cfg.optString("backup_method", "nas");
            if ("cloud".equals(method)) {
                rbCloud.setChecked(true);
                // 显式同步面板（不依赖 setChecked 触发 listener 的时序）
                syncPanels(R.id.rb_cloud);
                var lastCloud = cfg.optString("last_cloud_account_id", "");
                for (var i = 0; i < cloudAccounts.size(); i++) {
                    if (cloudAccounts.get(i).id.equals(lastCloud)) {
                        cloudSpinner.setSelection(i);
                        break;
                    }
                }
            } else if ("pc".equals(method) && findPcProfile() != null) {
                rbPc.setChecked(true);
                syncPanels(R.id.rb_pc);
            } else {
                rbNas.setChecked(true);
                syncPanels(R.id.rb_nas);
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

    /** 配对成功后把电脑保存为「电脑备份」方案（同名复用保留凭据位置）并设为激活 */
    private void savePcProfile(String url, String user, String pass) {
        var pid = java.util.UUID.randomUUID().toString();
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("webdav_url", url);
        params.put("webdav_user", user);
        var profile = new Profile(pid, PC_PROFILE_NAME, Profile.TYPE_WEBDAV, System.currentTimeMillis(), params);
        var saved = ProfileStore.upsertByName(PC_PROFILE_NAME, profile);
        com.suileyan.cloud.EncryptedCredStore.put(saved.id, "webdav_pass", pass);
        ProfileStore.setActive(saved.id);
        ProviderRegistry.clearCloudTarget();
        ProviderRegistry.invalidateAll();
        rememberState("pc", saved.id, "");
        loadProfiles();
        for (var i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(saved.id)) {
                profileSpinner.setSelection(i);
                break;
            }
        }
        com.suileyan.comm.LogHelp.i("XpMiBackup", "PC backup profile saved/activated: " + saved.id);
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
                    // 123 云盘已撤销支持（接口不稳定）：目标选择隐藏，同 115 策略
                    if (com.suileyan.cloud.CloudAccount.PROVIDER_123.equals(a.provider)) continue;
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

    private String typeLabel(String type) {
        if (Profile.TYPE_SMB.equals(type)) return getString(R.string.account_type_smb);
        if (Profile.TYPE_WEBDAV.equals(type)) return getString(R.string.account_type_webdav);
        if (Profile.TYPE_SCRIPT.equals(type)) return getString(R.string.account_type_custom);
        return type;
    }
}
