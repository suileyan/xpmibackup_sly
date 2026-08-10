package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
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
 * 设备配置界面
 * 管理设备名称、备份路径、最大备份数、设置页面描述文字的读写
 */
public class DeviceConfigFragment extends Fragment {

    private static final String TAG = "XpMiBackup";
    private EditText etDeviceName, etBackupPath, etMaxBackups, etSettingsSummary;
    private Switch swLogEnabled;

    /**
     * 创建设备配置界面视图
     * 绑定输入框控件，加载已有配置，注册保存按钮点击事件
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var t0 = System.currentTimeMillis();
        var view = inflater.inflate(R.layout.fragment_device_config, container, false);
        etDeviceName = view.findViewById(R.id.et_device_name);
        etSettingsSummary = view.findViewById(R.id.et_device_describe);
        etBackupPath = view.findViewById(R.id.et_backup_path);
        etMaxBackups = view.findViewById(R.id.et_backup_max);
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

        // 底部链接
        var tvFooter = (TextView) view.findViewById(R.id.tv_footer);
        tvFooter.setOnClickListener(v -> {
            try {
                var uri = Uri.parse(getString(R.string.author_url));
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                // 无浏览器/Intent 不可解析时提示而非崩溃（MED-39）
                LogHelp.e(TAG, "open author url failed", e);
            }
        });

        return view;
    }

    /**
     * 从配置文件读取所有配置项，填充到输入框
     */
    private void loadConfig() {
        // 读取配置
        var cfg = com.suileyan.comm.ConfigHelp.load();
        // 显示到页面
        etDeviceName.setText(cfg.optString("device_name", ""));
        etSettingsSummary.setText(cfg.optString("device_describe", ""));
        etBackupPath.setText(cfg.optString("backup_path", ""));
        etMaxBackups.setText(cfg.optString("backup_max", "5"));
        swLogEnabled.setChecked("true".equalsIgnoreCase(cfg.optString("log_enabled", "false")));
    }

    /**
     * 将输入框内容保存到配置文件
     * 增加输入校验（LOW-45）：备份路径非空、最大备份数为合法数字
     *
     * @return 是否保存成功（校验失败或 IO 异常返回 false）
     */
    private boolean saveConfig() {
        try {
            var cfg = com.suileyan.comm.ConfigHelp.load();
            var name = etDeviceName.getText().toString().trim();
            var describe = etSettingsSummary.getText().toString().trim();
            var path = etBackupPath.getText().toString().trim();
            var count = etMaxBackups.getText().toString().trim();

            if (name.isEmpty()) {
                Toast.makeText(getActivity(), R.string.toast_device_name_required, Toast.LENGTH_SHORT).show();
                return false;
            }
            if (path.isEmpty()) {
                Toast.makeText(getActivity(), R.string.toast_backup_path_required, Toast.LENGTH_SHORT).show();
                return false;
            }
            var maxBackups = 5;
            try {
                maxBackups = Integer.parseInt(count);
                if (maxBackups < 0) throw new NumberFormatException("negative");
            } catch (NumberFormatException e) {
                Toast.makeText(getActivity(), R.string.toast_backup_max_invalid, Toast.LENGTH_SHORT).show();
                return false;
            }

            cfg.put("device_id", generateDeviceId(name));
            cfg.put("device_name", name);
            cfg.put("device_describe", describe);
            cfg.put("backup_path", path);
            cfg.put("backup_max", String.valueOf(maxBackups));
            cfg.put("log_enabled", swLogEnabled.isChecked() ? "true" : "false");
            com.suileyan.comm.ConfigHelp.save(cfg);
            return true;
        } catch (Exception e) {
            LogHelp.e(TAG, "save device config failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 生成设备ID：对设备名称取MD5后取前6位
     * 空名称回退为固定前缀+时间戳，避免所有空名设备生成相同 ID（LOW-44）
     */
    private String generateDeviceId(String name) {
        try {
            if (name == null || name.isEmpty()) {
                name = "miback-" + System.currentTimeMillis();
            }
            var md = java.security.MessageDigest.getInstance("MD5");
            var hash = md.digest(name.getBytes("UTF-8"));
            var sb = new StringBuilder();
            for (var i = 0; i < 3; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return name;
        }
    }
}
