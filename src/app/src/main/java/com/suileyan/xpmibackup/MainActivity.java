package com.suileyan.xpmibackup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.suileyan.comm.Async;
import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.LogHelp;
import com.suileyan.comm.UpdateChecker;

/**
 * 云备份助手主界面
 * 底部Tab切换：设备配置 / NAS / 云盘 / 备份
 * 采用"桌面滑动"式布局：4 个 Tab 页面常驻同一容器横向排开，
 * 切换时整体平移（中间 Tab 可见，像桌面翻页）；二级页面（云盘登录流程）用 overlay 容器压层
 * 通过Xposed Hook注入到小米设置"小米澎湃AI"下方，点击跳转至此
 */
public class MainActivity extends Activity {

    /** Tab 顺序 */
    private static final String[] TAB_NAMES = {"device", "service", "account", "backup"};
    private static final int TAB_COUNT = TAB_NAMES.length;
    private static final long SLIDE_MS = 280;

    /** 底部Tab图标和文字控件，用于切换时更新选中/未选中颜色 */
    private ImageView tabDeviceIcon, tabServiceIcon, tabAccountIcon, tabBackupIcon;
    private TextView tabDeviceText, tabServiceText, tabAccountText, tabBackupText;

    private android.widget.FrameLayout tabContainer;
    private android.widget.FrameLayout overlayContainer;
    /** 当前显示 Tab 下标 */
    private int currentIndex = 0;
    /** 4 个 Tab 页面是否已初始化（防重复 add） */
    private boolean tabsReady = false;
    /** 二次返回确认：记录上次返回键时间 */
    private long lastBackPressTime = 0;
    /** 主题切换重建标志：重建后淡入 + 重开设置页 */
    private boolean themeTransition = false;
    /** 启动版本检测只执行一次（进程内），使用过程中不再自动检测 */
    private static volatile boolean sUpdateChecked = false;

    /**
     * 初始化界面：绑定Tab控件，注册切换事件，检查文件管理权限
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 恢复主题切换重建前的 Tab 位置（切主题后停留在原页面，不回到设备配置页）
        if (savedInstanceState != null) {
            currentIndex = savedInstanceState.getInt("current_index", 0);
            if (currentIndex < 0 || currentIndex >= TAB_COUNT) currentIndex = 0;
            // 主题切换标志跨重建保留（淡入 + 重开设置页）
            themeTransition = savedInstanceState.getBoolean("theme_transition", false);
        }
        // 应用主题设置（白昼/黑夜/跟随系统），在加载资源前生效
        applyTheme();
        getWindow().setStatusBarColor(getResources().getColor(R.color.status_bar));
        // 浅色主题状态栏为浅色，状态栏图标用深色（LSPosed 黑白灰风格）
        if (getResources().getBoolean(R.bool.light_status_bar_icons)) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        setContentView(R.layout.activity_main);

        tabDeviceIcon = findViewById(R.id.tab_device_icon);
        tabDeviceText = findViewById(R.id.tab_device_text);
        tabServiceIcon = findViewById(R.id.tab_service_icon);
        tabServiceText = findViewById(R.id.tab_service_text);
        tabAccountIcon = findViewById(R.id.tab_account_icon);
        tabAccountText = findViewById(R.id.tab_account_text);
        tabBackupIcon = findViewById(R.id.tab_backup_icon);
        tabBackupText = findViewById(R.id.tab_backup_text);
        tabContainer = findViewById(R.id.fragment_container);
        overlayContainer = findViewById(R.id.overlay_container);

        findViewById(R.id.tab_device).setOnClickListener(v -> switchTabByIndex(0));
        findViewById(R.id.tab_service).setOnClickListener(v -> switchTabByIndex(1));
        findViewById(R.id.tab_account).setOnClickListener(v -> switchTabByIndex(2));
        findViewById(R.id.tab_backup).setOnClickListener(v -> switchTabByIndex(3));
        // 顶部菜单：设置 / 关于
        findViewById(R.id.btn_top_menu).setOnClickListener(v -> showTopMenu());

        // 二级页面返回栈清空后隐藏 overlay 层
        getFragmentManager().addOnBackStackChangedListener(() -> {
            if (getFragmentManager().getBackStackEntryCount() == 0 && overlayContainer != null) {
                overlayContainer.setVisibility(View.GONE);
            }
        });

        // 状态栏占位：动态设置空白View高度为状态栏高度
        var statusBarRes = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (statusBarRes > 0) {
            var spacer = findViewById(R.id.status_bar_spacer);
            spacer.getLayoutParams().height = getResources().getDimensionPixelSize(statusBarRes);
        }

        // 检查文件管理权限，未授权则跳转系统设置页面
        if (!Environment.isExternalStorageManager()) {
            var intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        initTabs();
        // 高亮当前 Tab（主题切换重建后恢复上次位置，不强制回设备配置页）
        updateTabSelection(TAB_NAMES[currentIndex]);
        // 主题切换重建：窗口淡入过渡 + 恢复设置页
        if (themeTransition) {
            themeTransition = false;
            var decor = getWindow().getDecorView();
            decor.setAlpha(0f);
            decor.animate().alpha(1f).setDuration(350).start();
            tabContainer.post(() -> openOverlay(new com.suileyan.xpmibackup.ui.SettingsFragment()));
        }
        // 全面屏手势返回：API 33+ 注册 OnBackInvokedCallback（按设置开关启用/关闭预测动画）
        updateBackInvoke();
        // 仅应用进入时自动检测一次版本（设置页可关闭，弹小窗可点空白取消）
        checkUpdatesOnLaunch();
    }

    /**
     * 启动版本检测：受 config.ini `update_check`（默认 on）控制；
     * 进程内只检测一次（sUpdateChecked），检测到新版本弹小窗（可点空白/返回取消），不打断使用
     */
    private void checkUpdatesOnLaunch() {
        if (sUpdateChecked) return;
        sUpdateChecked = true;
        if ("off".equals(ConfigHelp.getString("update_check", "on"))) return;
        String currentVersion;
        try {
            currentVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            currentVersion = "";
        }
        final var version = currentVersion == null ? "" : currentVersion;
        Async.run("check-update-launch", () -> {
            var result = UpdateChecker.check(version);
            if (!result.ok || !result.hasNew) return;
            runOnUiThread(() -> {
                // Activity 已销毁（用户快速退出/主题重建中）时不弹窗，避免 BadTokenException
                if (isFinishing() || isDestroyed()) return;
                showUpdateDialog(result);
            });
        });
    }

    /** 发现新版本小窗：可点空白/返回取消；「前往下载」GitHub 页 + 「下载 APK」直链 */
    private void showUpdateDialog(UpdateChecker.Result result) {
        var builder = new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_new_version, result.latestVersion))
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(true);
        // 下载 APK：默认 GitHub 官方直链（无安全警报）；仅显式配置 update_mirror 时走镜像
        if (!result.downloadUrl.isEmpty()) {
            builder.setNeutralButton(R.string.about_download_apk,
                    (d, w) -> openUpdateBrowser(UpdateChecker.downloadUrlWithMirror(result.downloadUrl)));
        }
        builder.setPositiveButton(R.string.about_open_browser,
                (d, w) -> openUpdateBrowser(result.htmlUrl));
        var dialog = builder.create();
        // 点击空白处取消弹窗
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    /** 打开浏览器（无浏览器应用时捕获异常提示） */
    private void openUpdateBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            LogHelp.w("XpMiBackup", "open browser failed: " + url, e);
            android.widget.Toast.makeText(this, R.string.about_check_failed, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 主题切换前的过渡准备：标记重建后淡入并重开设置页
     */
    public void markThemeTransition() {
        themeTransition = true;
    }

    /** 保存 Tab 位置与主题过渡标志，主题切换重建后恢复 */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current_index", currentIndex);
        outState.putBoolean("theme_transition", themeTransition);
    }

    /**
     * 应用主题设置：白昼/黑夜/跟随系统（config.ini theme_mode），API 31+ 用 UiModeManager
     */
    private void applyTheme() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            var mode = com.suileyan.comm.ConfigHelp.getString("theme_mode", "system");
            var uiModeManager = (android.app.UiModeManager) getSystemService(android.content.Context.UI_MODE_SERVICE);
            if (uiModeManager == null) return;
            if ("day".equals(mode)) {
                uiModeManager.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_NO);
            } else if ("night".equals(mode)) {
                uiModeManager.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_YES);
            } else {
                uiModeManager.setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_AUTO);
            }
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "apply theme failed", e);
        }
    }

    /** 已注册的手势返回回调 */
    private android.window.OnBackInvokedCallback backCallback;
    private boolean backCallbackRegistered = false;

    /**
     * 注册/更新手势返回回调（API 33+）。
     * 预测性返回开关开启 → 注册 OnBackAnimationCallback，用 onBackProgressed 自绘"盒子推开"跟手动画
     * （当前页跟手右移，露出下层页面——纯应用层动画，不依赖系统渲染）；
     * 关闭 → 普通 OnBackInvokedCallback（无动画立即返回）。
     * 按键返回（onBackPressed）与手势返回统一走 handleBack()
     */
    public void updateBackInvoke() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            var dispatcher = getOnBackInvokedDispatcher();
            if (backCallbackRegistered && backCallback != null) {
                dispatcher.unregisterOnBackInvokedCallback(backCallback);
                backCallbackRegistered = false;
            }
            var enabled = !"off".equals(com.suileyan.comm.ConfigHelp.getString("predictive_back", "on"));
            backCallback = enabled ? new BackAnimCallback() : this::handleBack;
            dispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            backCallbackRegistered = true;
        } catch (Exception e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "update back invoke failed", e);
        }
    }

    /**
     * 手动预测性返回动画（API 33+，自绘不依赖系统渲染），通用可复用引擎：
     * 任意 overlay 页面层级（栈顶页 vs 其"上一层"页）自动适配——
     *   当前页跟手右移，下层"上一层"页从左侧同步滑入（推开视差，露出真实渲染的上一层）；
     *   overlay 栈底时下层自动落到 tab 层当前页；
     * Tab 层场景 → 无预测动画，onBackStarted 直接执行返回。
     */
    private class BackAnimCallback implements android.window.OnBackAnimationCallback {
        private boolean overlayMode = false;
        private boolean animating = false;
        /** 当前 overlay 栈顶页 view（add 叠放时 findFragmentById 返回最后 add 的） */
        private View overlayTopView = null;
        /** 当前页的"上一层"页 view：overlay 下层页，或 overlay 栈底时 tab 层当前页 */
        private View overlayBelowView = null;
        /** 返回已触发、等待推出动画结束再 pop（防重复 pop 与进度干扰） */
        private boolean pendingPop = false;
        /** Tab 层场景：无需预测动画，onBackStarted 已直接执行返回，后续回调全部忽略 */
        private boolean tabInstantHandled = false;

        private int screenW() {
            return getResources().getDisplayMetrics().widthPixels;
        }

        /** overlay 容器当前栈顶 fragment 的 view */
        private View overlayTopView() {
            var fm = getFragmentManager();
            if (fm == null) return null;
            var f = fm.findFragmentById(R.id.overlay_container);
            return f != null ? f.getView() : null;
        }

        /**
         * 当前页的"上一层"页 view（按页面路由）：
         * overlay 叠放 ≥2 层 → 倒数第二个子 view（add 顺序即层级顺序）；
         * overlay 仅 1 层 → tab 层当前页（其上一层）。
         */
        private View overlayBelowView() {
            if (overlayContainer != null && overlayContainer.getChildCount() >= 2) {
                return overlayContainer.getChildAt(overlayContainer.getChildCount() - 2);
            }
            var fm = getFragmentManager();
            if (fm == null) return null;
            var f = fm.findFragmentByTag("tab-" + TAB_NAMES[currentIndex]);
            return f != null ? f.getView() : null;
        }

        @Override
        public void onBackStarted(android.window.BackEvent event) {
            animating = false;
            overlayMode = overlayContainer != null && overlayContainer.getVisibility() == View.VISIBLE;
            if (!overlayMode) {
                // Tab 层：无预测动画（跟手阶段页面不动），仅在松手（onBackInvoked）时真正执行返回
                tabInstantHandled = true;
                return;
            }
            tabInstantHandled = false;
            pendingPop = false;
            var w = screenW();
            overlayTopView = overlayTopView();
            overlayBelowView = overlayBelowView();
            // 下层页预置到屏左外 + buildLayer 离屏预热：被不透明上层完全遮挡时
            // 系统可能跳过其绘制，预热保证跟手露出瞬间直接合成显示、不空白
            if (overlayBelowView != null) {
                overlayBelowView.setTranslationX(-w);
                overlayBelowView.buildLayer();
            }
        }

        @Override
        public void onBackProgressed(android.window.BackEvent event) {
            if (tabInstantHandled || animating) return;
            // 推开视差：当前页右移 p*w，下层"上一层"页从 -w 同步滑入（p*w 偏移），
            // 两者始终无缝衔接，露出的是真实渲染的上一层页面
            var p = event.getProgress();
            var w = screenW();
            if (overlayTopView != null) {
                overlayTopView.setTranslationX(p * w);
            }
            if (overlayBelowView != null) {
                overlayBelowView.setTranslationX(-w + p * w);
            }
        }

        @Override
        public void onBackCancelled() {
            if (tabInstantHandled) {
                tabInstantHandled = false;
                return;
            }
            animating = false;
            var w = screenW();
            if (overlayTopView != null) {
                overlayTopView.animate().translationX(0f).setDuration(150).start();
            }
            if (overlayBelowView != null) {
                overlayBelowView.animate().translationX(-w).setDuration(150).start();
            }
        }

        @Override
        public void onBackInvoked() {
            if (tabInstantHandled) {
                // Tab 层：手势完成（松手）才执行返回——跟手阶段无动画不动作，符合直觉
                tabInstantHandled = false;
                handleBack();
                return;
            }
            if (animating || pendingPop) return;
            animating = true;
            pendingPop = true;
            var w = screenW();
            var top = overlayTopView;
            var below = overlayBelowView;
            // 从跟手位置无缝衔接继续动画：当前页继续推出到屏外、下层"上一层"页滑入到位，
            // 消除快速手势时"当前页瞬移消失 + 下层长距离回弹"的顿挫感
            if (top != null) {
                top.animate().translationX(w).setDuration(200).start();
            }
            if (below != null) {
                below.animate().translationX(0f).setDuration(200).start();
            }
            // 动画结束后移除当前页（无转场动画，此时当前页已在屏外，移除不可见）
            overlayContainer.postDelayed(() -> {
                if (pendingPop) {
                    pendingPop = false;
                    popOverlayNoAnim();
                }
            }, 220);
        }

        /** overlay 无动画弹出（pop 不播放任何 fragment 转场动画） */
        private void popOverlayNoAnim() {
            var fm = getFragmentManager();
            if (fm != null && fm.getBackStackEntryCount() > 0) {
                fm.popBackStackImmediate();
            }
            overlayTopView = null;
            overlayBelowView = null;
        }
    }

    /** 顶部菜单：点击弹出 设置 / 关于 */
    private void showTopMenu() {
        var popup = new android.widget.PopupMenu(this, findViewById(R.id.btn_top_menu));
        popup.getMenu().add(0, 1, 0, R.string.settings_title);
        popup.getMenu().add(0, 2, 0, R.string.about_title);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                openOverlay(new com.suileyan.xpmibackup.ui.SettingsFragment());
            } else {
                openOverlay(new com.suileyan.xpmibackup.ui.AboutFragment());
            }
            return true;
        });
        popup.show();
    }

    /** 打开二级页面到 overlay 层（设置/关于等） */
    private void openOverlay(android.app.Fragment fragment) {
        var fm = getFragmentManager();
        // 清理可能残留的 backstack（如主题切换重建时 FragmentManager 恢复的旧 overlay），避免页面叠加
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate(null, android.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        var overlay = findViewById(R.id.overlay_container);
        if (overlay != null) {
            // 防御：清除预测返回推开后可能残留的容器偏移，确保页面从正常位置显示
            overlay.setTranslationX(0f);
            overlay.setVisibility(View.VISIBLE);
        }
        var ft = fm.beginTransaction();
        // add 叠放：下层页 view 常驻，预测返回跟手时露出"上一层"；pop 用 0ms 空动画避免转场闪烁
        ft.setCustomAnimations(R.animator.slide_in_right, R.animator.no_anim,
                R.animator.no_anim, R.animator.no_anim);
        ft.add(R.id.overlay_container, fragment);
        ft.addToBackStack("top-menu");
        ft.commit();
    }

    /**
     * 初始化 4 个 Tab 页面：全部 add 到同一容器（横向排开），非当前页偏移到屏外
     */
    private void initTabs() {
        if (tabsReady) return;
        var fm = getFragmentManager();
        for (var i = 0; i < TAB_COUNT; i++) {
            var tag = "tab-" + TAB_NAMES[i];
            var f = fm.findFragmentByTag(tag);
            if (f == null) {
                f = createTabFragment(i);
                fm.beginTransaction().add(R.id.fragment_container, f, tag).commitAllowingStateLoss();
            }
        }
        tabsReady = true;
        // 等待 Fragment view 创建后设置初始横向偏移
        tabContainer.post(() -> {
            var width = tabContainer.getWidth();
            if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
            layoutTabs(currentIndex, false);
        });
    }

    private android.app.Fragment createTabFragment(int index) {
        switch (index) {
            case 1:
                return new com.suileyan.xpmibackup.ui.ServiceConfigFragment();
            case 2:
                return new com.suileyan.xpmibackup.ui.AccountConfigFragment();
            case 3:
                return new com.suileyan.xpmibackup.ui.BackupFragment();
            default:
                return new com.suileyan.xpmibackup.ui.DeviceConfigFragment();
        }
    }

    /**
     * 平移到目标 Tab：4 个页面各自动画到 (i - target) * 屏宽，中间页面在滑动中可见（桌面滑动效果）
     */
    private void layoutTabs(int targetIndex, boolean animate) {
        var container = tabContainer;
        if (container == null) return;
        var width = container.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        var fm = getFragmentManager();
        for (var i = 0; i < TAB_COUNT; i++) {
            var f = fm.findFragmentByTag("tab-" + TAB_NAMES[i]);
            if (f == null || f.getView() == null) continue;
            var v = f.getView();
            var target = (float) (i - targetIndex) * width;
            if (animate) {
                v.animate().translationX(target).setDuration(SLIDE_MS).start();
            } else {
                v.setTranslationX(target);
            }
        }
    }

    /**
     * 切换 Tab：若二级页面（overlay）打开则先关闭，记录历史、更新高亮、平移页面
     */
    private void switchTabByIndex(int newIndex) {
        if (newIndex < 0 || newIndex >= TAB_COUNT) {
            return;
        }
        // overlay 二级页面打开时先关闭（否则覆盖层拦截 tab 点击且页面压在上面）
        var fm = getFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate(null, android.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            if (overlayContainer != null) {
                overlayContainer.setVisibility(View.GONE);
            }
        }
        if (newIndex == currentIndex) {
            return;
        }
        currentIndex = newIndex;
        updateTabSelection(TAB_NAMES[newIndex]);
        layoutTabs(newIndex, true);
        // 切到备份页时刷新云盘账号下拉（常驻 Fragment 不会自动重建，添加新账号后需即时可见）
        if (newIndex == 3) {
            var backupFrag = (com.suileyan.xpmibackup.ui.BackupFragment) fm.findFragmentByTag("tab-backup");
            if (backupFrag != null) {
                backupFrag.refresh();
            }
        }
    }

    /**
     * 返回键（按键导航 / API<33）：与手势返回统一走 handleBack
     */
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * 统一返回处理（按键 + 手势）：
     * 优先回退二级页面（云盘流程，逐级返回）；Tab 层不记录路由——
     * 任意非 0 Tab 返回都直接回到第 0 Tab（设备配置页），0 Tab 再按一次二次确认退出。
     */
    private void handleBack() {
        var fm = getFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            super.onBackPressed();
            return;
        }
        // Tab 层：任意 Tab 返回都回到设备配置页（第 0 Tab）
        if (currentIndex != 0) {
            currentIndex = 0;
            updateTabSelection(TAB_NAMES[0]);
            layoutTabs(0, true);
            return;
        }
        // 已在设备配置页：二次返回确认退出
        var now = System.currentTimeMillis();
        if (now - lastBackPressTime < 2000) {
            super.onBackPressed();
        } else {
            lastBackPressTime = now;
            android.widget.Toast.makeText(this, R.string.toast_press_again_exit, android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 界面恢复时检查权限和Tab初始化
     * 用户从权限设置页面返回后，若已授权且Tab未初始化，则初始化
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (Environment.isExternalStorageManager() && !tabsReady) {
            initTabs();
            updateTabSelection(TAB_NAMES[0]);
            updateBackInvoke();
        }
    }

    /** 根据Tab名更新四个底部Tab的选中高亮 */
    private void updateTabSelection(String tab) {
        setTabColors("device".equals(tab), "service".equals(tab), "account".equals(tab), "backup".equals(tab));
    }

    /** 更新四个底部Tab的选中颜色与胶囊背景 */
    private void setTabColors(boolean device, boolean service, boolean account, boolean backup) {
        setTabState(tabDeviceIcon, tabDeviceText, device);
        setTabState(tabServiceIcon, tabServiceText, service);
        setTabState(tabAccountIcon, tabAccountText, account);
        setTabState(tabBackupIcon, tabBackupText, backup);
    }

    /** 设置单个Tab的选中态：选中 = 实心主色胶囊 + 反色图标文字（白昼黑胶囊白字/黑夜白胶囊黑字） */
    private void setTabState(ImageView icon, TextView text, boolean selected) {
        var parent = (View) icon.getParent();
        parent.setSelected(selected);
        var color = getResources().getColor(selected ? R.color.primary_text_on : R.color.text_disabled);
        icon.setColorFilter(color);
        text.setTextColor(color);
    }
}
