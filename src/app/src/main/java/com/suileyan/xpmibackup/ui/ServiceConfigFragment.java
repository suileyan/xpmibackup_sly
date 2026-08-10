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
    private RadioButton rbSmb, rbWebdav;
    private LinearLayout panelSmb, panelWebdav;
    private TextView tabNas, tabCustom;
    private LinearLayout containerNas, containerCustom;
    private LinearLayout containerScriptVars;
    private TextView tvScriptVarsTitle, tvScriptVarsHint;
    private TextView tvScriptHelp;
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
        var t0 = System.currentTimeMillis();
        var view = inflater.inflate(R.layout.fragment_service_config, container, false);

        profileSpinner = view.findViewById(R.id.profile_spinner);
        etProfileName = view.findViewById(R.id.et_profile_name);
        rgProtocol = view.findViewById(R.id.rg_protocol);
        rbSmb = view.findViewById(R.id.rb_smb);
        rbWebdav = view.findViewById(R.id.rb_webdav);
        panelSmb = view.findViewById(R.id.panel_smb);
        panelWebdav = view.findViewById(R.id.panel_webdav);
        tabNas = view.findViewById(R.id.tab_nas);
        tabCustom = view.findViewById(R.id.tab_custom);
        containerNas = view.findViewById(R.id.container_nas);
        containerCustom = view.findViewById(R.id.container_custom);
        containerScriptVars = view.findViewById(R.id.container_script_vars);
        tvScriptVarsTitle = view.findViewById(R.id.tv_script_vars_title);
        tvScriptVarsHint = view.findViewById(R.id.tv_script_vars_hint);
        tvScriptHelp = view.findViewById(R.id.tv_script_help);
        tvScriptHelp.setPaintFlags(tvScriptHelp.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
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
        // 智能滚动协调：脚本内容可内部滚动时才拦截父级 ScrollView（否则页面可下滑，
        // 用户能看到下方脚本配置项与保存按钮；脚本滚到底后继续滑动也交还页面）
        etCustomScript.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                v.getParent().requestDisallowInterceptTouchEvent(etCustomScript.canScrollVertically(1));
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        btnSave = view.findViewById(R.id.btn_save);
        testingPanel = view.findViewById(R.id.testing_panel);

        tabNas.setOnClickListener(v -> {
            showTab(false);
            refreshSpinnerForActiveTab();
        });
        tabCustom.setOnClickListener(v -> {
            showTab(true);
            refreshSpinnerForActiveTab();
        });
        tvScriptHelp.setOnClickListener(v -> openScriptHelp());
        // 脚本内容变化 → 重新扫描占位符 → 重建动态配置项（保留已填值）
        etCustomScript.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                rebuildScriptVars();
            }
        });
        rgProtocol.setOnCheckedChangeListener((group, checkedId) -> showProtocolPanel(checkedId));
        btnSave.setOnClickListener(v -> saveAndTest());
        ((TextView) view.findViewById(R.id.tv_footer)).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.author_url))));
        });

        loadConfig();
        com.suileyan.comm.LogHelp.i(TAG, "STARTUP ServiceConfigFragment onCreateView: " + (System.currentTimeMillis() - t0) + "ms");
        return view;
    }

    // ========== 加载 ==========

    /**
     * 填充方案下拉并加载激活方案
     * 方案列表（profiles.json 小文件）主线程读；敏感凭据解密放后台
     */
    private void loadConfig() {
        profiles = ProfileStore.list();

        // 全局配置
        var cfg = com.suileyan.comm.ConfigHelp.load();
        etUploadThreads.setText(cfg.optString("upload_threads", "3"));
        etChunkSizeMb.setText(cfg.optString("chunk_size_mb", "64"));

        var active = ProfileStore.getActive();
        if (active != null) {
            // 先按激活方案类型定选项卡，再刷新对应类型下拉（setSelection 触发 onItemSelected → applyProfileToForm）
            var isScript = Profile.TYPE_SCRIPT.equals(active.type);
            showTab(isScript);
            refreshSpinner(active.id, isScript);
        } else {
            // 无方案（当前为未保存的配置）：命名自动为「新配置」，引导用户可命名并保存为多套配置之一
            etProfileName.setText(R.string.profile_unsaved_name);
            showTab(false);
            refreshSpinner("", false);
            rbSmb.setChecked(true);
            showProtocolPanel(R.id.rb_smb);
        }
    }

    /**
     * 刷新方案下拉列表并选中指定方案。
     * 下拉只显示指定选项卡类型的方案：NAS 配置（custom=false）→ smb/webdav；自定义配置（custom=true）→ script
     */
    private void refreshSpinner(String selectedId, boolean custom) {
        var filtered = new ArrayList<Profile>();
        for (var p : profiles) {
            var isScript = Profile.TYPE_SCRIPT.equals(p.type);
            if (custom == isScript) filtered.add(p);
        }
        var names = new ArrayList<String>();
        for (var p : filtered) {
            names.add(p.name != null && !p.name.isEmpty() ? p.name : typeLabel(p.type));
        }
        if (names.isEmpty()) {
            // 当前选项卡下无已保存方案：下拉显示「新配置」占位，与命名区一致，引导多配置管理
            names.add(getString(R.string.profile_unsaved_name));
        }
        var adapter = new ArrayAdapter<>(getActivity(), android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);

        var index = -1;
        for (var i = 0; i < filtered.size(); i++) {
            if (filtered.get(i).id.equals(selectedId)) {
                index = i;
                break;
            }
        }
        if (index < 0 && filtered.size() > 0) index = 0;
        profileSpinner.setSelection(Math.max(index, 0));

        // 切换方案：加载该方案到表单并设为激活
        profileSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filtered.size()) return;
                var profile = filtered.get(position);
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
        var isScript = Profile.TYPE_SCRIPT.equals(protocol);
        // 脚本方案切到「自定义配置」选项卡；NAS 方案切回 NAS 选项卡并按协议勾选
        showTab(isScript);
        if (!isScript) {
            rbSmb.setChecked(Profile.TYPE_SMB.equals(protocol));
            rbWebdav.setChecked(Profile.TYPE_WEBDAV.equals(protocol));
            // RadioGroup 勾选已触发 onCheckedChangeListener → showProtocolPanel，无需显式调用
        }

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
        snapshot.scriptVars = collectScriptVars();

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
        /** 脚本占位符配置项（name → value），写入 EncryptedCredStore 的 script_var_<name> */
        Map<String, String> scriptVars;
    }

    /** 用快照构造临时方案测试连接（后台线程调用）；记录开始/结果/耗时与主机参数（不含任何凭据） */
    private boolean testWithForm(FormSnapshot snapshot) {
        var start = System.currentTimeMillis();
        LogHelp.i(TAG, "NAS test start: type=" + snapshot.type + " " + safeTestParams(snapshot.params));
        try {
            EncryptedCredStore.put(TEST_KEY, "smb_pass", snapshot.smbPass);
            EncryptedCredStore.put(TEST_KEY, "webdav_pass", snapshot.webdavPass);
            EncryptedCredStore.put(TEST_KEY, "custom_script_b64", snapshot.customScript);
            if (snapshot.scriptVars != null) {
                for (var e : snapshot.scriptVars.entrySet()) {
                    EncryptedCredStore.put(TEST_KEY, "script_var_" + e.getKey(), e.getValue());
                }
            }
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
            if (snapshot.scriptVars != null) {
                for (var e : snapshot.scriptVars.entrySet()) {
                    EncryptedCredStore.put(saved.id, "script_var_" + e.getKey(), e.getValue());
                }
            }
            ProfileStore.setActive(saved.id);
            ProviderRegistry.invalidateAll();

            editingProfileId = saved.id;
            profiles = ProfileStore.list();
            refreshSpinner(saved.id, Profile.TYPE_SCRIPT.equals(saved.type));
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

    /** 切换「NAS 配置 / 自定义配置」选项卡（脚本为独立选项卡，不再作为协议单选）。
     * 仅切换 UI 状态；下拉刷新由调用方显式触发（避免 setSelection→onItemSelected→applyProfileToForm→showTab 循环） */
    private void showTab(boolean custom) {
        tabNas.setSelected(!custom);
        tabCustom.setSelected(custom);
        tabNas.setBackgroundResource(custom ? R.drawable.bg_tab_normal : R.drawable.bg_tab_selected);
        tabCustom.setBackgroundResource(custom ? R.drawable.bg_tab_selected : R.drawable.bg_tab_normal);
        containerNas.setVisibility(custom ? View.GONE : View.VISIBLE);
        containerCustom.setVisibility(custom ? View.VISIBLE : View.GONE);
        tabNas.setTextColor(getResources().getColor(custom ? R.color.text_disabled : R.color.primary_text_on));
        tabCustom.setTextColor(getResources().getColor(custom ? R.color.primary_text_on : R.color.text_disabled));
        // 「如何自定义脚本」链接仅自定义配置选项卡显示（NAS 选项卡无脚本上下文）
        tvScriptHelp.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    /** 按当前选项卡刷新下拉：选中该类型下当前激活方案（不在该类型则回退第一个） */
    private void refreshSpinnerForActiveTab() {
        var custom = tabCustom.isSelected();
        var active = ProfileStore.getActive();
        refreshSpinner(active != null && (Profile.TYPE_SCRIPT.equals(active.type) == custom)
                ? active.id : "", custom);
    }

    /**
     * 扫描脚本中的 %*名称*% 占位符，为每个占位符生成配置项输入框（值加密存 script_var_<name>）。
     * 重建时保留已填值；新占位符从 EncryptedCredStore 回填
     */
    private void rebuildScriptVars() {
        if (containerScriptVars == null) return;
        var scriptText = etCustomScript.getText().toString();
        var names = CustomHttpFileHelp.extractScriptPlaceholders(scriptText);

        // 保留当前已填值（编辑脚本时用户输入不丢失）
        var current = new LinkedHashMap<String, String>();
        for (var i = 0; i < containerScriptVars.getChildCount(); i++) {
            var child = containerScriptVars.getChildAt(i);
            if (child instanceof LinearLayout row && row.getTag() instanceof String name) {
                var et = (EditText) row.findViewById(R.id.et_script_var);
                if (et != null) current.put(name, et.getText().toString());
            }
        }

        containerScriptVars.removeAllViews();
        var show = !names.isEmpty();
        tvScriptVarsTitle.setVisibility(show ? View.VISIBLE : View.GONE);
        tvScriptVarsHint.setVisibility(show ? View.VISIBLE : View.GONE);
        containerScriptVars.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        for (var name : names) {
            var row = new LinearLayout(getActivity());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setTag(name);

            var label = new TextView(getActivity());
            label.setText(name);
            label.setTextSize(13f);
            label.setTextColor(getResources().getColor(R.color.text_secondary));
            row.addView(label);

            var et = new EditText(getActivity());
            et.setId(R.id.et_script_var);
            et.setSingleLine(true);
            et.setTextSize(14f);
            et.setTextColor(getResources().getColor(R.color.text_primary));
            et.setHintTextColor(getResources().getColor(R.color.text_hint));
            et.setBackgroundResource(R.drawable.bg_input);
            et.setPadding(dp(12), dp(8), dp(12), dp(8));
            // 已填值优先；否则回填加密存储中的旧值（加载方案场景）
            var value = current.containsKey(name) ? current.get(name)
                    : EncryptedCredStore.get(editingProfileId, "script_var_" + name);
            if (value == null) value = "";
            et.setText(value);
            var lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(16);
            row.addView(et, lp);
            containerScriptVars.addView(row);
        }
    }

    /** 收集脚本占位符配置项值（name → value，不含空值） */
    private Map<String, String> collectScriptVars() {
        var map = new LinkedHashMap<String, String>();
        if (containerScriptVars == null) return map;
        for (var i = 0; i < containerScriptVars.getChildCount(); i++) {
            var child = containerScriptVars.getChildAt(i);
            if (child instanceof LinearLayout row && row.getTag() instanceof String name) {
                var et = (EditText) row.findViewById(R.id.et_script_var);
                if (et != null) {
                    var v = et.getText().toString().trim();
                    if (!v.isEmpty()) map.put(name, v);
                }
            }
        }
        return map;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void showProtocolPanel(int checkedId) {
        panelSmb.setVisibility(checkedId == R.id.rb_webdav ? View.GONE : View.VISIBLE);
        panelWebdav.setVisibility(checkedId == R.id.rb_webdav ? View.VISIBLE : View.GONE);
    }

    private String selectedProtocol() {
        if (tabCustom.isSelected()) return ScriptProvider.TYPE;
        if (rbWebdav.isChecked()) return WebdavProvider.TYPE;
        return SmbProvider.TYPE;
    }

    private String typeLabel(String type) {
        if (Profile.TYPE_SMB.equals(type)) return getString(R.string.account_type_smb);
        if (Profile.TYPE_WEBDAV.equals(type)) return getString(R.string.account_type_webdav);
        if (Profile.TYPE_SCRIPT.equals(type)) return getString(R.string.account_type_custom);
        return type;
    }

    /** 打开「如何自定义脚本」帮助页（overlay 二级页面，与云盘登录页同款动画/返回栈） */
    private void openScriptHelp() {
        var help = new ScriptHelpFragment();
        var ft = getFragmentManager().beginTransaction();
        var overlay = getActivity() != null ? getActivity().findViewById(R.id.overlay_container) : null;
        if (overlay != null) {
            overlay.setTranslationX(0f);
            overlay.setVisibility(View.VISIBLE);
        }
        ft.setCustomAnimations(R.animator.slide_in_right, R.animator.no_anim,
                R.animator.no_anim, R.animator.no_anim);
        ft.add(R.id.overlay_container, help);
        ft.addToBackStack("script-help");
        ft.commit();
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
