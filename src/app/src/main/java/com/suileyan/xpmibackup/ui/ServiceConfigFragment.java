package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.content.Intent;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import android.widget.Button;

import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.Profile;
import com.suileyan.cloud.ProfileStore;
import com.suileyan.cloud.ProviderRegistry;
import com.suileyan.cloud.provider.ScriptProvider;
import com.suileyan.cloud.provider.SmbProvider;
import com.suileyan.cloud.provider.WebdavProvider;
import com.suileyan.comm.CustomHttpFileHelp;
import com.suileyan.comm.LogHelp;
import com.suileyan.xpmibackup.R;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NAS 配置界面（配置方案模式）
 * 支持多套命名配置方案：标题右侧下拉切换已保存方案，
 * 表单含配置名称 + 协议参数；测试通过后按名称保存并设为激活；
 * 敏感凭据按方案 id 存加密存储
 */
public class ServiceConfigFragment extends Fragment {

    private static final String TAG = "XpMiBackup";
    /** 测试专用临时凭据键，测试后清理 */
    private static final String TEST_KEY = "__test__";

    private Spinner profileSpinner;
    private EditText etProfileName;
    private RadioGroup rgProtocol;
    private RadioButton rbSmb, rbWebdav, rbCustom;
    private LinearLayout panelSmb, panelWebdav, panelCustom;
    private EditText etUploadThreads, etChunkSizeMb;
    private EditText etSmbServer, etSmbPort, etSmbShare, etSmbUser, etSmbPass;
    private EditText etWebdavUrl, etWebdavUser, etWebdavPass;
    private EditText etCustomScript;
    private Button btnSave;
    private LinearLayout testingPanel;

    /** 下拉方案列表缓存 */
    private List<Profile> profiles = new ArrayList<>();
    /** 当前编辑/选中的方案 id */
    private String editingProfileId = "";

    /**
     * 创建 NAS 配置界面视图
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_service_config, container, false);

        profileSpinner = view.findViewById(R.id.profile_spinner);
        etProfileName = view.findViewById(R.id.et_profile_name);
        rgProtocol = view.findViewById(R.id.rg_protocol);
        rbSmb = view.findViewById(R.id.rb_smb);
        rbWebdav = view.findViewById(R.id.rb_webdav);
        rbCustom = view.findViewById(R.id.rb_custom);
        panelSmb = view.findViewById(R.id.panel_smb);
        panelWebdav = view.findViewById(R.id.panel_webdav);
        panelCustom = view.findViewById(R.id.panel_custom);
        etUploadThreads = view.findViewById(R.id.et_upload_threads);
        etChunkSizeMb = view.findViewById(R.id.et_chunk_size_mb);
        etSmbServer = view.findViewById(R.id.et_smb_server);
        etSmbPort = view.findViewById(R.id.et_smb_port);
        etSmbShare = view.findViewById(R.id.et_smb_share);
        etSmbUser = view.findViewById(R.id.et_smb_user);
        etSmbPass = view.findViewById(R.id.et_smb_pass);
        etWebdavUrl = view.findViewById(R.id.et_webdav_url);
        etWebdavUser = view.findViewById(R.id.et_webdav_user);
        etWebdavPass = view.findViewById(R.id.et_webdav_pass);
        etCustomScript = view.findViewById(R.id.et_custom_script);
        etCustomScript.setVerticalScrollBarEnabled(true);
        etCustomScript.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        btnSave = view.findViewById(R.id.btn_save);
        testingPanel = view.findViewById(R.id.testing_panel);

        rgProtocol.setOnCheckedChangeListener((group, checkedId) -> showProtocolPanel(checkedId));
        btnSave.setOnClickListener(v -> saveAndTest());
        ((TextView) view.findViewById(R.id.tv_footer)).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.author_url))));
        });

        loadConfig();
        return view;
    }

    // ========== 加载 ==========

    /**
     * 填充方案下拉并加载激活方案
     * 方案列表（profiles.json 小文件）主线程读；敏感凭据解密放后台
     */
    private void loadConfig() {
        profiles = ProfileStore.list();
        var activeId = ProfileStore.getActiveId();
        refreshSpinner(activeId);

        // 全局配置
        var cfg = com.suileyan.comm.ConfigHelp.load();
        etUploadThreads.setText(cfg.optString("upload_threads", "3"));
        etChunkSizeMb.setText(cfg.optString("chunk_size_mb", "64"));

        var active = ProfileStore.getActive();
        if (active != null) {
            applyProfileToForm(active);
        } else {
            // 无方案（当前为未保存的配置）：命名自动为「新配置」，引导用户可命名并保存为多套配置之一
            etProfileName.setText(R.string.profile_unsaved_name);
            rbSmb.setChecked(true);
            showProtocolPanel(R.id.rb_smb);
        }
    }

    /**
     * 刷新方案下拉列表并选中指定方案
     */
    private void refreshSpinner(String selectedId) {
        var names = new ArrayList<String>();
        for (var p : profiles) {
            names.add(p.name != null && !p.name.isEmpty() ? p.name : typeLabel(p.type));
        }
        if (names.isEmpty()) {
            // 无任何已保存方案：下拉显示「新配置」占位，与命名区一致，引导多配置管理
            names.add(getString(R.string.profile_unsaved_name));
        }
        var adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);

        var index = -1;
        for (var i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(selectedId)) {
                index = i;
                break;
            }
        }
        if (index < 0 && profiles.size() > 0) index = 0;
        profileSpinner.setSelection(Math.max(index, 0));

        // 切换方案：加载该方案到表单并设为激活
        profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= profiles.size()) return;
                var profile = profiles.get(position);
                if (profile.id.equals(editingProfileId)) return;
                ProfileStore.setActive(profile.id);
                ProviderRegistry.invalidateAll();
                editingProfileId = profile.id;
                applyProfileToForm(profile);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    /**
     * 将方案参数填充到表单；敏感凭据后台加载
     */
    private void applyProfileToForm(Profile profile) {
        if (profile == null) return;
        editingProfileId = profile.id;
        etProfileName.setText(profile.name);

        var protocol = profile.type;
        rbSmb.setChecked(Profile.TYPE_SMB.equals(protocol));
        rbWebdav.setChecked(Profile.TYPE_WEBDAV.equals(protocol));
        rbCustom.setChecked(Profile.TYPE_SCRIPT.equals(protocol));
        showProtocolPanel(protocolCheckedId(protocol));

        etSmbServer.setText(profile.params.getOrDefault("smb_server", ""));
        etSmbPort.setText(profile.params.getOrDefault("smb_port", "445"));
        etSmbShare.setText(profile.params.getOrDefault("smb_share", ""));
        etSmbUser.setText(profile.params.getOrDefault("smb_user", ""));
        etWebdavUrl.setText(profile.params.getOrDefault("webdav_url", ""));
        etWebdavUser.setText(profile.params.getOrDefault("webdav_user", ""));

        // 敏感凭据与默认脚本后台加载
        final var profileId = profile.id;
        new Thread(() -> {
            try {
                var data = new SensitiveData();
                data.smbPass = EncryptedCredStore.get(profileId, "smb_pass");
                data.webdavPass = EncryptedCredStore.get(profileId, "webdav_pass");
                var script = decodeScript(EncryptedCredStore.get(profileId, "custom_script_b64"));
                data.script = script.isEmpty() ? CustomHttpFileHelp.getDefaultScript(getActivity()) : script;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    etSmbPass.setText(data.smbPass);
                    etWebdavPass.setText(data.webdavPass);
                    etCustomScript.setText(data.script);
                });
            } catch (Exception e) {
                LogHelp.e(TAG, "load sensitive data in background failed", e);
            }
        }, "XpMiBackup-load-config").start();
    }

    private static class SensitiveData {
        String smbPass = "";
        String webdavPass = "";
        String script = "";
    }

    // ========== 保存 ==========

    /**
     * 测试通过后按名称保存配置方案
     * 测试用临时凭据键，通过后写入真实方案 id；失败不保存
     */
    private void saveAndTest() {
        var name = etProfileName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(getActivity(), R.string.toast_profile_name_required, Toast.LENGTH_SHORT).show();
            return;
        }
        // 未保存配置的默认命名「新配置」：同名已存在时自动追加序号（新配置 2/3/…），
        // 避免覆盖已有方案，引导用户保存多套配置
        var savedName = name;
        if (savedName.equals(getString(R.string.profile_unsaved_name))) {
            savedName = uniqueProfileName(savedName, editingProfileId);
        }
        final var profileName = savedName;
        btnSave.setEnabled(false);
        btnSave.setText(R.string.testing_connection);
        testingPanel.setVisibility(View.VISIBLE);

        // 主线程快照表单值，后台线程不直接访问 EditText（MED-21）
        final var snapshot = new FormSnapshot();
        snapshot.type = selectedProtocol();
        snapshot.params = formParams();
        snapshot.smbPass = etSmbPass.getText().toString();
        snapshot.webdavPass = etWebdavPass.getText().toString();
        snapshot.customScript = encodeScript(etCustomScript.getText().toString());

        // 后台线程命名，便于排查（NEW-L-11）
        new Thread(() -> {
            var ok = testWithForm(snapshot);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                testingPanel.setVisibility(View.GONE);
                btnSave.setEnabled(true);
                btnSave.setText(R.string.test_then_save);
                if (ok) {
                    saveProfile(profileName, snapshot);
                    Toast.makeText(getActivity(), R.string.toast_profile_saved, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getActivity(), R.string.toast_profile_test_failed, Toast.LENGTH_LONG).show();
                }
            });
        }, "XpMiBackup-test-profile").start();
    }

    /** 表单快照：测试/保存均在后台线程使用，避免跨线程访问 EditText */
    private static class FormSnapshot {
        String type;
        Map<String, String> params;
        String smbPass;
        String webdavPass;
        String customScript;
    }

    /** 用快照构造临时方案测试连接（后台线程调用）；记录开始/结果/耗时与主机参数（不含任何凭据） */
    private boolean testWithForm(FormSnapshot snapshot) {
        var start = System.currentTimeMillis();
        LogHelp.i(TAG, "NAS test start: type=" + snapshot.type + " " + safeTestParams(snapshot.params));
        try {
            EncryptedCredStore.put(TEST_KEY, "smb_pass", snapshot.smbPass);
            EncryptedCredStore.put(TEST_KEY, "webdav_pass", snapshot.webdavPass);
            EncryptedCredStore.put(TEST_KEY, "custom_script_b64", snapshot.customScript);
            var tmpProfile = new Profile(TEST_KEY, "test", snapshot.type, System.currentTimeMillis(), snapshot.params);
            var ok = newProvider(snapshot.type, tmpProfile).testConnection();
            LogHelp.i(TAG, "NAS test " + (ok ? "OK" : "FAILED") + " type=" + snapshot.type
                    + " cost=" + (System.currentTimeMillis() - start) + "ms");
            return ok;
        } catch (Exception e) {
            LogHelp.e(TAG, "NAS test failed type=" + snapshot.type
                    + " cost=" + (System.currentTimeMillis() - start) + "ms", e);
            return false;
        } finally {
            EncryptedCredStore.removeAccount(TEST_KEY);
        }
    }

    /** 测试日志只输出非敏感连接参数（服务器/端口/共享/用户名/URL），密码与脚本内容绝不落日志 */
    private static String safeTestParams(Map<String, String> params) {
        if (params == null) return "";
        var sb = new StringBuilder();
        for (var key : new String[]{"smb_server", "smb_port", "smb_share", "smb_user", "webdav_url", "webdav_user"}) {
            var v = params.get(key);
            if (v != null && !v.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(key).append("=").append(v);
            }
        }
        return sb.toString();
    }

    /**
     * 默认「新配置」名唯一化：同名（排除当前编辑 id）已存在时追加序号（新配置 2/3/…），
     * 避免未命名保存覆盖已有方案，体现多配置管理能力
     */
    private static String uniqueProfileName(String name, String excludeId) {
        var profiles = ProfileStore.list();
        var taken = false;
        for (var p : profiles) {
            if (p.id.equals(excludeId)) continue;
            if (name.equals(p.name)) {
                taken = true;
                break;
            }
        }
        if (!taken) return name;
        for (var i = 2; ; i++) {
            var candidate = name + " " + i;
            var dup = false;
            for (var p : profiles) {
                if (p.id.equals(excludeId)) continue;
                if (candidate.equals(p.name)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) return candidate;
        }
    }

    /**
     * 按名称保存方案：同名覆盖，写入敏感凭据，设为激活
     */
    private void saveProfile(String name, FormSnapshot snapshot) {
        try {
            var type = snapshot.type;
            var newId = (editingProfileId != null && !editingProfileId.isEmpty())
                    ? editingProfileId : "pf_" + System.currentTimeMillis();
            var saved = ProfileStore.upsertByName(name, new Profile(newId, name, type, System.currentTimeMillis(), snapshot.params));

            // 敏感凭据写入最终方案 id
            EncryptedCredStore.put(saved.id, "smb_pass", snapshot.smbPass);
            EncryptedCredStore.put(saved.id, "webdav_pass", snapshot.webdavPass);
            EncryptedCredStore.put(saved.id, "custom_script_b64", snapshot.customScript);
            ProfileStore.setActive(saved.id);
            ProviderRegistry.invalidateAll();

            editingProfileId = saved.id;
            profiles = ProfileStore.list();
            refreshSpinner(saved.id);
        } catch (Exception e) {
            LogHelp.e(TAG, "save profile failed: " + e.getMessage(), e);
        }
    }

    /** 收集表单非敏感参数 */
    private Map<String, String> formParams() {
        var params = new LinkedHashMap<String, String>();
        params.put("smb_server", etSmbServer.getText().toString().trim());
        params.put("smb_port", etSmbPort.getText().toString().trim());
        params.put("smb_share", etSmbShare.getText().toString().trim());
        params.put("smb_user", etSmbUser.getText().toString().trim());
        params.put("webdav_url", etWebdavUrl.getText().toString().trim());
        params.put("webdav_user", etWebdavUser.getText().toString().trim());
        return params;
    }

    /** 按协议类型构造 Provider */
    private static CloudProvider newProvider(String type, Profile profile) {
        if (SmbProvider.TYPE.equals(type)) return new SmbProvider(profile);
        if (WebdavProvider.TYPE.equals(type)) return new WebdavProvider(profile);
        return new ScriptProvider(profile);
    }

    // ========== UI 工具 ==========

    private void showProtocolPanel(int checkedId) {
        panelSmb.setVisibility(View.GONE);
        panelWebdav.setVisibility(View.GONE);
        panelCustom.setVisibility(View.GONE);
        if (checkedId == R.id.rb_custom) {
            panelCustom.setVisibility(View.VISIBLE);
        } else if (checkedId == R.id.rb_webdav) {
            panelWebdav.setVisibility(View.VISIBLE);
        } else {
            panelSmb.setVisibility(View.VISIBLE);
        }
    }

    private int protocolCheckedId(String protocol) {
        if (ScriptProvider.TYPE.equals(protocol)) return R.id.rb_custom;
        if (WebdavProvider.TYPE.equals(protocol)) return R.id.rb_webdav;
        return R.id.rb_smb;
    }

    private String selectedProtocol() {
        if (rbCustom.isChecked()) return ScriptProvider.TYPE;
        if (rbWebdav.isChecked()) return WebdavProvider.TYPE;
        return SmbProvider.TYPE;
    }

    private String typeLabel(String type) {
        if (Profile.TYPE_SMB.equals(type)) return getString(R.string.account_type_smb);
        if (Profile.TYPE_WEBDAV.equals(type)) return getString(R.string.account_type_webdav);
        if (Profile.TYPE_SCRIPT.equals(type)) return getString(R.string.account_type_custom);
        return type;
    }

    private static String encodeScript(String script) {
        if (script == null || script.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeScript(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
