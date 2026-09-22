package com.suileyan.xpmibackup.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.suileyan.xpmibackup.R;

import java.nio.charset.StandardCharsets;

/**
 * 自定义脚本使用说明页（overlay 二级页面）
 * 内容来自 res/raw/script_help.txt，monospace 等宽展示便于阅读代码示例
 */
public class ScriptHelpFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_script_help, container, false);

        var tvContent = (TextView) view.findViewById(R.id.tv_help_content);
        tvContent.setText(readHelpText());

        var btnBack = (Button) view.findViewById(R.id.btn_help_back);
        btnBack.setOnClickListener(v -> {
            if (getFragmentManager() != null) {
                getFragmentManager().popBackStack();
            }
        });
        return view;
    }

    private String readHelpText() {
        try (var is = getResources().openRawResource(R.raw.script_help)) {
            // MED-26：不能用 is.available() 当长度分配数组——它只是"当前可无阻塞读取的字节数"，
            // 部分 ROM 对 raw 资源返回 0，会直接得到空白帮助页。
            var buf = new java.io.ByteArrayOutputStream();
            var chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
