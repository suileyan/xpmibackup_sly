package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.suileyan.xpmibackup.R;

/**
 * 网盘选择列表页
 * 每行一个网盘，点击进入对应网盘的登录流程；当前支持 139 云盘（移动云盘）、光鸭云盘
 */
public class CloudProviderSelectFragment extends Fragment {

    /**
     * 初始化界面：渲染网盘列表
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_cloud_provider_select, container, false);
        var list = (LinearLayout) view.findViewById(R.id.provider_list);
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_139),
                getString(R.string.cloud_provider_139_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_139)));
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_guangya),
                getString(R.string.cloud_provider_guangya_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_GUANGYA)));
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_quark),
                getString(R.string.cloud_provider_quark_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_QUARK)));
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_123),
                getString(R.string.cloud_provider_123_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_123)));
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_189),
                getString(R.string.cloud_provider_189_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_189)));
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_baidu),
                getString(R.string.cloud_provider_baidu_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_BAIDU)));
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_wo),
                getString(R.string.cloud_provider_wo_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_WO)));
        list.addView(createProviderRow(R.drawable.ic_tab_cloud,
                getString(R.string.cloud_provider_115),
                getString(R.string.cloud_provider_115_hint),
                v -> openLogin(WebViewLoginFragment.PROVIDER_115)));
        return view;
    }

    /**
     * 构造单个网盘卡片行
     */
    private View createProviderRow(int iconRes, String name, String hint, View.OnClickListener listener) {
        var row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_card);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        var rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        row.setLayoutParams(rowLp);
        row.setOnClickListener(listener);

        var icon = new ImageView(getActivity());
        icon.setImageResource(iconRes);
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
        nameText.setText(name);
        info.addView(nameText);

        var hintText = new TextView(getActivity());
        hintText.setTextSize(12f);
        hintText.setTextColor(getResources().getColor(R.color.text_secondary));
        hintText.setText(hint);
        hintText.setPadding(0, dp(2), 0, 0);
        info.addView(hintText);

        row.addView(info);

        var arrow = new TextView(getActivity());
        arrow.setText("›");
        arrow.setTextSize(22f);
        arrow.setTextColor(getResources().getColor(R.color.text_hint));
        row.addView(arrow);

        return row;
    }

    /**
     * 打开对应网盘的 WebView 登录页
     * @param provider 网盘类型（WebViewLoginFragment.PROVIDER_*）
     */
    private void openLogin(String provider) {
        var login = new WebViewLoginFragment();
        var args = new Bundle();
        args.putString(WebViewLoginFragment.ARG_PROVIDER, provider);
        login.setArguments(args);
        var ft = getFragmentManager().beginTransaction();
        // 二级页面：压到 overlay 层，右滑进入/左滑退出，返回时反向
        var overlay = getActivity() != null ? getActivity().findViewById(R.id.overlay_container) : null;
        if (overlay != null) {
            // 防御：清除预测返回推开后可能残留的容器偏移，确保页面从正常位置显示
            overlay.setTranslationX(0f);
            overlay.setVisibility(View.VISIBLE);
        }
        // add 叠放：下层选择页 view 常驻，预测返回跟手时从左侧露出（返回显示上一层）；
        // pop 用 0ms 空动画避免转场闪烁
        ft.setCustomAnimations(R.animator.slide_in_right, R.animator.no_anim,
                R.animator.no_anim, R.animator.no_anim);
        ft.add(R.id.overlay_container, login);
        ft.addToBackStack("cloud-login");
        ft.commit();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
