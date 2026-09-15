package com.suileyan.xpmibackup.hook;

import com.suileyan.comm.LogHelp;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 宿主进程内放行「私网明文 HTTP」（HIGH-26）
 *
 * 背景：「备份至 PC」的局域网通道必然是明文 http（`http://192.168.x.x:8321/dav/`），
 * 自建 NAS/WebDAV 同理。而**小米备份宿主进程（com.miui.backup）自己没放开明文策略**，
 * 宿主进程里的 OkHttp 在建立连接前会查 `NetworkSecurityPolicy`，命中即抛：
 *
 *   java.net.UnknownServiceException:
 *     CLEARTEXT communication to 192.168.31.83 not permitted by network security policy
 *   ↑ com.suileyan.xpmibackup.hook.AIDLHook.sendMockConnectResult(AIDLHook.java:420)
 *     → CloudFileHelp.testConnection → WebdavProvider.testConnection
 *     → WebdavFileHelp.propfind → OkHttp RealConnection.connect
 *
 * 用户可见现象是小米备份弹「连接异常」提示（智能存储/备份至 PC 连不上）。
 *
 * 为什么不改清单：模块清单上的 `android:usesCleartextTraffic` **只作用于模块自身进程**
 * （配对与凭据校验跑在模块进程，所以那部分本来就通）；宿主进程用的是小米备份 APK 的
 * network security config，模块改不了，只能在宿主进程内 Hook 判定函数。
 *
 * 放行范围**仅限私网与回环**：10/8、172.16/12、192.168/16、127/8、169.254/16、
 * localhost、::1、fe80::/10、fc00::/7，以及单标签主机名（如 "nas"）与 `.local`（mDNS）。
 * 公网主机仍沿用宿主原有策略，不扩大攻击面 —— 与 `CustomHttpFileHelp` 的 SSRF 防护
 * （HIGH-05）使用同一套私网口径。
 *
 * 刻意不做 DNS 解析：本 Hook 在每次建连判定时都会执行，解析会阻塞且可能触发网络请求；
 * 因此用「字面量 + 主机名形态」判定，解析后落在私网的公网域名（如 nas.example.com）
 * 不在放行范围 —— 需要时直接用 IP 访问即可。
 */
public final class LocalNetworkHook {

    private static final String TAG = "XpMiBackup";
    private static final String TAG_HOOK = "LocalNetworkHook";
    private static final String POLICY_CLASS = "android.security.NetworkSecurityPolicy";
    private static final String METHOD = "isCleartextTrafficPermitted";

    private LocalNetworkHook() {
    }

    /**
     * 在宿主进程安装明文放行 Hook。任何异常只记日志，绝不拖垮其它 Hook 链。
     *
     * @return true 表示至少装上一个重载
     */
    public static boolean hook(XC_LoadPackage.LoadPackageParam lpparam) {
        var cl = lpparam.classLoader;
        // 安装前基线：宿主自身策略对私网/公网的判定（用于证明 Hook 到底改变了什么）
        var before = queryPrivate();
        // 用 |= 而非 ||：两个重载都尝试，避免系统/OkHttp 分支走的是另一个签名时漏放行
        var okString = install(POLICY_CLASS, cl, METHOD, String.class);
        var okNoArg = install(POLICY_CLASS, cl, METHOD);
        var ok = okString || okNoArg;
        var after = queryPrivate();
        LogHelp.i(TAG, TAG_HOOK + ": 私网明文放行 " + (ok ? "已安装" : "安装失败（版本结构漂移？）")
                + " stringOverload=" + okString + " noArgOverload=" + okNoArg
                + "；宿主对私网判定 安装前=" + before + " → 安装后=" + after
                + "（false→true 即本次放行生效）");
        return ok;
    }

    /**
     * 进程内查询宿主策略对样例主机的明文判定。纯查询、不发起任何网络请求。
     * 安装前调用得到基线，安装后调用得到结果——两者对照即可确认 Hook 是否真的生效；
     * 否则一旦策略变化/Hook 失效，用户只看到小米备份弹「连接异常」，日志里只有一条
     * CLEARTEXT 报错，无法区分「Hook 没装」与「装了但被覆盖」。
     */
    private static String queryPrivate() {
        try {
            var policyClass = Class.forName(POLICY_CLASS);
            var instance = XposedHelpers.callStaticMethod(policyClass, "getInstance");
            var privateOk = XposedHelpers.callMethod(instance, METHOD, "192.168.31.83");
            var publicOk = XposedHelpers.callMethod(instance, METHOD, "example.com");
            return "私网(" + privateOk + ")/公网(" + publicOk + ")";
        } catch (Throwable e) {
            // 隐藏 API 反射受限时不影响 Hook 本身，仅记录
            return "查询不可用(" + e.getClass().getSimpleName() + ")";
        }
    }

    /** 单签名安装：找不到方法时返回 false（不抛） */
    private static boolean install(String className, ClassLoader cl, String method, Class<?>... paramTypes) {
        try {
            if (paramTypes == null || paramTypes.length == 0) {
                XposedHelpers.findAndHookMethod(className, cl, method, privateOnlyHook());
            } else {
                XposedHelpers.findAndHookMethod(className, cl, method, paramTypes[0], privateOnlyHook());
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 判定回调：仅当目标主机属于私网/回环时强制放行，其余不干预（保持宿主原策略）。
     * 每次新建实例：同一 XC_MethodHook 实例复用到多个方法上行为未定义。
     */
    private static XC_MethodHook privateOnlyHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    var args = param.args;
                    if (args == null || args.length == 0 || args[0] == null) {
                        return; // 无参重载：宿主整体策略，不干预
                    }
                    var host = String.valueOf(args[0]).trim().toLowerCase(java.util.Locale.ROOT);
                    if (!isPrivateHost(host)) {
                        return;
                    }
                    if (Boolean.TRUE.equals(param.getResult())) {
                        return; // 宿主本来就放行，无需干预
                    }
                    param.setResult(Boolean.TRUE);
                } catch (Throwable ignored) {
                    // 判定失败时保持宿主原行为（fail-safe：不额外放行）
                }
            }
        };
    }

    /** 私网/回环主机判定（纯字面量，不做 DNS） */
    static boolean isPrivateHost(String h) {
        if (h == null || h.isEmpty()) {
            return false;
        }
        // IPv6 形式可能带方括号
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals("localhost") || h.equals("::1")) {
            return true;
        }
        // IPv6 链路本地 / ULA
        if (h.startsWith("fe80:")) {
            return true;
        }
        if (h.matches("^f[cd][0-9a-f]{2}:.*")) {
            return true; // fc00::/7
        }
        var v4 = h.split("\\.");
        if (v4.length == 4) {
            int a;
            int b;
            try {
                a = Integer.parseInt(v4[0]);
                b = Integer.parseInt(v4[1]);
                for (var seg : v4) {
                    if (Integer.parseInt(seg) < 0 || Integer.parseInt(seg) > 255) {
                        return false;
                    }
                }
            } catch (Exception e) {
                return false;
            }
            if (a == 127) {
                return true; // 回环
            }
            if (a == 10) {
                return true; // 10/8
            }
            if (a == 192 && b == 168) {
                return true; // 192.168/16
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true; // 172.16/12
            }
            return a == 169 && b == 254; // 链路本地
        }
        // 单标签主机名（"nas"/"router"）与 mDNS（.local）几乎只可能是内网设备
        if (!h.contains(".")) {
            return true;
        }
        return h.endsWith(".local");
    }
}
