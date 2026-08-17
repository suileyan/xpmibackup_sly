package com.suileyan.xpmibackup.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import com.suileyan.comm.LogHelp;
import java.lang.reflect.Method;

/**
 * Hook 跨版本兼容工具（Android 9~17 / MIUI 10~HyperOS 3.x 适配，HIGH-25）
 *
 * 系统化「多候选 fallback 链」：小米备份 App（com.miui.backup）在不同系统版本上
 * 存在三类结构漂移——① 类名混淆/匿名内部类序号变化；② 业务方法名混淆变化；
 * ③ 静态工具方法（如 NotificationUtils）混淆名变化。本项目过往做法是各 Hook 点
 * 手写 if-exists 守卫与候选名（如 NASBackupDataCenter.getInstance/i、字段 mTempPath/f），
 * 分散且难以统一审计。本类把这些模式收敛为四个稳定入口：
 *
 * - findClassAny：按候选类名顺序加载，全失败返回 null（调用方降级）
 * - hookMethodAny：对同一 Hook 逻辑尝试多个候选方法名，首个命中即生效
 * - findMethodByParams：按参数类型特征定位方法（抗混淆，不依赖方法名）
 * - callStaticAny：按候选方法名顺序调用静态方法
 *
 * 设计约束：
 * 1. 所有入口不抛异常——候选全失败时记诊断日志并返回失败标志，由调用方决定降级行为，
 *    绝不让单个版本的结构差异拖垮整条 Hook 链（原 findAndHookMethod 失败即抛）。
 * 2. 方法名候选必须按「明文稳定名 → 已知混淆名 → 特征匹配」顺序。
 * 3. 命中/失败均有结构化日志（tag="XpMiBackup"，消息带调用方标识与候选清单），
 *    供真机版本矩阵收集（见 docs/ANDROID-VERSION-MATRIX.md）。
 * 4. findMethodByParams 返回的 Method 未 setAccessible，调用方按需自行设置。
 */
public final class HookCompat {

    private static final String TAG = "XpMiBackup";

    private HookCompat() {
    }

    /**
     * 多候选类查找：按序返回第一个可加载的 Class。
     *
     * @param cl   目标进程 ClassLoader（lpparam.classLoader）
     * @param tag  调用方标识（如 "BackupHook.NotificationUtils"），用于诊断日志
     * @param desc 语义描述（如 "通知工具类"），用于诊断日志
     * @param names 候选类名，按「已知版本 → 可能漂移版本」顺序
     * @return 命中的 Class；全部失败返回 null（不抛异常）
     */
    public static Class<?> findClassAny(ClassLoader cl, String tag, String desc, String... names) {
        for (var name : names) {
            try {
                var c = XposedHelpers.findClass(name, cl);
                LogHelp.i(TAG, tag + ": " + desc + " 命中 " + name);
                return c;
            } catch (Throwable ignored) {
                // 继续尝试下一个候选
            }
        }
        LogHelp.w(TAG, tag + ": " + desc + " 所有候选类名均未找到（版本结构漂移？） candidates="
                + java.util.Arrays.toString(names));
        return null;
    }

    /**
     * 多候选方法 Hook：对同一 Hook 逻辑按候选方法名顺序尝试 findAndHookMethod，
     * 首个签名匹配成功即生效并停止。
     *
     * @param clazz       目标类（通常来自 findClassAny）
     * @param tag         调用方标识
     * @param desc        语义描述
     * @param hook        Hook 回调（所有候选方法共用）
     * @param paramTypes  方法参数类型（各候选同名方法的参数必须一致）
     * @param methodNames 候选方法名，按「明文稳定名 → 已知混淆名」顺序
     * @return true 命中并已 Hook；false 全部失败（调用方按能力降级）
     */
    public static boolean hookMethodAny(Class<?> clazz, String tag, String desc, XC_MethodHook hook,
                                        Class<?>[] paramTypes, String... methodNames) {
        if (clazz == null) {
            return false;
        }
        for (var name : methodNames) {
            try {
                XposedHelpers.findAndHookMethod(clazz, name, paramTypes, hook);
                LogHelp.i(TAG, tag + ": " + desc + " 方法命中 " + clazz.getName() + "." + name);
                return true;
            } catch (Throwable ignored) {
                // 继续尝试下一个候选方法名
            }
        }
        LogHelp.w(TAG, tag + ": " + desc + " 所有候选方法名均未命中 " + clazz.getName()
                + " candidates=" + java.util.Arrays.toString(methodNames)
                + " params=" + java.util.Arrays.toString(paramTypes));
        return false;
    }

    /**
     * 按参数类型特征定位方法（抗混淆）：遍历目标类全部方法（含父类），
     * 返回第一个参数类型完全匹配的方法。不依赖方法名——混淆后方法名随机，
     * 但参数类型（如 Context.class, Class.class, int.class）与返回类型保持不变。
     *
     * @param clazz      目标类
     * @param returnType 期望返回类型；不关心传 null
     * @param paramTypes 期望参数类型
     * @return 匹配的 Method；无匹配返回 null
     */
    public static Method findMethodByParams(Class<?> clazz, Class<?> returnType, Class<?>... paramTypes) {
        if (clazz == null) {
            return null;
        }
        for (var current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            for (var m : current.getDeclaredMethods()) {
                var pts = m.getParameterTypes();
                if (pts.length != paramTypes.length) {
                    continue;
                }
                var matched = true;
                for (var i = 0; i < pts.length; i++) {
                    if (pts[i] != paramTypes[i]) {
                        matched = false;
                        break;
                    }
                }
                if (matched && (returnType == null || returnType.isAssignableFrom(m.getReturnType()))) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * 多候选静态方法调用：按候选方法名顺序调用静态方法，首个存在即调用并返回结果。
     * 用于混淆静态工具方法（如 NotificationUtils.d/e、NASBackupDataCenter.getInstance/i）。
     *
     * @param clazz       目标类
     * @param tag         调用方标识
     * @param desc        语义描述
     * @param paramTypes  方法参数类型（用于校验候选是否真的存在）
     * @param args        实际调用参数
     * @param methodNames 候选方法名
     * @return 调用结果；全部失败返回 null（不抛）
     */
    public static Object callStaticAny(Class<?> clazz, String tag, String desc,
                                       Class<?>[] paramTypes, Object[] args, String... methodNames) {
        if (clazz == null) {
            return null;
        }
        for (var name : methodNames) {
            try {
                var m = XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) paramTypes);
                if (m == null) {
                    continue;
                }
                var result = XposedHelpers.callStaticMethod(clazz, name, args);
                LogHelp.i(TAG, tag + ": " + desc + " 静态方法命中 " + clazz.getName() + "." + name);
                return result;
            } catch (Throwable ignored) {
                // 候选存在但调用失败，继续下一个候选
            }
        }
        LogHelp.w(TAG, tag + ": " + desc + " 静态方法调用失败 candidates="
                + java.util.Arrays.toString(methodNames)
                + " params=" + java.util.Arrays.toString(paramTypes));
        return null;
    }
}
