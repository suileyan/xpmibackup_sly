package com.suileyan.xpmibackup.ui;

import android.annotation.SuppressLint;
import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudAccountStore;
import com.suileyan.cloud.CredentialChecker;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.ProviderRegistry;
import com.suileyan.cloud.login.GDriveOAuth;
import com.suileyan.cloud.login.LoopbackAuthServer;
import com.suileyan.comm.LogHelp;
import com.suileyan.xpmibackup.R;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google Drive 授权登录页（loopback 换取方案，不走 WebView——Google 拒绝内嵌 WebView 的 OAuth）
 *
 * 流程：
 * 1. 内置 OAuth 客户端已配置时（`GDriveOAuth.hasBuiltinClient()`）直接「一键授权」，
 *    client 输入区折叠，用户可展开「使用自己的 OAuth 客户端」覆盖；
 *    未内置时该输入区直接展开，必填 client_id/secret（见 GDriveOAuth 的内置凭据说明）；
 * 2. 「开始授权」→ 启动 LoopbackAuthServer（127.0.0.1 随机端口）→ 系统浏览器打开授权页；
 * 3. 浏览器完成授权后 302 回 loopback 地址，服务器接住 code（state 严格校验）；
 * 4. code 换 token（GDriveOAuth.exchange）→ testConnection 验证 → 凭据入 EncryptedCredStore → 账号落库。
 *
 * 失败/取消路径（计划 §2.3 全覆盖）：拒绝授权/超时/state 不匹配/交换失败/无浏览器——
 * 均无脏状态，可安全重试；幂等复用已存在 gdrive 账号 id，失败时恢复旧凭据（HIGH-01 同款）。
 */
public class GDriveLoginFragment extends Fragment {

    private static final String TAG = "XpMiBackup";

    private EditText etClientId;
    private EditText etClientSecret;
    private EditText etProxyHost;
    private EditText etProxyPort;
    private Button btnStart;
    private TextView tvStatus;

    private LoopbackAuthServer authServer;
    private volatile Thread worker;
    private volatile boolean released;

    @SuppressLint("SetTextI18n")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        var view = inflater.inflate(R.layout.fragment_gdrive_login, container, false);
        etClientId = view.findViewById(R.id.et_gdrive_client_id);
        etClientSecret = view.findViewById(R.id.et_gdrive_client_secret);
        etProxyHost = view.findViewById(R.id.et_gdrive_proxy_host);
        etProxyPort = view.findViewById(R.id.et_gdrive_proxy_port);
        btnStart = view.findViewById(R.id.btn_gdrive_start);
        tvStatus = view.findViewById(R.id.tv_gdrive_status);

        // 内置 OAuth 客户端时：折叠 client 输入区，只留「一键授权」；
        // 仍保留「使用自己的 OAuth 客户端」入口展开自填（覆盖 + 内置 client 被封时的自救路径）
        var containerClient = view.findViewById(R.id.container_gdrive_client);
        var tvBuiltinNote = (TextView) view.findViewById(R.id.tv_gdrive_builtin_note);
        var tvUseOwn = (TextView) view.findViewById(R.id.tv_gdrive_use_own);
        var builtin = GDriveOAuth.hasBuiltinClient();
        containerClient.setVisibility(builtin ? View.GONE : View.VISIBLE);
        tvBuiltinNote.setVisibility(builtin ? View.VISIBLE : View.GONE);
        tvUseOwn.setVisibility(builtin ? View.VISIBLE : View.GONE);
        tvUseOwn.setPaintFlags(tvUseOwn.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        tvUseOwn.setOnClickListener(v -> {
            containerClient.setVisibility(View.VISIBLE);
            tvUseOwn.setVisibility(View.GONE);
        });

        // 「如何获取 Client ID / Secret」引导：默认折叠，点击标题展开/收起
        // （自有客户端路径下这是首次使用的最大门槛，故给出分步指引）
        var tvHelpLink = (TextView) view.findViewById(R.id.tv_gdrive_help_link);
        var tvHelp = view.findViewById(R.id.tv_gdrive_help);
        tvHelpLink.setPaintFlags(tvHelpLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        tvHelpLink.setOnClickListener(v ->
                tvHelp.setVisibility(tvHelp.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // 预填 OAuth client / 代理（幂等重授权场景）：账号里存过就用账号的，
        // 没存过（或存的是空）则回退内置，避免「已登录却换不了 token」
        var existing = existingGdriveAccount();
        var clientId = existing != null ? EncryptedCredStore.get(existing.id, "client_id") : "";
        var clientSecret = existing != null ? EncryptedCredStore.get(existing.id, "client_secret") : "";
        if (clientId.isEmpty() && GDriveOAuth.hasBuiltinClient()) {
            clientId = GDriveOAuth.BUILTIN_CLIENT_ID;
            clientSecret = GDriveOAuth.BUILTIN_CLIENT_SECRET;
        }
        etClientId.setText(clientId);
        etClientSecret.setText(clientSecret);
        if (existing != null) {
            etProxyHost.setText(EncryptedCredStore.get(existing.id, "proxy_host"));
            etProxyPort.setText(EncryptedCredStore.get(existing.id, "proxy_port"));
        }

        btnStart.setOnClickListener(v -> startAuth());
        return view;
    }

    @Override
    public void onDestroyView() {
        released = true;
        if (authServer != null) {
            authServer.stop();
            authServer = null;
        }
        var w = worker;
        if (w != null) {
            w.interrupt();
        }
        super.onDestroyView();
    }

    /** 单账号幂等（与其它 Provider 一致）：复用已存在 gdrive 账号 id */
    private static CloudAccount existingGdriveAccount() {
        return CloudAccountStore.list().stream()
                .filter(a -> CloudAccount.PROVIDER_GDRIVE.equals(a.provider))
                .findFirst().orElse(null);
    }

    private void startAuth() {
        var clientId = etClientId.getText().toString().trim();
        var clientSecret = etClientSecret.getText().toString().trim();
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            Toast.makeText(getActivity(), R.string.gdrive_need_client, Toast.LENGTH_LONG).show();
            return;
        }
        var proxyHost = etProxyHost.getText().toString().trim();
        var proxyPort = etProxyPort.getText().toString().trim();
        if (proxyPort.isEmpty() && !proxyHost.isEmpty()
                || !proxyPort.isEmpty() && proxyHost.isEmpty()) {
            Toast.makeText(getActivity(), R.string.gdrive_proxy_incomplete, Toast.LENGTH_LONG).show();
            return;
        }
        if (!proxyPort.isEmpty()) {
            try {
                var p = Integer.parseInt(proxyPort);
                if (p <= 0 || p > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                Toast.makeText(getActivity(), R.string.gdrive_proxy_incomplete, Toast.LENGTH_LONG).show();
                return;
            }
        }

        var existing = existingGdriveAccount();
        var accountId = existing != null ? existing.id : "gdrive_" + System.currentTimeMillis();
        // HIGH-01：幂等复用场景先快照旧凭据，失败恢复而非删除
        var prev = snapshotCredentials(accountId);

        btnStart.setEnabled(false);
        setStatus(getString(R.string.gdrive_auth_preparing));

        final var account = accountId;
        final var portHolder = new int[1];
        final var serverHolder = new LoopbackAuthServer[1];
        worker = new Thread(() -> {
            try {
                // 1. 持久化 client 信息与代理（Provider 后续直接读）
                EncryptedCredStore.put(account, "client_id", clientId);
                EncryptedCredStore.put(account, "client_secret", clientSecret);
                EncryptedCredStore.put(account, "proxy_host", proxyHost);
                EncryptedCredStore.put(account, "proxy_port", proxyPort);

                // 2. 起 loopback 回调服务器（state 严格校验）；端口启动后才确定，
                //    回调经 portHolder 取（callback 先于 port 赋值创建）
                var state = GDriveOAuth.newState();
                var server = LoopbackAuthServer.start(state, new LoopbackAuthServer.Callback() {
                    @Override
                    public void onCode(String code) {
                        exchangeAndSave(account, clientId, clientSecret, portHolder[0], code, prev);
                    }

                    @Override
                    public void onError(String error, String errorDescription) {
                        LogHelp.w(TAG, "Google Drive 授权错误: " + error);
                        restoreCredentials(account, prev);
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                setStatus(getString(R.string.gdrive_auth_failed) + ": " + error);
                                resetButton();
                            });
                        }
                    }

                    @Override
                    public void onTimeout() {
                        restoreCredentials(account, prev);
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                setStatus(getString(R.string.gdrive_auth_timeout));
                                resetButton();
                            });
                        }
                    }
                });
                serverHolder[0] = server;
                authServer = server;
                portHolder[0] = server.port();
                var redirectUri = "http://127.0.0.1:" + server.port();
                var authUrl = GDriveOAuth.buildAuthUrl(clientId, redirectUri, state);

                // 3. 拉起系统浏览器（Google 拒绝内嵌 WebView 的 OAuth，必须系统浏览器）
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        setStatus(getString(R.string.gdrive_auth_waiting));
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
                        } catch (Exception e) {
                            LogHelp.e(TAG, "拉起浏览器失败", e);
                            serverHolder[0].stop();
                            restoreCredentials(account, prev);
                            setStatus(getString(R.string.gdrive_no_browser));
                            resetButton();
                        }
                    });
                }
            } catch (Exception e) {
                LogHelp.e(TAG, "Google Drive 授权发起失败", e);
                restoreCredentials(account, prev);
                if (serverHolder[0] != null) {
                    serverHolder[0].stop();
                }
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        setStatus(getString(R.string.gdrive_auth_failed) + ": " + e.getMessage());
                        resetButton();
                    });
                }
            }
        }, "XpMiBackup-gdrive-auth");
        worker.start();
    }

    /** code 换 token → testConnection 验证 → 落库；失败恢复旧凭据（后台线程） */
    private void exchangeAndSave(String accountId, String clientId, String clientSecret,
                                 int serverPort, String code, Map<String, String> prev) {
        if (released) return;
        setStatus(getString(R.string.gdrive_authorizing));
        try {
            var redirectUri = "http://127.0.0.1:" + serverPort;
            var token = GDriveOAuth.exchange(clientId, clientSecret, redirectUri, code);
            EncryptedCredStore.put(accountId, "access_token", token.accessToken);
            EncryptedCredStore.put(accountId, "token_expiry", String.valueOf(token.expiresAt));
            if (!token.refreshToken.isEmpty()) {
                EncryptedCredStore.put(accountId, "refresh_token", token.refreshToken);
            }

            var provider = ProviderRegistry.forAccount(new CloudAccount(accountId,
                    CloudAccount.PROVIDER_GDRIVE, "", "", System.currentTimeMillis()));
            var ok = provider != null && provider.testConnection();
            if (released || getActivity() == null) return;
            var okFinal = ok;
            getActivity().runOnUiThread(() -> {
                if (okFinal) {
                    saveAccount(accountId);
                } else {
                    restoreCredentials(accountId, prev);
                    setStatus(getString(R.string.gdrive_auth_failed));
                    resetButton();
                    Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            LogHelp.e(TAG, "Google Drive 凭据换取失败", e);
            restoreCredentials(accountId, prev);
            if (released || getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                var msg = e.getMessage() != null ? e.getMessage() : "";
                setStatus(getString(R.string.gdrive_auth_failed) + (msg.isEmpty() ? "" : ": " + msg));
                resetButton();
                Toast.makeText(getActivity(), R.string.toast_cloud_auth_invalid, Toast.LENGTH_LONG).show();
            });
        }
    }

    private void saveAccount(String id) {
        try {
            var email = EncryptedCredStore.get(id, "email");
            var nickname = EncryptedCredStore.get(id, "nickname");
            var display = !email.isEmpty() ? email : nickname;
            CloudAccountStore.add(new CloudAccount(id, CloudAccount.PROVIDER_GDRIVE,
                    display, getString(R.string.cloud_provider_gdrive), System.currentTimeMillis()));
            CredentialChecker.invalidateAll();
            LogHelp.i(TAG, "Google Drive 账号已保存: " + id);
            Toast.makeText(getActivity(), R.string.gdrive_auth_ok, Toast.LENGTH_SHORT).show();
            if (getFragmentManager() != null) {
                getFragmentManager().popBackStack();
            }
        } catch (Exception e) {
            LogHelp.e(TAG, "save Google Drive account failed", e);
            Toast.makeText(getActivity(), R.string.toast_cloud_account_save_failed, Toast.LENGTH_LONG).show();
            resetButton();
        }
    }

    // ---- 凭据快照/恢复（幂等重授权失败回滚，HIGH-01 同款） ----

    private static final String[] CRED_KEYS =
            {"client_id", "client_secret", "refresh_token", "access_token",
                    "token_expiry", "proxy_host", "proxy_port"};

    private Map<String, String> snapshotCredentials(String accountId) {
        var map = new LinkedHashMap<String, String>();
        for (var key : CRED_KEYS) {
            map.put(key, EncryptedCredStore.get(accountId, key));
        }
        return map;
    }

    private void restoreCredentials(String accountId, Map<String, String> prev) {
        if (prev == null) return;
        for (var entry : prev.entrySet()) {
            try {
                EncryptedCredStore.put(accountId, entry.getKey(), entry.getValue());
            } catch (Exception ex) {
                LogHelp.w(TAG, "恢复凭据失败 key=" + entry.getKey());
            }
        }
    }

    private void setStatus(String text) {
        if (getActivity() == null || isDetached()) return;
        getActivity().runOnUiThread(() -> {
            if (tvStatus != null) tvStatus.setText(text);
        });
    }

    private void resetButton() {
        if (getActivity() == null || isDetached()) return;
        getActivity().runOnUiThread(() -> {
            if (btnStart != null) {
                btnStart.setEnabled(true);
            }
        });
    }
}
