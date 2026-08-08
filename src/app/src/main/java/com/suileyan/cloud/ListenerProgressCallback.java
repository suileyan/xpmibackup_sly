package com.suileyan.cloud;

import com.suileyan.comm.ProgressCallbackHelp;

/**
 * 把 AIDL 传入的 Object listener（小米 IFileOperationProgressListener）反射适配为 ProgressCallback
 * 统一封装 Y0(启动)/D0(进度)/l0(完成) 及按签名兜底查找，消除各实现重复的 invokeProgress 代码
 */
public class ListenerProgressCallback implements ProgressCallback {

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
        if (listener == null) return;
        invoke(listener, "D0", new Class[]{String.class, long.class, long.class},
                ProgressCallbackHelp.safeString(taskId), current, total);
    }

    @Override
    public void onFinish(String taskId, int code, String msg) {
        if (listener == null) return;
        invoke(listener, "l0", new Class[]{String.class, int.class, String.class},
                ProgressCallbackHelp.safeString(taskId), code, ProgressCallbackHelp.safeString(msg));
    }

    /** 优先按混淆名调用，失败后按参数签名兜底查找回调方法 */
    private static void invoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            target.getClass().getMethod(method, types).invoke(target, args);
        } catch (Exception e) {
            invokeFallback(target, types, args);
        }
    }

    private static void invokeFallback(Object target, Class<?>[] types, Object... args) {
        try {
            for (var m : target.getClass().getMethods()) {
                if (m.getParameterCount() != types.length || m.getReturnType() != void.class) continue;
                var matched = true;
                for (var i = 0; i < types.length; i++) {
                    if (m.getParameterTypes()[i] != types[i]) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    m.invoke(target, args);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }
}
