package com.suileyan.comm;

/**
 * 备份取消信号。
 *
 * 宿主取消备份时只会收掉它自己的任务，本模块在途/排队中的上传完全不受影响：
 * 实测取消微信（8.75GB / 140 片）后，分片仍继续 PUT 了二十多片，直到云端目录被取消清理
 * 删掉、PUT 报 500 才停下；期间还误判为"上传成功"把本地源文件删了。
 *
 * 这里只做一件事：取消时打一个时间戳。上传链路用
 * 「本次上传请求的发起时刻 &lt; 取消时刻」判定该请求属于已取消的那一轮，从而尽快中止。
 * 不需要显式重置：新备份的上传请求必然晚于上一次取消的时刻。
 */
public final class BackupCancel {

    private static volatile long sCancelledAt = 0L;

    private BackupCancel() {
    }

    /** 记录一次取消（宿主的 abortNASTask / stopNasTransferTask 进入时调用） */
    public static void markCancelled() {
        sCancelledAt = android.os.SystemClock.elapsedRealtime();
    }

    /** 该上传请求是否属于已被取消的那一轮备份 */
    public static boolean isCancelledFor(long requestAt) {
        var at = sCancelledAt;
        return at > 0 && requestAt > 0 && requestAt < at;
    }

    /** 最近一次取消时刻（0 表示从未取消过），仅用于日志 */
    public static long cancelledAt() {
        return sCancelledAt;
    }
}
