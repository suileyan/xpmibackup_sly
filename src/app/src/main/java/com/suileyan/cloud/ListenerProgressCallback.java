package com.suileyan.cloud;

import com.suileyan.comm.LogHelp;
import com.suileyan.comm.ProgressCallbackHelp;

/**
 * 把 AIDL 传入的 Object listener（小米 IFileOperationProgressListener）反射适配为 ProgressCallback
 * 统一封装 Y0(启动)/D0(进度)/l0(完成) 及按签名兜底查找，消除各实现重复的 invokeProgress 代码
 *
 * 进度链路排查要点（备份项长期停在 30% 的真因就在这里）：
 * 宿主的 D0(taskId, current, total) 会先用 taskId 反查任务项（NASBackupDataCenter），
 * 查不到就整段丢弃——UI 上表现为该项进度不动。所以本类必须做到：
 * 1) 回调失败绝不静默（原实现 catch(Exception ignored) 把 IllegalAccessException 一并吞掉）；
 * 2) 包的可见性要兜住：宿主监听器是匿名内部类（包内可见），跨包 getMethod+invoke
 *    会抛 IllegalAccessException，必须 setAccessible(true)；
 * 3) 按 taskId 节流留痕，便于对照宿主侧日志定位。
 */
public class ListenerProgressCallback implements ProgressCallback {

    private static final String TAG = "XpMiBackup";

    private final Object listener;

    public ListenerProgressCallback(Object listener) {
        this.listener = listener;
    }

    @Override
    public void onStart(String taskId) {
        if (listener == null) return;
        invoke(listener, "Y0", new Class[]{String.class}, ProgressCallbackHelp.safeString(taskId));
    }

    @Override
    public void onProgress(String taskId, long current, long total) {
        recordSpeed(taskId, current);
        if (listener == null) return;
        trace(taskId, current, total);
        invoke(listener, "D0", new Class[]{String.class, long.class, long.class},
                ProgressCallbackHelp.safeString(taskId), normalize(taskId, current, total), total);
    }

    @Override
    public void onFinish(String taskId, int code, String msg) {
        if (listener == null) return;
        LogHelp.i(TAG, "progress finish task=" + taskId + " code=" + code + " item=" + itemOf(taskId));
        invoke(listener, "l0", new Class[]{String.class, int.class, String.class},
                ProgressCallbackHelp.safeString(taskId), code, ProgressCallbackHelp.safeString(msg));
    }

    // ---------- 实时速率统计（进度页角标用） ----------

    /** taskId -> 上次累计字节数（只用来求增量，所以不参与任何判定） */
    private static final java.util.Map<String, Long> LAST_BYTES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LAST_BYTES_MAX = 1024;

    /**
     * 把本次进度增量喂给速率统计。
     *
     * 本回调只在上传链路创建（{@code CloudFileHelp.uploadWithProgress}），所以这里统计到的
     * 就是上传速率；current 是累计值，按 taskId 求差得到增量（分片上传时即每片大小）。
     */
    private static void recordSpeed(String taskId, long current) {
        try {
            var key = taskId == null ? "" : taskId;
            if (LAST_BYTES.size() > LAST_BYTES_MAX) {
                // 条目只增不减（taskId 是每次备份新生成的 UUID），满了重置一次；
                // 重置后直接返回，避免"上次值丢失"把累计字节当成本次增量
                LAST_BYTES.clear();
                LAST_BYTES.put(key, current);
                return;
            }
            var prev = LAST_BYTES.put(key, current);
            // current 变小 = 底层换了新的一轮计数（provider 自己切片时会从 0 重新报），
            // 此时把 current 本身当增量，否则新一轮的字节会被整段漏记
            var delta = prev == null || current < prev ? current : current - prev;
            if (delta > 0L) {
                com.suileyan.comm.TransferSpeed.add(delta);
            }
        } catch (Throwable ignored) {
            // 速率统计绝不能影响上传主流程
        }
    }

    // ---------- 进度归一化（宿主的 totalSize 分母口径不可靠，见 registerProgressScale） ----------

    /** taskId -> [宿主分母 totalSize, 是否宿主特殊归一化项] */
    private static final java.util.Map<String, long[]> SCALE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SCALE_MAX = 128;
    private static final java.util.Set<String> SCALE_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 登记该项在宿主侧用来算进度的分母（由 AIDLHook 在每次上传开始前解析宿主任务项得到）。
     *
     * 宿主 NASTransferService.H1 算 `30 + 70 × (transferSize / item.totalSize)`，其中 totalSize 是
     * 宿主自己记录的「该项尺寸」，常与本次实际要传的字节数差几个数量级（短信设置 16.5MB vs 6.2KB、
     * 相册 646MB vs 7.7KB）→ 比值长期≈0，进度贴在 30% 地板不动，传完由宿主直接置 100%。
     * 按分母折算上报后，该项进度即随本次上传真实地从 30% 走到 100%（一项传完即 100%）。
     *
     * @param special 宿主自己用 (current/bakFileSize)×totalSize 归一化的项（mms/contacts feature 1|2）：
     *                必须保持原始字节，否则整数除法恒为 0
     */
    public static void registerProgressScale(String taskId, long denominator, boolean special) {
        if (taskId == null || taskId.isEmpty() || denominator <= 0) return;
        if (SCALE.size() >= SCALE_MAX) SCALE.clear();
        SCALE.put(taskId, new long[]{denominator, special ? 1L : 0L});
    }

    /** 按宿主分母折算本次上报的字节数（未登记/特殊项/参数异常时原样返回） */
    private static long normalize(String taskId, long current, long total) {
        try {
            var s = SCALE.get(taskId == null ? "" : taskId);
            if (s == null || s[1] != 0 || total <= 0 || current <= 0) return current;
            var scaled = (long) ((double) s[0] * (double) current / (double) total);
            if (scaled > s[0]) scaled = s[0];
            if (scaled < 0) scaled = 0;
            if (SCALE_LOGGED.add(taskId)) {
                LogHelp.i(TAG, "progress normalized: task=" + taskId + " hostDenom=" + s[0]
                        + " -> report " + scaled + " (raw " + current + "/" + total + ")");
            }
            return scaled;
        } catch (Throwable e) {
            return current;
        }
    }

    // ---------- 进度留痕（节流：首条 + 每 3s + 末条） ----------

    private static final long TRACE_INTERVAL_MS = 3000;
    private static final java.util.Map<String, long[]> TRACE_AT = new java.util.concurrent.ConcurrentHashMap<>();

    private void trace(String taskId, long current, long total) {
        try {
            var key = taskId == null ? "" : taskId;
            var slot = TRACE_AT.computeIfAbsent(key, k -> new long[]{0L, 0L, 0L}); // [lastLogAt, count, lastLogged]
            boolean last = total > 0 && current >= total;
            synchronized (slot) {
                var now = android.os.SystemClock.elapsedRealtime();
                var first = slot[1] == 0;
                if (!first && !last && now - slot[0] < TRACE_INTERVAL_MS) {
                    slot[1]++;
                    return;
                }
                slot[0] = now;
                slot[2] = current;
            }
            LogHelp.i(TAG, "progress task=" + key + " " + current + "/" + total
                    + (last ? " (final)" : "") + " item=" + itemOf(key));
        } catch (Throwable ignored) {
            // 诊断日志绝不能影响上传主流程
        }
    }

    /** 诊断：taskId 在宿主里的任务项关键字段（查不到就写明 unresolved） */
    private String itemOf(String taskId) {
        try {
            return com.suileyan.xpmibackup.hook.BackupHook.describeNasTaskItem(
                    listener.getClass().getClassLoader(), taskId);
        } catch (Throwable e) {
            return "describe failed: " + e.getClass().getSimpleName();
        }
    }

    /**
     * 回调方法解析缓存（NEW：反射在热路径）。
     * onProgress 每 200ms/每分片触发一次，每次都 getMethod（失败还会遍历全部方法）纯属浪费；
     * 按「类 + 方法名 + 参数类型」缓存，未命中同样缓存，避免反复全量扫描。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Method>> METHOD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int METHOD_CACHE_MAX = 64;
    /** 解析/调用失败只告警一次（结果已缓存，避免热路径刷屏） */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 优先按混淆名解析，失败后按参数签名兜底查找回调方法（结果缓存） */
    private static void invoke(Object target, String method, Class<?>[] types, Object... args) {
        var key = target.getClass().getName() + "#" + method + java.util.Arrays.toString(types);
        var cached = METHOD_CACHE.get(key);
        java.lang.reflect.Method resolved;
        if (cached != null) {
            resolved = cached.orElse(null);
        } else {
            resolved = resolve(target, method, types);
            if (METHOD_CACHE.size() >= METHOD_CACHE_MAX) {
                METHOD_CACHE.clear();
            }
            METHOD_CACHE.put(key, java.util.Optional.ofNullable(resolved));
        }
        if (resolved == null) {
            if (WARNED.add(key)) {
                LogHelp.w(TAG, "progress callback not found: " + method + " on "
                        + target.getClass().getName() + " " + java.util.Arrays.toString(types));
            }
            return;
        }
        try {
            // 宿主监听器是匿名内部类（包内可见）：跨包 invoke 其 public 方法会 IllegalAccessException，
            // 必须显式放开可见性（原实现缺这一步，异常又被吞掉 → 进度回调静默失效）
            resolved.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            resolved.invoke(target, args);
        } catch (Throwable e) {
            if (WARNED.add(key + "@invoke")) {
                LogHelp.w(TAG, "progress callback failed: " + method + " on " + target.getClass().getName()
                        + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        }
    }

    private static java.lang.reflect.Method resolve(Object target, String method, Class<?>[] types) {
        var cls = target.getClass();
        // 1) 公开方法（含继承链）
        try {
            return cls.getMethod(method, types);
        } catch (Throwable ignored) {
        }
        // 2) 本类及父类的「非 public」同名方法（匿名内部类实现接口时可能是包内可见）
        for (var c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(method, types);
            } catch (Throwable ignored) {
            }
        }
        // 3) 按参数签名兜底（public + declared）
        for (var c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != types.length || m.getReturnType() != void.class) continue;
                var matched = true;
                for (var i = 0; i < types.length; i++) {
                    if (m.getParameterTypes()[i] != types[i]) {
                        matched = false;
                        break;
                    }
                }
                if (matched) return m;
            }
        }
        return null;
    }
}
