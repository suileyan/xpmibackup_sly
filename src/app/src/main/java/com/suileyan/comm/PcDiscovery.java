package com.suileyan.comm;

import android.os.SystemClock;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 局域网 / USB 自动发现电脑端 mibackpc。
 *
 * 三路并发探测，任一路命中即报告（去重后合并）：
 *  1. 局域网：UDP 广播探测码到 8322，mibackpc 单播回 JSON（手机只发广播 + 收单播，
 *     不需要组播锁等特殊权限）
 *  2. USB：adb reverse 建立后手机访问 127.0.0.1:8321/miback/info
 *  3. 上次连接过的地址：覆盖路由器重启后 IP 漂移、UDP 被路由器/AP 隔离吞掉的场景
 */
public final class PcDiscovery {

    /** 电脑端发现应答端口（与 mibackpc serveDiscovery 固定对齐） */
    public static final int DISCOVER_PORT = 8322;
    /** USB 通道默认端口（mibackpc 默认 8321，adb reverse 双端同端口） */
    public static final int USB_PORT = 8321;

    private static final String MAGIC = "MIBACKPC_DISCOVER_V1";

    /** 一台发现的电脑 */
    public static final class PcInfo {
        public final String host;
        public final int port;
        public final String name;
        public final boolean usb; // true = USB 通道（127.0.0.1），false = 局域网

        public PcInfo(String host, int port, String name, boolean usb) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.usb = usb;
        }

        /** WebDAV base（与 BackupFragment.normalizePcUrl 产物一致，PC 端固定挂载 /dav/） */
        public String davUrl() {
            return "http://" + host + ":" + port + "/dav/";
        }

        public boolean sameAs(PcInfo o) {
            return o != null && host.equals(o.host) && port == o.port;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PcInfo && sameAs((PcInfo) o);
        }

        @Override
        public int hashCode() {
            return host.hashCode() * 31 + port;
        }
    }

    private PcDiscovery() {
    }

    /**
     * 同步扫描（约 1.5~2.5s，必须在后台线程调用）。永不抛异常，失败返回空列表。
     */
    public static List<PcInfo> scan() {
        List<PcInfo> out = new ArrayList<>();
        long deadline = SystemClock.elapsedRealtime() + 2500;
        Thread udp = new Thread(() -> udpScan(out, 1500), "pc-scan-udp");
        udp.setDaemon(true);
        udp.start();

        // USB：仅当 adb reverse 已建立时 127.0.0.1 才有应答，短超时快速失败
        probeHttp("127.0.0.1", USB_PORT, true, out, deadline);
        // 上次地址：恢复 config.ini 中记录的 host:port（兼容旧手动配置 pc_backup_addr；
        // 历史值可能是裸 IP:端口，也可能带 scheme / 路径，统一剥掉再解析）
        for (String key : new String[]{"pc_paired_addr", "pc_backup_addr"}) {
            var addr = ConfigHelp.getString(key, "");
            if (addr.isEmpty()) continue;
            addr = addr.replaceFirst("^https?://", "");
            int slash = addr.indexOf('/');
            if (slash >= 0) addr = addr.substring(0, slash);
            if (addr.isEmpty()) continue;
            var host = addr;
            var port = 0;
            int colon = addr.lastIndexOf(':');
            if (colon > 0) {
                host = addr.substring(0, colon);
                try {
                    port = Integer.parseInt(addr.substring(colon + 1));
                } catch (Exception ignored) {
                }
            }
            if (port <= 0 || port > 65535) port = USB_PORT;
            probeHttp(host, port, false, out, deadline);
        }
        // PC 局域网地址候选（USB 通道发现时由 /miback/info 的 lan/lans + tcp 自动存入）：
        // UDP 发现通道失效时靠这条兜底——只要能 HTTP 到达电脑的局域网 IP 就能连上，
        // 端口以电脑下发的 pc_lan_port 为准（不一定等于默认 8321）
        int lanPort = ConfigHelp.getInt("pc_lan_port", USB_PORT);
        if (lanPort <= 0 || lanPort > 65535) lanPort = USB_PORT;
        for (String lan : lanCandidates()) {
            probeHttp(lan, lanPort, false, out, deadline);
        }

        try {
            udp.join(Math.max(500, deadline - SystemClock.elapsedRealtime()));
        } catch (InterruptedException ignored) {
        }
        return out;
    }

    /** UDP 广播探测：发探测码到全局广播 + 各网卡定向广播 + 已知地址单播，收 1.5s 单播应答 */
    private static void udpScan(List<PcInfo> out, long windowMs) {
        int boundPort = -1;
        int sendFail = 0;
        String firstSendErr = null;
        try (DatagramSocket s = new DatagramSocket()) {
            boundPort = s.getLocalPort();
            s.setBroadcast(true);
            s.setSoTimeout(200);
            byte[] magic = MAGIC.getBytes(StandardCharsets.US_ASCII);
            var targets = new ArrayList<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (var ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (var a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        var b = a.getAddress();
                        targets.add(InetAddress.getByAddress(new byte[]{b[0], b[1], b[2], (byte) 0xFF}));
                    }
                }
            }
            // 单播探测已知地址 + 子网常见主机：绕过路由器 AP 隔离（广播被吞时单播仍可达）
            var seen = new java.util.HashSet<>(targets); // 避免重复发包
            // 1) 已知 PC 地址 + 电脑下发的全部局域网候选（物理网卡优先，虚拟网卡在最后）
            var known = new ArrayList<String>();
            for (String key : new String[]{"pc_paired_addr", "pc_backup_addr"}) {
                known.add(ConfigHelp.getString(key, ""));
            }
            known.addAll(lanCandidates());
            for (String raw : known) {
                if (raw.isEmpty()) continue;
                var addr = raw.replaceFirst("^https?://", "");
                int slash = addr.indexOf('/');
                if (slash >= 0) addr = addr.substring(0, slash);
                int colon = addr.lastIndexOf(':');
                var host = colon > 0 ? addr.substring(0, colon) : addr;
                if (host.isEmpty()) continue;
                try {
                    var ip = InetAddress.getByName(host);
                    if (!ip.isLoopbackAddress() && seen.add(ip)) targets.add(ip);
                } catch (Exception ignored) {
                }
            }
            // 2) 手机所在子网的网关（.1）和常见 PC 段（.2~.10, .100~.110）：覆盖首次无历史的场景
            for (var ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (var a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    var b = a.getAddress();
                    for (int last : new int[]{1, 2, 3, 4, 5, 100, 101, 102}) {
                        try {
                            var ip = InetAddress.getByAddress(new byte[]{b[0], b[1], b[2], (byte) last});
                            if (seen.add(ip)) targets.add(ip);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            for (var t : targets) {
                try {
                    s.send(new DatagramPacket(magic, magic.length, t, DISCOVER_PORT));
                } catch (Exception e) {
                    // 原实现静默吞掉：明文策略/本地网络保护拦截时只表现为"扫不到"，
                    // 排查成本极高（本次故障即由静默 send 失败 + 静默 http 失败共同掩盖）
                    sendFail++;
                    if (firstSendErr == null) {
                        firstSendErr = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
            }
            long deadline = SystemClock.elapsedRealtime() + windowMs;
            var buf = new byte[512];
            int heard = 0;
            while (SystemClock.elapsedRealtime() < deadline) {
                var p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                var info = parseInfo(new String(buf, 0, p.getLength(), StandardCharsets.UTF_8),
                        p.getAddress().getHostAddress(), false);
                if (info != null) {
                    addIfNew(out, info);
                    heard++;
                }
            }
            if (out.isEmpty()) {
                LogHelp.i("XpMiBackup", "pc udp scan: no reply (bound port " + boundPort
                        + ", tried " + targets.size() + " targets (broadcast+unicast), heard " + heard
                        + " datagrams)"
                        + (sendFail > 0 ? ", sendFailures=" + sendFail + " (first: " + firstSendErr + ")" : ""));
            }
        } catch (Exception e) {
            LogHelp.w("XpMiBackup", "pc udp scan failed (bound port " + boundPort + ")", e);
        }
    }

    /** HTTP 探测 /miback/info（USB 与上次地址通道共用） */
    private static void probeHttp(String host, int port, boolean usb, List<PcInfo> out, long deadline) {
        if (SystemClock.elapsedRealtime() > deadline) return;
        try {
            var conn = (java.net.HttpURLConnection) new URL(
                    "http://" + host + ":" + port + "/miback/info").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setInstanceFollowRedirects(false);
            var info = parseInfo(readAll(conn), host, usb);
            conn.disconnect();
            if (info != null) addIfNew(out, info);
        } catch (Exception e) {
            // 原实现静默吞掉：明文策略（Cleartext HTTP traffic to 192.168.x.x not permitted）
            // 与本地网络保护拦截都只表现为"扫不到"，必须留痕
            LogHelp.i("XpMiBackup", "pc http probe " + host + ":" + port + " failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String readAll(java.net.HttpURLConnection conn) {
        try (var in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (in == null) return "";
            // InputStream.readAllBytes() 需 API 33，Android 11/12 会 NoSuchMethodError
            // （Error 不被 catch(Exception) 捕获 → 直接崩进程；本方法在发现链路上必须绝对安全）
            var buf = new java.io.ByteArrayOutputStream();
            var chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 解析发现应答：{"service":"mibackpc","name":…,"tcp":…,"lan":…,"lans":[…]} */
    private static PcInfo parseInfo(String body, String fallbackHost, boolean usb) {
        try {
            var o = new JSONObject(body);
            if (!"mibackpc".equals(o.optString("service"))) return null;
            var host = fallbackHost;
            var port = o.optInt("tcp", usb ? USB_PORT : 0);
            if (port <= 0) return null;
            // PC 下发的局域网地址（lans 全量候选，兼容旧版只有 lan 单值）：
            // 存入 config 供下次 UDP 单播探测与 HTTP 兜底探测
            if (usb) {
                var lans = new ArrayList<String>();
                var arr = o.optJSONArray("lans");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        var s = arr.optString(i, "").trim();
                        if (!s.isEmpty() && !lans.contains(s)) lans.add(s);
                    }
                }
                var single = o.optString("lan", "").trim();
                if (lans.isEmpty() && !single.isEmpty()) lans.add(single);
                if (!lans.isEmpty()) saveLanAddrs(lans, port);
            }
            return new PcInfo(host, port, o.optString("name", "PC"), usb);
        } catch (Exception e) {
            return null;
        }
    }

    /** 落盘电脑下发的局域网候选地址与端口（有变化才写，避免每轮扫描都刷配置） */
    private static void saveLanAddrs(List<String> lans, int port) {
        try {
            var cfg = ConfigHelp.load();
            var joined = String.join(",", lans);
            var changed = false;
            if (!joined.equals(cfg.optString("pc_lan_addrs", ""))) {
                cfg.put("pc_lan_addrs", joined);
                changed = true;
            }
            if (!lans.get(0).equals(cfg.optString("pc_lan_addr", ""))) {
                cfg.put("pc_lan_addr", lans.get(0));
                changed = true;
            }
            if (cfg.optInt("pc_lan_port", 0) != port) {
                cfg.put("pc_lan_port", port);
                changed = true;
            }
            if (changed) ConfigHelp.save(cfg);
        } catch (Exception ignored) {
        }
    }

    /** PC 局域网地址候选（pc_lan_addrs 逗号分隔优先，兼容旧版 pc_lan_addr 单值） */
    private static List<String> lanCandidates() {
        var out = new ArrayList<String>();
        for (String key : new String[]{"pc_lan_addrs", "pc_lan_addr"}) {
            var raw = ConfigHelp.getString(key, "");
            if (raw.isEmpty()) continue;
            for (String part : raw.split(",")) {
                var s = part.trim().replaceFirst("^https?://", "");
                int slash = s.indexOf('/');
                if (slash >= 0) s = s.substring(0, slash);
                int colon = s.lastIndexOf(':');
                if (colon > 0) s = s.substring(0, colon); // 只留主机，端口统一取 pc_lan_port
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    private static void addIfNew(List<PcInfo> out, PcInfo info) {
        if (!out.contains(info)) out.add(info);
    }
}
