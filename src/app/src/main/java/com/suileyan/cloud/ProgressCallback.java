package com.suileyan.cloud;

/**
 * 上传进度回调抽象
 * 替代各实现里直接反射 Object listener 的做法，统一 onStart/onProgress/onFinish
 */
public interface ProgressCallback {

    /** 任务开始（小米混淆方法名 Y0） */
    default void onStart(String taskId) {
    }

    /** 进度更新（小米混淆方法名 D0） */
    void onProgress(String taskId, long current, long total);

    /** 任务完成或失败（小米混淆方法名 l0） */
    default void onFinish(String taskId, int code, String msg) {
    }
}
