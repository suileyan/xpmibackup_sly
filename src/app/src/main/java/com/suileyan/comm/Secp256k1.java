package com.suileyan.comm;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * secp256k1 ECDSA 签名（纯 JDK BigInteger 实现，零依赖）
 *
 * 用途：阿里云盘新版 Web API 的设备签名——客户端按 user_id 派生 secp256k1 密钥对，
 * 通过 create_session 注册公钥后，每个请求携带 X-Signature（对
 * "secpAppID:deviceID:userID:nonce" 的 SHA-256 签名，hex 编码 r‖s）。
 *
 * Android 标准 JCE 不支持 secp256k1 曲线（Conscrypt 只有 NIST 曲线），
 * 因此自带曲线参数与点运算。签名输出做 Low-S 归一化（与 Go dustinxie/ecc 的
 * ecc.RecID|ecc.LowerS 序列化一致：64 字节 r‖s，s 取 n-s 中较小者）。
 */
public final class Secp256k1 {

    // y² = x³ + 7 (mod p)
    private static final BigInteger P = new BigInteger(
            "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16);
    private static final BigInteger N = new BigInteger(
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);
    private static final BigInteger GX = new BigInteger(
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16);
    private static final BigInteger GY = new BigInteger(
            "483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16);
    /** (n-1)/2，Low-S 归一化阈值 */
    private static final BigInteger HALF_N = N.shiftRight(1);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secp256k1() {
    }

    /** 仿射点；ZERO 表示无穷远点（x/y 为 null 时判零） */
    private static final class Point {
        final BigInteger x;
        final BigInteger y;

        Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }

        boolean isZero() {
            return x == null;
        }
    }

    private static final Point G = new Point(GX, GY);
    private static final Point ZERO = new Point(null, null);

    /** 由 32 字节私钥导出 64 字节公钥（X ‖ Y，各 32 字节大端） */
    public static byte[] publicKey(byte[] privateKey) {
        var q = multiply(new BigInteger(1, privateKey), G);
        if (q.isZero()) {
            throw new IllegalArgumentException("invalid private key");
        }
        return concat(fixed32(q.x), fixed32(q.y));
    }

    /**
     * ECDSA 签名：返回 64 字节 r‖s（s 做了 Low-S 归一化）。
     *
     * @param hash       32 字节待签摘要（SHA-256）
     * @param privateKey 32 字节私钥
     */
    public static byte[] sign(byte[] hash, byte[] privateKey) {
        var z = new BigInteger(1, hash);
        var d = new BigInteger(1, privateKey);
        if (d.signum() == 0 || d.compareTo(N) >= 0) {
            throw new IllegalArgumentException("invalid private key");
        }
        while (true) {
            var k = new BigInteger(N.bitLength(), RANDOM).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
            var rp = multiply(k, G);
            var r = rp.x.mod(N);
            if (r.signum() == 0) continue;
            var s = k.modInverse(N).multiply(z.add(r.multiply(d))).mod(N);
            if (s.signum() == 0) continue;
            if (s.compareTo(HALF_N) > 0) {
                s = N.subtract(s);
            }
            return concat(fixed32(r), fixed32(s));
        }
    }

    // ---------- 点运算（仿射坐标） ----------

    /** 椭圆曲线点乘 k·P（double-and-add） */
    private static Point multiply(BigInteger k, Point p) {
        var acc = ZERO;
        var addend = p;
        var n = k.mod(N);
        while (n.signum() > 0) {
            if (n.testBit(0)) {
                acc = add(acc, addend);
            }
            addend = add(addend, addend);
            n = n.shiftRight(1);
        }
        return acc;
    }

    /** 点加法（含二倍）；任一为无穷远返回另一点 */
    private static Point add(Point a, Point b) {
        if (a.isZero()) return b;
        if (b.isZero()) return a;
        if (a.x.equals(b.x)) {
            if (!a.y.equals(b.y)) {
                return ZERO; // P + (-P) = O
            }
            return twice(a);
        }
        // 斜率 λ = (y2-y1)/(x2-x1) mod p
        var slope = b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P);
        return fromSlope(a, b, slope);
    }

    /** 2 的常量：绝不能用 {@code BigInteger.TWO} —— 它直到 API 33 才存在，
     *  在 minSdk 30 的设备（Android 11/12）上取该字段抛的是 NoSuchFieldError（属 Error，
     *  catch (Exception) 拦不住），PC 配对的 ECDH 会直接崩进程。 */
    private static final BigInteger TWO = BigInteger.valueOf(2L);

    /** 二倍：λ = (3x²)/(2y) mod p（曲线 a=0，无 a 项） */
    private static Point twice(Point a) {
        if (a.isZero() || a.y.signum() == 0) return ZERO;
        var slope = a.x.modPow(TWO, P)
                .multiply(BigInteger.valueOf(3)).mod(P)
                .multiply(a.y.shiftLeft(1).mod(P).modInverse(P)).mod(P);
        return fromSlope(a, a, slope);
    }

    /** 由斜率求第三交点并翻转 y：x3 = λ²-x1-x2，y3 = λ(x1-x3)-y1 */
    private static Point fromSlope(Point a, Point b, BigInteger slope) {
        var x3 = slope.modPow(TWO, P).subtract(a.x).subtract(b.x).mod(P);
        var y3 = slope.multiply(a.x.subtract(x3)).subtract(a.y).mod(P);
        return new Point(x3, y3);
    }

    // ---------- 工具 ----------

    private static byte[] fixed32(BigInteger v) {
        var out = new byte[32];
        var src = v.toByteArray();
        // toByteArray 可能带符号位多一个字节；取低 32 字节（值为正时等价左侧补零）
        if (src.length <= 32) {
            System.arraycopy(src, 0, out, 32 - src.length, src.length);
        } else {
            System.arraycopy(src, src.length - 32, out, 0, 32);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
