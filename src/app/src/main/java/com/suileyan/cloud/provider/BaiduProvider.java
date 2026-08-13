package com.suileyan.cloud.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import com.suileyan.cloud.CloudAccount;
import com.suileyan.cloud.CloudException;
import com.suileyan.cloud.CloudProvider;
import com.suileyan.cloud.EncryptedCredStore;
import com.suileyan.cloud.LoginContext;
import com.suileyan.cloud.LoginState;
import com.suileyan.cloud.ProgressCallback;
import com.suileyan.cloud.RemoteEntry;
import com.suileyan.comm.LogHelp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/**
 * 百度网盘（pan.baidu.com）Provider —— xpan API（BDUSS Cookie 直连，可访问全盘）
 *
 * 认证：WebView 登录捕获完整 Cookie 串（存 "cookie" 键，含 BDUSS），直连 xpan API，app_id=250528。
 * 参考 tmp/bypy（houtianze/bypy 1.8.9）：其 PanAPI 类即 BDUSS+xpan 模式；hash 算法（slice-md5=前 256KB
 * 明文小写 MD5、crc32 无符号）采信；其 encrypt_md5 混淆为旧 PCS 私有做法，xpan 不用。
 * - 列表：GET /api/list?dir=<绝对路径>（根 "/"）
 * - 建目录：POST /api/create（type=1&isdir=1）；31061 已存在幂等忽略
 * - 上传：rapidupload 秒传（content-md5 + slice-md5 + content-crc32）→ 31079 回退
 *   precreate（4MB 块 block_list）→ superfile2 并发分片（partseq 0 起）→ create(type=2) 合并
 * - 下载：GET /api/filemetas?dlink=1 取直链 → 流式下载（Cookie/Referer/UA）
 * - 删除：POST /api/filemanager（opera=delete，filelist=[{"path":...}]）
 * - bdstoken：写端点需要，GET /api/gettemplatevariable?fields=["bdstoken"] 取一次缓存
 * - BDUSS 无刷新机制：errno -6 / HTTP 403+31045 → AUTH_EXPIRED，引导重新 WebView 登录
 */
public class BaiduProvider implements CloudProvider {

    private static final String TAG = "XpMiBackup";
    public static final String TYPE = "baidu";

    private static final String API_BASE = "https://pan.baidu.com/api/";
    /** superfile2 分片上传端点 */
    private static final String UPLOAD_URL = "https://d.pcs.baidu.com/rest/2.0/pcs/superfile2";
    private static final String APP_ID = "250528";
    private static final String REFERER = "https://pan.baidu.com/";
    private static final String ROOT_PATH = "/";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/131.0.0.0 Safari/537.36";
    /** 百度网盘客户端 UA：dlink 下载用客户端 UA 可避开网页端限速（网页 UA 实测大文件被限到 ~30KB/s） */
    private static final String NETDISK_UA =
            "netdisk;5.2.7;PC;PC-Windows;10.0.17763;WindowsBaiduYunGuanJia";

    /** 分片 4MB（xpan precreate 约定） */
    private static final int BLOCK_SIZE = 4 * 1024 * 1024;
    /** 分片并发数（百度风控，2-3 路） */
    private static final int MAX_PART = 3;
    /** HTTP 429/5xx 简单退避重试上限 */
    private static final int MAX_RETRY = 3;
    private static final int BUFFER_SIZE = 64 * 1024;

    private final CloudAccount account;
    /** bdstoken 缓存（gettemplatevariable 取一次，失效重取） */
    private volatile String bdstoken = "";

    private static OkHttpClient sClient;

    public BaiduProvider(CloudAccount account) {
        this.account = account;
    }

    @Override
    public String id() {
        return account != null ? account.id : "";
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String displayName() {
        return account != null && account.name != null && !account.name.isEmpty() ? account.name : "百度网盘";
    }

    @Override
    public boolean isLoggedIn() {
        return !cookie().isEmpty();
    }

    @Override
    public LoginState login(LoginContext ctx) {
        // 百度凭据来自 WebView 网页登录捕获 Cookie（BDUSS），无应用内登录流程
        return LoginState.NOT_SUPPORTED;
    }

    @Override
    public boolean refresh() {
        // BDUSS 无刷新机制，失效只能重新网页登录
        return false;
    }

    // ========== 目录与列表（xpan 按绝对路径） ==========

    @Override
    public boolean testConnection() throws CloudException {
        var params = new LinkedHashMap<String, String>();
        params.put("dir", ROOT_PATH);
        params.put("start", "0");
        params.put("limit", "1");
        apiGet("list", params);
        return true;
    }

    @Override
    public List<String> listDirs() throws CloudException {
        var out = new ArrayList<String>();
        for (var e : listDir(ROOT_PATH)) {
            if (e.isDir) out.add(e.name);
        }
        return out;
    }

    @Override
    public List<RemoteEntry> listEntries(String remoteDir) throws CloudException {
        var dir = resolvePath(remoteDir, false);
        if (dir == null) return new ArrayList<>();
        var out = new ArrayList<RemoteEntry>();
        for (var it : listDir(dir)) {
            out.add(new RemoteEntry(it.name, it.size, it.isDir, it.modifiedTime));
        }
        return out;
    }

    @Override
    public void mkdirs(String remoteDir) throws CloudException {
        resolvePath(remoteDir, true);
    }

    /** 列出目录下全部条目（单页 limit=1000，百度 xpan list 每页上限 1000） */
    private List<Item> listDir(String dirPath) throws CloudException {
        var params = new LinkedHashMap<String, String>();
        params.put("dir", dirPath == null || dirPath.isEmpty() ? ROOT_PATH : dirPath);
        params.put("start", "0");
        params.put("limit", "1000");
        params.put("order", "name");
        params.put("desc", "0");
        params.put("showempty", "0");
        params.put("web", "1");
        var json = apiGet("list", params);
        var arr = json.optJSONArray("list");
        var out = new ArrayList<Item>();
        if (arr != null) {
            for (var i = 0; i < arr.length(); i++) {
                var o = arr.optJSONObject(i);
                if (o != null) out.add(Item.fromJson(o));
            }
        }
        return out;
    }

    private Item findChild(String dirPath, String name) throws CloudException {
        for (var it : listDir(dirPath)) {
            if (name.equals(it.name)) return it;
        }
        return null;
    }

    private Item findEntry(String parentPath, String targetName) throws CloudException {
        var dir = resolvePath(parentPath, false);
        if (dir == null) return null;
        return findChild(dir, targetName);
    }

    /** 建目录；errno 31061（已存在）幂等忽略，返回目标绝对路径
     * 参数对齐百度网页版：a=commit + path（完整目标路径）+ type=1（目录），
     * 不传 isdir（实测多传 isdir=1 触发 errno=2 参数错误——百度 /api/create 创建目录不认该字段） */
    private String createFolder(String dirPath, String name) throws CloudException {
        var target = joinPath(dirPath, name);
        var form = new LinkedHashMap<String, String>();
        form.put("a", "commit");
        form.put("type", "1");
        form.put("path", target);
        var json = apiPost("create", form);
        // 其余非 0 errno 已在 parseApiResponse 抛出
        return target;
    }

    /** 解析路径为绝对路径（根 "/"）；createMissing=true 自动建目录；缺失返回 null */
    private String resolvePath(String path, boolean createMissing) throws CloudException {
        var v = trimSlashes(path);
        if (v.isEmpty()) return ROOT_PATH;
        var parts = v.split("/");
        var cur = ROOT_PATH;
        for (var part : parts) {
            var name = cleanName(part);
            if (name.isEmpty()) continue;
            var child = findChild(cur, name);
            if (child == null) {
                if (!createMissing) return null;
                cur = createFolder(cur, name);
                continue;
            }
            if (!child.isDir) {
                if (!createMissing) return null;
                throw new CloudException(CloudException.Kind.REMOTE, "百度路径非目录: " + name);
            }
            cur = child.path;
            if (cur == null || cur.isEmpty()) {
                throw new CloudException(CloudException.Kind.REMOTE, "百度缺少目录路径: " + name);
            }
        }
        return cur;
    }

    // ========== 上传 ==========

    @Override
    public String upload(String localPath, String remoteDir) throws CloudException {
        uploadWithProgress(localPath, null, remoteDir, "");
        return "OK: " + localPath;
    }

    /**
     * 上传：rapidupload 秒传（>256KB）→ 31079 回退 precreate/superfile2 4MB 并发分片 → create(type=2) 合并；
     * 31081/31363 整文件重传一次
     */
    @Override
    public void uploadWithProgress(String localPath, ProgressCallback cb, String remoteDir, String taskId) throws CloudException {
        var localFile = new File(localPath);
        if (!localFile.exists()) {
            throw new CloudException(CloudException.Kind.LOCAL, "file not found: " + localPath);
        }
        try {
            var remoteDirPath = resolvePath(remoteDir, true);
            if (remoteDirPath == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "百度无法解析上传目录: " + remoteDir);
            }
            if (cb != null) cb.onStart(taskId);
            var size = localFile.length();
            var targetPath = joinPath(remoteDirPath, localFile.getName());
            // 百度不允许 0 字节文件（实测 precreate size=0&block_list=[] 返回 errno=2，
            // AList/RaiDrive 文档亦记录"百度网盘不允许创建空文件"）：
            // end 等 0 字节标记文件直接 mock 成功——云端无需真实存在，恢复列表只依赖 descript.xml
            if (size == 0) {
                LogHelp.i(TAG, "百度跳过 0 字节文件（服务端不支持空文件）: " + targetPath);
                if (cb != null) cb.onFinish(taskId, 0, "success");
                return;
            }
            var hashes = computeHashes(localFile, size);

            // 注：rapidupload 旧接口已被百度限制（errno=2），秒传检测统一走 precreate
            // （precreate 响应 block_list 为空数组即表示服务端已有全部块 = 秒传命中，跳过上传直接合并）

            // 预创建 + 分片上传 + 合并（31081/31363 整文件重传一次）
            var errno = uploadChunks(targetPath, size, hashes, localFile, cb, taskId);
            if (errno == 31081 || errno == 31363) {
                LogHelp.w(TAG, "百度合并失败 errno=" + errno + "，整文件重传一次");
                errno = uploadChunks(targetPath, size, hashes, localFile, cb, taskId);
            }
            if (errno == 10) {
                // 合并返回 errno=10（文件已存在）：秒传命中或此前已传完，视为上传成功
                LogHelp.i(TAG, "百度合并 errno=10（文件已存在），视为成功: " + targetPath);
                errno = 0;
            }
            if (errno != 0) {
                throw new CloudException(CloudException.Kind.REMOTE, "百度合并失败 errno=" + errno);
            }
            if (cb != null) cb.onFinish(taskId, 0, "success");
        } catch (CloudException e) {
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw e;
        } catch (Exception e) {
            var ce = findCloudException(e);
            if (ce != null) {
                if (cb != null) cb.onFinish(taskId, -1, ce.getMessage());
                throw ce;
            }
            LogHelp.e(TAG, "百度上传失败", e);
            if (cb != null) cb.onFinish(taskId, -1, e.getMessage());
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    /** precreate + 并发分片上传 + create 合并，返回最终 errno */
    private int uploadChunks(String targetPath, long size, FileHashes hashes, File localFile,
                             ProgressCallback cb, String taskId) throws CloudException {
        var form = new LinkedHashMap<String, String>();
        form.put("path", targetPath);
        form.put("size", String.valueOf(size));
        form.put("isdir", "0");
        form.put("autoinit", "1");
        form.put("block_list", new JSONArray(hashes.chunkMd5s).toString());
        // 不传 content-md5：该参数属 rapidupload 秒传接口，/api/precreate 不认，
        // 多传可能触发 errno=2 参数错误（与 createFolder 多传 isdir 同类教训）
        LogHelp.d(TAG, "百度 precreate path=" + targetPath + " size=" + size
                + " blocks=" + hashes.chunkMd5s.size());
        var json = apiPost("precreate", form);
        var uploadId = json.optString("uploadid", "");
        if (uploadId.isEmpty()) {
            throw new CloudException(CloudException.Kind.REMOTE, "百度 precreate 缺少 uploadid");
        }
        // block_list 为空数组 = 秒传命中（服务端已有全部块），跳过上传直接合并
        var needUpload = json.optJSONArray("block_list");
        if (needUpload == null || needUpload.length() > 0) {
            uploadParts(uploadId, targetPath, localFile, size, hashes.chunkMd5s, cb, taskId);
        } else {
            LogHelp.i(TAG, "百度 precreate 秒传命中，跳过分片上传: " + targetPath);
            if (cb != null) cb.onProgress(taskId, size, size);
        }
        var create = new LinkedHashMap<String, String>();
        create.put("type", "2");
        create.put("path", targetPath);
        create.put("size", String.valueOf(size));
        create.put("isdir", "0");
        create.put("uploadid", uploadId);
        create.put("block_list", new JSONArray(hashes.chunkMd5s).toString());
        var cj = apiPost("create", create);
        return cj.optInt("errno", -1);
    }

    /** 并发分片上传（partseq 0 起，multipart 字段 file；每片响应 md5 与本地比对，失败重传一片） */
    private void uploadParts(String uploadId, String targetPath, File file, long size,
                             List<String> chunkMd5s, ProgressCallback cb, String taskId) throws CloudException {
        var chunkCount = chunkMd5s.size();
        if (chunkCount == 0) return;
        var uploaded = new AtomicLong(0L);
        var executor = Executors.newFixedThreadPool(MAX_PART);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var i = 0; i < chunkCount; i++) {
                final var idx = i;
                futures.add(executor.submit(() -> {
                    var offset = (long) idx * BLOCK_SIZE;
                    var len = (int) Math.min(BLOCK_SIZE, size - offset);
                    uploadPart(uploadId, targetPath, idx, chunkMd5s.get(idx), file, offset, len);
                    var done = uploaded.addAndGet(len);
                    if (cb != null) cb.onProgress(taskId, done, size);
                    return null;
                }));
            }
            for (var f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    var ce = findCloudException(e);
                    if (ce != null) throw ce;
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "百度分片上传异常", e.getCause() != null ? e.getCause() : e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CloudException(CloudException.Kind.REMOTE, "百度分片上传被中断", e);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** 单个分片上传（partseq 0 起），响应 md5 与期望值比对，失败重传一次 */
    private void uploadPart(String uploadId, String targetPath, int partseq, String expectMd5,
                            File file, long offset, int len) throws CloudException {
        CloudException last = null;
        for (var attempt = 0; attempt < 2; attempt++) {
            try {
                var url = HttpUrl.parse(UPLOAD_URL).newBuilder()
                        .addQueryParameter("method", "upload")
                        .addQueryParameter("app_id", APP_ID)
                        .addQueryParameter("type", "tmpfile")
                        .addQueryParameter("path", targetPath)
                        .addQueryParameter("uploadid", uploadId)
                        .addQueryParameter("partseq", String.valueOf(partseq))
                        .build();
                var partBody = new RequestBody() {
                    @Override
                    public MediaType contentType() {
                        return MediaType.parse("application/octet-stream");
                    }

                    @Override
                    public long contentLength() {
                        return len;
                    }

                    @Override
                    public void writeTo(BufferedSink sink) throws IOException {
                        var buffer = new byte[BUFFER_SIZE];
                        var remaining = (long) len;
                        try (var in = new FileInputStream(file)) {
                            var skipped = 0L;
                            while (skipped < offset) {
                                var s = in.skip(offset - skipped);
                                if (s <= 0) break;
                                skipped += s;
                            }
                            while (remaining > 0) {
                                var read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (read == -1) break;
                                sink.write(buffer, 0, read);
                                remaining -= read;
                            }
                        }
                    }
                };
                var body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", "file", partBody)
                        .build();
                var builder = new Request.Builder().url(url)
                        .header("Cookie", cookie())
                        .header("User-Agent", UA)
                        .header("Referer", REFERER)
                        .post(body);
                var resp = execute(builder.build());
                if (resp.code < 200 || resp.code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE,
                            "百度分片 HTTP " + resp.code + ": " + truncate(resp.body, 300));
                }
                var md5 = new JSONObject(resp.body).optString("md5", "");
                if (!md5.isEmpty() && md5.equalsIgnoreCase(expectMd5)) {
                    return;
                }
                throw new CloudException(CloudException.Kind.REMOTE,
                        "百度分片 md5 不匹配 partseq=" + partseq + ": " + md5);
            } catch (CloudException e) {
                last = e;
                if (attempt == 0) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                    }
                }
            } catch (Exception e) {
                last = new CloudException(CloudException.Kind.REMOTE, "百度分片响应异常 partseq=" + partseq, e);
                if (attempt == 0) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        throw last != null ? last : new CloudException(CloudException.Kind.REMOTE, "百度分片上传失败 partseq=" + partseq);
    }

    /** 单遍流式计算：全文件 MD5 + CRC32 + 4MB 块 MD5；sliceMd5 独立读取前 256KB */
    private static FileHashes computeHashes(File file, long size) throws Exception {
        var fullMd5 = MessageDigest.getInstance("MD5");
        var crc = new CRC32();
        var chunks = new ArrayList<String>();
        var chunkHash = MessageDigest.getInstance("MD5");
        var inChunk = 0L;
        var buffer = new byte[BUFFER_SIZE];
        try (var in = new FileInputStream(file)) {
            var read = 0;
            while ((read = in.read(buffer)) != -1) {
                fullMd5.update(buffer, 0, read);
                crc.update(buffer, 0, read);
                chunkHash.update(buffer, 0, read);
                inChunk += read;
                if (inChunk >= BLOCK_SIZE) {
                    chunks.add(hexLower(chunkHash.digest()));
                    chunkHash = MessageDigest.getInstance("MD5");
                    inChunk = 0L;
                }
            }
            if (inChunk > 0) {
                chunks.add(hexLower(chunkHash.digest()));
            }
        }
        // slice-md5：前 256KB（不足则全文件）
        var slice = MessageDigest.getInstance("MD5");
        var sliceLen = (int) Math.min(256L * 1024, size);
        if (sliceLen > 0) {
            try (var in = new FileInputStream(file)) {
                var buf2 = new byte[BUFFER_SIZE];
                var remaining = sliceLen;
                while (remaining > 0) {
                    var r = in.read(buf2, 0, (int) Math.min(buf2.length, remaining));
                    if (r == -1) break;
                    slice.update(buf2, 0, r);
                    remaining -= r;
                }
            }
        }
        var h = new FileHashes();
        h.contentMd5 = hexLower(fullMd5.digest());
        h.sliceMd5 = hexLower(slice.digest());
        // content-crc32：无符号十进制（校准点：rapidupload 若稳定 31079 且 md5 正确，改 "0x%x" hex 格式）
        h.contentCrc32 = String.valueOf(crc.getValue());
        h.chunkMd5s = chunks;
        return h;
    }

    // ========== 下载 ==========

    @Override
    public String downloadFile(String remotePath, String localPath) throws CloudException {
        try {
            var remote = trimSlashes(remotePath);
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null) {
                throw new CloudException(CloudException.Kind.REMOTE, "百度文件不存在: " + remotePath);
            }
            if (entry.isDir) {
                throw new CloudException(CloudException.Kind.REMOTE, "百度目标为目录: " + remotePath);
            }
            var params = new LinkedHashMap<String, String>();
            params.put("fsids", new JSONArray().put(entry.fsId).toString());
            params.put("dlink", "1");
            var json = apiGet("filemetas", params);
            String dlink = "";
            // 响应字段：web 端点 pan.baidu.com/api/filemetas 是 info（实测 errno=0 + info 数组）；
            // 官方 openapi 端点 /rest/2.0/xpan/multimedia 才是 list——两种都兼容
            var arr = json.optJSONArray("info");
            if (arr == null || arr.length() == 0) arr = json.optJSONArray("list");
            if (arr != null && arr.length() > 0) {
                var first = arr.optJSONObject(0);
                if (first != null) dlink = first.optString("dlink", "");
            }
            if (dlink.isEmpty()) {
                // 响应结构变化时打印完整响应定位（dlink 为签名 URL 属敏感值，截断且只打长度）
                LogHelp.w(TAG, "百度 filemetas 无 dlink: fsid=" + entry.fsId
                        + " resp=" + truncate(json.toString(), 300));
                throw new CloudException(CloudException.Kind.REMOTE, "百度缺少下载地址: " + remotePath);
            }
            var builder = new Request.Builder().url(dlink)
                    .header("Cookie", cookie())
                    // 下载用网盘客户端 UA（网页 UA 被限速）；Referer 留空——客户端 UA 下带 Referer 反而异常
                    .header("User-Agent", NETDISK_UA)
                    .header("Accept", "*/*");
            try (var resp = downloadClient().newCall(builder.build()).execute()) {
                var code = resp.code();
                if (code < 200 || code >= 300) {
                    throw new CloudException(CloudException.Kind.REMOTE, "百度下载 HTTP " + code);
                }
                var body = resp.body();
                if (body == null) {
                    throw new CloudException(CloudException.Kind.REMOTE, "百度下载空响应");
                }
                var startMs = System.currentTimeMillis();
                try (var out = new FileOutputStream(localPath); var in = body.byteStream()) {
                    var buffer = new byte[BUFFER_SIZE];
                    var read = 0;
                    var written = 0L;
                    var total = body.contentLength();
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        written += read;
                    }
                    if (total > 0 && written != total) {
                        throw new CloudException(CloudException.Kind.REMOTE,
                                "百度下载不完整: " + written + "/" + total);
                    }
                    var cost = System.currentTimeMillis() - startMs;
                    var kbPerSec = cost > 0 ? written * 1000.0 / cost / 1024 : 0.0;
                    // 诊断：下载耗时/速度（大文件慢排查——网页 UA vs 客户端 UA 限速对比）
                    LogHelp.i(TAG, "百度 download done name=" + pathName(remote)
                            + " size=" + written + " cost=" + cost + "ms speed=" + (int) kbPerSec + "KB/s"
                            + " ua=" + (NETDISK_UA.startsWith("netdisk") ? "client" : "web"));
                }
            }
            return "OK: " + remotePath + " -> " + localPath;
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 删除 ==========

    @Override
    public void deleteDir(String remoteDir) throws CloudException {
        deletePath(remoteDir);
    }

    @Override
    public void deleteFile(String remotePath) throws CloudException {
        deletePath(remotePath);
    }

    /** 批量管理删除：filemanager?opera=delete，filelist=[{"path":...,"fs_id":...}]（fs_id 必需，仅 path 会 errno=2） */
    private void deletePath(String remotePath) throws CloudException {
        var remote = trimSlashes(remotePath);
        if (remote.isEmpty()) return;
        try {
            var entry = findEntry(pathParent(remote), pathName(remote));
            if (entry == null) return;
            var filelist = new JSONArray();
            var item = new JSONObject();
            item.put("path", entry.path);
            item.put("fs_id", entry.fsId);
            filelist.put(item);
            var form = new LinkedHashMap<String, String>();
            form.put("async", "1");
            form.put("filelist", filelist.toString());
            // opera 必须在 URL query（web 前端为 POST /api/filemanager?opera=delete），
            // 放 form body 会被服务端判参数错误 errno=2（实测 12:48 日志 deletePath errno=2 即此因）
            apiPost("filemanager?opera=delete", form);
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, e);
        }
    }

    // ========== 请求层 ==========

    private static class HttpResponse {
        int code;
        String body = "";
    }

    private String cookie() {
        return EncryptedCredStore.get(account.id, "cookie");
    }

    private static OkHttpClient client() {
        if (sClient != null) return sClient;
        synchronized (BaiduProvider.class) {
            if (sClient != null) return sClient;
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
            return sClient;
        }
    }

    /** 下载专用 client：读超时 120s（百度限速下大文件下载可能长时间无数据，60s 会误断） */
    private static OkHttpClient sDownloadClient;

    private static OkHttpClient downloadClient() {
        if (sDownloadClient != null) return sDownloadClient;
        synchronized (BaiduProvider.class) {
            if (sDownloadClient != null) return sDownloadClient;
            sDownloadClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
            return sDownloadClient;
        }
    }

    private HttpResponse execute(Request request) throws CloudException {
        var resp = new HttpResponse();
        try (var r = client().newCall(request).execute()) {
            resp.code = r.code();
            resp.body = r.body() != null ? r.body().string() : "";
            return resp;
        } catch (IOException e) {
            throw new CloudException(CloudException.Kind.NETWORK, e);
        }
    }

    @FunctionalInterface
    private interface ApiCall {
        HttpResponse run() throws CloudException;
    }

    /**
     * 请求统一出口：HTTP 429/5xx 退避重试；errno -2/2（bdstoken 相关或写操作参数被拒）首次清缓存重取重试；
     * 最终 parseApiResponse 做 errno 映射
     */
    private JSONObject withRetry(ApiCall call) throws CloudException {
        var last = new HttpResponse();
        for (var attempt = 0; attempt <= MAX_RETRY; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ignored) {
                }
            }
            last = call.run();
            if (last.code == 429 || last.code >= 500) {
                continue;
            }
            var errno = tryParseErrno(last);
            if (errno == -2 && !bdstoken.isEmpty()) {
                // bdstoken 失效：清缓存重取重试
                bdstoken = "";
                continue;
            }
            if (errno == 2 && !bdstoken.isEmpty() && attempt == 0) {
                // 写操作参数错误（常见为 bdstoken 缺失/无效导致）：清缓存重取后仅重试一次
                LogHelp.w(TAG, "百度 errno=2（写操作被拒），清空 bdstoken 重取重试: " + truncate(last.body, 200)
                        + " stack=" + stackTop(5));
                bdstoken = "";
                continue;
            }
            return parseApiResponse(last);
        }
        throw new CloudException(CloudException.Kind.REMOTE, "百度 API 重试耗尽 HTTP " + last.code);
    }

    private static int tryParseErrno(HttpResponse resp) {
        try {
            return new JSONObject(resp.body).optInt("errno", Integer.MIN_VALUE);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * errno 映射（唯一出口）：
     * -6/403+31045 → AUTH_EXPIRED；31079/31061/31081/31363 返回 JSON 由调用方特判；其余非 0 → REMOTE
     */
    private JSONObject parseApiResponse(HttpResponse resp) throws CloudException {
        if (resp.code == 401 || (resp.code == 403 && resp.body.contains("31045"))) {
            throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "百度 BDUSS 已失效，请重新登录");
        }
        if (resp.code < 200 || resp.code >= 300) {
            throw new CloudException(CloudException.Kind.REMOTE,
                    "百度 API HTTP " + resp.code + ": " + truncate(resp.body, 200));
        }
        try {
            var json = new JSONObject(resp.body);
            var errno = json.optInt("errno", 0);
            if (errno == 0) {
                return json;
            }
            if (errno == -6) {
                throw new CloudException(CloudException.Kind.AUTH_EXPIRED, "百度未登录或 BDUSS 已过期");
            }
            if (errno == 132) {
                // verify_scene=1 安全验证风控（删除/移动等写操作被拦截），API 无法绕过
                throw new CloudException(CloudException.Kind.REMOTE,
                        "百度操作触发安全验证(errno=132)，建议在网页端手动处理");
            }
            if (errno == 31079 || errno == 31061 || errno == 31081 || errno == 31363 || errno == 10) {
                // 秒传未命中 / 已存在幂等 / 整文件重传 / 目录或文件已存在（/api/create 对已存在目录返回 errno=10）：
                // 由调用方特判；errno=10 打调用栈便于确认出现位置（precreate 出现则 uploadId 缺失会显式抛错）
                if (errno == 10) {
                    LogHelp.w(TAG, "百度 errno=10（已存在），stack=" + stackTop(5));
                }
                return json;
            }
            throw new CloudException(CloudException.Kind.REMOTE,
                    "百度 API 错误 errno=" + errno + ": " + truncate(resp.body, 200));
        } catch (CloudException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudException(CloudException.Kind.REMOTE, "百度响应非 JSON: " + truncate(resp.body, 200));
        }
    }

    /** 获取 bdstoken（写端点需要），缓存；失败返回空串由写端点兜底 */
    private String ensureBdstoken() throws CloudException {
        if (!bdstoken.isEmpty()) return bdstoken;
        try {
            var url = HttpUrl.parse(API_BASE + "gettemplatevariable").newBuilder()
                    .addQueryParameter("fields", "[\"bdstoken\"]")
                    .addQueryParameter("app_id", APP_ID)
                    .build();
            var builder = new Request.Builder().url(url)
                    .header("Cookie", cookie())
                    .header("User-Agent", UA)
                    .header("Referer", REFERER);
            var resp = execute(builder.build());
            if (resp.code >= 200 && resp.code < 300) {
                var json = new JSONObject(resp.body);
                if (json.optInt("errno", -1) == 0) {
                    var result = json.optJSONObject("result");
                    if (result != null) {
                        var token = result.optString("bdstoken", "");
                        if (!token.isEmpty()) {
                            bdstoken = token;
                            LogHelp.d(TAG, "百度 bdstoken 获取成功, len=" + token.length());
                            return token;
                        }
                    }
                }
                // 诊断：errno 非 0 或 result 中无 bdstoken（body 可能含 bdstoken 值，JSON 形不被脱敏正则覆盖，不打印 body）
                LogHelp.w(TAG, "百度 bdstoken 获取失败: code=" + resp.code
                        + " errno=" + json.optInt("errno", -1));
            } else {
                LogHelp.w(TAG, "百度 bdstoken 获取失败: HTTP " + resp.code);
            }
        } catch (Exception e) {
            LogHelp.w(TAG, "百度获取 bdstoken 失败", e);
        }
        return "";
    }

    /** GET：query 带 app_id + 业务参数 */
    private JSONObject apiGet(String path, Map<String, String> params) throws CloudException {
        return withRetry(() -> {
            var url = HttpUrl.parse(API_BASE + path).newBuilder();
            url.addQueryParameter("app_id", APP_ID);
            for (var e : params.entrySet()) url.addQueryParameter(e.getKey(), e.getValue());
            var builder = new Request.Builder().url(url.build())
                    .header("Cookie", cookie())
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .header("Accept", "application/json");
            return execute(builder.build());
        });
    }

    /** POST：form 带 app_id + bdstoken（写端点） */
    private JSONObject apiPost(String path, Map<String, String> form) throws CloudException {
        return withRetry(() -> {
            var data = new LinkedHashMap<String, String>(form);
            data.put("app_id", APP_ID);
            var bd = ensureBdstoken();
            if (!bd.isEmpty()) {
                data.put("bdstoken", bd);
            } else {
                // bdstoken 获取失败：写操作不带 bdstoken 大概率返回 errno=2，显式记录便于诊断
                LogHelp.e(TAG, "百度写操作缺少 bdstoken（预期 errno=2）: " + path);
            }
            var fb = new FormBody.Builder();
            for (var e : data.entrySet()) fb.add(e.getKey(), e.getValue());
            var builder = new Request.Builder().url(API_BASE + path)
                    .header("Cookie", cookie())
                    .header("User-Agent", UA)
                    .header("Referer", REFERER)
                    .header("Accept", "application/json")
                    .post(fb.build());
            return execute(builder.build());
        });
    }

    // ========== 工具 ==========

    private static String hexLower(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    /** 拼接绝对路径："/" + name 或 dirPath + "/" + name */
    private static String joinPath(String dirPath, String name) {
        var dir = dirPath == null ? ROOT_PATH : dirPath;
        while (dir.endsWith("/") && dir.length() > 1) dir = dir.substring(0, dir.length() - 1);
        return (dir.length() == 1 && dir.charAt(0) == '/') ? dir + name : dir + "/" + name;
    }

    /** 在异常链中查找 CloudException（并发分片 Future.get 会包 ExecutionException） */
    private static CloudException findCloudException(Throwable t) {
        for (var cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof CloudException) return (CloudException) cur;
        }
        return null;
    }

    private static String trimSlashes(String path) {
        var v = path == null ? "" : path.replace('\\', '/');
        while (v.startsWith("/")) v = v.substring(1);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String pathParent(String path) {
        var v = trimSlashes(path);
        var i = v.lastIndexOf('/');
        return i < 0 ? "" : v.substring(0, i);
    }

    private static String pathName(String path) {
        var v = trimSlashes(path);
        var i = v.lastIndexOf('/');
        return i < 0 ? v : v.substring(i + 1);
    }

    /** 清理文件名中的控制字符/零宽字符并 trim（对齐 Quark Provider） */
    private static String cleanName(String name) {
        if (name == null) return "";
        var out = new StringBuilder();
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            var code = (int) c;
            if ((code >= 0x0000 && code <= 0x001F) || (code >= 0x007F && code <= 0x009F)
                    || (code >= 0x200B && code <= 0x200F) || code == 0xFEFF) {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 调用栈顶部摘要（诊断用，定位 errno=2 等错误出自哪个 API 调用链） */
    private static String stackTop(int depth) {
        try {
            var st = Thread.currentThread().getStackTrace();
            var sb = new StringBuilder();
            var count = 0;
            for (var i = 2; i < st.length && count < depth; i++) {
                var el = st[i];
                var cls = el.getClassName();
                if (cls.startsWith("com.suileyan.")) {
                    if (sb.length() > 0) sb.append(" <- ");
                    sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append(".").append(el.getMethodName());
                    count++;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ========== 内部模型 ==========

    private static class FileHashes {
        String contentMd5 = "";
        String sliceMd5 = "";
        String contentCrc32 = "";
        List<String> chunkMd5s = new ArrayList<>();
    }

    /** 目录/文件条目 */
    private static class Item {
        final long fsId;
        final String path;
        final String name;
        final long size;
        final boolean isDir;
        final long modifiedTime;

        Item(long fsId, String path, String name, long size, boolean isDir, long modifiedTime) {
            this.fsId = fsId;
            this.path = path;
            this.name = name;
            this.size = size;
            this.isDir = isDir;
            this.modifiedTime = modifiedTime;
        }

        static Item fromJson(JSONObject obj) {
            var fsId = obj.optLong("fs_id", 0L);
            var path = obj.optString("path", "");
            var name = obj.optString("server_filename", "");
            var isDir = obj.optInt("isdir", 0) == 1;
            var mtime = obj.optLong("server_mtime", 0L) * 1000L;
            if (mtime <= 0) mtime = System.currentTimeMillis();
            return new Item(fsId, path, name, obj.optLong("size", 0L), isDir, mtime);
        }
    }
}
