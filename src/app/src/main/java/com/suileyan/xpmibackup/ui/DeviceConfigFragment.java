package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.suileyan.xpmibackup.R;
import com.suileyan.comm.LogHelp;

/**
 * 备份配置界面（原「设备配置」）
 * 管理备份路径、最大备份数、上传线程数（切片并发）、切片大小、备份项并发与日志开关。
 *
 * 设备名称 / 设备描述输入已移除：两者不参与备份链路的任何判定，
 * 其中 device_name 仅作为宿主 DFS 上报的设备名，保留配置键与默认值即可。
 */
public class DeviceConfigFragment extends Fragment {

    private static final String TAG = "XpMiBackup";

    /** 并发/切片配置的合法上限：与 ConfigHelp 的钳制保持一致，避免保存后被静默截断 */
    private static final int MAX_CHUNK_THREADS = 64;
    private static final int MAX_ITEM_THREADS = 16;
    private static final int MAX_CHUNK_SIZE_MB = 1024;

    private EditText etBackupPath, etMaxBackups, etChunkThreads, etChunkSizeMb, etItemThreads;
    private Switch swLogEnabled;

    /**
     * 创建备份配置界面视图
     * 绑定输入框控件，加载已有配置，注册保存按钮点击事件
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var t0 = System.currentTimeMillis();
        var view = inflater.inflate(R.layout.fragment_device_config, container, false);
        // 底部预留：内容穿过悬浮底栏背后（clipToPadding=false），滚到底时最后一项滚出遮挡范围
        if (getActivity() instanceof com.suileyan.xpmibackup.MainActivity main) {
            com.suileyan.xpmibackup.MainActivity.applyBottomClearance(view, main.getBottomContentPadding());
        }
        etBackupPath = view.findViewById(R.id.et_backup_path);
        etMaxBackups = view.findViewById(R.id.et_backup_max);
        etChunkThreads = view.findViewById(R.id.et_chunk_threads);
        etChunkSizeMb = view.findViewById(R.id.et_chunk_size_mb);
        etItemThreads = view.findViewById(R.id.et_item_threads);
        swLogEnabled = view.findViewById(R.id.sw_log_enabled);
        var btnSave = view.findViewById(R.id.btn_save);

        // 加载配置
        loadConfig();
        com.suileyan.comm.LogHelp.i(TAG, "STARTUP DeviceConfigFragment onCreateView: " + (System.currentTimeMillis() - t0) + "ms");

        // 点击事件：校验通过才提示保存成功（NEW-M-01）
        btnSave.setOnClickListener(v -> {
            if (saveConfig()) {
                Toast.makeText(getActivity(), R.string.toast_config_saved, Toast.LENGTH_SHORT).show();
            }
        });

        // 底部链接：Powered by 两位作者 + 脚本帮助（与 NAS 页一致，各自独立跳转）
        var tvFooterZgcwkj = (TextView) view.findViewById(R.id.tv_footer_zgcwkj);
        tvFooterZgcwkj.setPaintFlags(tvFooterZgcwkj.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvFooterZgcwkj.setOnClickListener(v -> openUrl(getString(R.string.author_url)));
        var tvFooterSuileyan = (TextView) view.findViewById(R.id.tv_footer_suileyan);
        tvFooterSuileyan.setPaintFlags(tvFooterSuileyan.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvFooterSuileyan.setOnClickListener(v -> openUrl(getString(R.string.suileyan_url)));
        var tvScriptHelp = (TextView) view.findViewById(R.id.tv_script_help);
        tvScriptHelp.setOnClickListener(v -> openScriptHelp());

        return view;
    }

    /**
     * 从配置文件读取所有配置项，填充到输入框
     */
    private void loadConfig() {
        var cfg = com.suileyan.comm.ConfigHelp.load();
        etBackupPath.setText(cfg.optString("backup_path", ""));
        etMaxBackups.setText(cfg.optString("backup_max", "5"));
        etChunkThreads.setText(cfg.optString("chunk_threads", "8"));
        etChunkSizeMb.setText(cfg.optString("chunk_size_mb", "64"));
        // 备份项并发走 ConfigHelp.itemThreads()：兼顾旧键 serial_upload（逐项备份）/ upload_threads
        etItemThreads.setText(String.valueOf(com.suileyan.comm.ConfigHelp.itemThreads()));
        swLogEnabled.setChecked(!cfg.has("log_enabled") || "true".equalsIgnoreCase(cfg.optString("log_enabled", "true")));
    }

    /**
     * 将输入框内容保存到配置文件
     * 校验（LOW-45）：备份路径非空、最大备份数为非负整数、三个并发/切片项在合法区间内
     *
     * @return 是否保存成功（校验失败或 IO 异常返回 false）
     */
    private boolean saveConfig() {
        try {
            var cfg = com.suileyan.comm.ConfigHelp.load();
            var path = etBackupPath.getText().toString().trim();
            if (path.isEmpty()) {
                Toast.makeText(getActivity(), R.string.toast_backup_path_required, Toast.LENGTH_SHORT).show();
                return false;
            }
            var maxBackups = parseRange(etMaxBackups, 0, Integer.MAX_VALUE, R.string.toast_backup_max_invalid);
            if (maxBackups < 0) return false;
            var chunkThreads = parseRange(etChunkThreads, 1, MAX_CHUNK_THREADS, R.string.toast_chunk_threads_invalid);
            if (chunkThreads < 0) return false;
            var chunkSizeMb = parseRange(etChunkSizeMb, 0, MAX_CHUNK_SIZE_MB, R.string.toast_chunk_size_invalid);
            if (chunkSizeMb < 0) return false;
            var itemThreads = parseRange(etItemThreads, 1, MAX_ITEM_THREADS, R.string.toast_item_threads_invalid);
            if (itemThreads < 0) return false;

            cfg.put("backup_path", path);
            cfg.put("backup_max", String.valueOf(maxBackups));
            cfg.put("chunk_threads", String.valueOf(chunkThreads));
            cfg.put("chunk_size_mb", String.valueOf(chunkSizeMb));
            cfg.put("item_threads", String.valueOf(itemThreads));
            cfg.put("log_enabled", swLogEnabled.isChecked() ? "true" : "false");
            // 旧并发键已被 item_threads 取代：保存时一并清掉，避免新旧两套键长期并存
            cfg.remove("upload_threads");
            cfg.remove("serial_upload");
            // 设备ID 不再由界面维护：保留已有值，缺失时兜底（备份前置校验要求非空）
            if (cfg.optString("device_id", "").trim().isEmpty()) {
                cfg.put("device_id", "miback");
            }
            com.suileyan.comm.ConfigHelp.save(cfg);
            return true;
        } catch (Exception e) {
            LogHelp.e(TAG, "save backup config failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 解析并校验 [min, max] 区间内的整数；非法或越界时弹出对应提示并返回 -1
     */
    private int parseRange(EditText field, int min, int max, int errRes) {
        try {
            var v = Integer.parseInt(field.getText().toString().trim());
            if (v < min || v > max) throw new NumberFormatException("out of range");
            return v;
        } catch (NumberFormatException e) {
            Toast.makeText(getActivity(), errRes, Toast.LENGTH_SHORT).show();
            return -1;
        }
    }

    /** 打开 http(s) 链接，非 http(s) 拦截；无浏览器时静默失败（与 NAS 页一致） */
    private void openUrl(String url) {
        if (url == null) return;
        try {
            var uri = Uri.parse(url);
            var scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                LogHelp.w(TAG, "blocked non-http(s) url: " + url);
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            LogHelp.e(TAG, "open url failed: " + url, e);
        }
    }

    /** 打开「如何自定义脚本」说明页（overlay 叠加，与 NAS 页一致） */
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
}
