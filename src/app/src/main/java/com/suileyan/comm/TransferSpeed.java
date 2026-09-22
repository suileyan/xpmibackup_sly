package com.suileyan.comm;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时传输速率统计（上传侧）。
 *
 * 分片上传每完成一片就累加一次字节（接入点见 {@code ListenerProgressCallback.onProgress}，
 * 该回调只在上传链路创建），进度页的速度角标每秒读一次。
 *
 * 速率算法：两次采样之间的字节差 ÷ 时间差，再做一次指数平滑，避免逐秒跳变。
 * 分片之间有短暂空档（等额度/切分），所以空档按衰减处理而不是直接归零；
 * 超过 {@link #IDLE_RESET_MS} 完全没有推进才归零，界面显示 "--"。
 *
 * 注意：{@link #bytesPerSecond()} 只应由 UI 线程按固定节奏调用，多线程并发读会互相
 * 抢采样窗口（写侧 {@link #add(long)} 是原子的，任意线程可调）。
 */
public final class TransferSpeed {

    /** 空闲判定：这么久没有任何新字节就认为传输停了（分片间隙通常只有百毫秒级） */
    private static final long IDLE_RESET_MS = 2500L;
    /** 采样最小间隔：比这更密的读取直接沿用上次结果，避免 dt 过小把速率算爆 */
    private static final long MIN_SAMPLE_MS = 200L;

    private static final AtomicLong TOTAL = new AtomicLong(0L);
    private static volatile long lastBytes;
    private static volatile long lastAt;
    private static volatile long lastProgressAt;
    private static volatile long smoothed;

    private TransferSpeed() {
    }

    /** 传输推进时累加增量字节（任意线程） */
    public static void add(long bytes) {
        if (bytes > 0L) {
            TOTAL.addAndGet(bytes);
        }
    }

    /** 速率（字节/秒）；未开始或已空闲返回 0 */
    public static long bytesPerSecond() {
        try {
            var now = android.os.SystemClock.elapsedRealtime();
            var current = TOTAL.get();
            if (lastAt == 0L) {
                lastBytes = current;
                lastAt = now;
                lastProgressAt = now;
                return 0L;
            }
            var dt = now - lastAt;
            if (dt < MIN_SAMPLE_MS) {
                return smoothed;
            }
            var delta = current - lastBytes;
            lastBytes = current;
            lastAt = now;
            if (delta <= 0L) {
                // 没有推进：短暂空档缓慢衰减（分片间隙），长时间空闲直接归零
                smoothed = now - lastProgressAt > IDLE_RESET_MS ? 0L : smoothed / 2L;
                return smoothed;
            }
            lastProgressAt = now;
            var instant = delta * 1000L / dt;
            smoothed = smoothed == 0L ? instant : (smoothed * 2L + instant) / 3L;
            return smoothed;
        } catch (Throwable e) {
            return 0L;
        }
    }

    /** 人类可读速率文本，如 "12.3 MB/s"；空闲或未开始返回 "--" */
    public static String text() {
        var bps = bytesPerSecond();
        return bps <= 0L ? "--" : human(bps) + "/s";
    }

    /** 字节数人类可读化（B / KB / MB / GB） */
    public static String human(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1048576L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
