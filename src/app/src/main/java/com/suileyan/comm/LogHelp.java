package com.suileyan.comm;

import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 统一处理应用日志输出，可按配置追加写入本地日志文件
 * 内置错误日志落盘：进程内环形缓冲近期日志，出现 error 级日志时
 * 自动把"出错前流程 + 错误详情"写入 /sdcard/MIUI/backup/logs/年月日_err.log
 */
public class LogHelp {
    private static final String LOG_KEY = "log_enabled";
    private static final String CONFIG_PATH = ConfigHelp.BACKUP_ROOT + "/config.ini";
    private static final String LOG_DIR = ConfigHelp.BACKUP_ROOT + "/logs";
    /** 错误日志文件目录（与每日日志同目录） */
    private static final String ERR_LOG_DIR = LOG_DIR;
    /** 环形缓冲容量：出错时携带的流程日志条数 */
    private static final int RING_BUFFER_SIZE = 300;
    /** 错误日志文件超过该大小（字节）后清空重写，避免无限膨胀 */
    private static final long ERR_FILE_MAX_BYTES = 1024 * 1024;

    /** 进程内近期日志环形缓冲（含时间戳/级别/tag/message） */
    private static final ArrayDeque<String> RING = new ArrayDeque<>(RING_BUFFER_SIZE + 1);

    /** 文件日志开关缓存（按 config.ini mtime 失效），避免每条日志都重读配置文件 */
    private static volatile boolean sLogEnabledCached = false;
    private static volatile long sLogEnabledTs = Long.MIN_VALUE;

    /** ThreadLocal 时间格式器：SimpleDateFormat 非线程安全，避免每次日志新建 */
    private static final ThreadLocal<SimpleDateFormat> TS_FORMAT = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT));
    private static final ThreadLocal<SimpleDateFormat> DAY_FORMAT = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyyMMdd", Locale.ROOT));
    private static final ThreadLocal<SimpleDateFormat> FULL_FORMAT = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT));

    /**
     * 输出详细日志，开启文件日志时同步追加到每日日志文件
     */
    public static void v(String tag, String message) {
        log(Log.VERBOSE, tag, message, null);
    }

    /**
     * 输出详细日志和异常堆栈，开启文件日志时同步追加到每日日志文件
     */
    public static void v(String tag, String message, Throwable throwable) {
        log(Log.VERBOSE, tag, message, throwable);
    }

    /**
     * 输出调试日志，开启文件日志时同步追加到每日日志文件
     */
    public static void d(String tag, String message) {
        log(Log.DEBUG, tag, message, null);
    }

    /**
     * 输出调试日志和异常堆栈，开启文件日志时同步追加到每日日志文件
     */
    public static void d(String tag, String message, Throwable throwable) {
        log(Log.DEBUG, tag, message, throwable);
    }

    /**
     * 输出信息日志，开启文件日志时同步追加到每日日志文件
     */
    public static void i(String tag, String message) {
        log(Log.INFO, tag, message, null);
    }

    /**
     * 输出信息日志和异常堆栈，开启文件日志时同步追加到每日日志文件
     */
    public static void i(String tag, String message, Throwable throwable) {
        log(Log.INFO, tag, message, throwable);
    }

    /**
     * 输出警告日志，开启文件日志时同步追加到每日日志文件
     */
    public static void w(String tag, String message) {
        log(Log.WARN, tag, message, null);
    }

    /**
     * 输出警告日志和异常堆栈，开启文件日志时同步追加到每日日志文件
     */
    public static void w(String tag, String message, Throwable throwable) {
        log(Log.WARN, tag, message, throwable);
    }

    /**
     * 输出错误日志，开启文件日志时同步追加到每日日志文件
     */
    public static void e(String tag, String message) {
        log(Log.ERROR, tag, message, null);
    }

    /**
     * 输出带异常堆栈的错误日志，开启文件日志时同步追加到每日日志文件
     */
    public static void e(String tag, String message, Throwable throwable) {
        log(Log.ERROR, tag, message, throwable);
    }

    /**
     * 按日志类型输出到系统日志，并在开关开启时写入本地文件
     * error 级日志同时触发错误日志落盘（缓冲流程 + 错误详情）
     */
    public static void log(int priority, String tag, String message, Throwable throwable) {
        if (throwable == null) {
            Log.println(priority, tag, message);
        } else {
            Log.println(priority, tag, message + "\n" + Log.getStackTraceString(throwable));
        }
        recordRing(priority, tag, message, throwable);
        writeFileLog(priority, tag, message, throwable);
        if (priority >= Log.ERROR) {
            flushErrorLog(priority, tag, message, throwable);
        }
    }

    /** 记录一条近期日志到环形缓冲 */
    private static void recordRing(int priority, String tag, String message, Throwable throwable) {
        var line = new StringBuilder();
        line.append(TS_FORMAT.get().format(new Date()))
                .append(' ').append(priorityToLetter(priority)).append('/').append(tag)
                .append(": ").append(message == null ? "" : message);
        if (throwable != null) {
            // 缓冲只保留异常首行，完整堆栈随错误详情落盘
            var first = throwable.toString();
            var idx = first.indexOf('\n');
            line.append(" | ").append(idx < 0 ? first : first.substring(0, idx));
        }
        synchronized (RING) {
            RING.addLast(line.toString());
            while (RING.size() > RING_BUFFER_SIZE) {
                RING.removeFirst();
            }
        }
    }

    /**
     * 错误日志落盘：将"出错前流程（环形缓冲）+ 本次错误详情"写入 年月日_err.log
     * 双进程（settings/backup）可能并发写同一文件，进程内 synchronized，跨进程竞态可接受
     */
    private static void flushErrorLog(int priority, String tag, String message, Throwable throwable) {
        try {
            var dir = new File(ERR_LOG_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            var date = DAY_FORMAT.get().format(new Date());
            var file = new File(dir, date + "_err.log");
            // 文件过大先清空，避免无限膨胀
            if (file.exists() && file.length() > ERR_FILE_MAX_BYTES) {
                Files.write(file.toPath(), new byte[0]);
            }
            var sb = new StringBuilder();
            var now = FULL_FORMAT.get().format(new Date());
            sb.append("============ ").append(now).append(" ERROR ============\n");
            sb.append("---- 出错前流程 ----\n");
            synchronized (RING) {
                for (var line : RING) {
                    sb.append(line).append('\n');
                }
            }
            sb.append("---- 错误详情 ----\n");
            sb.append(priorityToLetter(priority)).append('/').append(tag).append(": ").append(message).append('\n');
            if (throwable != null) {
                sb.append(Log.getStackTraceString(throwable)).append('\n');
            }
            sb.append("------------------------------------------------\n");
            try (var writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                writer.write(sb.toString());
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断文件日志开关是否已开启，读取失败时按关闭处理
     * 带 mtime 缓存：配置文件未变化时直接复用上次判定结果
     */
    private static boolean isFileLogEnabled() {
        var file = new File(CONFIG_PATH);
        var ts = file.exists() ? file.lastModified() : 0L;
        if (ts == sLogEnabledTs) {
            return sLogEnabledCached;
        }
        var enabled = false;
        if (file.exists()) {
            try (var reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                var line = reader.readLine();
                while (line != null) {
                    line = line.trim();
                    if (!line.startsWith("#") && line.startsWith(LOG_KEY + "=")) {
                        var value = line.substring((LOG_KEY + "=").length()).trim();
                        enabled = "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
                        break;
                    }
                    line = reader.readLine();
                }
            } catch (Exception ignored) {
            }
        }
        sLogEnabledCached = enabled;
        sLogEnabledTs = ts;
        return enabled;
    }

    /**
     * 追加写入本地日志文件，任何写入异常都只回落到系统日志，避免影响主流程
     */
    private static void writeFileLog(int priority, String tag, String message, Throwable throwable) {
        if (!isFileLogEnabled()) {
            return;
        }
        try {
            var dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            var date = DAY_FORMAT.get().format(new Date());
            var time = TS_FORMAT.get().format(new Date());
            var file = new File(dir, date + ".log");
            try (var writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                writer.write(time + " " + priorityToLetter(priority) + "/" + tag + ": " + message);
                writer.newLine();
                if (throwable != null) {
                    writer.write(Log.getStackTraceString(throwable));
                    writer.newLine();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 将Android日志优先级转换为文件内展示的单字母类型
     */
    private static String priorityToLetter(int priority) {
        switch (priority) {
            case Log.VERBOSE:
                return "V";
            case Log.DEBUG:
                return "D";
            case Log.INFO:
                return "I";
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            case Log.ASSERT:
                return "A";
            default:
                return String.valueOf(priority);
        }
    }
}
