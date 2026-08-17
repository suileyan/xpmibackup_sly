package com.suileyan.xpmibackup.hook;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.ResultReceiver;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.comm.CloudFileHelp;
import com.suileyan.comm.ConfigHelp;
import com.suileyan.comm.LocalBackupFileHelp;
import com.suileyan.comm.LogHelp;
import com.suileyan.comm.ProgressCallbackHelp;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将小米DFS AIDL调用重定向到当前云端备份协议
 * Hook停留在公开DFS服务边界，备份和恢复仍走小米自己的SDK包装层，避免直接Hook备份应用里的混淆业务函数
 */
public class AIDLHook {
    private static final String DESCRIPTOR = "com.xiaomi.dist.file.client.common.IDistFileClientKit";
    private static final String DFS_PACKAGE = "com.milink.service";
    private static final String DFS_SERVICE = "com.xiaomi.dist.file.client.core.DistFileClientService";
    private static final String KEY_CODE = "code";
    private static final String KEY_DATA = "data";
    private static final String KEY_MESSAGE = "message";
    private static final String TAG = "XpMiBackup";
    private static final String DFS_ROOT_PATH = "";
    private static volatile IBinder mockBinder;
    private static volatile ExecutorService uploadExecutor;

    /** BinderProxy.transact 的 interfaceDescriptor 缓存：避免对同一 Binder 重复跨进程查询（CRIT-07）
     * 使用弱引用 key：BinderProxy 被回收后条目自动清除，防止内存泄漏（NEW-M-02） */
    private static final Map<IBinder, String> BINDER_DESCRIPTOR_CACHE =
            Collections.synchronizedMap(new java.util.WeakHashMap<IBinder, String>());

    /** 守护线程工厂：后台线程不阻止进程退出 */
    private static final ThreadFactory DAEMON_THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            var t = new Thread(r, "XpMiBackup-worker-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    };

    /**
     * 获取上传线程池，允许通过配置控制并发上传数量
     * 双重检查锁定：避免 check-then-act 竞态导致线程池泄漏（CRIT-05）
     */
    private static ExecutorService getUploadExecutor() {
        var e = uploadExecutor;
        if (e != null && !e.isShutdown()) {
            return e;
        }
        synchronized (AIDLHook.class) {
            e = uploadExecutor;
            if (e == null || e.isShutdown()) {
                e = Executors.newFixedThreadPool(Math.max(1, ConfigHelp.getInt("upload_threads", 3)), DAEMON_THREAD_FACTORY);
                uploadExecutor = e;
            }
            return e;
        }
    }

    /**
     * 在共享守护线程池执行普通DFS模拟回调，避免阻塞AIDL调用线程（NEW-H-01）
     */
    private static void runAsync(String name, Runnable runnable) {
        com.suileyan.comm.Async.run(name, runnable);
    }

    /**
     * 安装DFS服务发现、绑定和低层Binder Hook
     */
    public void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        hookQueryIntentServices();
        hookBindService(lpparam);
        hookBinderProxyTransact();
    }

    /**
     * 伪造DFS服务查询结果，让SDK认为设备上存在米联DFS服务
     */
    private void hookQueryIntentServices() {
        try {
            XposedHelpers.findAndHookMethod(Class.forName("android.app.ApplicationPackageManager"), "queryIntentServices", new Object[]{Intent.class, Integer.TYPE, new XC_MethodHook() {
                /**
                 * 在DFS服务查询前直接返回伪造的服务信息
                 */
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                    var intent = (Intent) param.args[0];
                    if (intent != null && "com.xiaomi.dist.file.client.action.MANAGER".equals(intent.getAction())) {
                        var ri = new ResolveInfo();
                        ri.serviceInfo = new ServiceInfo();
                        ri.serviceInfo.packageName = AIDLHook.DFS_PACKAGE;
                        ri.serviceInfo.name = AIDLHook.DFS_SERVICE;
                        var list = new ArrayList<>();
                        list.add(ri);
                        param.setResult(list);
                    }
                }
            }});
        } catch (Throwable e) {
            logError("AIDLHook: hook queryIntentServices failed", e);
        }
    }

    /**
     * 将真实DFS服务绑定替换为进程内模拟Binder
     */
    private void hookBindService(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ContextWrapper.class, "bindService", new Object[]{Intent.class, Integer.TYPE, Executor.class, ServiceConnection.class, new BindServiceHook(lpparam)});
        } catch (Throwable e) {
            logError("AIDLHook: hook bindService failed", e);
        }
    }

    /**
     * 处理ContextWrapper.bindService，把DFS服务连接回调切到模拟Binder
     */
    private class BindServiceHook extends XC_MethodHook {
        final XC_LoadPackage.LoadPackageParam val$lpparam;

        /**
         * 保存当前备份应用的类加载器参数
         */
        BindServiceHook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
            this.val$lpparam = loadPackageParam;
        }

        /**
         * 拦截DFS服务绑定，并异步触发onServiceConnected
         */
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
            var intent = (Intent) param.args[0];
            var comp = intent.getComponent();
            if (comp != null && AIDLHook.DFS_PACKAGE.equals(comp.getPackageName()) && AIDLHook.DFS_SERVICE.equals(comp.getClassName())) {
                var connection = (ServiceConnection) param.args[3];
                var executor = (Executor) param.args[2];
                AIDLHook.mockBinder = AIDLHook.this.createMockBinder(this.val$lpparam);
                executor.execute(new Runnable() {
                    /**
                     * 在调用方指定线程里派发服务已连接回调
                     */
                    @Override
                    public final void run() {
                        AIDLHook.BindServiceHook.notifyServiceConnected(connection);
                    }
                });
                param.setResult(true);
            }
        }

        /**
         * 调用原ServiceConnection回调，让SDK继续走正常AIDL初始化
         */
        static void notifyServiceConnected(ServiceConnection connection) {
            try {
                connection.onServiceConnected(new ComponentName(AIDLHook.DFS_PACKAGE, AIDLHook.DFS_SERVICE), AIDLHook.mockBinder);
            } catch (Exception e) {
                AIDLHook.logError("AIDLHook: onServiceConnected failed", e);
            }
        }
    }

    /**
     * 兜底短路部分SDK直接发出的one-way Binder调用，避免真实DFS服务缺失导致异常
     */
    private void hookBinderProxyTransact() {
        try {
            XposedHelpers.findAndHookMethod(Class.forName("android.os.BinderProxy"), "transact", new Object[]{Integer.TYPE, Parcel.class, Parcel.class, Integer.TYPE, new XC_MethodHook() {
                /**
                 * 只处理one-way DFS事务，同步事务交回系统Binder正常处理
                 */
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                    var code = ((Integer) param.args[0]).intValue();
                    var flags = ((Integer) param.args[3]).intValue();
                    if (flags != 1) {
                        return;
                    }
                    var binder = (IBinder) param.thisObject;
                    try {
                        // 缓存已检查过的 Binder 的 interfaceDescriptor，避免每个 one-way 事务
                        // 都额外做一次同步跨进程 Binder 往返（CRIT-07）
                        var descriptor = BINDER_DESCRIPTOR_CACHE.get(binder);
                        if (descriptor == null) {
                            descriptor = binder.getInterfaceDescriptor();
                            BINDER_DESCRIPTOR_CACHE.put(binder, descriptor == null ? "" : descriptor);
                        }
                        if (AIDLHook.DESCRIPTOR.equals(descriptor)) {
                            switch (code) {
                                case 1:
                                case 6:
                                case 7:
                                case 9:
                                case 16:
                                case 22:
                                    param.setResult(true);
                                    break;
                                default:
                                    // Android 9~17 版本兼容（HIGH-25）：未知 transact code 不短路（交回系统），
                                    // 节流记录一次，供真机版本矩阵发现 DFS AIDL 接口漂移
                                    logUnknownDfsCode(binder, code);
                                    break;
                            }
                        }
                    } catch (Exception e) {
                        AIDLHook.logError("AIDLHook: BinderProxy.transact inspect failed", e);
                    }
                }
            }});
        } catch (Throwable e) {
            logError("AIDLHook: hook BinderProxy.transact failed", e);
        }
    }

    /**
     * 记录未知 DFS transact code（HIGH-25 版本兼容诊断）。
     * 同一 code 进程生命周期内只记一次，避免 one-way 高频事务刷爆日志。
     * 新版本小米备份 DFS AIDL 若新增接口方法，此处出现未知 code 即为漂移信号。
     */
    private static final java.util.Set<Integer> REPORTED_UNKNOWN_DFS_CODES =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private static void logUnknownDfsCode(IBinder binder, int code) {
        if (REPORTED_UNKNOWN_DFS_CODES.add(code)) {
            LogHelp.w(TAG, "DFS unknown transact code=" + code
                    + " binder=" + binder.getClass().getName()
                    + "（未短路，交回系统处理；如为 DFS 新接口请收集上报）");
        }
    }

    /**
     * 创建本地Binder，并用动态代理实现IDistFileClientKit
     * 使用标准 new Binder() 而非 sun.misc.Unsafe.allocateInstance（HIGH-12）：
     * Unsafe 绕过构造函数属于非 SDK 接口，高版本 Android 可能被限制，
     * 且绕过初始化可能引发 native 崩溃；new Binder() 内部状态完整。
     */
    private IBinder createMockBinder(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        var binderInstance = new android.os.Binder();
        var ifaceClass = XposedHelpers.findClass(DESCRIPTOR, lpparam.classLoader);
        var ifaceProxy = Proxy.newProxyInstance(lpparam.classLoader, new Class[]{ifaceClass}, new InvocationHandler() {
            /**
             * 将AIDL接口调用统一派发到签名匹配逻辑
             */
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                // 处理 Object 公共方法，避免 equals/hashCode/toString 进入 AIDL 分发返回 null（NEW-M-07）
                var name = method.getName();
                if (args == null) {
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("toString".equals(name)) return "IDistFileClientKit-proxy";
                } else if (args.length == 1 && "equals".equals(name)) {
                    return proxy == args[0];
                }
                return AIDLHook.this.handleAidlMethod(method, args, lpparam);
            }
        });
        var attachMethod = android.os.Binder.class.getDeclaredMethod("attachInterface", IInterface.class, String.class);
        attachMethod.setAccessible(true);
        attachMethod.invoke(binderInstance, ifaceProxy, DESCRIPTOR);
        mockBinder = binderInstance;
        return binderInstance;
    }

    /**
     * 按参数签名分发AIDL方法，同时兼容旧版可读方法名和新版混淆方法名
     */
    private Object handleAidlMethod(Method method, Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        var name = method.getName();
        if ("asBinder".equals(name)) {
            return mockBinder;
        }
        if (matches(method, String.class, byName("IConnectionListener"))) {
            mockConnect(args, lpparam);
            return null;
        }
        if (matches(method, String.class)) {
            return null;
        }
        if (matches(method, String.class, String.class)) {
            return null;
        }
        if (matches(method, byName("DeviceFilter"), byName("IDeviceStateListener"))) {
            mockDeviceStateListener(args, lpparam);
            return null;
        }
        if (matches(method, byName("IDeviceStateListener"))) {
            mockDeviceStateListener(args, lpparam);
            return null;
        }
        if (matches(method, byName("DeviceFilter"), ResultReceiver.class)) {
            mockGetDeviceList(args, lpparam);
            return null;
        }
        if (matches(method, String.class, ResultReceiver.class)) {
            mockGetSharePathInfo(args, lpparam);
            return null;
        }
        if (matches(method, String.class, String.class, ResultReceiver.class)) {
            handleResultReceiverMethod(name, args, lpparam);
            return null;
        }
        if (matches(method, String.class, String.class, ParcelFileDescriptor.class, String.class, Long.TYPE, byName("IFileOperationProgressListener"), Integer.TYPE)) {
            if ("w1".equals(name) || "upload".equals(name)) {
                mockUpload(args, lpparam);
                return null;
            }
            if ("G0".equals(name) || "download".equals(name)) {
                mockDownload(args, lpparam);
                return null;
            }
            mockUpload(args, lpparam);
            return null;
        }
        if (matches(method, String.class, String.class, String.class, String.class, byName("IFileOperationProgressListener"))) {
            mockRemoteToRemoteOk(args);
            return null;
        }
        LogHelp.e(TAG, "AIDLCall: unhandled " + name + signatureOf(method));
        return null;
    }

    /**
     * 远端到远端的复制/移动调用先返回成功，避免旧文件迁移路径误报失败
     */
    private void mockRemoteToRemoteOk(Object[] args) {
        var taskId = (String) args[1];
        var listener = args[4];
        notifyProgressStart(listener, taskId);
        notifyProgressFinish(listener, taskId, 0, "success");
    }

    /**
     * 模拟DFS连接调用，并把耗时连接测试放到后台线程
     */
    private void mockConnect(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var deviceId = (String) args[0];
        var listener = args[1];
        runAsync("connect", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockConnectResult(listener, deviceId, lpparam);
            }
        });
    }

    /**
     * 模拟DFS连接结果，并在连接成功后初始化备份应用内部DFS状态
     */
    private void sendMockConnectResult(Object listener, String deviceId, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var connected = CloudFileHelp.testConnection();
            if (listener != null) {
                if (connected) {
                    invokeConnection(listener, deviceId, 0, true);
                } else {
                    invokeConnection(listener, deviceId, 1200, false);
                }
            }
            if (connected) {
                ensureDfsServiceReady(deviceId, lpparam);
                keepDfsConnected(lpparam);
            }
        } catch (Exception e) {
            logError("mock connect failed", e);
        }
    }

    /**
     * 模拟获取设备列表调用
     */
    private void mockGetDeviceList(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var receiver = args[1];
        runAsync("device-list", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockDeviceList(receiver, lpparam);
            }
        });
    }

    /**
     * 构造设备列表回调，供小米备份页面展示远端设备
     */
    private void sendMockDeviceList(Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        if (receiver != null) {
            try {
                var bundle = successBundle();
                var list = new ArrayList<Parcelable>();
                list.add(createDeviceInfo(lpparam));
                bundle.putParcelableArrayList(KEY_DATA, list);
                invokeReceiverSend(receiver, 0, bundle);
                ensureDfsServiceReady(getMockDeviceId(), lpparam);
            } catch (Exception e) {
                logError("mock getDeviceList failed", e);
            }
        }
    }

    /**
     * 模拟注册设备状态监听调用
     */
    private void mockDeviceStateListener(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var listener = args.length == 1 ? args[0] : args[1];
        runAsync("device-state", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockDeviceState(listener, lpparam);
            }
        });
    }

    /**
     * 发送模拟设备在线回调，并补齐内部DFS初始化
     */
    private void sendMockDeviceState(Object listener, XC_LoadPackage.LoadPackageParam lpparam) {
        if (listener != null) {
            try {
                invokeDeviceState(listener, "K", createDeviceInfo(lpparam));
                ensureDfsServiceReady(getMockDeviceId(), lpparam);
            } catch (Exception e) {
                logError("mock device state failed", e);
            }
        }
    }

    /**
     * 模拟远端目录列表调用
     */
    private void mockList(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var remotePath = (String) args[1];
        var receiver = args[2];
        runAsync("list", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockList(remotePath, receiver, lpparam);
            }
        });
    }

    /**
     * 从当前云端备份协议读取目录，并转换成小米NAS恢复列表需要的结果
     */
    private void sendMockList(String remotePath, Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            var remoteDir = normalizeRemotePath(remotePath);
            var entries = CloudFileHelp.listEntries(remoteDir);
            // 诊断：完整文件名清单（定位"备份文件损坏"= 云端缺文件/分片结构异常，mock list 只打 first 不够）
            var names = new StringBuilder();
            for (var e : entries) {
                if (names.length() > 0) names.append(',');
                names.append(e.name);
            }
            LogHelp.d(TAG, "mock list: aidl=" + remotePath + " -> remote=" + remoteDir
                    + " entries=" + entries.size()
                    + (names.length() == 0 ? "" : " names=" + names.substring(0, Math.min(names.length(), 400))));
            var aidlDir = normalizeAidlListPath(remotePath);
            entries = normalizeListEntries(entries, aidlDir);
            if (receiver != null) {
                var bundle = successBundle();
                bundle.putParcelable(KEY_DATA, createSmbFileBatchResult(entries, aidlDir, lpparam));
                invokeReceiverSend(receiver, 0, bundle);
            }
        } catch (Exception e) {
            logError("mock list failed: aidl=" + remotePath + " -> remote=" + normalizeRemotePathQuiet(remotePath), e);
            sendEmptyList(receiver, lpparam);
        }
    }

    /** 失败日志用：normalizeRemotePath 抛异常时不阻塞异常打印 */
    private static String normalizeRemotePathQuiet(String aidlPath) {
        try {
            return normalizeRemotePath(aidlPath);
        } catch (Exception e) {
            return "<unresolved: " + aidlPath + ">";
        }
    }

    /**
     * 列表读取失败时返回空列表，避免恢复页面一直空等
     */
    private void sendEmptyList(Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        if (receiver == null) {
            return;
        }
        try {
            var bundle = successBundle();
            bundle.putParcelable(KEY_DATA, createSmbFileBatchResult(new ArrayList<>(), ".AllBackup", lpparam));
            invokeReceiverSend(receiver, 0, bundle);
        } catch (Exception e) {
            logError("send empty list failed", e);
        }
    }

    /**
     * 模拟上传文件调用，新版w1和旧版upload同签名方法都会走这里
     */
    private void mockUpload(Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        var taskId = (String) args[1];
        var pfd = (ParcelFileDescriptor) args[2];
        var aidlPath = (String) args[3];
        var listener = args[5];
        if (pfd == null) {
            return;
        }
        try {
            getUploadExecutor().execute(new Runnable() {
                /**
                 * 在线程池中执行上传，避免多个大文件串行阻塞
                 */
                @Override
                public final void run() {
                    AIDLHook.this.runMockUpload(pfd, aidlPath, listener, taskId, lpparam);
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池拒绝执行时立即关闭文件描述符，避免泄漏（CRIT-06）
            logError("mock upload rejected by executor", e);
            closeQuietly(pfd);
            notifyProgressFinish(listener, taskId, -1, "upload executor rejected");
        }
    }

    /**
     * 执行上传并保证文件描述符最终关闭
     */
    private void runMockUpload(ParcelFileDescriptor pfd, String aidlPath, Object listener, String taskId, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            keepDfsConnected(lpparam);
            uploadViaFd(pfd, aidlPath, listener, taskId);
            keepDfsConnected(lpparam);
        } catch (Exception e) {
            logError("mock upload failed", e);
            notifyProgressFinish(listener, taskId, -1, e.getMessage());
        } finally {
            closeQuietly(pfd);
        }
    }

    /**
     * 模拟下载文件调用，新版G0和旧版download同签名方法都会走这里
     */
    private void mockDownload(Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        var taskId = (String) args[1];
        var pfd = (ParcelFileDescriptor) args[2];
        var aidlPath = (String) args[3];
        final long startPos = ((Long) args[4]).longValue();
        var listener = args[5];
        final int flags = ((Integer) args[6]).intValue();
        if (pfd == null) {
            return;
        }
        try {
            runAsync("download", new Runnable() {
                @Override
                public final void run() {
                    AIDLHook.this.runMockDownload(taskId, aidlPath, startPos, flags, pfd, listener, lpparam);
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池拒绝执行时立即关闭文件描述符，避免泄漏（CRIT-06）
            logError("mock download rejected by executor", e);
            closeQuietly(pfd);
            notifyProgressFinish(listener, taskId, -1, "download executor rejected");
        }
    }

    /**
     * 执行下载并把完成或失败状态回调给小米备份
     */
    private void runMockDownload(String taskId, String aidlPath, long startPos, int flags, ParcelFileDescriptor pfd, Object listener, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            BackupHook.clearActiveBackupDirs();
            keepDfsConnected(lpparam);
            notifyProgressStart(listener, taskId);
            downloadViaFd(aidlPath, pfd, taskId, startPos, listener);
            keepDfsConnected(lpparam);
            notifyProgressFinish(listener, taskId, 0, "success");
            LogHelp.d(TAG, "mock download OK: path=" + aidlPath + " start=" + startPos + " flags=" + flags);
        } catch (Exception e) {
            logError("mock download failed: path=" + aidlPath + ", start=" + startPos + ", flags=" + flags, e);
            notifyProgressFinish(listener, taskId, -1, e.getMessage());
        } finally {
            closeQuietly(pfd);
        }
    }

    /**
     * 模拟文件存在性检查调用
     */
    private void mockExists(Object[] args) {
        var receiver = args[2];
        runAsync("exists", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockExists(receiver);
            }
        });
    }

    /**
     * 返回存在结果；这里保持成功，交给后续读写流程验证真实文件
     */
    private void sendMockExists(Object receiver) {
        if (receiver != null) {
            try {
                var bundle = successBundle();
                bundle.putInt(KEY_DATA, 1);
                invokeReceiverSend(receiver, 0, bundle);
            } catch (Exception e) {
                logError("mock exists failed", e);
            }
        }
    }

    /**
     * 模拟获取共享路径信息调用
     */
    private void mockGetSharePathInfo(Object[] args, final XC_LoadPackage.LoadPackageParam lpparam) {
        var receiver = args[1];
        runAsync("share-path", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendMockSharePathInfo(receiver, lpparam);
            }
        });
    }

    /**
     * 构造共享目录信息，供小米备份显示远端容量
     */
    private void sendMockSharePathInfo(Object receiver, XC_LoadPackage.LoadPackageParam lpparam) {
        if (receiver != null) {
            try {
                var bundle = successBundle();
                var list = new ArrayList<Parcelable>();
                list.add(createPathInfo(lpparam));
                bundle.putParcelableArrayList(KEY_DATA, list);
                invokeReceiverSend(receiver, 0, bundle);
            } catch (Exception e) {
                logError("mock getSharePathInfo failed", e);
            }
        }
    }

    /**
     * 处理exists、list以及其他带ResultReceiver的两路径调用
     */
    private void handleResultReceiverMethod(String name, Object[] args, XC_LoadPackage.LoadPackageParam lpparam) {
        if ("i0".equals(name) || "list".equals(name)) {
            mockList(args, lpparam);
        } else if ("f0".equals(name) || "exists".equals(name)) {
            mockExists(args);
        } else {
            mockSimpleOk(args, name);
        }
    }

    /**
     * 模拟不需要额外业务数据的成功回调调用
     */
    private void mockSimpleOk(Object[] args, final String operation) {
        var receiver = args[2];
        runAsync("simple-ok", new Runnable() {
            @Override
            public final void run() {
                AIDLHook.this.sendSimpleOk(receiver, operation);
            }
        });
    }

    /**
     * 为mkdir、delete、cancel等不需要真实数据的调用返回成功
     */
    private void sendSimpleOk(Object receiver, String operation) {
        if (receiver != null) {
            try {
                var bundle = successBundle();
                bundle.putInt(KEY_DATA, 0);
                invokeReceiverSend(receiver, 0, bundle);
            } catch (Exception e) {
                logError("mock " + operation + " failed", e);
            }
        }
    }

    /**
     * 创建DFS ResultReceiver成功回调数据
     */
    private static Bundle successBundle() {
        var bundle = new Bundle();
        bundle.putInt(KEY_CODE, 0);
        bundle.putString(KEY_MESSAGE, "success");
        return bundle;
    }

    /**
     * 把DFS虚拟路径转换为当前云端备份协议使用的远端路径
     */
    private static String normalizeRemotePath(String aidlPath) {
        if (aidlPath == null || aidlPath.isEmpty()) {
            return "";
        }
        var backupPath = ConfigHelp.getString("backup_path", "MIUI/backup");
        var path = aidlPath;
        if (path.startsWith(backupPath + "/")) {
            path = path.substring(backupPath.length() + 1);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        var firstSlash = path.indexOf('/');
        if (firstSlash > 0 && !path.startsWith(".AllBackup") && !path.startsWith(".AppBackup")) {
            path = path.substring(firstSlash + 1);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (".AllBackup".equals(path)) {
            return backupPath;
        }
        if (path.startsWith(".AllBackup/")) {
            path = path.substring(".AllBackup/".length());
        }
        if (path.startsWith("AllBackup/")) {
            path = path.substring("AllBackup/".length());
        }
        if ("AllBackup".equals(path)) {
            return backupPath;
        }
        if (path.startsWith(".AppBackup/")) {
            path = path.substring(".AppBackup/".length());
        }
        var path2 = cleanRemotePathSegments(path);
        return path2.isEmpty() ? backupPath : backupPath + "/" + path2;
    }

    /**
     * 把调用方传入路径转换回DFS列表展示路径
     */
    private static String normalizeAidlListPath(String aidlPath) {
        if (aidlPath == null || aidlPath.isEmpty()) {
            return ".AllBackup";
        }
        var path = aidlPath.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        var firstSlash = path.indexOf('/');
        if (firstSlash > 0 && !path.startsWith(".AllBackup") && !path.startsWith(".AppBackup")) {
            path = path.substring(firstSlash + 1);
        }
        if (path.startsWith(".AppBackup")) {
            path = ".AllBackup" + path.substring(".AppBackup".length());
        }
        if (path.startsWith("AllBackup")) {
            path = ".AllBackup" + path.substring("AllBackup".length());
        }
        if (!path.startsWith(".AllBackup")) {
            path = ".AllBackup";
        }
        return path;
    }

    /**
     * 移除DFS虚拟目录，保留备份数据里的真实隐藏文件
     */
    private static String cleanRemotePathSegments(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        var cleaned = new StringBuilder();
        for (var segment : path.split("/")) {
            if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                || ".AllBackup".equals(segment) || ".AppBackup".equals(segment)) {
                continue;
            }
            if (cleaned.length() > 0) {
                cleaned.append("/");
            }
            cleaned.append(segment);
        }
        return cleaned.toString();
    }

    /**
     * 从远端文件路径中取父目录
     */
    private static String extractRemoteDir(String remotePath) {
        var lastSlash = remotePath.lastIndexOf('/');
        return lastSlash > 0 ? remotePath.substring(0, lastSlash) : remotePath;
    }

    /**
     * 从远端文件路径中取文件名
     */
    private static String extractFileName(String remotePath) {
        var lastSlash = remotePath.lastIndexOf('/');
        return lastSlash >= 0 ? remotePath.substring(lastSlash + 1) : remotePath;
    }

    /**
     * 把远端目录条目整理成小米NAS恢复加载器期望的形态
     */
    private static List<RemoteEntry> normalizeListEntries(List<RemoteEntry> entries, String aidlDir) {
        var normalized = new ArrayList<RemoteEntry>();
        addDfsListPadding(normalized);
        for (var entry : entries) {
            var looksLikeBackupDir = ".AllBackup".equals(aidlDir)
                && entry.name != null
                && entry.name.matches("\\d{8}_\\d{6}");
            var displayName = decodeDfsListName(entry.name);
            normalized.add(new RemoteEntry(displayName, entry.size, entry.directory || looksLikeBackupDir, entry.modifiedTime));
        }
        return normalized;
    }

    /**
     * 解码WebDAV返回的百分号编码名称
     */
    private static String decodeDfsListName(String name) {
        // WebDAV的href可能带百分号编码，DFS调用方比较的是解码后的显示名称
        if (name == null || name.indexOf('%') < 0) {
            return name;
        }
        var decoded = new StringBuilder();
        var bytes = new ByteArrayOutputStream();
        for (var i = 0; i < name.length(); i++) {
            var ch = name.charAt(i);
            if (ch == '%' && i + 2 < name.length()) {
                var hi = Character.digit(name.charAt(i + 1), 16);
                var lo = Character.digit(name.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    bytes.write((hi << 4) + lo);
                    i += 2;
                    continue;
                }
            }
            appendDecodedBytes(decoded, bytes);
            decoded.append(ch);
        }
        appendDecodedBytes(decoded, bytes);
        return decoded.toString();
    }

    /**
     * 将暂存的UTF-8字节追加到解码结果
     */
    private static void appendDecodedBytes(StringBuilder decoded, ByteArrayOutputStream bytes) {
        if (bytes.size() == 0) {
            return;
        }
        decoded.append(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        bytes.reset();
    }

    /**
     * 添加DFS列表占位项，兼容小米NAS加载器跳过前两条记录的行为
     */
    private static void addDfsListPadding(List<RemoteEntry> entries) {
        var now = System.currentTimeMillis();
        entries.add(new RemoteEntry("__dfs_skip_0", 0, true, now));
        entries.add(new RemoteEntry("__dfs_skip_1", 0, true, now));
    }

    /**
     * DFS SDK 模型类查找（Android 9~17 版本兼容，HIGH-25）：
     * 包名/类名随小米备份 App 版本可能漂移，findClassAny 优雅降级——找不到时抛异常
     * 由上层 catch 记录诊断日志（模型类缺失属于致命结构变化，无降级路径）
     */
    private static Class<?> dfsModelClass(XC_LoadPackage.LoadPackageParam lpparam, String simpleName) throws Exception {
        var clazz = HookCompat.findClassAny(lpparam.classLoader, "AIDLHook", "DFS 模型类 " + simpleName,
                "com.xiaomi.dist.file.client.common.model." + simpleName);
        if (clazz == null) {
            throw new IllegalStateException("DFS model class not found: " + simpleName);
        }
        return clazz;
    }

    /**
     * 使用目标应用类加载器构造SmbFileBatchResult
     */
    private Parcelable createSmbFileBatchResult(List<RemoteEntry> entries, String aidlDir, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var smbFiles = new ArrayList<Parcelable>();
        for (var entry : entries) {
            smbFiles.add(createSmbFile(entry, aidlDir, lpparam));
        }
        var clazz = dfsModelClass(lpparam, "SmbFileBatchResult");
        var parcel = Parcel.obtain();
        try {
            parcel.writeInt(1);
            parcel.writeInt(1);
            parcel.writeTypedList(smbFiles);
            parcel.setDataPosition(0);
            return createFromParcel(clazz, parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 使用Parcel构造小米SDK里的SmbFile对象
     */
    private Parcelable createSmbFile(RemoteEntry entry, String aidlDir, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var clazz = dfsModelClass(lpparam, "SmbFile");
        var parcel = Parcel.obtain();
        try {
            var now = System.currentTimeMillis();
            var modified = entry.modifiedTime > 0 ? entry.modifiedTime : now;
            var directory = entry.directory || ".AllBackup".equals(aidlDir);
            parcel.writeString(entry.name);
            parcel.writeLong(entry.size);
            parcel.writeLong(modified);
            parcel.writeLong(modified);
            parcel.writeString(directory ? "inode/directory" : "application/octet-stream");
            parcel.writeString(entry.name);
            parcel.writeString(entry.name);
            parcel.writeInt(0);
            parcel.writeInt(0);
            parcel.writeInt(directory ? 16 : 128);
            parcel.writeLong(modified);
            parcel.writeParcelable(null, 0);
            parcel.setDataPosition(0);
            return createFromParcel(clazz, parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 构造模拟设备信息，保持设备ID和页面入口传参一致
     */
    private Parcelable createDeviceInfo(XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var clazz = dfsModelClass(lpparam, "DeviceInfo");
        var parcel = Parcel.obtain();
        try {
            parcel.writeString(ConfigHelp.getString("device_id", "miback"));
            parcel.writeString(ConfigHelp.getString("device_name", "Remote Backup"));
            parcel.writeInt(1);
            parcel.writeInt(128);
            parcel.writeInt(1);
            parcel.setDataPosition(0);
            return createFromParcel(clazz, parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 构造共享路径信息，供备份页面展示远端空间
     */
    private static Parcelable createPathInfo(XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var clazz = dfsModelClass(lpparam, "PathInfo");
        var parcel = Parcel.obtain();
        try {
            parcel.writeInt(1);
            parcel.writeString(ConfigHelp.getString("device_name", "Remote Backup"));
            parcel.writeString(DFS_ROOT_PATH);
            parcel.writeLong(1099511627776L);
            parcel.writeLong(1099511627776L);
            parcel.writeByte((byte) 1);
            parcel.writeTypedList(new ArrayList<Parcelable>());
            parcel.writeBundle(new Bundle());
            parcel.setDataPosition(0);
            return createFromParcel(clazz, parcel);
        } finally {
            parcel.recycle();
        }
    }

    /**
     * 通过目标应用的CREATOR从Parcel还原Parcelable，反射泛型转换集中在这里处理
     */
    @SuppressWarnings("unchecked")
    private static Parcelable createFromParcel(Class<?> clazz, Parcel parcel) throws Exception {
        var creator = (Parcelable.Creator<? extends Parcelable>) clazz.getField("CREATOR").get(null);
        return creator.createFromParcel(parcel);
    }

    /**
     * 从ParcelFileDescriptor读取备份文件并上传到当前云端备份协议
     */
    private void uploadViaFd(ParcelFileDescriptor pfd, String aidlPath, Object listener, String taskId) throws Exception {
        var remotePath = normalizeRemotePath(aidlPath);
        var remoteDir = extractRemoteDir(remotePath);
        var fileName = extractFileName(remotePath);
        var localFile = LocalBackupFileHelp.resolveUploadFile(pfd, aidlPath);
        var copiedTempFile = (java.io.File) null;
        if (localFile != null && localFile.exists()) {
            // 复制到独立临时副本再上传：备份流程可能在上传期间删除源文件
            // （夸克需先算 md5/sha1 + 分片，窗口长，源文件 ENOENT 频发）。复制失败则回退原文件
            var safeCopy = LocalBackupFileHelp.createUploadTempFile(aidlPath, fileName);
            try {
                safeCopy.getParentFile().mkdirs();
                try (var is = new FileInputStream(localFile); var os = new FileOutputStream(safeCopy)) {
                    copyStream(is, os);
                }
                localFile = safeCopy;
                copiedTempFile = safeCopy;
            } catch (Exception e) {
                LogHelp.w(TAG, "copy upload source failed, fallback to original: " + localFile.getAbsolutePath(), e);
            }
        } else {
            localFile = null;
            copiedTempFile = LocalBackupFileHelp.createUploadTempFile(aidlPath, fileName);
        }
        try {
            BackupHook.recordActiveBackupDir(remoteDir);
            notifyProgressStart(listener, taskId);
            if (localFile == null) {
                copiedTempFile.getParentFile().mkdirs();
                try (var is = new FileInputStream(pfd.getFileDescriptor()); var os = new FileOutputStream(copiedTempFile)) {
                    copyStream(is, os);
                }
                localFile = copiedTempFile;
            }
            CloudFileHelp.uploadWithProgress(localFile.getAbsolutePath(), listener, remoteDir, taskId);
            if (isBackupEndFile(fileName)) {
                uploadLocalDescriptorIfPresent(localFile.getParentFile(), remoteDir);
                CloudFileHelp.cleanupOldBackups();
                BackupHook.clearActiveBackupDirs();
            }
        } finally {
            if (copiedTempFile != null) {
                deleteTempFile(copiedTempFile);
                LocalBackupFileHelp.deleteEmptyDirsUntilTempRoot(copiedTempFile.getParentFile());
            }
        }
    }

    /**
     * 判断当前上传文件是否为一次备份完成标记
     */
    private static boolean isBackupEndFile(String fileName) {
        return "end".equals(fileName);
    }

    /**
     * 自动备份完成时补传本地备份描述文件
     */
    private static void uploadLocalDescriptorIfPresent(File backupDir, String remoteDir) {
        if (backupDir == null || remoteDir == null || remoteDir.isEmpty()) {
            return;
        }
        var descriptFile = new File(backupDir, "descript.xml");
        if (!descriptFile.exists() || !descriptFile.isFile()) {
            return;
        }
        var result = CloudFileHelp.upload(descriptFile.getAbsolutePath(), remoteDir);
        if (result != null && result.startsWith("ERROR:")) {
            logError("upload local descript.xml failed", new IllegalStateException(result));
        }
    }

    /**
     * 提前通知小米备份当前任务已开始，避免本地临时文件阶段列表没有焦点
     */
    private static void notifyProgressStart(Object listener, String taskId) {
        invokeProgress(listener, "Y0", new Class[]{String.class}, ProgressCallbackHelp.safeString(taskId));
        BackupHook.notifyNasItemTaskStart(listener, taskId);
    }

    /**
     * 从当前云端备份协议下载文件并写入ParcelFileDescriptor。
     * startPos 断点续传：App 端 ReDownloadSplitTask 以本地已有长度作偏移，pfd 以 READ_WRITE（不 TRUNCATE）打开
     * 且本地保留 [0,startPos)——本方法下载整文件后跳过前 startPos 字节、从 fd 偏移 startPos 处写尾部，
     * 避免整文件从头覆盖损坏恢复数据（startPos > fileSize 拒绝）
     */
    private void downloadViaFd(String aidlPath, ParcelFileDescriptor pfd, String taskId, long startPos, Object listener) throws Exception {
        var remotePath = normalizeRemotePath(aidlPath);
        var tmpFile = new File(LocalBackupFileHelp.TEMP_BACKUP_ROOT + taskId + "_download_tmp");
        tmpFile.getParentFile().mkdirs();
        try {
            var result = CloudFileHelp.downloadFile(remotePath, tmpFile.getAbsolutePath());
            if (result != null && result.startsWith("ERROR:")) {
                throw new IllegalStateException(result);
            }
            var fileSize = tmpFile.length();
            if (startPos > fileSize) {
                throw new IllegalStateException("download startPos " + startPos + " > fileSize " + fileSize
                        + " path=" + remotePath + "（拒绝覆盖恢复数据）");
            }
            if (startPos > 0) {
                android.system.Os.lseek(pfd.getFileDescriptor(), startPos, android.system.OsConstants.SEEK_SET);
            }
            try (var is = new FileInputStream(tmpFile); var os = new FileOutputStream(pfd.getFileDescriptor())) {
                skipFully(is, startPos);
                copyStreamWithProgress(is, os, listener, taskId, fileSize - startPos);
            }
        } finally {
            deleteTempFile(tmpFile);
        }
    }

    /** 可靠跳过 N 字节（InputStream.skip 可能部分跳过，读空抛 EOF） */
    private static void skipFully(java.io.InputStream is, long n) throws Exception {
        var remaining = n;
        while (remaining > 0) {
            var skipped = is.skip(remaining);
            if (skipped <= 0) {
                if (is.read() == -1) {
                    throw new java.io.EOFException("EOF while skipping " + n + " bytes");
                }
                remaining -= 1;
            } else {
                remaining -= skipped;
            }
        }
    }

    /** 拷贝流并回调 D0(taskId, done, total) 进度 */
    private static void copyStreamWithProgress(java.io.InputStream is, java.io.OutputStream os, Object listener, String taskId, long total) throws Exception {
        var buf = new byte[1048576];
        long written = 0;
        while (true) {
            var len = is.read(buf);
            if (len == -1) {
                return;
            }
            os.write(buf, 0, len);
            written += len;
            invokeProgress(listener, "D0", new Class[]{String.class, long.class, long.class},
                    ProgressCallbackHelp.safeString(taskId), written, total);
        }
    }

    /**
     * 拷贝流内容，调用方负责关闭输入输出流
     */
    private static void copyStream(java.io.InputStream is, java.io.OutputStream os) throws Exception {
        var buf = new byte[1048576];
        while (true) {
            var len = is.read(buf);
            if (len == -1) {
                return;
            }
            os.write(buf, 0, len);
        }
    }

    /**
     * 删除临时文件，删除失败时只记录异常
     */
    private static void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            logError("delete temp file failed", new IllegalStateException(file.getAbsolutePath()));
        }
    }

    /**
     * 触发备份应用自己的DFS服务初始化，补齐deviceId和PathInfo
     */
    private void ensureDfsServiceReady(String deviceId, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Android 9~17 版本兼容（HIGH-25）：DFS 服务类名随 MIUI 版本可能混淆，findClassAny 优雅降级
            var serviceClass = HookCompat.findClassAny(lpparam.classLoader, "AIDLHook", "DFS 服务类",
                    "com.miui.backup.dfs.DistFileClientService");
            if (serviceClass == null) {
                return;
            }
            var instance = getDfsServiceInstance(serviceClass);
            if (instance == null) {
                LogHelp.e(TAG, "ensureDfsServiceReady failed: DistFileClientService instance is null");
                return;
            }
            setDfsDeviceId(serviceClass, instance, deviceId);
            keepDfsConnected(serviceClass, instance);
            ensureDistFileClient(serviceClass, instance, deviceId, lpparam);
            ensureDfsTempPath(serviceClass, instance, deviceId);
            ensureDfsDeviceInfo(serviceClass, instance, lpparam);
            ensureRestoreDescriptors(serviceClass, instance);
            startDfsInit(serviceClass, instance);
        } catch (Exception e) {
            logError("ensureDfsServiceReady failed", e);
        }
    }

    /**
     * 让备份应用内部DFS服务保持已连接状态，避免误入断开暂停分支
     */
    private void keepDfsConnected(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Android 9~17 版本兼容（HIGH-25）：DFS 服务类名随 MIUI 版本可能混淆，findClassAny 优雅降级
            var serviceClass = HookCompat.findClassAny(lpparam.classLoader, "AIDLHook", "DFS 服务类",
                    "com.miui.backup.dfs.DistFileClientService");
            if (serviceClass == null) {
                return;
            }
            var instance = getDfsServiceInstance(serviceClass);
            if (instance != null) {
                keepDfsConnected(serviceClass, instance);
            }
        } catch (Exception e) {
            logError("keepDfsConnected failed", e);
        }
    }

    /**
     * 通过字段类型兼容新版混淆名和旧版明文字段名
     * 只把「连接/状态相关」的 boolean 字段置 true（HIGH-13）：
     * 排除 isDestroyed/isPaused/isCancelled 等表达否定状态的字段，
     * 避免把无关布尔字段改坏导致备份应用进入不一致状态
     */
    private static void keepDfsConnected(Class<?> serviceClass, Object instance) throws Exception {
        // 遍历类层级，父类声明的连接状态字段同样需要处理（NEW-L-23）
        var current = serviceClass;
        while (current != null && current != Object.class) {
            for (var field : current.getDeclaredFields()) {
                field.setAccessible(true);
                var name = field.getName();
                if (field.getType() == Boolean.TYPE && !isNegativeStateField(name)) {
                    field.setBoolean(instance, true);
                } else if (field.getType() == Integer.TYPE && ("f1750e".equals(name) || "mConnectType".equals(name))) {
                    field.setInt(instance, 0);
                }
            }
            current = current.getSuperclass();
        }
    }

    /** 判断字段名是否表达销毁/暂停/取消等否定状态，这些字段不应被强制置 true */
    private static boolean isNegativeStateField(String name) {
        if (name == null || name.isEmpty()) return false;
        var lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("destroy")
                || lower.contains("paus")
                || lower.contains("cancel")
                || lower.contains("abort")
                || lower.contains("stop")           // 涵盖 stop/stopp（NEW-M-03）
                || lower.contains("disconnect")      // 断开连接状态（NEW-M-04）
                || lower.contains("finish")
                || lower.contains("exit")
                || lower.contains("error")
                || lower.contains("fail")
                || lower.contains("close")
                || lower.contains("suspend")
                || lower.contains("quited")
                || lower.contains("interrupt");
    }

    /**
     * 旧版备份会直接读取DistFileClientService.mDistFileClient，这里补齐SDK自己的客户端实例
     */
    private static void ensureDistFileClient(Class<?> serviceClass, Object instance, String deviceId, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var field = findFieldByTypeName(serviceClass, "com.xiaomi.dist.file.client.kit.manager.IDistFileClient");
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        if (field.get(instance) != null) {
            return;
        }
        var implClass = HookCompat.findClassAny(lpparam.classLoader, "AIDLHook", "DFS 客户端实现",
                "com.xiaomi.dist.file.client.kit.manager.DistFileClientImpl");
        if (implClass == null) {
            return;
        }
        var constructor = implClass.getConstructor(String.class);
        field.set(instance, constructor.newInstance(deviceId));
    }

    /**
     * 旧版恢复列表会同步检查mTempPath，这里直接补齐它要解析的本地临时目录。
     * 注意：字段名随 MIUI 版本可能混淆（实证为 "f"），按候选名回退查找，全找不到必须告警（否则恢复列表为空且无日志）
     */
    private static void ensureDfsTempPath(Class<?> serviceClass, Object instance, String deviceId) throws Exception {
        var field = findFieldByNames(serviceClass, "mTempPath", "f");
        if (field == null) {
            logError("ensureDfsTempPath: mTempPath 字段未找到（版本混淆变更？）", new IllegalStateException(
                    "DistFileClientService fields=" + java.util.Arrays.toString(serviceClass.getDeclaredFields())));
            return;
        }
        field.setAccessible(true);
        var tempPath = LocalBackupFileHelp.TEMP_BACKUP_ROOT + deviceId;
        field.set(instance, tempPath);
        var tempDir = new File(tempPath);
        // File.mkdirs() 对已存在目录返回 false（恢复页每次打开都会走到这里）：
        // 目录已存在时直接跳过，仅"不存在且创建失败"才记错误（避免每次进恢复页刷一条 ERROR）
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            logError("create restore temp dir failed", new IllegalStateException(tempPath));
        }
    }

    /**
     * 旧版恢复列表会同步检查mDeviceInfo，这里补齐共享路径信息避免页面直接返回空列表
     */
    private static void ensureDfsDeviceInfo(Class<?> serviceClass, Object instance, XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        var field = findFieldByTypeName(serviceClass, "com.xiaomi.dist.file.client.common.model.PathInfo");
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        if (field.get(instance) == null) {
            field.set(instance, createPathInfo(lpparam));
        }
    }

    /** ensureRestoreDescriptors 节流间隔：小米 App 打开恢复页会反复触发 getDeviceList/设备状态，
     * 每次都全量 listAndDownloadXml（19 个备份目录 × findEntry 全量 list）会引发 list 请求风暴
     * 并挤占上传连接（实测 60-140 list/s，上传分片被拖 14-34s 甚至卡死）。10s 内只执行一次。 */
    private static final long RESTORE_DESCRIPT_THROTTLE_MS = 10_000L;
    private static volatile long sLastRestoreDescriptorsAt = 0L;
    private static final Object RESTORE_DESCRIPT_LOCK = new Object();

    /**
     * 把云端descript.xml预下载到备份应用自己的临时目录，恢复列表仍走原生BackupDescriptor解析。
     * 字段名兼容混淆（"f"）；mTempPath 未设置时用模块默认临时目录兜底
     */
    private static void ensureRestoreDescriptors(Class<?> serviceClass, Object instance) throws Exception {
        // 节流 + 串行：App 可能在 1 秒内多次触发（getDeviceList/state/connect 各一次），
        // 全量下载耗时长，并发跑会互相放大 list 风暴
        synchronized (RESTORE_DESCRIPT_LOCK) {
            var now = System.currentTimeMillis();
            if (now - sLastRestoreDescriptorsAt < RESTORE_DESCRIPT_THROTTLE_MS) {
                return;
            }
            sLastRestoreDescriptorsAt = now;
        }
        var field = findFieldByNames(serviceClass, "mTempPath", "f");
        String tempPath = null;
        if (field != null) {
            field.setAccessible(true);
            var v = field.get(instance);
            if (v instanceof String && !((String) v).isEmpty()) {
                tempPath = (String) v;
            }
        }
        if (tempPath == null || tempPath.isEmpty()) {
            tempPath = LocalBackupFileHelp.TEMP_BACKUP_ROOT + getMockDeviceId();
            var dir = new File(tempPath);
            if (!dir.exists() && !dir.mkdirs()) {
                logError("create restore temp dir failed", new IllegalStateException(tempPath));
            }
        }
        var result = CloudFileHelp.listAndDownloadXml(tempPath);
        if (result != null && result.startsWith("ERROR:")) {
            throw new IllegalStateException(result);
        }
    }

    /**
     * 按字段类型名查找字段，兼容旧版明文字段名和新版混淆字段名
     */
    private static java.lang.reflect.Field findFieldByTypeName(Class<?> clazz, String typeName) {
        var current = clazz;
        while (current != null) {
            for (var field : current.getDeclaredFields()) {
                if (typeName.equals(field.getType().getName())) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 按字段名查找字段，只用于旧版公开明文字段
     */
    private static java.lang.reflect.Field findFieldByName(Class<?> clazz, String name) {
        var current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** 按候选名逐个查找字段（兼容明文名与混淆名，如 mTempPath / f） */
    private static java.lang.reflect.Field findFieldByNames(Class<?> clazz, String... names) {
        for (var name : names) {
            var f = findFieldByName(clazz, name);
            if (f != null) return f;
        }
        return null;
    }

    /**
     * 同时兼容新版混淆单例n()和旧版可读getInstance()
     */
    private static Object getDfsServiceInstance(Class<?> serviceClass) throws Exception {
        for (var methodName : new String[]{"n", "getInstance"}) {
            try {
                var method = serviceClass.getDeclaredMethod(methodName, new Class[0]);
                method.setAccessible(true);
                return method.invoke(null, new Object[0]);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /**
     * 设置备份应用内部DFS服务持有的设备ID
     */
    private static void setDfsDeviceId(Class<?> serviceClass, Object instance, String deviceId) throws Exception {
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("deviceId is empty");
        }
        for (var methodName : new String[]{"I", "setDeviceId"}) {
            try {
                var method = serviceClass.getDeclaredMethod(methodName, String.class);
                method.setAccessible(true);
                method.invoke(instance, deviceId);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (var field : serviceClass.getDeclaredFields()) {
            if (field.getType() == String.class) {
                field.setAccessible(true);
                if (field.get(instance) == null) {
                    field.set(instance, deviceId);
                    return;
                }
            }
        }
        throw new NoSuchMethodException("No DistFileClientService deviceId setter found");
    }

    /**
     * 启动小米自己的DFS初始化流程，让它正常创建IDistFileClient和PathInfo
     */
    private static void startDfsInit(Class<?> serviceClass, Object instance) throws Exception {
        for (var methodName : new String[]{"r", "initDistFileClientService"}) {
            try {
                var method = serviceClass.getDeclaredMethod(methodName, new Class[0]);
                method.setAccessible(true);
                method.invoke(instance, new Object[0]);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("No DistFileClientService init method found");
    }

    /**
     * 读取当前配置里的模拟设备ID
     */
    private static String getMockDeviceId() {
        return ConfigHelp.getString("device_id", "miback");
    }

    /**
     * 标记按类简单名匹配参数类型
     */
    private static String byName(String simpleName) {
        return simpleName;
    }

    /**
     * 比较方法参数签名，支持Class精确匹配和简单类名后缀匹配
     */
    private static boolean matches(Method method, Object... types) {
        var params = method.getParameterTypes();
        if (params.length != types.length) {
            return false;
        }
        for (var i = 0; i < params.length; i++) {
            var expected = types[i];
            if (expected instanceof String) {
                if (!params[i].getName().endsWith("." + expected)) {
                    return false;
                }
            } else if ((expected instanceof Class) && params[i] != expected) {
                return false;
            }
        }
        return true;
    }

    /**
     * 生成方法参数签名文本，用于异常日志定位未覆盖的AIDL方法
     */
    private static String signatureOf(Method method) {
        var sb = new StringBuilder("(");
        var params = method.getParameterTypes();
        for (var i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    /**
     * 调用DFS连接监听器，兼容新旧方法名和不同参数数量
     */
    private void invokeConnection(Object listener, String deviceId, int code, boolean success) {
        var methodNames = success ? new String[]{"D1", "onSuccess"} : new String[]{"o1", "X", "onFailed"};
        for (var methodName : methodNames) {
            try {
                var method = listener.getClass().getMethod(methodName, String.class, Integer.TYPE);
                method.invoke(listener, deviceId, Integer.valueOf(code));
                return;
            } catch (Exception e) {
                try {
                    var method2 = listener.getClass().getMethod(methodName, String.class);
                    method2.invoke(listener, deviceId);
                    return;
                } catch (Exception e2) {
                    try {
                        var method3 = listener.getClass().getMethod(methodName, new Class[0]);
                        method3.invoke(listener, new Object[0]);
                        return;
                    } catch (Exception e3) {
                    }
                }
            }
        }
        LogHelp.e(TAG, "invokeConnection failed: no callback method matched, success=" + success);
    }

    /**
     * 调用IDeviceStateListener在线回调，兼容旧版可读名和新版混淆名
     */
    private void invokeDeviceState(Object listener, String methodName, Parcelable deviceInfo) {
        try {
            for (var method : listener.getClass().getMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == 1) {
                    method.invoke(listener, deviceInfo);
                    return;
                }
            }
            for (var method2 : listener.getClass().getMethods()) {
                if (method2.getParameterCount() == 1 && method2.getParameterTypes()[0].getName().endsWith(".DeviceInfo") && method2.getReturnType() == Void.TYPE) {
                    method2.invoke(listener, deviceInfo);
                    return;
                }
            }
            LogHelp.e(TAG, "invokeDeviceState failed: no callback method matched");
        } catch (Exception e) {
            logError("invokeDeviceState failed", e);
        }
    }

    /**
     * 调用ResultReceiver.send返回AIDL异步结果
     */
    private void invokeReceiverSend(Object receiver, int resultCode, Bundle bundle) {
        if (receiver == null) {
            return;
        }
        try {
            var sendMethod = receiver.getClass().getMethod("send", Integer.TYPE, Bundle.class);
            sendMethod.invoke(receiver, Integer.valueOf(resultCode), bundle);
        } catch (Exception e) {
            logError("invokeReceiverSend failed", e);
        }
    }

    /**
     * 先按可读名调用进度回调，再按签名匹配混淆版本
     */
    private static void invokeProgress(Object obj, String method, Class<?>[] types, Object... args) {
        if (obj == null) {
            return;
        }
        args = ProgressCallbackHelp.sanitizeStringArgs(types, args);
        try {
            obj.getClass().getMethod(method, types).invoke(obj, args);
        } catch (Exception e) {
            try {
                for (var m : obj.getClass().getMethods()) {
                    if (m.getParameterCount() == types.length) {
                        var match = true;
                        var i = 0;
                        while (true) {
                            if (i >= types.length) {
                                break;
                            }
                            if (!m.getParameterTypes()[i].equals(types[i])) {
                                match = false;
                                break;
                            }
                            i++;
                        }
                        if (match && m.getReturnType().equals(Void.TYPE)) {
                            m.invoke(obj, args);
                            return;
                        }
                    }
                }
            } catch (Exception e2) {
                logError("invokeProgress fallback failed", e2);
            }
        }
    }

    /**
     * 通知单个文件传输完成或失败
     */
    private static void notifyProgressFinish(Object listener, String taskId, int code, String msg) {
        invokeProgress(listener, "l0", new Class[]{String.class, Integer.TYPE, String.class},
            ProgressCallbackHelp.safeString(taskId), Integer.valueOf(code), ProgressCallbackHelp.safeString(msg));
    }

    /**
     * 安静关闭ParcelFileDescriptor，失败时只记录异常
     */
    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            if (pfd != null) {
                pfd.close();
            }
        } catch (Exception e) {
            logError("close ParcelFileDescriptor failed", e);
        }
    }

    /**
     * 统一记录异常日志；正常路径不输出日志，避免刷屏
     */
    private static void logError(String message, Throwable e) {
        LogHelp.e(TAG, message + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
    }
}
