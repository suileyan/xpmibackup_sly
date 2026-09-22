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
 * 自动把"出错前流程 + 错误详情"写入 /sdcard/MIUI/backup/sly/logs/年月日_err.log
 */
public class LogHelp {
    private static final String LOG_KEY = "log_enabled";
    /** 日志/配置路径统一走 ConfigHelp.slyRoot() 下的 sly/ 子目录（首次由 ConfigHelp.ensureSlyLayout 迁移） */
    private static final String LOG_DIR = com.suileyan.comm.ConfigHelp.logDir();
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
     * 全链路统一脱敏：message 与异常堆栈在进入任何输出通道前打码（NEW-H-07）
     */
    public static void log(int priority, String tag, String message, Throwable throwable) {
        var safeMessage = sanitizeSensitive(message);
        var safeStack = throwable != null ? sanitizeSensitive(Log.getStackTraceString(throwable)) : null;
        if (safeStack == null) {
            Log.println(priority, tag, safeMessage);
        } else {
            Log.println(priority, tag, safeMessage + "\n" + safeStack);
        }
        recordRing(priority, tag, safeMessage, safeStack);
        writeFileLog(priority, tag, safeMessage, safeStack);
        if (priority >= Log.ERROR) {
            flushErrorLog(priority, tag, safeMessage, safeStack);
        }
    }

    /** 记录一条近期日志到环形缓冲（message/stack 已脱敏） */
    private static void recordRing(int priority, String tag, String message, String stack) {
        var line = new StringBuilder();
        line.append(TS_FORMAT.get().format(new Date()))
                .append(' ').append(priorityToLetter(priority)).append('/').append(tag)
                .append(": ").append(message == null ? "" : message);
        if (stack != null) {
            // 缓冲只保留异常首行，完整堆栈随错误详情落盘
            var first = stack;
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
     * 文件日志写入器缓存（MED-31）。
     * 早期实现每条日志都 FileOutputStream open→write→close，高频日志（备份进度）会产生
     * 大量系统调用与主线程 I/O 抖动。这里按文件复用 BufferedWriter：
     * ERROR 级立即 flush（保证关键日志不丢），其余按 1s 节流 flush。
     * 崩溃堆栈另有 CrashLog 独立落盘，不受此缓冲影响。
     */
    private static final java.util.Map<String, WriterHolder> WRITERS = new java.util.HashMap<>();
    /** 写入器数量上限（跨天会产生新路径，超限时关闭最旧的一个） */
    private static final int WRITERS_MAX = 6;

    private static final class WriterHolder {
        final BufferedWriter writer;
        long lastFlush;

        WriterHolder(BufferedWriter writer) {
            this.writer = writer;
        }
    }

    /** 追加文本到指定日志文件（复用缓冲写入器） */
    private static void appendToFile(String path, String text, boolean flushNow) {
        synchronized (WRITERS) {
            try {
                var holder = WRITERS.get(path);
                if (holder == null) {
                    var file = new File(path);
                    var dir = file.getParentFile();
                    if (dir != null && !dir.exists() && !dir.mkdirs()) {
                        return;
                    }
                    holder = new WriterHolder(new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream(file, true), StandardCharsets.UTF_8)));
                    if (WRITERS.size() >= WRITERS_MAX) {
                        var oldest = WRITERS.keySet().iterator().next();
                        closeWriter(oldest);
                    }
                    WRITERS.put(path, holder);
                }
                holder.writer.write(text);
                var now = System.currentTimeMillis();
                if (flushNow || now - holder.lastFlush >= 1000L) {
                    holder.writer.flush();
                    holder.lastFlush = now;
                }
            } catch (Exception e) {
                // 写失败（磁盘满/权限）：丢弃缓存写入器，下次调用重建
                closeWriter(path);
            }
        }
    }

    /** 关闭并移除某个文件的缓存写入器 */
    private static void closeWriter(String path) {
        var holder = WRITERS.remove(path);
        if (holder != null) {
            try {
                holder.writer.flush();
                holder.writer.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 落盘错误日志前先冲刷缓存，保证"出错前流程"与错误详情顺序一致 */
    private static void flushAllWriters() {
        synchronized (WRITERS) {
            for (var holder : WRITERS.values()) {
                try {
                    holder.writer.flush();
                    holder.lastFlush = System.currentTimeMillis();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 错误日志落盘：将"出错前流程（环形缓冲）+ 本次错误详情"写入 年月日_err.log
     * 双进程（settings/backup）可能并发写同一文件，进程内 synchronized，跨进程竞态可接受
     */
    private static void flushErrorLog(int priority, String tag, String message, String stack) {
        try {
            flushAllWriters();
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
            if (stack != null) {
                sb.append(stack).append('\n');
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
        var file = new File(com.suileyan.comm.ConfigHelp.configPath());
        var ts = file.exists() ? file.lastModified() : 0L;
        if (ts == sLogEnabledTs) {
            return sLogEnabledCached;
        }
        // 默认开启（v0.9.1）：错误/流程日志默认落盘便于排查；config.ini 显式 log_enabled=false 才关闭
        var enabled = true;
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
     * 敏感 URL 参数：匹配 query/header/文本中的 token/cookie 等凭据，日志写入时值打码（NEW-H-07）
     * 覆盖：token 类、authorization/cookie/密码/签名/会话 id 等键值形式
     */
    private static final java.util.regex.Pattern SENSITIVE_PARAM = java.util.regex.Pattern.compile(
            "(?i)((?:^|[?&;\\s'\"\\\\])(?:token|access_token|accessToken|refresh_token|refreshToken|"
            + "authorization|auth|cookie|cookie_token|__puus|__pus|passwd|password|pass|pwd|secret|signature|"
            + "api_key|apikey|sessionid|sid|did|x-device-sign)=)[^&;\\s'\"\\\\]*");

    /** URL 内嵌凭据（user:pass@host）：打码 userinfo 部分，防止 webdav_url 等含账号密码落日志 */
    private static final java.util.regex.Pattern URL_USERINFO = java.util.regex.Pattern.compile(
            "(?i)((?:https?|ftp)://)[^/@\\s]+@");

    /** Authorization: Basic xxx 形式（冒号分隔的请求头），Basic 后的 Base64 凭据打码 */
    private static final java.util.regex.Pattern BASIC_HEADER = java.util.regex.Pattern.compile(
            "(?i)(authorization:\\s*basic\\s+)[^\\s,;]+");

    /**
     * 日志脱敏：将 URL 内嵌凭据、Basic 头、文本中的敏感参数值替换为 ***，避免 token/cookie/密码落入日志
     * 包内可见：CrashLog 直接落盘崩溃文本前也必须过同一脱敏（HIGH-07）
     */
    static String sanitizeSensitive(String text) {
        if (text == null || text.isEmpty()) return text;
        var out = SENSITIVE_PARAM.matcher(text).replaceAll("$1***");
        out = URL_USERINFO.matcher(out).replaceAll("$1***@");
        out = BASIC_HEADER.matcher(out).replaceAll("$1***");
        return out;
    }

    /**
     * 追加 WebView 调试日志到独立的 年月日_web.log（不受 log_enabled 开关影响，NEW-H-06）
     * 用于排查网盘前端请求逻辑（如容量接口定位）：记录页面请求/跳转/console 输出；
     * 写入前统一脱敏（URL query 中的 token/cookie 等参数值打码，NEW-H-07）
     */
    public static void web(String message) {
        try {
            var dir = new File(LOG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            var date = DAY_FORMAT.get().format(new Date());
            var time = TS_FORMAT.get().format(new Date());
            var file = new File(dir, date + "_web.log");
            appendToFile(file.getPath(), time + " " + sanitizeSensitive(message) + "\n", false);
        } catch (Exception ignored) {
        }
    }

    /**
     * 追加写入本地日志文件，任何写入异常都只回落到系统日志，避免影响主流程
     */
    private static void writeFileLog(int priority, String tag, String message, String stack) {
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
            var sb = new StringBuilder();
            sb.append(time).append(' ').append(priorityToLetter(priority)).append('/')
                    .append(tag).append(": ").append(message).append('\n');
            if (stack != null) {
                sb.append(stack).append('\n');
            }
            // ERROR 级立即落盘，其余 1s 节流（MED-31）
            appendToFile(file.getPath(), sb.toString(), priority >= Log.ERROR);
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
