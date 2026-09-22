package com.suileyan.xpmibackup.hook;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suileyan.comm.LogHelp;
import com.suileyan.comm.TransferSpeed;

/**
 * 备份/恢复进度页：在总进度右侧显示实时上传速率。
 *
 * 定位方式刻意**不依赖资源 id**（宿主版本升级会让 id 漂移）：
 * 宿主的总进度是自绘的 {@code com.miui.backup.NumberProgressView}（extends TextView，负责显示 "45"），
 * 它所在的那一行就是"总进度 + 百分比"行。取它的父容器，把角标追加到该容器末尾，
 * 视觉效果是 {@code 45% --}，也就是总进度右边。
 *
 * 刷新：每秒一次，只在角标已 attach 时续期，页面销毁自动停止（不持有 Fragment/Activity 引用）。
 */
public final class ProgressSpeedBadge {

    private static final String TAG = "XpMiBackup";
    /** 角标标记：同一容器只挂一个（宿主重建视图时靠它去重） */
    private static final String BADGE_TAG = "xpmi_speed_badge";
    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final int MARGIN_START_DP = 10;
    /** 找不到同行的百分比文字时用的字号 */
    private static final int DEFAULT_TEXT_SP = 14;

    private ProgressSpeedBadge() {
    }

    /** 取同一行里那个百分比符号的 TextView，用来对齐字号与颜色（进度数字自己是巨字，不能跟随） */
    private static TextView siblingLabel(ViewGroup parent, View exclude) {
        for (var i = 0; i < parent.getChildCount(); i++) {
            var child = parent.getChildAt(i);
            if (child != exclude && child instanceof TextView) {
                return (TextView) child;
            }
        }
        return null;
    }

    /**
     * 往总进度所在容器追加速率角标（幂等；必须在 UI 线程调用）
     *
     * @param progressView 宿主的总进度视图（com.miui.backup.NumberProgressView）
     */
    public static void attach(View progressView) {
        try {
            if (!(progressView instanceof TextView)) {
                return;
            }
            var container = progressView.getParent();
            if (!(container instanceof ViewGroup)) {
                return;
            }
            var parent = (ViewGroup) container;
            if (parent.findViewWithTag(BADGE_TAG) != null) {
                return; // 已经挂过（onAttachedToWindow 可能触发多次）
            }
            var badge = new TextView(progressView.getContext());
            badge.setTag(BADGE_TAG);
            badge.setText(TransferSpeed.text());
            // 字号/颜色取"进度行里的百分比符号"（NumberProgressView 自身是 60sp 级别的巨字，
            // 跟随它会把 "12.3 MB/s" 撑满整行甚至换行）
            var label = siblingLabel(parent, progressView);
            if (label != null) {
                badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, label.getTextSize());
                badge.setTextColor(label.getCurrentTextColor());
                badge.setTypeface(label.getTypeface());
            } else {
                badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SP);
                badge.setTextColor(((TextView) progressView).getCurrentTextColor());
            }
            badge.setIncludeFontPadding(false);
            badge.setSingleLine(true);
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            parent.addView(badge, layoutParams(parent, badge));
            LogHelp.i(TAG, "进度页速度角标已挂载: parent=" + parent.getClass().getName()
                    + " children=" + parent.getChildCount());
            startTicker(badge);
        } catch (Throwable e) {
            LogHelp.w(TAG, "attach progress speed badge failed", e);
        }
    }

    /** 按父容器类型生成布局参数：进度行是横向 LinearLayout，其余容器给个能跑通的兜底 */
    private static ViewGroup.LayoutParams layoutParams(ViewGroup parent, View view) {
        if (parent instanceof LinearLayout) {
            var lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMarginStart(dp(view, MARGIN_START_DP));
            return lp;
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * 每秒刷新一次；一旦角标从窗口脱离（页面销毁/切换）就停止续期，避免泄漏与空转
     */
    private static void startTicker(TextView badge) {
        badge.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!badge.isAttachedToWindow() || badge.getParent() == null) {
                    return;
                }
                badge.setText(TransferSpeed.text());
                badge.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }, REFRESH_INTERVAL_MS);
    }

    private static int dp(View view, int value) {
        return Math.round(view.getResources().getDisplayMetrics().density * value);
    }
}
