package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.suileyan.xpmibackup.R;

/**
 * 关于页（占位）：应用图标 + 名称 + 版本号
 */
public class AboutFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_about, container, false);

        try {
            var versionName = getActivity() != null
                    ? getActivity().getPackageManager()
                        .getPackageInfo(getActivity().getPackageName(), 0).versionName
                    : "";
            ((TextView) view.findViewById(R.id.tv_about_version))
                    .setText(getString(R.string.about_version, versionName == null ? "" : versionName));
        } catch (Exception ignored) {
        }

        return view;
    }
}
