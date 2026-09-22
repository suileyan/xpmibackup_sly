package com.suileyan.comm;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局未捕获异常落盘（闪退排查用）
 *
 * 背景：主线程与 {@link Async} 线程池中的未捕获异常都会走默认 UncaughtExceptionHandler
 * 直接终止进程；而 logcat 经常被按 tag 过滤或提前清空，崩溃堆栈就丢了。
 * 本类把堆栈同时写入应用私有目录与外部私有目录，事后无论 adb 是否可用都能取到。
 *
 * 语义保持不变：记录完成后仍回调原有 handler（进程照常终止），不吞异常、不改变行为。
 */
public final class CrashLog {

    private static final String TAG = "XpMiBackup";
    /** 崩溃日志文件名前缀 */
    private static final String PREFIX = "crash-";
    /** 最多保留的历史崩溃文件数 */
    private static final int KEEP = 10;

    private static volatile boolean sInstalled = false;

    private CrashLog() {
    }

    /** 安装全局未捕获异常处理器（进程内只生效一次，重复调用无副作用） */
    public static void install(Context ctx) {
        if (sInstalled || ctx == null) return;
        sInstalled = true;
        final var app = ctx.getApplicationContext();
        bind(app);
        final var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                record("uncaught:" + (t == null ? "?" : t.getName()), e);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(t, e);
            }
        });
    }

    /**
     * 记录一次异常（可主动调用，用于已被捕获但需要留痕的场景）
     *
     * @param where 异常来源标识，如 "Async-check-update-launch"
     * @param e     原始异常
     */
    public static void record(String where, Throwable e) {
        var sb = new StringBuilder();
        sb.append("time : ").append(now()).append('\n');
        sb.append("where: ").append(where).append('\n');
        sb.append("thread: ").append(Thread.currentThread().getName()).append('\n');
        var sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        sb.append("stack:\n").append(sw).append('\n');
        var text = sb.toString();
        // 同时进 logcat（不再依赖用户过滤条件是否正确）
        LogHelp.e(TAG, "!! CRASH LOGGED [" + where + "] -> " + text);
        save(text);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** 落盘：优先外部私有目录（免 root 即可 adb pull），失败退回内部私有目录 */
    private static void save(String text) {
        // HIGH-07：崩溃文本必须与 LogHelp 走同一套脱敏后再落盘——
        // 异常 message 里常带 token/URL 凭据（Authorization、?token=…），
        // 此前 LogHelp 通道已脱敏、这里却是原文直写，等于开了个后门
        var safeText = LogHelp.sanitizeSensitive(text);
        var file = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        for (var dir : dirs()) {
            if (dir == null) continue;
            try {
                if (!dir.exists() && !dir.mkdirs()) continue;
                var f = new File(dir, PREFIX + file + ".txt");
                try (var out = new FileOutputStream(f)) {
                    out.write(safeText.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                prune(dir);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 候选目录：外部私有（易取）→ 内部私有（恒可写） */
    private static File[] dirs() {
        return new File[]{sExternalDir, sInternalDir};
    }

    private static volatile File sExternalDir;
    private static volatile File sInternalDir;

    /** 绑定应用上下文后即可解析目录（install 时自动调用） */
    private static void bind(Context ctx) {
        sExternalDir = ctx.getExternalFilesDir("crash");
        sInternalDir = new File(ctx.getFilesDir(), "crash");
    }

    /** 仅保留最近 KEEP 份，避免无限堆积 */
    private static void prune(File dir) {
        try {
            var files = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(".txt"));
            if (files == null || files.length <= KEEP) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (var i = KEEP; i < files.length; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
