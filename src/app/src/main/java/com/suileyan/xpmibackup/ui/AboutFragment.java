package com.suileyan.xpmibackup.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.suileyan.comm.Async;
import com.suileyan.comm.LogHelp;
import com.suileyan.comm.UpdateChecker;
import com.suileyan.xpmibackup.R;

/**
 * 关于页：应用图标 + 名称 + 版本号 + 检查更新
 * 更新检查走 GitHub releases/latest（多源降级，见 UpdateChecker），
 * 发现新版本弹窗提供「前往下载」（GitHub 页）与「下载 APK」（官方直链/可配置镜像）两个动作。
 */
public class AboutFragment extends Fragment {

    private static final String TAG = "XpMiBackup";

    private Button btnCheckUpdate;
    private TextView tvUpdateStatus;
    /** 当前版本号（PackageManager），点击检查时复用 */
    private String currentVersion = "";
    /** 防连点：检查中忽略重复点击 */
    private boolean checking = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_about, container, false);

        try {
            var versionName = getActivity() != null
                    ? getActivity().getPackageManager()
                        .getPackageInfo(getActivity().getPackageName(), 0).versionName
                    : "";
            currentVersion = versionName == null ? "" : versionName;
            ((TextView) view.findViewById(R.id.tv_about_version))
                    .setText(getString(R.string.about_version, currentVersion));
        } catch (Exception ignored) {
        }

        btnCheckUpdate = view.findViewById(R.id.btn_check_update);
        tvUpdateStatus = view.findViewById(R.id.tv_update_status);
        btnCheckUpdate.setOnClickListener(v -> onCheckUpdate());

        return view;
    }

    /** 检查更新：后台请求 GitHub（多源降级），结果回主线程更新状态/弹窗 */
    private void onCheckUpdate() {
        if (checking || getActivity() == null) return;
        checking = true;
        btnCheckUpdate.setEnabled(false);
        tvUpdateStatus.setText(R.string.about_checking);
        Async.run("check-update", () -> {
            var result = UpdateChecker.check(currentVersion);
            var activity = getActivity();
            if (activity == null) {
                // Fragment 已脱离 → 静默返回
                checking = false;
                return;
            }
            activity.runOnUiThread(() -> {
                checking = false;
                btnCheckUpdate.setEnabled(true);
                if (!result.ok) {
                    tvUpdateStatus.setText(R.string.about_check_failed);
                    return;
                }
                if (result.hasNew) {
                    tvUpdateStatus.setText(getString(R.string.about_new_version, result.latestVersion));
                    showUpdateDialog(result.latestVersion, result.htmlUrl, result.downloadUrl);
                } else {
                    tvUpdateStatus.setText(R.string.about_latest);
                }
            });
        });
    }

    /** 发现新版本弹窗：自定义竖排按钮布局（长标签不再被横排按钮条截断）；可点空白/返回取消 */
    private void showUpdateDialog(String version, String htmlUrl, String downloadUrl) {
        var activity = getActivity();
        if (activity == null) return;
        var view = activity.getLayoutInflater().inflate(R.layout.dialog_update, null);
        ((TextView) view.findViewById(R.id.tv_update_message))
                .setText(getString(R.string.update_dialog_message, version));
        var dialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(true)
                .create();
        // 下载 APK：固定指向 123 网盘分享链接（国内可达）；config.ini download_url 可覆盖
        view.findViewById(R.id.btn_update_download).setOnClickListener(v -> {
            dialog.dismiss();
            openBrowser(UpdateChecker.apkDownloadUrl());
        });
        view.findViewById(R.id.btn_update_github).setOnClickListener(v -> {
            dialog.dismiss();
            openBrowser(htmlUrl);
        });
        view.findViewById(R.id.btn_update_cancel).setOnClickListener(v -> dialog.dismiss());
        // 点击空白处取消弹窗
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    /** 打开浏览器；无浏览器应用时捕获异常提示，不崩溃 */
    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            LogHelp.w(TAG, "open browser failed: " + url, e);
            Toast.makeText(getActivity(), R.string.about_check_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
