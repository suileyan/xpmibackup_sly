package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.LogHelp;
import com.suileyan.xpmibackup.MainActivity;
import com.suileyan.xpmibackup.R;

/**
 * 设置页
 * - 应用主题：白昼 / 黑夜 / 跟随系统（config.ini theme_mode，UiModeManager 应用）
 * - 预测性返回开关（config.ini predictive_back，控制 MainActivity 手势返回动画）
 */
public class SettingsFragment extends Fragment {

    private static final String TAG = "XpMiBackup";

    private RadioGroup rgTheme;
    private android.widget.Switch swPredictiveBack;
    private android.widget.Switch swUpdateCheck;
    /** 初始化标志：避免初始化 setChecked 触发监听器导致重复保存 */
    private boolean initialized = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_settings, container, false);

        rgTheme = view.findViewById(R.id.rg_theme);
        swPredictiveBack = view.findViewById(R.id.sw_predictive_back);
        swUpdateCheck = view.findViewById(R.id.sw_update_check);

        // 读取当前设置
        var themeMode = ConfigHelp.getString("theme_mode", "system");
        if ("day".equals(themeMode)) {
            rgTheme.check(R.id.rb_theme_day);
        } else if ("night".equals(themeMode)) {
            rgTheme.check(R.id.rb_theme_night);
        } else {
            rgTheme.check(R.id.rb_theme_system);
        }
        swPredictiveBack.setChecked(!"off".equals(ConfigHelp.getString("predictive_back", "on")));
        swUpdateCheck.setChecked(!"off".equals(ConfigHelp.getString("update_check", "on")));
        initialized = true;

        rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            if (!initialized) return;
            String mode;
            if (checkedId == R.id.rb_theme_day) {
                mode = "day";
            } else if (checkedId == R.id.rb_theme_night) {
                mode = "night";
            } else {
                mode = "system";
            }
            try {
                var cfg = ConfigHelp.load();
                cfg.put("theme_mode", mode);
                ConfigHelp.save(cfg);
            } catch (Exception e) {
                LogHelp.w(TAG, "save theme mode failed", e);
            }
            // 应用主题：setApplicationNightMode 异步生效；窗口淡出后重建，重建时配置已切换完成
            var activity = getActivity();
            if (activity == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                var uiModeManager = (android.app.UiModeManager) activity.getSystemService(android.content.Context.UI_MODE_SERVICE);
                if (uiModeManager != null) {
                    if ("day".equals(mode)) {
                        uiModeManager.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_NO);
                    } else if ("night".equals(mode)) {
                        uiModeManager.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_YES);
                    } else {
                        uiModeManager.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_AUTO);
                    }
                }
            }
            // 标记：重建后淡入并重开设置页（停留在设置页，不回 Tab 页）
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).markThemeTransition();
            }
            // 柔和过渡：整窗淡出 → 重建 → MainActivity 淡入 + 恢复设置页
            var decor = activity.getWindow().getDecorView();
            decor.animate().alpha(0f).setDuration(220)
                    .withEndAction(activity::recreate).start();
        });

        swPredictiveBack.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!initialized) return;
            try {
                var cfg = ConfigHelp.load();
                cfg.put("predictive_back", isChecked ? "on" : "off");
                ConfigHelp.save(cfg);
            } catch (Exception e) {
                LogHelp.w(TAG, "save predictive back failed", e);
            }
            var activity = getActivity();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).updateBackInvoke();
            }
        });

        swUpdateCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!initialized) return;
            try {
                var cfg = ConfigHelp.load();
                cfg.put("update_check", isChecked ? "on" : "off");
                ConfigHelp.save(cfg);
            } catch (Exception e) {
                LogHelp.w(TAG, "save update check failed", e);
            }
        });

        return view;
    }
}
