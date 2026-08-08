# Xposed 模块入口：xposed_init 通过类名字符串加载，必须保留原名（HIGH-20）
-keep class com.suileyan.xpmibackup.XposedEntry { *; }

# Hook 类：通过反射按类名/方法名查找，禁止混淆
-keep class com.suileyan.xpmibackup.hook.** { *; }

# 主界面 Activity：被设置 APP 以组件名 Intent 启动
-keep class com.suileyan.xpmibackup.MainActivity { *; }

# 云盘 Provider/存储类：被反射调用或跨类加载器传递
-keep class com.suileyan.cloud.** { *; }
-keep class com.suileyan.comm.** { *; }

# 保留第三方库的默认规则（由 AGP 内置规则处理，这里仅防止 Rhino/OkHttp 被过度裁剪）
-dontwarn org.mozilla.javascript.**
-dontwarn okhttp3.**
-dontwarn okio.**
