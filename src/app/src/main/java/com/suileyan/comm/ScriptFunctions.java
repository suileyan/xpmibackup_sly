package com.suileyan.comm;

import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

import com.suileyan.cloud.EncryptedCredStore;

/**
 * 自定义脚本宿主函数库（沙箱 v2）。
 *
 * 设计原则：安全为第一前提，权限最小化。
 * - ClassShutter 保持 className -> false 全拒绝，scope 中不放置任何 Java 对象实例，
 *   即使 ClassShutter 被绕过，也没有 Class 实例可用于 getClass().forName() 反射（CVE-2025-0982 教训）；
 * - 所有新能力以宿主函数形式暴露，Java 侧逻辑由我们控制，脚本只能按函数签名传参，无法越权；
 * - 加密密钥、临时文件路径等敏感参数不落日志。
 *
 * 函数清单见 install()，文档见 plugins/README.md。
 */
public final class ScriptFunctions {

    private static final String TAG = "XpMiBackup";

    /** readTempFile 单次读取上限（字节）：防恶意脚本把超大文件读入内存导致 OOM */
    private static final int READ_TEMP_FILE_MAX = 16 * 1024 * 1024;

    /** 自定义超时上限（秒），防止脚本把超时设成天文数字挂死线程 */
    private static final int MAX_TIMEOUT_SECONDS = 300;

    /** fileHashHex 流式哈希缓冲区（字节） */
    private static final int HASH_BUFFER_SIZE = 8192;

    /** 允许脚本使用的摘要算法白名单（最小权限：标准算法即可满足签名/校验场景，
     * 防止依赖 Provider 注册的非标准算法导致行为不可预期，M-01） */
    private static final Set<String> ALLOWED_DIGESTS = Set.of(
            "MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512");

    /** 允许脚本使用的 HMAC 算法白名单（M-01） */
    private static final Set<String> ALLOWED_HMACS = Set.of(
            "HMACSHA1", "HMACSHA256", "HMACSHA384", "HMACSHA512");

    private ScriptFunctions() {
    }

    /**
     * 安装全部宿主函数到脚本 scope。
     * 分组安装，便于审查与维护。
     */
    public static void install(Scriptable scope) {
        installHttpFunctions(scope);   // httpRequest / httpDownload / httpRequestMultipart / httpHead / getResponseHeader
        installStateFunctions(scope);  // stateGet / stateSet
        installCryptoFunctions(scope); // base64Encode / base64Decode / hashHex / hashHexBytes / hmacHex / hmacBase64 / aesEncrypt / aesDecrypt / sha256Hex / md5Hex
        installFileFunctions(scope);   // tempFile / tempFileName / readTempFile / writeTempFile / deleteTempFile / fileHashHex / fileSize
        installConsoleFunctions(scope);// console.log / console.error
        installUtilityFunctions(scope);// uuid / timestampSeconds / timestampMillis / randomHex
    }

    // ========== HTTP 能力 ==========

    private static void installHttpFunctions(Scriptable scope) {
        // 脚本内的原始HTTP请求入口（迁移自 CustomHttpFileHelp.installUtilityFunctions）
        ScriptableObject.putProperty(scope, "httpRequest", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var spec = args.length > 0 ? CustomHttpFileHelp.toRequestSpec(args[0]) : null;
                    if (spec == null) {
                        throw new IllegalArgumentException("httpRequest expects a request object");
                    }
                    var streamBody = Boolean.TRUE.equals(spec.streamFile) ? CustomHttpFileHelp.currentUploadBody() : null;
                    var readBody = spec.readBody == null || spec.readBody;
                    var response = CustomHttpFileHelp.execute(spec, streamBody, readBody);
                    return CustomHttpFileHelp.responseToScriptObject(scope, response);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // 脚本内把远端响应流直接写入当前下载目标文件（迁移自 CustomHttpFileHelp.installUtilityFunctions）
        ScriptableObject.putProperty(scope, "httpDownload", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var spec = args.length > 0 ? CustomHttpFileHelp.toRequestSpec(args[0]) : null;
                    if (spec == null) {
                        throw new IllegalArgumentException("httpDownload expects a request object");
                    }
                    var target = CustomHttpFileHelp.currentDownloadTarget();
                    var response = CustomHttpFileHelp.execute(spec, null, false);
                    try {
                        if (response.code >= 200 && response.code < 300 && response.response != null && response.response.body() != null) {
                            var parent = target.getParentFile();
                            if (parent != null) {
                                parent.mkdirs();
                            }
                            try (var in = response.response.body().byteStream(); var fos = new FileOutputStream(target)) {
                                CustomHttpFileHelp.copyStream(in, fos);
                            }
                        }
                        return CustomHttpFileHelp.responseToScriptObject(scope, response);
                    } finally {
                        CustomHttpFileHelp.closeQuietly(response);
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // 表单上传：fields 为文本字段，parts 为文件（引用 tempFile() 创建的临时文件路径）
        ScriptableObject.putProperty(scope, "httpRequestMultipart", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    if (args.length < 1 || !(args[0] instanceof Scriptable)) {
                        throw new IllegalArgumentException("httpRequestMultipart expects a request object");
                    }
                    var obj = (Scriptable) args[0];
                    var spec = CustomHttpFileHelp.toRequestSpec(obj);
                    var builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
                    // 文本字段
                    var fields = ScriptableObject.getProperty(obj, "fields");
                    if (fields instanceof Scriptable) {
                        var f = (Scriptable) fields;
                        for (var id : f.getIds()) {
                            var key = String.valueOf(id);
                            builder.addFormDataPart(key, Context.toString(ScriptableObject.getProperty(f, key)));
                        }
                    }
                    // 文件字段：file 必须指向脚本临时目录内的文件（防路径遍历/越权读取）
                    var parts = ScriptableObject.getProperty(obj, "parts");
                    if (parts instanceof NativeArray) {
                        var arr = (NativeArray) parts;
                        for (var i = 0L; i < arr.getLength(); i++) {
                            var item = arr.get((int) i, arr);
                            if (!(item instanceof Scriptable)) continue;
                            var p = (Scriptable) item;
                            var name = stringProperty(p, "name", "file");
                            var filePath = stringProperty(p, "file", "");
                            var fileName = stringProperty(p, "filename", null);
                            var contentType = stringProperty(p, "contentType", "application/octet-stream");
                            var resolved = resolveTempPath(filePath);
                            var partFile = new File(resolved);
                            if (!partFile.isFile()) {
                                throw new IllegalArgumentException("multipart part file not found: " + filePath);
                            }
                            if (fileName == null || fileName.isEmpty()) {
                                fileName = partFile.getName();
                            }
                            builder.addFormDataPart(name, fileName, RequestBody.create(MediaType.parse(contentType), partFile));
                        }
                    }
                    var response = CustomHttpFileHelp.execute(spec, builder.build());
                    return CustomHttpFileHelp.responseToScriptObject(scope, response);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // HEAD 请求：探测文件大小 / ETag
        ScriptableObject.putProperty(scope, "httpHead", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var url = args.length > 0 ? Context.toString(args[0]) : "";
                    var spec = new CustomHttpFileHelp.RequestSpec();
                    spec.method = "HEAD";
                    spec.url = url;
                    if (args.length > 1 && args[1] instanceof Scriptable) {
                        var h = (Scriptable) args[1];
                        for (var id : h.getIds()) {
                            var key = String.valueOf(id);
                            spec.headers.put(key, Context.toString(ScriptableObject.getProperty(h, key)));
                        }
                    }
                    var response = CustomHttpFileHelp.execute(spec, null, true);
                    return CustomHttpFileHelp.responseToScriptObject(scope, response);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // 取响应头（大小写不敏感）；同名多值时以逗号拼接返回
        ScriptableObject.putProperty(scope, "getResponseHeader", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    if (args.length < 2 || !(args[0] instanceof Scriptable)) {
                        return null;
                    }
                    var resp = (Scriptable) args[0];
                    var name = Context.toString(args[1]);
                    var headers = ScriptableObject.getProperty(resp, "headers");
                    if (!(headers instanceof Scriptable)) {
                        return null;
                    }
                    var h = (Scriptable) headers;
                    for (var id : h.getIds()) {
                        var key = String.valueOf(id);
                        if (!key.equalsIgnoreCase(name)) continue;
                        var value = ScriptableObject.getProperty(h, key);
                        if (value instanceof NativeArray) {
                            var arr = (NativeArray) value;
                            var sb = new StringBuilder();
                            for (var i = 0L; i < arr.getLength(); i++) {
                                if (sb.length() > 0) sb.append(", ");
                                sb.append(Context.toString(arr.get((int) i, arr)));
                            }
                            return sb.toString();
                        }
                        return value == null || value == Scriptable.NOT_FOUND ? null : Context.toString(value);
                    }
                    return null;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    // ========== 状态持久化 ==========

    private static void installStateFunctions(Scriptable scope) {
        // 脚本内读取持久化状态（迁移自 CustomHttpFileHelp.installUtilityFunctions）
        ScriptableObject.putProperty(scope, "stateGet", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var key = args.length > 0 ? Context.toString(args[0]) : "";
                var def = args.length > 1 ? Context.toString(args[1]) : "";
                return getScriptState(key, def);
            }
        });
        // 脚本内写入持久化状态（迁移自 CustomHttpFileHelp.installUtilityFunctions）
        ScriptableObject.putProperty(scope, "stateSet", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var key = args.length > 0 ? Context.toString(args[0]) : "";
                var value = args.length > 1 ? Context.toString(args[1]) : "";
                setScriptState(key, value);
                return value;
            }
        });
    }

    /** 读取JS脚本持久化状态；按当前脚本账号隔离存入加密存储（迁移自 CustomHttpFileHelp） */
    private static String getScriptState(String key, String def) {
        if (key == null || key.isEmpty()) return def;
        var accountId = CustomHttpFileHelp.scriptAccountId();
        if (accountId.isEmpty()) return def;
        var value = EncryptedCredStore.get(accountId, "state:" + key);
        return value == null || value.isEmpty() ? def : value;
    }

    /** 写入JS脚本持久化状态；按当前脚本账号隔离存入加密存储（迁移自 CustomHttpFileHelp） */
    private static void setScriptState(String key, String value) {
        if (key == null || key.isEmpty()) return;
        var accountId = CustomHttpFileHelp.scriptAccountId();
        if (accountId.isEmpty()) return;
        EncryptedCredStore.put(accountId, "state:" + key, value == null ? "" : value);
    }

    // ========== 编码与加密能力 ==========

    private static void installCryptoFunctions(Scriptable scope) {
        // 把文本编码成Base64（迁移自 CustomHttpFileHelp.installUtilityFunctions）
        ScriptableObject.putProperty(scope, "base64Encode", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return base64EncodeText(args.length > 0 ? Context.toString(args[0]) : "");
            }
        });
        // 把Base64解码回UTF-8文本（迁移自 CustomHttpFileHelp.installUtilityFunctions）
        ScriptableObject.putProperty(scope, "base64Decode", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return base64DecodeText(args.length > 0 ? Context.toString(args[0]) : "");
            }
        });
        // 计算任意算法的十六进制摘要（迁移自 CustomHttpFileHelp.installUtilityFunctions）
        ScriptableObject.putProperty(scope, "hashHex", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var algorithm = args.length > 0 ? Context.toString(args[0]) : "MD5";
                var value = args.length > 1 ? Context.toString(args[1]) : "";
                return hashHex(algorithm, value);
            }
        });
        // 对 Base64 编码的字节数据计算摘要（二进制内容哈希）
        ScriptableObject.putProperty(scope, "hashHexBytes", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var algorithm = args.length > 0 ? Context.toString(args[0]) : "SHA-256";
                    requireDigestAllowed(algorithm);
                    var bytes = args.length > 1 ? Base64.getDecoder().decode(Context.toString(args[1])) : new byte[0];
                    return hex(MessageDigest.getInstance(algorithm).digest(bytes));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        // HMAC 摘要（十六进制）：OSS 签名 / 阿里云网关等场景
        ScriptableObject.putProperty(scope, "hmacHex", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var algorithm = args.length > 0 ? Context.toString(args[0]) : "HmacSHA1";
                var key = args.length > 1 ? Context.toString(args[1]) : "";
                var message = args.length > 2 ? Context.toString(args[2]) : "";
                return hmac(algorithm, key, message, true);
            }
        });
        // HMAC 摘要（Base64）：AWS SigV4 风格签名
        ScriptableObject.putProperty(scope, "hmacBase64", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var algorithm = args.length > 0 ? Context.toString(args[0]) : "HmacSHA1";
                var key = args.length > 1 ? Context.toString(args[1]) : "";
                var message = args.length > 2 ? Context.toString(args[2]) : "";
                return hmac(algorithm, key, message, false);
            }
        });
        // AES-GCM 加密（输出 Base64 密文，tag 附在密文尾部）；只允许 GCM，禁止 ECB
        ScriptableObject.putProperty(scope, "aesEncrypt", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var keyB64 = args.length > 0 ? Context.toString(args[0]) : "";
                var ivB64 = args.length > 1 ? Context.toString(args[1]) : "";
                var data = args.length > 2 ? Context.toString(args[2]) : "";
                return aes(keyB64, ivB64, data, true);
            }
        });
        // AES-GCM 解密（输入 Base64 密文）
        ScriptableObject.putProperty(scope, "aesDecrypt", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var keyB64 = args.length > 0 ? Context.toString(args[0]) : "";
                var ivB64 = args.length > 1 ? Context.toString(args[1]) : "";
                var dataB64 = args.length > 2 ? Context.toString(args[2]) : "";
                return aes(keyB64, ivB64, dataB64, false);
            }
        });
        // SHA-256 快捷封装
        ScriptableObject.putProperty(scope, "sha256Hex", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return hashHex("SHA-256", args.length > 0 ? Context.toString(args[0]) : "");
            }
        });
        // MD5 快捷封装
        ScriptableObject.putProperty(scope, "md5Hex", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return hashHex("MD5", args.length > 0 ? Context.toString(args[0]) : "");
            }
        });
    }

    /** 计算任意算法的十六进制摘要文本（迁移自 CustomHttpFileHelp；算法限白名单 M-01） */
    private static String hashHex(String algorithm, String value) {
        var algo = algorithm == null || algorithm.isEmpty() ? "MD5" : algorithm;
        requireDigestAllowed(algo);
        try {
            var digest = MessageDigest.getInstance(algo);
            var bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return hex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** HMAC 计算；hex=true 输出十六进制，否则输出 Base64；算法限白名单 M-01 */
    private static String hmac(String algorithm, String key, String message, boolean hexOut) {
        var algo = algorithm == null || algorithm.isEmpty() ? "HmacSHA1" : algorithm;
        requireHmacAllowed(algo);
        try {
            var mac = Mac.getInstance(algo);
            mac.init(new SecretKeySpec((key == null ? "" : key).getBytes(StandardCharsets.UTF_8), algo));
            var out = mac.doFinal((message == null ? "" : message).getBytes(StandardCharsets.UTF_8));
            return hexOut ? hex(out) : Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** AES-GCM 加解密；encrypt=true 加密，否则解密。密钥/IV 均为 Base64。 */
    private static String aes(String keyB64, String ivB64, String data, boolean encrypt) {
        try {
            var key = Base64.getDecoder().decode(keyB64 == null ? "" : keyB64);
            var iv = Base64.getDecoder().decode(ivB64 == null ? "" : ivB64);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            var in = (data == null ? "" : data).getBytes(StandardCharsets.UTF_8);
            var out = encrypt ? cipher.doFinal(in) : cipher.doFinal(Base64.getDecoder().decode(data == null ? "" : data));
            return encrypt ? Base64.getEncoder().encodeToString(out) : new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("aes " + (encrypt ? "encrypt" : "decrypt") + " failed: " + e.getMessage(), e);
        }
    }

    // ========== 文件操作能力 ==========

    private static void installFileFunctions(Scriptable scope) {
        // 在脚本临时目录创建临时文件，返回绝对路径
        ScriptableObject.putProperty(scope, "tempFile", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    // 顺序：先取默认值 -> 再校验 -> 最后补足 createTempFile 要求的最小 prefix 长度（M-03）
                    var prefix = args.length > 0 ? Context.toString(args[0]) : "tmp";
                    var suffix = args.length > 1 ? Context.toString(args[1]) : ".tmp";
                    if (prefix == null || prefix.isEmpty()) prefix = "tmp";
                    if (suffix == null || suffix.isEmpty()) suffix = ".tmp";
                    validateComponent(prefix, "prefix");
                    validateComponent(suffix, "suffix");
                    if (prefix.length() < 3) prefix = "tmp" + prefix;
                    var file = File.createTempFile(prefix, suffix, scriptTempDir());
                    return file.getAbsolutePath();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        // 返回唯一临时文件名（不创建文件），适合用作远端唯一文件名
        ScriptableObject.putProperty(scope, "tempFileName", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                // nanoTime + 随机数组合：多账号并发执行时避免同纳秒碰撞导致远端文件覆盖（L-03）
                return "tmp_" + Long.toHexString(System.nanoTime())
                        + "_" + Integer.toHexString(new SecureRandom().nextInt());
            }
        });

        // 读取临时文件为 Base64（限 16MB）
        ScriptableObject.putProperty(scope, "readTempFile", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var path = args.length > 0 ? Context.toString(args[0]) : "";
                    var file = new File(resolveTempPath(path));
                    if (!file.isFile()) {
                        throw new IllegalStateException("temp file not found: " + path);
                    }
                    // 流式读取并在写入时同步检查上限：避免"先检查大小再 readAllBytes"之间的 TOCTOU 竞态（M-02）
                    try (var in = new FileInputStream(file); var out = new java.io.ByteArrayOutputStream()) {
                        var buffer = new byte[HASH_BUFFER_SIZE];
                        var total = 0;
                        var read = 0;
                        while ((read = in.read(buffer)) != -1) {
                            total += read;
                            if (total > READ_TEMP_FILE_MAX) {
                                throw new SecurityException("readTempFile too large (max 16MB)");
                            }
                            out.write(buffer, 0, read);
                        }
                        return Base64.getEncoder().encodeToString(out.toByteArray());
                    }
                } catch (SecurityException e) {
                    throw e;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        // 把 Base64 数据写入临时文件；文件不存在（如父目录未创建）抛清晰异常（L-02）
        ScriptableObject.putProperty(scope, "writeTempFile", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var path = args.length > 0 ? Context.toString(args[0]) : "";
                    var data = args.length > 1 ? Context.toString(args[1]) : "";
                    try {
                        Files.write(java.nio.file.Path.of(resolveTempPath(path)), Base64.getDecoder().decode(data));
                    } catch (IOException e) {
                        throw new IllegalStateException("writeTempFile failed for " + path + ": " + e.getMessage(), e);
                    }
                    return Boolean.TRUE;
                } catch (SecurityException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        // 删除临时文件
        ScriptableObject.putProperty(scope, "deleteTempFile", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                try {
                    var path = args.length > 0 ? Context.toString(args[0]) : "";
                    return Boolean.valueOf(Files.deleteIfExists(java.nio.file.Path.of(resolveTempPath(path))));
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        // 流式计算文件哈希（大文件秒传 / 分片一致性校验）；算法限白名单，文件不存在抛清晰异常（M-01 / L-02）
        ScriptableObject.putProperty(scope, "fileHashHex", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var path = args.length > 0 ? Context.toString(args[0]) : "";
                try {
                    var algorithm = args.length > 1 ? Context.toString(args[1]) : "SHA-256";
                    requireDigestAllowed(algorithm);
                    var digest = MessageDigest.getInstance(algorithm);
                    var buffer = new byte[HASH_BUFFER_SIZE];
                    try (var in = new FileInputStream(new File(resolveTempPath(path)))) {
                        var read = 0;
                        while ((read = in.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                        }
                    }
                    return hex(digest.digest());
                } catch (SecurityException e) {
                    throw e;
                } catch (java.io.FileNotFoundException e) {
                    throw new IllegalStateException("temp file not found: " + path, e);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        // 返回文件大小
        ScriptableObject.putProperty(scope, "fileSize", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var path = args.length > 0 ? Context.toString(args[0]) : "";
                return Long.valueOf(new File(resolveTempPath(path)).length());
            }
        });
    }

    /** 当前脚本账号专属的临时目录；按账号隔离，防脚本互读文件 */
    private static File scriptTempDir() {
        var dir = new File(ConfigHelp.BACKUP_ROOT + "/script_temp/" + CustomHttpFileHelp.scriptAccountId());
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 校验并规范化临时文件路径。
     * 用 canonical path 前缀校验：阻断 .. 穿越与绝对路径逃逸（如 /etc/passwd）。
     */
    private static String resolveTempPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new SecurityException("invalid temp file path: empty");
        }
        var dir = scriptTempDir();
        String canonicalDir;
        String canonicalPath;
        try {
            canonicalDir = dir.getCanonicalPath();
            canonicalPath = new File(path).getCanonicalPath();
        } catch (IOException e) {
            throw new SecurityException("invalid temp file path: " + path, e);
        }
        if (!canonicalPath.equals(canonicalDir) && !canonicalPath.startsWith(canonicalDir + File.separator)) {
            throw new SecurityException("temp file path outside script temp dir: " + path);
        }
        return canonicalPath;
    }

    /** tempFile 的 prefix/suffix 校验：不允许路径分隔符、.. 与 NUL */
    private static void validateComponent(String value, String what) {
        if (value == null || value.isEmpty()) return;
        if (value.contains("/") || value.contains("\\") || value.contains("..") || value.contains("\u0000")) {
            throw new SecurityException("invalid temp file " + what + ": '" + value + "'");
        }
    }

    // ========== console 能力 ==========

    private static void installConsoleFunctions(Scriptable scope) {
        var console = Context.getCurrentContext().newObject(scope);
        ScriptableObject.putProperty(console, "log", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                LogHelp.d(TAG, "[script] " + joinArgs(args));
                return Context.getUndefinedValue();
            }
        });
        ScriptableObject.putProperty(console, "error", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                LogHelp.e(TAG, "[script] " + joinArgs(args));
                return Context.getUndefinedValue();
            }
        });
        ScriptableObject.putProperty(scope, "console", console);
    }

    /**
     * 拼接 console 参数为单行文本。
     * 引擎不做凭据脱敏（无法区分哪些参数是凭据），超长参数做截断以降低凭据意外落日志的泄露面；
     * 真正的脱敏应由脚本作者负责，文档已明确（L-01）。
     */
    private static String joinArgs(Object[] args) {
        var sb = new StringBuilder();
        for (var arg : args) {
            if (sb.length() > 0) sb.append(' ');
            if (arg == null || arg == Undefined.instance || arg == Scriptable.NOT_FOUND) {
                sb.append("undefined");
            } else {
                var text = Context.toString(arg);
                if (text.length() > 200) {
                    text = text.substring(0, 200) + "...(truncated " + text.length() + " chars)";
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    // ========== 通用工具 ==========

    private static void installUtilityFunctions(Scriptable scope) {
        // 生成 UUID（请求 ID）
        ScriptableObject.putProperty(scope, "uuid", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return UUID.randomUUID().toString();
            }
        });
        // 秒级时间戳（签名场景）
        ScriptableObject.putProperty(scope, "timestampSeconds", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return Long.valueOf(System.currentTimeMillis() / 1000L);
            }
        });
        // 毫秒时间戳
        ScriptableObject.putProperty(scope, "timestampMillis", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return Long.valueOf(System.currentTimeMillis());
            }
        });
        // 生成随机 hex（nonce）；使用 SecureRandom
        ScriptableObject.putProperty(scope, "randomHex", new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                var n = args.length > 0 ? (int) Context.toNumber(args[0]) : 16;
                if (n <= 0) n = 16;
                if (n > 1024) n = 1024;
                var bytes = new byte[n];
                new SecureRandom().nextBytes(bytes);
                return hex(bytes);
            }
        });
    }

    // ========== 内部工具 ==========

    /** 把文本编码成Base64（迁移自 CustomHttpFileHelp） */
    private static String base64EncodeText(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    /** 把Base64文本解码成UTF-8字符串（迁移自 CustomHttpFileHelp） */
    private static String base64DecodeText(String value) {
        try {
            return new String(Base64.getDecoder().decode(value == null ? "" : value), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 字节数组转小写十六进制 */
    private static String hex(byte[] bytes) {
        var out = new StringBuilder(bytes.length * 2);
        for (var b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }

    /** 摘要算法白名单校验（M-01）：算法名大小写不敏感，非白名单直接拒绝 */
    private static void requireDigestAllowed(String algorithm) {
        if (algorithm == null || !ALLOWED_DIGESTS.contains(algorithm.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unsupported digest algorithm: " + algorithm);
        }
    }

    /** HMAC 算法白名单校验（M-01） */
    private static void requireHmacAllowed(String algorithm) {
        if (algorithm == null || !ALLOWED_HMACS.contains(algorithm.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("unsupported hmac algorithm: " + algorithm);
        }
    }

    /** 从脚本对象读取字符串属性，缺省时返回默认值 */
    private static String stringProperty(Scriptable obj, String name, String def) {
        var value = ScriptableObject.getProperty(obj, name);
        return value == null || value == Scriptable.NOT_FOUND ? def : Context.toString(value);
    }
}
