package com.suileyan.comm;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

/**
 * 脚本执行看门狗：通过 Rhino 指令计数机制实现超时终止。
 *
 * 机制说明：
 * Rhino 没有公开的 InstructionObserver 监听器接口，超时保护的扩展点是
 * ContextFactory 的两个 protected 方法：
 * - makeContext()：创建 Context 时设置 setInstructionObserverThreshold(N)，
 *   解释器每执行约 N 条字节码指令回调一次；
 * - observeInstructionCount(cx, count)：指令数达到阈值时被调用，这里做 deadline 检查。
 *
 * 因此每次 callFunction 用本类创建独立 factory 并调用 factory.enterContext()，
 * 使该次执行拥有独立 deadline。超时后抛出 ScriptTimeoutError（继承 Error），
 * 防止被脚本里的 catch(Exception) 吞掉导致无法终止。
 *
 * 防护目标：
 * - 死循环脚本（while(true){}）在超时后自动中断，不阻塞备份线程；
 * - ReDoS（正则指数级回溯）同样消耗指令计数，会被同一机制捕获。
 */
public final class ScriptWatchdog {

    /** 单次脚本函数调用允许的最长执行时间（毫秒） */
    private static final long SCRIPT_TIMEOUT_MS = 30_000L;

    /** 指令计数回调阈值：每执行约 1000 条字节码指令检查一次 deadline */
    private static final int INSTRUCTION_THRESHOLD = 1000;

    private ScriptWatchdog() {
    }

    /**
     * 创建带超时保护的 ContextFactory。
     * 每次脚本调用应使用独立实例，以获得独立的执行 deadline。
     */
    public static ContextFactory newFactory() {
        return new WatchdogContextFactory(System.currentTimeMillis() + SCRIPT_TIMEOUT_MS);
    }

    /** 当前配置的单次脚本执行超时时间（毫秒），供日志与错误信息使用 */
    public static long timeoutMs() {
        return SCRIPT_TIMEOUT_MS;
    }

    /** 带 deadline 检查的 ContextFactory */
    private static final class WatchdogContextFactory extends ContextFactory {
        private final long deadline;

        WatchdogContextFactory(long deadline) {
            this.deadline = deadline;
        }

        @Override
        protected Context makeContext() {
            var cx = super.makeContext();
            cx.setInstructionObserverThreshold(INSTRUCTION_THRESHOLD);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            if (System.currentTimeMillis() > deadline) {
                throw new ScriptTimeoutError(
                    "custom script execution timed out after " + SCRIPT_TIMEOUT_MS + "ms");
            }
        }
    }

    /**
     * 脚本超时错误。
     * 继承 Error 而非 Exception：脚本里的 catch(Exception) 无法捕获它，
     * 确保超时一定能向上传播并终止本次脚本调用。
     */
    public static final class ScriptTimeoutError extends Error {
        ScriptTimeoutError(String message) {
            super(message);
        }
    }
}
