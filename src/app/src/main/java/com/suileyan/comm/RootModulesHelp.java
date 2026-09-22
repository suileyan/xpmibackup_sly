package com.suileyan.comm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root 模块备份（Magisk/KernelSU/APatch）纯逻辑工具——不依赖 Android 框架，可桌面 JVM 单测。
 *
 * 职责：su 探测/打包 /data/adb 下的模块目录为 tar、tar 清理、生成宿主端
 * files_for_backup 通道所需的文件清单 JSON。
 * Xposed 侧（RootModulesHook）只做注入；本类不碰任何 android.* 类。
 *
 * 通道原理（见 docs/ROOT-MODULES-BACKUP-DESIGN.md）：
 * com.miui.backup（android.uid.backup，非 root）读不了 /data/adb，
 * 由模块 App 授 root 后 su tar 打包到 /sdcard/MIUI/backup/Transfer/，
 * 宿主 hook 把该 tar 以 files_for_backup_<FEATURE_ID> 清单并入备份。
 */
public final class RootModulesHelp {

    private static final String TAG = "XpMiBackup";

    /** 宿主备份列表中本条目的 feature/type id（避开引擎已用的 0-13） */
    public static final int FEATURE_ID = 90;
    public static final String TAR_PREFIX = "root_modules_";
    public static final String TAR_SUFFIX = ".tar";

    /** 候选源目录：三家管理器共用的 modules 与各自的私有目录，存在才打包 */
    public static final String[] SOURCE_PATHS = {
            "/data/adb/modules", "/data/adb/modules_update", "/data/adb/ksu", "/data/adb/ap"
    };

    /** 管理器包名 → 显示名（APatch/KSU 系/Magisk 系；SukiSU 是 KSU 分支但包名不同） */
    public static final String[][] MANAGER_PACKAGES = {
            {"com.sukisu.ultra", "SukiSU"},
            {"com.sukisu.kpm", "SukiSU"},
            {"me.weishu.kernelsu", "KernelSU"},
            {"me.bmax.apatch", "APatch"},
            {"io.github.huskydg.magisk", "Magisk"},
            {"com.topjohnwu.magisk", "Magisk"},
    };

    /** 管理器名单标记文件（与 tar 同目录，宿主 hook 读取后拼「XX 模块备份」标题） */
    public static final String MANAGER_MARKER = "root_modules_manager.txt";

    /** su 命令超时：模块目录通常 <500MB，纯 tar 秒级；给足余量防大模块卡死 */
    private static final long PROBE_TIMEOUT_MS = 30_000;
    private static final long TAR_TIMEOUT_MS = 600_000;

    private RootModulesHelp() {
    }

    /** 备份通道文件清单 key：宿主 SP com.miui.backup.PREF_LIST_FILES 下的键名 */
    /** 路径规范化：/sdcard/ → /storage/emulated/0/。引擎 ArchiveHelper 用
     *  startsWith(Environment.getExternalStorageDirectory()) 做归档路径白名单校验，
     *  /sdcard 字面前缀会被判 "bad path"（真机实测），注入给宿主的路径必须用规范形式。 */
    public static String canon(String path) {
        if (path != null && path.startsWith("/sdcard/")) {
            return "/storage/emulated/0/" + path.substring("/sdcard/".length());
        }
        return path;
    }

    public static String spKey() {        return "files_for_backup_" + FEATURE_ID;
    }

    /**
     * 生成 files_for_backup 清单 JSON。格式对齐引擎写入侧（TransItemSizeGetter n() L642-647）：
     * 元素是「内嵌 JSON 对象文本的字符串」——扫描侧 new JSONObject((String) it.next())
     * 强转 String 后解析 path/size；若放裸对象元素，Gson 反序列化为 LinkedTreeMap，
     * 强转直接 ClassCastException 闪退（真机实测）。
     * 另：引擎只收录 file.exists() && isFile() && length>0 的文件。
     */
    public static String buildFileListJson(String tarAbsolutePath, long size) {
        var arr = new JSONArray();
        try {
            if (size > 0 && new File(tarAbsolutePath).isFile()) {
                var entry = new JSONObject();
                entry.put("path", tarAbsolutePath);
                entry.put("size", size);
                arr.put(entry.toString());
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    /** 取 Transfer 目录中最新的 root_modules_*.tar；不存在返回 null */
    public static File newestTar(String transferDir) {
        var dir = new File(transferDir);
        File best = null;
        var files = dir.listFiles();
        if (files == null) return null;
        for (var f : files) {
            if (isTar(f) && (best == null || f.lastModified() > best.lastModified())) {
                best = f;
            }
        }
        return best;
    }

    /** 仅保留最新 keep 个 tar，更旧的删除（防 /sdcard 膨胀）；返回删除个数 */
    public static int pruneOldTars(String transferDir, int keep) {
        var dir = new File(transferDir);
        var files = dir.listFiles();
        if (files == null) return 0;
        List<File> tars = new ArrayList<>();
        for (var f : files) {
            if (isTar(f)) tars.add(f);
        }
        tars.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        var removed = 0;
        for (var i = keep; i < tars.size(); i++) {
            if (tars.get(i).delete()) removed++;
        }
        return removed;
    }

    private static boolean isTar(File f) {
        var name = f.getName();
        return f.isFile() && name.startsWith(TAR_PREFIX) && name.endsWith(TAR_SUFFIX);
    }

    // ---------- 二期：恢复（快照解包回 /data/adb） ----------

    /** 恢复前快照文件前缀（自动保留最近 1 份，供回滚） */
    public static final String PRE_RESTORE_PREFIX = "pre_restore_";

    /** 模块快照专用目录：独立于 Transfer/（引擎备份/恢复流程会清理该目录，
     *  真机实测快照被误删），RootModules/ 引擎不感知，只有我们读写。 */
    public static String modulesDir() {
        return com.suileyan.comm.ConfigHelp.BACKUP_ROOT + "/RootModules";
    }

    /** 解包白名单：tar 条目必须落在这些前缀内（防路径穿越/写任意位置） */
    public static final String[] RESTORE_ALLOW_PREFIXES = {
            "data/adb/modules", "data/adb/modules_update", "data/adb/ksu", "data/adb/ap"
    };

    /** Transfer 目录下的全部快照，按时间倒序（最新在前） */
    public static List<File> listTars(String transferDir) {
        var dir = new File(transferDir);
        var files = dir.listFiles();
        List<File> tars = new ArrayList<>();
        if (files != null) {
            for (var f : files) {
                if (isTar(f)) tars.add(f);
            }
        }
        tars.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return tars;
    }

    /** 最新快照的"指纹"（名字+mtime）：用于检测原生恢复是否把 tar 还原回设备。
     *  目录为空返回空串。 */
    public static String newestTarStamp(String transferDir) {
        var tars = listTars(transferDir);
        if (tars.isEmpty()) return "";
        var t = tars.get(0);
        return t.getName() + ":" + t.lastModified();
    }

    /** Root 可用性检测：su -c id 输出含 uid=0。
     *  首次执行会触发管理器授权弹窗，超时给足 10s 等用户点授权。 */
    public static boolean hasSu() {
        var r = runSu("id", 10_000L);
        return r.error == null && r.output != null && r.output.contains("uid=0");
    }

    /** 条目白名单校验：全部合法返回 null；否则返回首个非法条目。
     *  规则（按 toybox tar 语义）：条目统一剥掉前导 / 后必须落在 data/adb 白名单内；
     *  拒绝 ".."、相对路径、空条目以外的任何越界路径。 */
    public static String validateEntries(List<String> entries) {
        for (var raw : entries) {
            if (raw == null) continue;
            var e = raw.trim();
            if (e.isEmpty()) continue;
            if (e.startsWith("/")) e = e.substring(1);
            if (e.contains("..")) return raw;
            boolean ok = false;
            for (var prefix : RESTORE_ALLOW_PREFIXES) {
                if (e.equals(prefix) || e.startsWith(prefix + "/")) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return raw;
        }
        return null;
    }

    /**
     * POSIX shell 单引号转义（MED-17）：
     * runSu 用 `su -c <command>` 执行，命令串仍会被 shell 解析——路径含空格 / ; / $ / 反引号时
     * 不加引号会被拆成多个参数甚至直接命令注入。单引号包裹并把内嵌单引号转义为 '\''。
     */
    static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 用空格连接多个路径，逐项转义 */
    private static String quoteJoin(List<String> paths) {
        var sb = new StringBuilder();
        for (var p : paths) {
            if (p == null || p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(shellQuote(p));
        }
        return sb.toString();
    }

    public static String buildListCommand(String tarPath) {
        return "tar -tf " + shellQuote(tarPath);
    }

    public static String buildExtractCommand(String tarPath) {
        return "tar -xf " + shellQuote(tarPath) + " -C /";
    }

    /** 恢复后修正 SELinux 上下文（toybox restorecon；不存在时由调用方忽略失败） */
    public static String buildRestoreconCommand() {
        return "restorecon -R /data/adb/modules /data/adb/modules_update"
                + " /data/adb/ksu /data/adb/ap";
    }

    /** 恢复前快照保留数量 */
    public static int pruneSnapshots(String transferDir, int keep) {
        var dir = new File(transferDir);
        var files = dir.listFiles();
        if (files == null) return 0;
        List<File> snaps = new ArrayList<>();
        for (var f : files) {
            var name = f.getName();
            if (f.isFile() && name.startsWith(PRE_RESTORE_PREFIX) && name.endsWith(TAR_SUFFIX)) {
                snaps.add(f);
            }
        }
        snaps.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        var removed = 0;
        for (var i = keep; i < snaps.size(); i++) {
            if (snaps.get(i).delete()) removed++;
        }
        return removed;
    }

    /** 统一保留策略（防 RootModules/ 无限膨胀，每个 tar 约 129MB）：
     *  业务快照（root_modules_*，含 restored_ 变体）保留最新 2 份；
     *  恢复前回滚快照（pre_restore_*）保留最新 1 份。
     *  所有写入点（备份/抢救/恢复）与 App 启动兜底均调用。 */
    public static int pruneAll(String transferDir) {
        return pruneOldTars(transferDir, 2) + pruneSnapshots(transferDir, 1);
    }

    /** 目录内是否已存在同字节大小的 tar（近似去重：129MB 快照逐一比对内容不现实，
     *  同大小即视为同一模块状态的快照，不再重复落盘）。 */
    public static boolean hasSameSizeTar(String transferDir, long size) {
        var dir = new File(transferDir);
        var files = dir.listFiles();
        if (files == null) return false;
        for (var f : files) {
            if (f.isFile() && f.getName().endsWith(TAR_SUFFIX) && f.length() == size) {
                return true;
            }
        }
        return false;
    }

    /**
     * 恢复流程（模块 App 内执行，需已授予 su）：
     * 校验快照条目白名单 → 快照当前 /data/adb 状态（回滚点）→ 解包 → restorecon。
     *
     * @param tarAbsolutePath 待恢复的快照（须位于 transferDir 内）
     * @return null=成功；否则失败原因（可直接 Toast）
     */
    public static String restoreViaSu(String tarAbsolutePath, String transferDir) {
        if (tarAbsolutePath == null
                || !new File(tarAbsolutePath).getPath().startsWith(new File(transferDir).getPath())) {
            return "非法快照路径";
        }
        // 1) 列出条目并校验白名单（防路径穿越）
        var list = runSu(buildListCommand(tarAbsolutePath), TAR_TIMEOUT_MS);
        if (list.output == null || list.output.isEmpty()) {
            LogHelp.w(TAG, "restore: 条目读取失败 " + list.error);
            return "无法读取快照内容: " + (list.error != null ? list.error : "空");
        }
        List<String> entries = new ArrayList<>();
        for (var line : list.output.split("\n")) {
            if (!line.trim().isEmpty()) entries.add(line.trim());
        }
        if (entries.isEmpty()) {
            return "快照为空";
        }
        var invalid = validateEntries(entries);
        if (invalid != null) {
            LogHelp.w(TAG, "restore: 非法条目 " + invalid);
            return "快照包含不允许的路径: " + invalid;
        }
        // 2) 快照当前 /data/adb 状态（回滚点；keep=1）
        var probe = runSu(buildProbeCommand(), PROBE_TIMEOUT_MS);
        List<String> existing = new ArrayList<>();
        if (probe.output != null) {
            for (var line : probe.output.split("\n")) {
                var p = line.trim();
                if (p.startsWith("/data/adb/")) existing.add(p);
            }
        }
        if (!existing.isEmpty()) {
            var snap = new File(transferDir, PRE_RESTORE_PREFIX + timeStamp() + TAR_SUFFIX);
            var snapRun = runSu(buildTarCommand(snap.getAbsolutePath(), existing), TAR_TIMEOUT_MS);
            if (snapRun.error != null) {
                LogHelp.w(TAG, "restore: 当前状态快照失败（继续恢复）" + snapRun.error);
            } else {
                pruneSnapshots(transferDir, 1);
                LogHelp.i(TAG, "restore: 当前状态已快照 " + snap.getName());
            }
        }
        // 3) 解包（tar 内为 /data/adb 绝对路径，-C / 落位）
        var ex = runSu(buildExtractCommand(tarAbsolutePath), TAR_TIMEOUT_MS);
        if (ex.error != null) {
            LogHelp.w(TAG, "restore: 解包失败 " + ex.error);
            return "解包失败: " + ex.error;
        }
        // 4) restorecon 修正上下文（best-effort）
        runSu(buildRestoreconCommand(), PROBE_TIMEOUT_MS);
        LogHelp.i(TAG, "restore: 完成 tar=" + new File(tarAbsolutePath).getName()
                + " entries=" + entries.size());
        // 5) 成功后清理快照：解包完成后 tar 已无用处（回滚靠 pre_restore_*.tar，
        //    备份集内还有一份在目标端），不留 129MB 垃圾
        if (new File(tarAbsolutePath).delete()) {
            LogHelp.i(TAG, "restore: 已清理已恢复的快照");
        }
        pruneAll(transferDir);
        return null;
    }


    /**
     * 探测命令：列出实际存在的源目录（无 root 无法 stat /data/adb，只能在 su shell 里判）。
     * 输出每行一个存在的路径。
     */
    public static String buildProbeCommand() {
        return "ls -d " + quoteJoin(java.util.Arrays.asList(SOURCE_PATHS)) + " 2>/dev/null";
    }

    /** 打包命令：仅包含探测到的现存目录（路径逐项转义，MED-17） */
    public static String buildTarCommand(String outTarPath, List<String> existingPaths) {
        // chmod 644：su(FUSE 归因 owner=本应用 uid) 打包后默认 660，宿主 com.miui.backup
        // (uid 6100) 无读权 → ArchiveHelper "Not exist or bad path"（真机实测）
        return "tar -cf " + shellQuote(outTarPath) + " " + quoteJoin(existingPaths)
                + " && chmod 644 " + shellQuote(outTarPath);
    }

    /**
     * 推断当前管理器显示名：先按已安装的管理器包名（最准，可区分 SukiSU/KernelSU），
     * 再按 su 探测到的目录特征（ap=APatch、ksu=KSU 系、magisk=Magisk）。
     * 全部未知返回 "Root"。
     */
    public static String managerName(List<String> existingSuPaths, List<String> installedPackages) {
        for (var pkg : installedPackages) {
            for (var pair : MANAGER_PACKAGES) {
                if (pair[0].equals(pkg)) return pair[1];
            }
        }
        var hasAp = false;
        var hasKsu = false;
        var hasMagisk = false;
        var hasModules = false;
        for (var p : existingSuPaths) {
            if (p.equals("/data/adb/ap")) hasAp = true;
            else if (p.equals("/data/adb/ksu")) hasKsu = true;
            else if (p.equals("/data/adb/modules")) hasModules = true;
            else if (p.startsWith("/data/adb/magisk")) hasMagisk = true;
        }
        if (hasAp) return "APatch";
        if (hasKsu) return "KernelSU";
        // 纯 Magisk 没有私有目录，只有共用 modules；KSU/APatch 各有 ksu/ap 目录已先命中
        if (hasModules || hasMagisk) return "Magisk";
        return "Root";
    }

    /** 标题：管理器名 + 模块备份（zh/en） */
    public static String buildTitle(String managerName, boolean zh) {
        return zh ? managerName + " 模块备份" : managerName + " modules";
    }

    /** 写管理器标记（与 tar 同目录），格式 "显示名|包名"；宿主 hook 读取后拼动态标题并加载管理器图标 */
    public static void writeManagerMarker(String transferDir, String managerName, String managerPkg) {
        try {
            var f = new File(transferDir, MANAGER_MARKER);
            var payload = managerName + (managerPkg == null || managerPkg.isEmpty() ? "" : "|" + managerPkg);
            AtomicFile.write(f, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            LogHelp.w(TAG, "write manager marker failed: " + e.getMessage());
        }
    }

    /** 旧版单值标记兼容：有 | 取前段 */
    public static String readManagerName(String transferDir) {
        var raw = readManagerMarkerRaw(transferDir);
        if (raw == null) return null;
        var name = raw.contains("|") ? raw.substring(0, raw.indexOf('|')) : raw;
        return name.isEmpty() ? null : name;
    }

    /** 管理器包名（标记 | 后段）；缺失返回 null */
    public static String readManagerPackage(String transferDir) {
        var raw = readManagerMarkerRaw(transferDir);
        if (raw == null || !raw.contains("|")) return null;
        var pkg = raw.substring(raw.indexOf('|') + 1).trim();
        return pkg.isEmpty() ? null : pkg;
    }

    private static String readManagerMarkerRaw(String transferDir) {
        try {
            var f = new File(transferDir, MANAGER_MARKER);
            if (!f.isFile()) return null;
            var content = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? null : content;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 完整打包流程（模块 App 内执行，需已授予 su）。
     *
     * @param installedManagerPackages 本 App 可见的管理器包名（可为空，改用目录特征推断）
     * @return null=成功（tar 已生成）；否则返回失败原因（简短中文，可直接 Toast）
     */
    public static String createTarViaSu(String transferDir, List<String> installedManagerPackages) {
        var dir = new File(transferDir);
        if (!dir.exists() && !dir.mkdirs()) {
            return "无法创建 " + transferDir;
        }
        pruneOldTars(transferDir, 2);

        // 1) 探测存在的源目录。
        // 注意：toybox ls 对「任一路径不存在」返回非 0（哪怕其余都在且已打印），
        // 所以判定成功与否只看有没有解析出有效路径，退出码仅记诊断日志。
        var probe = runSu(buildProbeCommand(), PROBE_TIMEOUT_MS);
        List<String> existing = new ArrayList<>();
        for (var line : probe.output.split("\n")) {
            var p = line.trim();
            if (p.isEmpty()) continue;
            if (!p.startsWith("/data/adb/")) {
                LogHelp.w(TAG, "root modules probe 异常路径: " + p);
                return "su 返回异常路径: " + p;
            }
            existing.add(p);
        }
        if (existing.isEmpty()) {
            LogHelp.w(TAG, "root modules probe failed: exit=" + probe.error + " out=" + probe.output);
            return "su 执行失败（请在 SukiSU/Magisk 中授权本应用）: "
                    + (probe.error != null ? probe.error : "未找到任何管理器目录");
        }
        LogHelp.i(TAG, "root modules probe: " + existing + (probe.error != null ? " (ls exit=" + probe.error + ")" : ""));

        // 2) 打包
        var out = new File(dir, TAR_PREFIX + timeStamp() + TAR_SUFFIX);
        var tar = runSu(buildTarCommand(out.getAbsolutePath(), existing), TAR_TIMEOUT_MS);

        // 3) 校验产物：产物有效即成功（个别 tar 实现对缺失路径返回非 0 但产物完整）
        if (!out.isFile() || out.length() == 0) {
            LogHelp.w(TAG, "root modules tar failed: exit=" + tar.error + " out=" + tar.output);
            return "tar 打包失败: " + (tar.error != null ? tar.error : "产物为空");
        }
        if (tar.error != null) {
            LogHelp.w(TAG, "root modules tar 非零退出但产物有效（继续）: " + tar.error);
        }
        var manager = managerName(existing, installedManagerPackages == null
                ? List.of() : installedManagerPackages);
        var managerPkg = "";
        if (installedManagerPackages != null) {
            for (var p : installedManagerPackages) {
                for (var pair : MANAGER_PACKAGES) {
                    if (pair[0].equals(p)) {
                        managerPkg = p;
                        break;
                    }
                }
                if (!managerPkg.isEmpty()) break;
            }
        }
        writeManagerMarker(transferDir, manager, managerPkg);
        LogHelp.i(TAG, "root modules tar OK: " + out.getName() + " " + out.length() + " bytes, sources="
                + existing + ", manager=" + manager);
        return null;
    }

    private static String timeStamp() {
        var sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }

    // ---------- su 执行 ----------

    private static final class SuResult {
        final String output;
        final String error;

        SuResult(String output, String error) {
            this.output = output;
            this.error = error;
        }
    }

    /** 执行 su -c <cmd>；独立线程消费输出防管道阻塞，超时强杀 */
    private static SuResult runSu(String command, long timeoutMs) {
        try {
            var process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            var out = new StringBuilder();
            var reader = new Thread(() -> {
                try (var in = process.getInputStream()) {
                    // readAllBytes 需 API 33，手写循环（minSdk 30）
                    var buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new SuResult("", "timeout " + timeoutMs + "ms");
            }
            reader.join(5000);
            var code = process.exitValue();
            // 关键：非零退出码也要带回输出——toybox ls 对「任一路径缺失」返回 1，
            // 但已把存在的路径打印到 stdout，调用方以解析结果为准
            var error = code == 0 ? null : ("exit=" + code + (out.length() > 0 ? " out=" + truncate(out.toString()) : ""));
            return new SuResult(out.toString(), error);
        } catch (Exception e) {
            return new SuResult("", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String truncate(String s) {
        s = s.trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
