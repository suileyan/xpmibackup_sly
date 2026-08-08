package com.suileyan.comm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享后台任务线程池
 *
 * Xposed 模块注入的 settings/backup 进程中存在多处后台任务（云端删除、包收集、临时目录清理等）。
 * 统一使用本类：守护线程池避免非 daemon 线程阻塞进程退出（NEW-H-01），
 * 复用线程避免每次 new Thread 的开销。
 */
public final class Async {

    private static volatile ExecutorService sPool;

    private static final ThreadFactory DAEMON_FACTORY = new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            var t = new Thread(r, "XpMiBackup-async-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    };

    private Async() {
    }

    private static ExecutorService pool() {
        var p = sPool;
        if (p != null && !p.isShutdown()) {
            return p;
        }
        synchronized (Async.class) {
            p = sPool;
            if (p == null || p.isShutdown()) {
                p = Executors.newCachedThreadPool(DAEMON_FACTORY);
                sPool = p;
            }
            return p;
        }
    }

    /**
     * 在共享守护线程池中执行任务
     * 任务执行期间临时把线程名改为 "XpMiBackup-<name>"，
     * 便于日志/线程 dump 区分任务来源（VRF-L-02），结束后还原
     */
    public static void run(String name, Runnable runnable) {
        if (runnable == null) return;
        var taskName = (name == null || name.isEmpty()) ? "async" : name;
        pool().execute(() -> {
            var thread = Thread.currentThread();
            var original = thread.getName();
            thread.setName("XpMiBackup-" + taskName);
            try {
                runnable.run();
            } finally {
                thread.setName(original);
            }
        });
    }
}
