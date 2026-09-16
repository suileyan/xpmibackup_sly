package com.suileyan.xpmibackup;

import android.app.Activity;

import com.suileyan.xpmibackup.ui.ModuleRestoreUi;
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
    /** MD3 中档位移时长（令牌 md3_anim_medium，300ms） */
    private static final long SLIDE_MS = 300;

    /** 顶栏标题文案（与 TAB_NAMES 一一对应） */
    private static final int[] TAB_TITLE_RES = {
            R.string.title_device_config, R.string.title_service_config,
            R.string.title_account_config, R.string.title_backup_config};
    /** 核心操作 Tab（备份）：图标常驻品牌色实心圆高亮，与其余导航项区分 */
    private static final int CORE_TAB = 3;
    /** 大屏横屏下切换为侧边导航栏的宽度断点（官方大屏 L2：宽屏用导航栏/抽屉取代底部导航条） */
    private static final int RAIL_BREAKPOINT_DP = 840;

    /** 悬浮底栏 4 个导航项（顺序与 TAB_NAMES 一致）：项容器 / 图标底板 / 图标 / 文字 */
    private View[] navItems;
    private View[] navIconBoxes;
    private ImageView[] navIcons;
    private TextView[] navTexts;

    /** 悬浮底栏容器、底部留白、侧边导航栏占位（大屏横屏）、顶栏标题 */
    private View floatingBar;
    private View bottomSpacer;
    private View navRailSpacer;
    private TextView tvTopTitle;

    /** 底栏当前是否可见（overlay 二级页面打开时收起，让二级页面铺满内容区） */
    private boolean barVisible = true;
    /** 导航栏底部 inset 缓存（Android 15+ 强制 edge-to-edge） */
    private int navBottomInset = 0;
    /** 大屏横屏：底部导航条切换为侧边导航栏 */
    private boolean railMode = false;

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
    /** 免责声明对话框引用：避免 onResume 多次触发时叠加弹出（需同意后才消失） */
    private AlertDialog disclaimerDialog = null;
    /** 免责声明未同意时暂存的更新检测结果，同意后再弹窗，避免两层对话框叠加（Bug #3） */
    private UpdateChecker.Result pendingUpdateResult = null;

    /**
     * 初始化界面：绑定Tab控件，注册切换事件，检查文件管理权限
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动耗时诊断（STARTUP 日志）：定位黑屏/卡顿阶段
        var startupT0 = System.currentTimeMillis();
        // 崩溃落盘：必须在任何业务代码之前安装，否则崩溃点之前的异常会被系统默认处理器吞掉
        com.suileyan.comm.CrashLog.install(this);
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
        com.suileyan.comm.LogHelp.i("XpMiBackup", "STARTUP setContentView done: " + (System.currentTimeMillis() - startupT0) + "ms");

        tvTopTitle = findViewById(R.id.tv_top_title);
        floatingBar = findViewById(R.id.tab_bar_container);
        bottomSpacer = findViewById(R.id.bottom_spacer);
        navRailSpacer = findViewById(R.id.nav_rail_spacer);

        int[] itemIds = {R.id.tab_device, R.id.tab_service, R.id.tab_account, R.id.tab_backup};
        int[] boxIds = {R.id.tab_device_icon_box, R.id.tab_service_icon_box,
                R.id.tab_account_icon_box, R.id.tab_backup_icon_box};
        int[] iconIds = {R.id.tab_device_icon, R.id.tab_service_icon,
                R.id.tab_account_icon, R.id.tab_backup_icon};
        int[] textIds = {R.id.tab_device_text, R.id.tab_service_text,
                R.id.tab_account_text, R.id.tab_backup_text};
        navItems = new View[TAB_COUNT];
        navIconBoxes = new View[TAB_COUNT];
        navIcons = new ImageView[TAB_COUNT];
        navTexts = new TextView[TAB_COUNT];
        for (var i = 0; i < TAB_COUNT; i++) {
            navItems[i] = findViewById(itemIds[i]);
            navIconBoxes[i] = findViewById(boxIds[i]);
            navIcons[i] = findViewById(iconIds[i]);
            navTexts[i] = findViewById(textIds[i]);
            final var index = i;
            navItems[i].setOnClickListener(v -> switchTabByIndex(index));
            // 按下反馈：图标底板轻微缩放（只动合成层，不掉帧）+ ripple 光效；
            // 返回 false 不消费事件，点击仍交给 OnClickListener
            navItems[i].setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        pressFeedback(navIconBoxes[index], true);
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        pressFeedback(navIconBoxes[index], false);
                        break;
                    default:
                        break;
                }
                return false;
            });
        }

        tabContainer = findViewById(R.id.fragment_container);
        overlayContainer = findViewById(R.id.overlay_container);

        // 顶部菜单：设置 / 关于
        findViewById(R.id.btn_top_menu).setOnClickListener(v -> showTopMenu());

        // 二级页面返回栈变化：控制 overlay 层显隐，并同步收起/展开悬浮底栏
        getFragmentManager().addOnBackStackChangedListener(() -> {
            var depth = getFragmentManager().getBackStackEntryCount();
            if (overlayContainer != null) {
                overlayContainer.setVisibility(depth == 0 ? View.GONE : View.VISIBLE);
            }
            // 二级页面铺满内容区，同时释放底栏留白
            setFloatingBarVisible(depth == 0);
        });

        // 状态栏占位：仅 Android 15+（targetSdk 35+ 强制 edge-to-edge）需要，
        // Android 11~14 内容本就从状态栏下方开始，补占位会多出一段空白（HIGH-24）
        if (Build.VERSION.SDK_INT >= 35) {
            var statusBarRes = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (statusBarRes > 0) {
                var spacer = findViewById(R.id.status_bar_spacer);
                spacer.getLayoutParams().height = getResources().getDimensionPixelSize(statusBarRes);
            }
        }

        // 响应式布局：大屏最大宽度约束 + （大屏横屏）侧边导航栏
        applyResponsiveLayout();
        // Android 15+ 强制 edge-to-edge：悬浮底栏按导航栏 inset 抬升，避免被手势条遮挡
        applyFloatingBarInsets();

        // 检查文件管理权限，未授权则跳转系统设置页面
        if (!Environment.isExternalStorageManager()) {
            var intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        // Android 16+ 本地网络保护：局域网发现/连接的前置权限
        ensureLocalNetworkPermission();

        initTabs();
        com.suileyan.comm.LogHelp.i("XpMiBackup", "STARTUP initTabs done: " + (System.currentTimeMillis() - startupT0) + "ms");
        // 高亮当前 Tab（主题切换重建后恢复上次位置，不强制回设备配置页）
        updateTabSelection(TAB_NAMES[currentIndex]);
        LogHelp.i("XpMiBackup", "STARTUP step: updateTabSelection ok");
        // 主题切换重建：窗口淡入过渡 + 恢复设置页
        if (themeTransition) {
            themeTransition = false;
            var decor = getWindow().getDecorView();
            decor.setAlpha(0f);
            decor.animate().alpha(1f).setDuration(350).start();
            tabContainer.post(() -> openOverlay(new com.suileyan.xpmibackup.ui.SettingsFragment()));
        }
        LogHelp.i("XpMiBackup", "STARTUP step: themeTransition ok");
        // 全面屏手势返回：API 33+ 注册 OnBackInvokedCallback（按设置开关启用/关闭预测动画）
        updateBackInvoke();
        LogHelp.i("XpMiBackup", "STARTUP step: updateBackInvoke ok");
        // 凭据库后台预热：PBKDF2 600000 迭代迁移较重，异步执行避免主线程阻塞（启动黑屏优化）
        com.suileyan.cloud.EncryptedCredStore.warmUp();
        LogHelp.i("XpMiBackup", "STARTUP step: credStore warmUp queued");
        // 仅应用进入时自动检测一次版本（设置页可关闭，弹小窗可点空白取消）
        checkUpdatesOnLaunch();
        LogHelp.i("XpMiBackup", "STARTUP step: checkUpdates queued");
        // 免责声明改由 onResume 触发（冷启动缺权限时 onCreate 提前 return 也会漏弹，见 Bug #3）
        LogHelp.i("XpMiBackup", "STARTUP step: onCreate END");
    }

    /** 本地网络权限申请码 */
    private static final int REQ_LOCAL_NETWORK = 0x4C4E; // "LN"

    /**
     * Android 16（API 36）本地网络保护（LNP）：targetSdk ≥ 36 时，应用访问局域网
     * （UDP 广播/单播、192.168/10/172.16 网段的 HTTP）必须先持有
     * ACCESS_LOCAL_NETWORK，否则发包/收包在系统侧被直接拦掉。
     *
     * 症状特别隐蔽：127.0.0.1 回环不受限（所以 USB 通道 adb reverse 一直能用），
     * 局域网地址却被拦，且拦截不抛异常 —— 「备份至 PC」的 UDP 发现会静默 0 应答，
     * HTTP 兜底探测也静默失败。此处补上运行时申请（清单里早已声明）。
     */
    private void ensureLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < 36) return; // 仅 Android 16+ 存在该权限
        var perm = "android.permission.ACCESS_LOCAL_NETWORK";
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        LogHelp.w("XpMiBackup", "本地网络权限未授予：局域网扫描/连接会被系统拦截，正在申请");
        requestPermissions(new String[]{perm}, REQ_LOCAL_NETWORK);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCAL_NETWORK) return;
        var granted = results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted) {
            LogHelp.i("XpMiBackup", "本地网络权限已授予，局域网发现可用");
        } else {
            LogHelp.w("XpMiBackup", "本地网络权限被拒：备份至 PC 只能走 USB 通道，"
                    + "如需局域网请到「设置 → 应用 → 权限」授予「本地网络」");
        }
    }

    /** 免责声明是否已同意（config.ini disclaimer_agreed 标记） */
    private boolean disclaimerAgreed() {
        return "true".equals(com.suileyan.comm.ConfigHelp.getString("disclaimer_agreed", ""));
    }

    /**
     * 确保免责声明已展示：进程内仅弹一次（对话框仍显示或已同意后不再弹），
     * 冷启动缺权限、主题重建后都会经 onResume 触发，解决首启漏弹（Bug #3）。
     */
    private void ensureDisclaimer() {
        if (disclaimerDialog != null && disclaimerDialog.isShowing()) return;
        if (disclaimerAgreed()) {
            disclaimerDialog = null;
            return;
        }
        maybeShowDisclaimer();
    }

    /** 首次启动免责声明：不可取消，同意按钮 3 秒倒计时后可点 */
    private void maybeShowDisclaimer() {
        if (disclaimerAgreed()) return;
        var dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_text)
                .setCancelable(false)
                .setPositiveButton(R.string.disclaimer_agree, null)
                .setNegativeButton(R.string.disclaimer_exit, (d, w) -> finishAffinity())
                .create();
        dialog.show();
        disclaimerDialog = dialog;
        var btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        btn.setEnabled(false);
        var remain = new int[]{3};
        var handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (remain[0] > 0) {
                    btn.setText(getString(R.string.disclaimer_agree_countdown, remain[0]));
                    remain[0]--;
                    handler.postDelayed(this, 1000);
                    return;
                }
                btn.setText(R.string.disclaimer_agree);
                btn.setEnabled(true);
                btn.setOnClickListener(v -> {
                    try {
                        var cfg = com.suileyan.comm.ConfigHelp.load();
                        cfg.put("disclaimer_agreed", "true");
                        com.suileyan.comm.ConfigHelp.save(cfg);
                    } catch (Exception ignored) {
                    }
                    dialog.dismiss();
                    // 同意后再弹出此前暂存的更新检测窗，避免与免责声明叠加（Bug #3）
                    if (pendingUpdateResult != null) {
                        var pending = pendingUpdateResult;
                        pendingUpdateResult = null;
                        showUpdateDialog(pending);
                    }
                });
            }
        };
        btn.setText(getString(R.string.disclaimer_agree_countdown, remain[0]));
        handler.postDelayed(tick, 1000);
    }

    /** 检测原生恢复是否把模块快照还原回设备（快照指纹变化即提示），避免用户漏掉解包步骤 */
    private void maybePromptModuleRestore() {
        if (!"true".equals(com.suileyan.comm.ConfigHelp.getString("disclaimer_agreed", ""))) return;
        com.suileyan.comm.Async.run("tar-seen-check", () -> {
            var transfer = com.suileyan.comm.RootModulesHelp.modulesDir();
            // 兜底收敛：清理历史遗留的多余快照（业务 keep 2 / 回滚 keep 1）
            try {
                com.suileyan.comm.RootModulesHelp.pruneAll(transfer);
            } catch (Throwable ignored) {
            }
            var stamp = com.suileyan.comm.RootModulesHelp.newestTarStamp(transfer);
            if (stamp.isEmpty()
                    || stamp.equals(com.suileyan.comm.ConfigHelp.getString("root_tar_seen", ""))) {
                return;
            }
            var tars = com.suileyan.comm.RootModulesHelp.listTars(transfer);
            if (tars.isEmpty()) return;
            var newest = tars.get(0);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                ModuleRestoreUi.markSeen(this); // 先记录指纹，避免反复打扰
                new AlertDialog.Builder(this)
                        .setTitle(R.string.restore_prompt_title)
                        .setMessage(getString(R.string.restore_prompt_msg, newest.getName()))
                        .setPositiveButton(R.string.restore_modules_title,
                                (d, w) -> ModuleRestoreUi.restore(this, newest))
                        .setNegativeButton(R.string.restore_prompt_later, null)
                        .show();
            });
        });
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
                // 免责声明未同意前不弹更新窗，暂存待同意后再弹，避免两层对话框叠加（Bug #3）
                if (!disclaimerAgreed()) {
                    pendingUpdateResult = result;
                    return;
                }
                showUpdateDialog(result);
            });
        });
    }

    /** 发现新版本小窗：可点空白/返回取消；「前往下载」GitHub 页 + 「下载 APK」123 网盘分享链接 */
    private void showUpdateDialog(UpdateChecker.Result result) {
        var builder = new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_new_version, result.latestVersion))
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(true);
        // 下载 APK：固定指向 123 网盘分享链接（国内可达）；config.ini download_url 可覆盖
        builder.setNeutralButton(R.string.about_download_apk,
                (d, w) -> openUpdateBrowser(UpdateChecker.apkDownloadUrl()));
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

    /**
     * edge-to-edge 底部适配（Android 15+ 强制，HIGH-24 / UI 重构）：
     * targetSdk 35+ 窗口延伸至导航栏区域。悬浮底栏不再靠「加高容器」吸收 inset，
     * 而是把 inset 折算成底栏的底部外边距——底栏整体抬高，形状保持 64dp 不被拉伸；
     * 内容区底部留白同步增加同样数值，避免内容被抬升后的底栏压住。
     * 绝对值赋值：insets 会多次派发，「+= 」累加会让边距持续膨胀。
     * Android 11~14 无强制 edge-to-edge（窗口本就在导航栏上方，insets.bottom=0）。
     */
    private void applyFloatingBarInsets() {
        if (Build.VERSION.SDK_INT < 35) {
            applyClearance();
            return;
        }
        try {
            var column = findViewById(R.id.content_column);
            column.setOnApplyWindowInsetsListener((v, insets) -> {
                navBottomInset = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                applyClearance();
                return insets;
            });
            column.requestApplyInsets();
        } catch (Throwable e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "apply edge-to-edge insets failed", e);
        }
        applyClearance();
    }

    /**
     * 统一维护「内容让位」尺寸：
     * · 手机/竖屏——底栏悬浮于底部，内容区底部留白 = 96dp + 导航栏 inset（底栏自身底部外边距同步抬升）
     * · 大屏横屏——底栏变侧边导航栏，底部留白归零，改为内容区左侧留白
     * · overlay 二级页面打开（底栏收起）——留白全部归零，二级页面铺满内容区
     */
    private void applyClearance() {
        if (bottomSpacer == null || navRailSpacer == null) return;
        var bottom = 0;
        var start = 0;
        if (barVisible) {
            if (railMode) {
                start = getResources().getDimensionPixelSize(R.dimen.nav_rail_clearance);
            } else {
                bottom = getResources().getDimensionPixelSize(R.dimen.floating_bar_clearance) + navBottomInset;
            }
        }
        setViewHeight(bottomSpacer, bottom);
        setViewWidth(navRailSpacer, start);

        if (floatingBar != null && !railMode) {
            var lp = floatingBar.getLayoutParams();
            if (lp instanceof android.view.ViewGroup.MarginLayoutParams mlp) {
                var target = getResources().getDimensionPixelSize(R.dimen.floating_bar_margin_bottom) + navBottomInset;
                if (mlp.bottomMargin != target) {
                    mlp.bottomMargin = target;
                    floatingBar.setLayoutParams(mlp);
                }
            }
        }
    }

    /**
     * 响应式适配（官方大屏体验标准 L2）：
     * · 宽屏（≥ content_max_width）——内容列与底栏设最大宽度并居中，控件不被横向拉满整屏
     * · 大屏横屏（宽 ≥ 840dp 且横屏）——底部导航条改为侧边导航栏（Navigation rail）
     */
    private void applyResponsiveLayout() {
        try {
            var config = getResources().getConfiguration();
            var wDp = config.screenWidthDp;
            railMode = wDp >= RAIL_BREAKPOINT_DP
                    && config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

            var maxContent = getResources().getDimensionPixelSize(R.dimen.content_max_width);
            var column = findViewById(R.id.content_column);
            if (column != null) {
                var lp = column.getLayoutParams();
                if (lp instanceof android.widget.FrameLayout.LayoutParams flp) {
                    var capped = Math.min(maxContent, getResources().getDisplayMetrics().widthPixels);
                    var width = wDp > 640 ? capped : android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                    if (flp.width != width || flp.gravity != android.view.Gravity.CENTER_HORIZONTAL) {
                        flp.width = width;
                        flp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                        column.setLayoutParams(flp);
                    }
                }
            }
            applyRailLayout();
        } catch (Throwable e) {
            com.suileyan.comm.LogHelp.w("XpMiBackup", "apply responsive layout failed", e);
        }
        applyClearance();
    }

    /** 按 railMode 切换底栏形态：横排（底部悬浮）/ 纵排（侧边导航栏） */
    private void applyRailLayout() {
        if (floatingBar == null || navItems == null) return;
        var bar = (android.widget.LinearLayout) floatingBar;
        var barLp = (android.widget.FrameLayout.LayoutParams) floatingBar.getLayoutParams();
        var density = getResources().getDisplayMetrics().density;

        if (railMode) {
            bar.setOrientation(android.widget.LinearLayout.VERTICAL);
            barLp.width = getResources().getDimensionPixelSize(R.dimen.nav_rail_width);
            barLp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            barLp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
            barLp.setMarginStart(Math.round(12 * density));
            barLp.setMarginEnd(0);
            barLp.bottomMargin = 0;
            bar.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_8),
                    0, getResources().getDimensionPixelSize(R.dimen.space_8));
            for (var item : navItems) {
                var lp = (android.widget.LinearLayout.LayoutParams) item.getLayoutParams();
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = getResources().getDimensionPixelSize(R.dimen.list_item_height);
                lp.weight = 0;
                item.setLayoutParams(lp);
            }
            // MD3 侧边导航栏：贴左边、纵向铺满（surface-container 色阶，与底部导航栏同色）
            barLp.topMargin = 0;
            barLp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            barLp.gravity = android.view.Gravity.START;
        } else {
            bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            // 贴边全宽（MD3 Navigation Bar）：不设最大宽度、无水平/底部留白，铺满内容列宽度
            barLp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            barLp.height = getResources().getDimensionPixelSize(R.dimen.nav_bar_height);
            barLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            barLp.setMarginStart(0);
            barLp.setMarginEnd(0);
            barLp.bottomMargin = getResources().getDimensionPixelSize(R.dimen.floating_bar_margin_bottom)
                    + navBottomInset;
            bar.setPadding(getResources().getDimensionPixelSize(R.dimen.space_8),
                    getResources().getDimensionPixelSize(R.dimen.space_8),
                    getResources().getDimensionPixelSize(R.dimen.space_8),
                    getResources().getDimensionPixelSize(R.dimen.space_6));
            for (var item : navItems) {
                var lp = (android.widget.LinearLayout.LayoutParams) item.getLayoutParams();
                lp.width = 0;
                lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
                lp.weight = 1f;
                item.setLayoutParams(lp);
            }
        }
        floatingBar.setLayoutParams(barLp);
    }

    /**
     * 收起 / 展开悬浮底栏（overlay 二级页面打开时收起，让二级页面铺满内容区）。
     * 收起方向随形态自适应：底部横排 → 向下滑出；大屏侧边导航栏 → 向左滑出。
     * 只动 translation / alpha（合成层），时长分档 160 / 220ms；
     * 系统「动画时长 0」时直接跳变，不做过渡（无障碍动效降级）。
     */
    private void setFloatingBarVisible(boolean visible) {
        if (floatingBar == null || barVisible == visible) {
            applyClearance();
            return;
        }
        barVisible = visible;
        floatingBar.animate().cancel();
        floatingBar.setTranslationX(0f);
        floatingBar.setTranslationY(0f);

        var hiddenX = 0f;
        var hiddenY = 0f;
        if (railMode) {
            var w = floatingBar.getWidth() > 0 ? floatingBar.getWidth()
                    : getResources().getDimensionPixelSize(R.dimen.nav_rail_width);
            hiddenX = -(w + getResources().getDimensionPixelSize(R.dimen.space_16));
        } else {
            var h = floatingBar.getHeight() > 0 ? floatingBar.getHeight()
                    : getResources().getDimensionPixelSize(R.dimen.floating_bar_height);
            hiddenY = h + getResources().getDimensionPixelSize(R.dimen.floating_bar_margin_bottom)
                    + navBottomInset;
        }
        final var tx = hiddenX;
        final var ty = hiddenY;

        if (visible) {
            floatingBar.setVisibility(View.VISIBLE);
            if (animationsEnabled()) {
                floatingBar.setTranslationX(tx);
                floatingBar.setTranslationY(ty);
                floatingBar.setAlpha(0f);
                floatingBar.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(300)
                        .setInterpolator(Md3SpringInterpolator.ENTER.withDuration(300)).start();
            } else {
                floatingBar.setAlpha(1f);
            }
        } else if (animationsEnabled()) {
            floatingBar.animate().translationX(tx).translationY(ty).alpha(0f).setDuration(300)
                    .setInterpolator(Md3SpringInterpolator.EXIT.withDuration(300))
                    .withEndAction(() -> {
                        if (!barVisible) floatingBar.setVisibility(View.GONE);
                    }).start();
        } else {
            floatingBar.setTranslationX(tx);
            floatingBar.setTranslationY(ty);
            floatingBar.setAlpha(0f);
            floatingBar.setVisibility(View.GONE);
        }
        applyClearance();
    }

    /** 按下反馈：图标底板轻微缩放（合成层，0.94 下压感）+ MD3 短档弹簧；点击同时给触觉反馈 */
    private void pressFeedback(View box, boolean pressed) {
        if (box == null) return;
        if (!animationsEnabled()) {
            box.setScaleX(1f);
            box.setScaleY(1f);
            return;
        }
        box.animate().cancel();
        var scale = pressed ? 0.94f : 1f;
        var anim = box.animate().scaleX(scale).scaleY(scale)
                .setDuration(pressed ? 150 : 220)
                .setInterpolator(pressed ? Md3SpringInterpolator.PRESS.withDuration(150)
                        : Md3SpringInterpolator.RELEASE.withDuration(220));
        // 按下瞬间触觉反馈（MD3 触感规范：轻触感 VIRTUAL_KEY）；
        // FLAG_IGNORE_GLOBAL_SETTING 不绕过系统"关闭震动"设置，无震动硬件时静默降级
        if (pressed && animationsEnabled()) {
            box.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        }
        anim.start();
    }

    /**
     * MD3 物理弹簧插值器（零依赖，framework TimeInterpolator）：
     * 临界阻尼弹簧 x(t)=1-e^(-wt)(1+w·t)，无过冲、收尾带物理"落定"感。
     * 归一化到终点值 1（见 getInterpolation）：spring 在 input=1 处本不等于 1，
     * 直接用作位移插值器会让动画残留目标外偏移（标签页切换偏移 Bug #1），归一化后精确落位。
     * 各场景独立实例（springAnimation 会改动内部时长，必须隔离）：
     *  · PRESS / RELEASE —— 150ms 短档（按下 / 回弹）
     *  · TAB / ENTER / EXIT —— 300ms 中档（Tab 位移 / 导航栏收展）
     *  · PULL —— 200ms（预测返回盒子跟手）
     * 系统"动画时长 0"时由调用方 animationsEnabled() 降级为直接跳变。
     */
    private static final class Md3SpringInterpolator implements android.animation.TimeInterpolator {
        /** 临界阻尼弹簧刚度（ω，单位 1/ms）：ω=12 时 300ms 内位移量≈1.0，收尾自然 */
        private static final float OMEGA = 12f;
        private static final Md3SpringInterpolator PRESS = new Md3SpringInterpolator();
        private static final Md3SpringInterpolator RELEASE = new Md3SpringInterpolator();
        private static final Md3SpringInterpolator TAB = new Md3SpringInterpolator();
        private static final Md3SpringInterpolator ENTER = new Md3SpringInterpolator();
        private static final Md3SpringInterpolator EXIT = new Md3SpringInterpolator();
        private static final Md3SpringInterpolator PULL = new Md3SpringInterpolator();

        private long durationMs = 300;

        private Md3SpringInterpolator withDuration(long ms) {
            this.durationMs = ms;
            return this;
        }

        @Override
        public float getInterpolation(float input) {
            // 采样点按当前调用方实例的时长换算
            var w = OMEGA * (durationMs / 1000f);
            var t = w * input;
            var v = 1f - (float) (Math.exp(-t) * (1f + t));
            // 归一化：临界阻尼弹簧在 input=1 处并不等于 1（ω=12、300ms 时仅≈0.874），
            // 直接用作 TimeInterpolator 会让 translationX 位移动画永远落不到精确目标，
            // 标签页切换残留 ~155px 偏移、跨多页跳转（如 Backup→Device）放大到 ~400px（Bug #1，
            // UI 证据 Backup +155 / Cloud −925）。除以终点值使其 getInterpolation(1.0)==1.0，
            // 所有调用方（Tab 平移 / 底栏收展 / 按压回弹）都精确落位，物理"落定"手感保留。
            var end = 1f - (float) (Math.exp(-w) * (1f + w));
            return end > 1e-6f ? v / end : v;
        }
    }

    /** 系统动画时长档位为 0（开发者选项/无障碍）时不播放过渡 */
    private boolean animationsEnabled() {
        try {
            return android.provider.Settings.Global.getFloat(getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        } catch (Throwable e) {
            return true;
        }
    }

    private void setViewHeight(View v, int height) {
        if (v == null || v.getLayoutParams() == null) return;
        var lp = v.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            v.setLayoutParams(lp);
        }
    }

    private void setViewWidth(View v, int width) {
        if (v == null || v.getLayoutParams() == null) return;
        var lp = v.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            v.setLayoutParams(lp);
        }
    }

    /** 已注册的手势返回回调 */
    private android.window.OnBackInvokedCallback backCallback;
    private boolean backCallbackRegistered = false;

    /**
     * 注册/更新手势返回回调（API 33+）。
     * API 34+ 且预测性返回开关开启 → 注册 OnBackAnimationCallback，用 onBackProgressed 自绘"盒子推开"跟手动画
     * （当前页跟手右移，露出下层页面——纯应用层动画，不依赖系统渲染）；
     * 其余（API 33，或 API 34+ 关闭开关）→ 普通 OnBackInvokedCallback（无动画立即返回）。
     * 注意：OnBackAnimationCallback / BackEvent.getProgress() 是 API 34，不能用 33 守卫，
     * 否则 Android 13 上实例化即 NoClassDefFoundError（Error 不被 catch(Exception) 捕获）。
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
            var predictiveEnabled = !"off".equals(com.suileyan.comm.ConfigHelp.getString("predictive_back", "on"));
            if (predictiveEnabled && Build.VERSION.SDK_INT >= 34) {
                // OnBackAnimationCallback / BackEvent.getProgress() 需 API 34：
                // Android 13 上实例化会 NoClassDefFoundError（Error 不被 catch(Exception) 捕获），故限定 34
                backCallback = new BackAnimCallback();
            } else {
                backCallback = this::handleBack;
            }
            dispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            backCallbackRegistered = true;
        } catch (Throwable e) {
            // 必须是 Throwable 而非 Exception：OnBackAnimationCallback / BackEvent.getProgress()
            // 属 API 34+，在部分 ROM 上实例化或注册会抛 NoClassDefFoundError / NoSuchMethodError，
            // 它们继承自 Error，catch(Exception) 捕不到 —— 异常会从 onCreate 直冒到主线程杀进程，
            // 表现为"启动即闪退、日志停在 initTabs done、Tab 视图从未创建"。
            // 此处捕获后降级为普通回调，保证手势返回可用，同时留下证据。
            com.suileyan.comm.LogHelp.e("XpMiBackup",
                    "update back invoke failed, fallback to plain callback", e);
            try {
                backCallback = this::handleBack;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
                backCallbackRegistered = true;
            } catch (Throwable e2) {
                com.suileyan.comm.LogHelp.e("XpMiBackup", "update back invoke fallback failed", e2);
            }
        }
    }

    /**
     * 手动预测性返回动画（API 34+，自绘不依赖系统渲染），通用可复用引擎：
     * 任意 overlay 页面层级（栈顶页 vs 其"上一层"页）自动适配——
     *   当前页跟手右移，下层"上一层"页从左侧同步滑入（推开视差，露出真实渲染的上一层）；
     *   overlay 栈底时下层自动落到 tab 层当前页；
     * Tab 层场景 → 无预测动画，onBackStarted 直接执行返回。
     * 仅在 updateBackInvoke() 的 SDK_INT >= 34 分支实例化（OnBackAnimationCallback 需 API 34）
     */
    @androidx.annotation.RequiresApi(34)
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
                overlayTopView.animate().translationX(0f).setDuration(150)
                        .setInterpolator(Md3SpringInterpolator.PULL.withDuration(150)).start();
            }
            if (overlayBelowView != null) {
                overlayBelowView.animate().translationX(-w).setDuration(150)
                        .setInterpolator(Md3SpringInterpolator.PULL.withDuration(150)).start();
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
                top.animate().translationX(w).setDuration(300)
                        .setInterpolator(Md3SpringInterpolator.PULL.withDuration(300)).start();
            }
            if (below != null) {
                below.animate().translationX(0f).setDuration(300)
                        .setInterpolator(Md3SpringInterpolator.PULL.withDuration(300)).start();
            }
            // 动画结束后移除当前页（无转场动画，此时当前页已在屏外，移除不可见）
            overlayContainer.postDelayed(() -> {
                if (pendingPop) {
                    pendingPop = false;
                    popOverlayNoAnim();
                }
            }, 320);
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
                // 启动耗时诊断：记录每个 Tab Fragment 创建耗时（黑屏排查）
                var t = System.currentTimeMillis();
                f = createTabFragment(i);
                fm.beginTransaction().add(R.id.fragment_container, f, tag).commitAllowingStateLoss();
                com.suileyan.comm.LogHelp.i("XpMiBackup", "STARTUP create tab[" + i + "]=" + TAB_NAMES[i]
                        + " new " + (System.currentTimeMillis() - t) + "ms");
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
            v.animate().translationX(target).setDuration(SLIDE_MS)
                    .setInterpolator(Md3SpringInterpolator.TAB.withDuration(SLIDE_MS)).start();
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
        LogHelp.i("XpMiBackup", "STARTUP step: onResume enter (tabsReady=" + tabsReady + ")");
        if (Environment.isExternalStorageManager() && !tabsReady) {
            initTabs();
            updateTabSelection(TAB_NAMES[0]);
            updateBackInvoke();
        }
        // 免责声明：冷启动/主题重建后经 onResume 触发，仅弹一次（Bug #3）
        ensureDisclaimer();
        maybePromptModuleRestore();
        LogHelp.i("XpMiBackup", "STARTUP step: onResume END");
    }

    /**
     * 更新导航栏选中态与顶栏标题。
     * MD3 Navigation Bar 配色规则：
     * · 未选中——图标/文字用 on-surface-variant（tab_icon_idle / tab_text_idle）
     * · 已选中——primary-container 胶囊底（bg_nav_item 已承载）+ on-primary-container 图标/文字
     * · 核心操作（备份）——图标常驻品牌色实心圆 + 白色反色图标，与其余导航项区分
     */
    private void updateTabSelection(String tab) {
        for (var i = 0; i < TAB_COUNT; i++) {
            applyTabState(i, TAB_NAMES[i].equals(tab));
        }
        if (tvTopTitle != null) {
            var index = indexOfTab(tab);
            if (index >= 0) tvTopTitle.setText(TAB_TITLE_RES[index]);
        }
    }

    /** 设置单个导航项的选中态 */
    private void applyTabState(int index, boolean selected) {
        if (navItems == null || index < 0 || index >= navItems.length) return;
        var res = getResources();
        var idleTextColor = res.getColor(R.color.tab_text_idle, getTheme());
        var activeTextColor = res.getColor(R.color.tab_text_selected, getTheme());
        navItems[index].setSelected(selected);
        navTexts[index].setTextColor(selected ? activeTextColor : idleTextColor);
        if (index == CORE_TAB) {
            // 核心操作：图标固定白色（落在品牌色实心圆内），不随选中态改色
            navIcons[index].setColorFilter(res.getColor(R.color.on_brand, getTheme()));
        } else {
            navIcons[index].setColorFilter(res.getColor(
                    selected ? R.color.tab_icon_selected : R.color.tab_icon_idle, getTheme()));
        }
    }

    private int indexOfTab(String tab) {
        for (var i = 0; i < TAB_COUNT; i++) {
            if (TAB_NAMES[i].equals(tab)) return i;
        }
        return -1;
    }
}
