package com.suileyan.comm;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 与电脑端 mibackpc 的配对握手：
 * POST /pair/requests（携带设备名 + 随机 reqId）→ 电脑端弹窗确认
 * → 轮询 GET /pair/requests/{reqId}/result → approved 时单次下发 WebDAV 凭据。
 * 结果仅下发给发起请求的同一 IP，凭据取走即焚。
 */
public final class PcPair {

    /** 电脑端确认等待上限（用户需要在电脑上点弹窗） */
    private static final long PAIR_TIMEOUT_MS = 60_000;

    public static final class Result {
        public enum Status {APPROVED, DENIED, TIMEOUT, ERROR}

        public Status status = Status.ERROR;
        public String name;  // 电脑主机名
        public String user;
        public String pass;
        public int port;
    }

    private PcPair() {
    }

    /**
     * 发起配对并阻塞等待结果（约 1~60s，必须在后台线程调用）。永不抛异常。
     *
     * @param cancel 置 true 可提前中止（用户关闭等待框）
     */
    public static Result pair(String host, int port, String deviceName, AtomicBoolean cancel) {
        var result = new Result();
        var reqId = randomHex(16);
        try {
            var body = new JSONObject()
                    .put("device", deviceName)
                    .put("reqId", reqId)
                    .toString();
            var conn = open(host, port, "/pair/requests", "POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.getBytes(StandardCharsets.UTF_8).length);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (var out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code < 200 || code >= 300) {
                LogHelp.w("XpMiBackup", "pair request rejected: HTTP " + code);
                return result;
            }
        } catch (Exception e) {
            LogHelp.w("XpMiBackup", "pair request failed", e);
            return result;
        }

        long deadline = System.currentTimeMillis() + PAIR_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !cancel.get()) {
            try {
                var conn = open(host, port, "/pair/requests/" + reqId + "/result", "GET");
                var resp = new JSONObject(readAll(conn));
                conn.disconnect();
                switch (resp.optString("status")) {
                    case "pending":
                        Thread.sleep(1000);
                        continue;
                    case "approved":
                        result.status = Result.Status.APPROVED;
                        result.name = resp.optString("name", "PC");
                        result.user = resp.optString("user", "");
                        result.pass = resp.optString("pass", "");
                        result.port = resp.optInt("port", port);
                        return result;
                    case "denied":
                        result.status = Result.Status.DENIED;
                        return result;
                    default: // expired / forbidden / 异常应答
                        return result;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            } catch (Exception e) {
                LogHelp.w("XpMiBackup", "pair poll failed", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return result;
                }
            }
        }
        return result;
    }

    private static HttpURLConnection open(String host, int port, String path, String method)
            throws Exception {
        var conn = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10_000);
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        try (var in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (in == null) return "";
            // InputStream.readAllBytes() 需 API 33，Android 11/12 会 NoSuchMethodError（配对待确认流程不能崩）
            var buf = new java.io.ByteArrayOutputStream();
            var chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String randomHex(int bytes) {
        var b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        var sb = new StringBuilder(bytes * 2);
        for (var x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
