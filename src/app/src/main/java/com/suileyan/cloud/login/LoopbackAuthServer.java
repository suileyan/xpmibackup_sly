package com.suileyan.cloud.login;

import com.suileyan.comm.LogHelp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Google OAuth loopback 回调服务器（一次性）
 *
 * 流程：绑定 127.0.0.1:<随机空闲端口> → 浏览器完成授权后 302 回
 * http://127.0.0.1:<port>/?code=...&state=... → 本类接住 code 回调给调用方，
 * 返回静态提示页后立即关闭（单次使用，防复用/探测）。
 *
 * 安全约定：
 * - 只绑回环地址，不暴露到局域网（回环不受 Android 16 本地网络保护限制）；
 * - state 由调用方生成并在回调时严格比对（CSRF 防护），不匹配直接拒绝并停止；
 * - 收到合法 code 或 error 后 ServerSocket 立即关闭，后续连接一律失败；
 * - 只解析请求行，不解析/不存储任何请求体；日志不落 code/state 明文（脱敏铁律）。
 *
 * 超时：accept 以 500ms 轮询，整体存活 AUTH_WAIT_MS；超时回调 onTimeout。
 */
public final class LoopbackAuthServer {

    private static final String TAG = "XpMiBackup";
    /** 授权等待窗口：用户在浏览器完成登录的合理上限 */
    private static final int AUTH_WAIT_MS = 10 * 60 * 1000;
    /** accept 轮询间隔（借 SoTimeout 实现，便于检查超时与外部 stop） */
    private static final int ACCEPT_POLL_MS = 500;
    private static final int MAX_HEADER_BYTES = 8 * 1024;

    /** 授权结果回调（在后台线程触发，调用方自行切 UI 线程） */
    public interface Callback {
        /** 收到合法授权码（state 已校验） */
        void onCode(String code);

        /** 用户在浏览器侧拒绝授权（error=access_denied 等） */
        void onError(String error, String errorDescription);

        /** 等待窗口内未收到回调 */
        void onTimeout();
    }

    private final String expectState;
    private final Callback callback;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean stoppedByOwner;
    /** 已产出结果（code/error），accept 循环据此退出 */
    private volatile boolean finished;

    private LoopbackAuthServer(String expectState, Callback callback) {
        this.expectState = expectState;
        this.callback = callback;
    }

    /** 启动并返回实例；端口在启动时随机分配，用 port() 取 */
    public static LoopbackAuthServer start(String expectState, Callback callback) throws IOException {
        var s = new LoopbackAuthServer(expectState, callback);
        s.begin();
        return s;
    }

    private synchronized void begin() throws IOException {
        // 只绑 127.0.0.1：不暴露局域网；端口 0 = 内核随机分配，规避固定端口被占/被探测
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(ACCEPT_POLL_MS);
        acceptThread = new Thread(this::acceptLoop, "XpMiBackup-gdrive-loopback");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LogHelp.i(TAG, "Google 授权回调服务已启动 port=" + port());
    }

    /** 回调端口（begin 成功后可用） */
    public int port() {
        return server != null ? server.getLocalPort() : -1;
    }

    /** 调用方主动停止（页面销毁/流程结束）；不触发 onTimeout */
    public void stop() {
        stoppedByOwner = true;
        closeQuietly();
    }

    private void acceptLoop() {
        var deadline = System.currentTimeMillis() + AUTH_WAIT_MS;
        while (!stoppedByOwner && !finished && System.currentTimeMillis() < deadline) {
            try (var sock = server.accept()) {
                handle(sock);
            } catch (SocketTimeoutException e) {
                // 轮询间隔，继续等
            } catch (IOException e) {
                if (!stoppedByOwner && !finished) {
                    LogHelp.w(TAG, "Google 授权回调 accept 异常: " + e.getMessage());
                }
            }
        }
        closeQuietly();
        if (!stoppedByOwner && !finished) {
            LogHelp.w(TAG, "Google 授权回调等待超时");
            try {
                callback.onTimeout();
            } catch (Exception ignored) {
            }
        }
    }

    /** 处理单条连接：解析请求行，命中 /?code= 或 /?error= 即完成 */
    private void handle(Socket sock) throws IOException {
        sock.setSoTimeout(10_000);
        String line;
        String requestLine = null;
        // 注意：这里刻意不用 try-with-resources 包 sock.getInputStream()——
        // 关闭 BufferedReader 会连带关闭底层 socket，导致后面 writeResponse 抛
        // "Socket is closed"，浏览器只看到连接错误页（用户以为授权失败，其实 code 已收到）。
        // 改用不关闭底层的包装，socket 统一由 acceptLoop 的 try-with-resources 关闭。
        var reader = new BufferedReader(new InputStreamReader(
                new java.io.FilterInputStream(sock.getInputStream()) {
                    @Override
                    public void close() {
                        // 故意空实现：响应还没写，不能关 socket
                    }
                }, StandardCharsets.US_ASCII));
        try {
            var size = 0;
            while ((line = reader.readLine()) != null) {
                size += line.length() + 2;
                if (size > MAX_HEADER_BYTES) break;
                if (requestLine == null) {
                    requestLine = line;
                }
                if (line.isEmpty()) break; // 头部结束
            }
        } catch (IOException e) {
            LogHelp.w(TAG, "Google 授权回调读取请求失败: " + e.getMessage());
            return;
        }
        if (requestLine == null || !requestLine.startsWith("GET ")) {
            return; // 非 GET（预连/探测），忽略
        }
        var target = requestLine.split(" ")[1];
        var path = target;
        var query = "";
        var q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        if (!"/".equals(path)) {
            // favicon 等：静默 404，继续监听
            writeResponse(sock, "404 Not Found", "not found");
            return;
        }
        var code = param(query, "code");
        var state = param(query, "state");
        var error = param(query, "error");
        if (error != null && !error.isEmpty()) {
            var desc = param(query, "error_description");
            LogHelp.w(TAG, "Google 授权被拒绝: " + error);
            writeResponse(sock, "200 OK", "授权未完成：" + error + "，请返回应用重新发起授权。");
            finished = true;
            closeQuietly();
            callback.onError(error, desc == null ? "" : desc);
            return;
        }
        if (code == null || code.isEmpty()) {
            // 无 code 无 error（如直接访问根路径）：继续等待真正的回调
            return;
        }
        if (!expectState.equals(state)) {
            // CSRF：state 不匹配，拒绝并停止（不回调成功路径）
            LogHelp.e(TAG, "Google 授权回调 state 不匹配，已拒绝");
            writeResponse(sock, "400 Bad Request", "state 校验失败，请返回应用重新发起授权。");
            finished = true;
            closeQuietly();
            callback.onError("state_mismatch", "回调 state 与发起时不一致");
            return;
        }
        LogHelp.i(TAG, "Google 授权回调已收到 code len=" + code.length());
        writeResponse(sock, "200 OK", "授权成功，请返回应用继续。");
        finished = true;
        closeQuietly();
        callback.onCode(code);
    }

    /** 极简查询参数解析（application/x-www-form-urlencoded） */
    private static String param(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (var pair : query.split("&")) {
            var eq = pair.indexOf('=');
            var k = eq < 0 ? pair : pair.substring(0, eq);
            if (!key.equals(k)) continue;
            var v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                // 注意：URLDecoder.decode(String, Charset) 是 API 33 才有的重载，minSdk 30 会 NoSuchMethodError
                return URLDecoder.decode(v, "UTF-8");
            } catch (Exception e) {
                return v;
            }
        }
        return null;
    }

    /** 写静态响应页并关闭（调用方持有 socket 的 try-with-resources） */
    private static void writeResponse(Socket sock, String status, String message) {
        try {
            var body = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>小米备份</title></head><body style=\"font-family:sans-serif;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;"
                    + "color:#333;\">" + escape(message) + "</body></html>";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            var out = sock.getOutputStream();
            out.write(("HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            LogHelp.w(TAG, "Google 授权回调响应写出失败: " + e.getMessage());
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private synchronized void closeQuietly() {
        if (server != null && !server.isClosed()) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }
}
