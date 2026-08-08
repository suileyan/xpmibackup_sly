package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudAccountStore;
import com.suileyan.cloud.CredentialChecker;
import com.suileyan.cloud.ProviderRegistry;
import com.suileyan.comm.Async;
import com.suileyan.xpmibackup.R;

/**
 * 云盘账号页面
 * 列出已登录的云盘账号，支持添加（139 账密登录）与删除
 */
public class AccountConfigFragment extends Fragment {

    private LinearLayout accountList;
    private TextView tvEmpty;

    /**
     * 初始化界面：绑定添加按钮，刷新云盘账号列表
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_account_config, container, false);

        accountList = view.findViewById(R.id.cloud_account_list);
        tvEmpty = view.findViewById(R.id.tv_cloud_empty);
        view.findViewById(R.id.btn_add_cloud_account).setOnClickListener(v -> openLogin());

        refreshList();
        return view;
    }

    /**
     * 重新可见时刷新列表：登录页/选择页保存账号后返回，新账号立即可见
     */
    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    /**
     * 重新渲染云盘账号列表；无账号时显示空提示
     * 公开供 WebViewLoginFragment 保存成功后直接调用（不依赖 onResume 时序）
     */
    public void refreshList() {
        // 视图可能尚未创建（如后台回调触发），防空指针（MED-38）
        if (accountList == null || tvEmpty == null) {
            return;
        }
        accountList.removeAllViews();
        var accounts = CloudAccountStore.list();
        if (accounts.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        tvEmpty.setVisibility(View.GONE);
        for (var account : accounts) {
            // 老账号 uid 回填放在刷新时机执行（展示函数不再写盘，HIGH-04）
            com.suileyan.cloud.AccountDisplay.healAccountUidIfNeeded(account);
            accountList.addView(createAccountRow(account));
        }
    }

    /**
     * 构造单条云盘账号卡片行：类型图标 + 名称 + 删除按钮
     */
    private View createAccountRow(CloudAccount account) {
        var row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        var rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        row.setLayoutParams(rowLp);

        var icon = new ImageView(getActivity());
        icon.setImageResource(R.drawable.ic_tab_cloud);
        icon.setColorFilter(getResources().getColor(R.color.primary));
        icon.setBackgroundResource(R.drawable.bg_account_icon);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        var iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        iconLp.setMarginEnd(dp(12));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        var info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        var nameText = new TextView(getActivity());
        nameText.setTextSize(15f);
        nameText.setTypeface(nameText.getTypeface(), android.graphics.Typeface.BOLD);
        nameText.setTextColor(getResources().getColor(R.color.text_primary));
        nameText.setText(com.suileyan.cloud.AccountDisplay.providerLabel(account.provider));
        info.addView(nameText);

        var subText = new TextView(getActivity());
        subText.setTextSize(12f);
        subText.setTextColor(getResources().getColor(R.color.text_secondary));
        // 副标题显示脱敏账号（区分同网盘不同账户）；无账号信息时提示
        var accountInfo = com.suileyan.cloud.AccountDisplay.accountInfo(account);
        if (accountInfo.isEmpty()) {
            subText.setText(R.string.account_no_info);
        } else {
            subText.setText(accountInfo);
        }
        subText.setPadding(0, dp(2), 0, 0);
        info.addView(subText);

        // 凭证状态行：异步检查后显示"有效/失效需重新登录/网络异常"（凭证预处理，NEW-H-04）
        var statusText = new TextView(getActivity());
        statusText.setTextSize(11f);
        statusText.setTextColor(getResources().getColor(R.color.text_secondary));
        statusText.setPadding(0, dp(2), 0, 0);
        info.addView(statusText);

        row.addView(info);

        // 右侧按钮区：失效/弱凭据时显示"重新登录"，其后为删除按钮
        var btnArea = new LinearLayout(getActivity());
        btnArea.setOrientation(LinearLayout.HORIZONTAL);
        btnArea.setGravity(Gravity.CENTER_VERTICAL);
        btnArea.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        var btnRelogin = new Button(getActivity());
        btnRelogin.setText(R.string.relogin_account);
        btnRelogin.setTextSize(11f);
        btnRelogin.setAllCaps(false);
        btnRelogin.setTextColor(getResources().getColor(R.color.primary_text_on));
        btnRelogin.setBackgroundResource(R.drawable.bg_button_primary);
        btnRelogin.setPadding(dp(12), 0, dp(12), 0);
        var reloginLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        reloginLp.setMarginEnd(dp(8));
        btnRelogin.setLayoutParams(reloginLp);
        // 初始隐藏：仅在凭证失效/建议重新登录时显示
        btnRelogin.setVisibility(View.GONE);
        btnRelogin.setOnClickListener(v -> openLoginFor(account.provider));
        btnArea.addView(btnRelogin);

        var btnDelete = new Button(getActivity());
        btnDelete.setText(R.string.delete_account);
        btnDelete.setTextSize(11f);
        btnDelete.setAllCaps(false);
        btnDelete.setTextColor(getResources().getColor(R.color.primary_text_on));
        btnDelete.setBackgroundResource(R.drawable.bg_button_danger);
        btnDelete.setPadding(dp(12), 0, dp(12), 0);
        btnDelete.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        btnDelete.setOnClickListener(v -> deleteAccount(account));
        btnArea.addView(btnDelete);

        row.addView(btnArea);

        // 异步校验凭证状态（不阻塞列表渲染；行被移除后忽略结果）
        startCredentialCheck(account, statusText, btnRelogin);

        return row;
    }

    /**
     * 异步检查账号凭证状态并更新状态行
     * 检查在共享守护线程池执行（Async），结果回主线程更新文本/颜色与"重新登录"按钮
     */
    private void startCredentialCheck(CloudAccount account, TextView statusText, Button btnRelogin) {
        statusText.setText(R.string.cred_checking);
        statusText.setTextColor(getResources().getColor(R.color.text_secondary));
        Async.run("cred-check-" + account.id, () -> {
            var status = CredentialChecker.check(account);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                // 行可能已被移除/重建（Fragment 重绘），通过 tag 兜底防止更新到孤儿 view
                if (statusText.getParent() == null) return;
                applyCredentialStatus(status, statusText, btnRelogin);
            });
        });
    }

    /** 按凭证状态刷新状态行文本/颜色，并决定是否显示"重新登录"按钮 */
    private void applyCredentialStatus(CredentialChecker.Status status, TextView statusText, Button btnRelogin) {
        switch (status) {
            case VALID:
                statusText.setText(R.string.cred_valid);
                statusText.setTextColor(getResources().getColor(R.color.success));
                btnRelogin.setVisibility(View.GONE);
                break;
            case WEAK:
                statusText.setText(R.string.cred_weak);
                statusText.setTextColor(getResources().getColor(R.color.warning));
                btnRelogin.setVisibility(View.VISIBLE);
                break;
            case INVALID:
                statusText.setText(R.string.cred_invalid);
                statusText.setTextColor(getResources().getColor(R.color.danger));
                btnRelogin.setVisibility(View.VISIBLE);
                break;
            case ERROR:
                statusText.setText(R.string.cred_error);
                statusText.setTextColor(getResources().getColor(R.color.text_secondary));
                btnRelogin.setVisibility(View.GONE);
                break;
            default: // CHECKING
                statusText.setText(R.string.cred_checking);
                statusText.setTextColor(getResources().getColor(R.color.text_secondary));
                btnRelogin.setVisibility(View.GONE);
                break;
        }
    }

    /**
     * 打开网盘选择列表页
     */
    private void openLogin() {
        var ft = getFragmentManager().beginTransaction();
        // 二级页面：压到 overlay 层（不触碰 tab 层常驻页面），右滑进入/左滑退出，返回时反向
        var overlay = getActivity() != null ? getActivity().findViewById(R.id.overlay_container) : null;
        if (overlay != null) {
            // 防御：清除预测返回推开后可能残留的容器偏移，确保页面从正常位置显示
            overlay.setTranslationX(0f);
            overlay.setVisibility(View.VISIBLE);
        }
        // add 叠放：下层页 view 常驻，预测返回跟手时露出"上一层"；pop 用 0ms 空动画避免转场闪烁
        ft.setCustomAnimations(R.animator.slide_in_right, R.animator.no_anim,
                R.animator.no_anim, R.animator.no_anim);
        ft.add(R.id.overlay_container, new CloudProviderSelectFragment());
        ft.addToBackStack("cloud-provider-select");
        ft.commit();
    }

    /**
     * 凭证失效时直接打开指定网盘登录页重新登录（跳过网盘选择）
     * @param provider 网盘类型（WebViewLoginFragment.PROVIDER_*）
     */
    private void openLoginFor(String provider) {
        var login = new WebViewLoginFragment();
        var args = new Bundle();
        args.putString(WebViewLoginFragment.ARG_PROVIDER, provider);
        login.setArguments(args);
        var ft = getFragmentManager().beginTransaction();
        var overlay = getActivity() != null ? getActivity().findViewById(R.id.overlay_container) : null;
        if (overlay != null) {
            overlay.setTranslationX(0f);
            overlay.setVisibility(View.VISIBLE);
        }
        ft.setCustomAnimations(R.animator.slide_in_right, R.animator.no_anim,
                R.animator.no_anim, R.animator.no_anim);
        ft.add(R.id.overlay_container, login);
        ft.addToBackStack("cloud-login");
        ft.commit();
    }

    /**
     * 删除云盘账号（同时清理加密凭据）
     */
    private void deleteAccount(CloudAccount account) {
        CloudAccountStore.remove(account.id);
        // 若删除的是当前云盘备份目标，清除目标回到 NAS 模式
        if (ProviderRegistry.isCloudTarget()) {
            ProviderRegistry.clearCloudTarget();
        }
        ProviderRegistry.invalidateAll();
        CredentialChecker.invalidate(account.id);
        Toast.makeText(getActivity(), R.string.toast_account_removed, Toast.LENGTH_SHORT).show();
        refreshList();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
