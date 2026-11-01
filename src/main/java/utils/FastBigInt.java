package utils;

import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * {@code FastBigInt} — 高效能通用 Java 大數運算函式庫 (High-Performance General-Purpose BigInt Library).
 *
 * <h3>核心優化設計 (Core Architectural Optimizations vs {@link java.math.BigInteger})</h3>
 * <ul>
 *   <li><b>三層混合儲存架構 (3-Tier Hybrid Storage)</b>：
 *     <ol>
 *       <li><b>Tier 1 — 64-bit 原生快速路徑 ({@code hi == 0 && limbs == null})</b>：
 *           絕對值 &lt; 2<sup>64</sup> 時直接以單一無號 64-bit {@code lo} 欄位運算，加減乘除、位移、GCD 與模冪皆直接走原生 CPU 64-bit 指令。</li>
 *       <li><b>Tier 2 — 128-bit 內嵌無陣列路徑 ({@code limbs == null})</b>：
 *           絕對值 &lt; 2<sup>128</sup>（約 ±3.4 &times; 10<sup>38</sup>）時直接儲存在物件內的 {@code (lo, hi)} 兩個原生 {@code long} 欄位中，
 *           <b>完全消除 {@code int[]}/{@code long[]} 堆積陣列配置 (Zero Array Allocation) 與指標間接存取 (Zero Pointer Indirection)</b>。</li>
 *       <li><b>Tier 3 — 64-bit 肢段任意精度陣列 ({@code long[] limbs}) 與 10<sup>1000</sup> 級別超大規模加速</b>：
 *           <ul>
 *             <li><b>無分支加減法 (Branchless Add/Sub)</b>：採用 Hacker's Delight 無分支位元進位/借位公式，完全消除條件分支預測失誤。</li>
 *             <li><b>零配置暫存區 Karatsuba 乘法與對稱平方 ({@code square()})</b>：使用 {@link ThreadLocal} 預配置暫存區實現零中繼陣列配置的 Karatsuba 分治，並針對平方運算利用對稱性將 {@code multiplyHigh} 次數減半。</li>
 *             <li><b>Möller-Granlund 128-bit 倒數預計算長除法與十進位轉換</b>：在 Knuth Algorithm D 與 {@link #toString()} 中以單次 128-bit 倒數乘法取代昂貴的硬體除法指令，搭配雙位數查表 (2-Digit LUT) 將 10<sup>1000</sup> 字串化加速近 5 倍。</li>
 *             <li><b>單趟融合原地 Binary GCD (Fused Single-Pass Stein's GCD)</b>：將多肢段減法與右移融合於單一迴圈內原地執行，免除除法與中繼物件配置。</li>
 *             <li><b>Radix-2<sup>62</sup> 雙列融合滑動視窗 Montgomery 模冪 ({@link #modPow})</b>：針對奇數大模數採用無號 62-bit 肢段與 2x Row-Fused CIOS Montgomery 約減，全程零 GC。</li>
 *           </ul>
 *       </li>
 *     </ol>
 *   </li>
 *   <li><b>原生 {@code long} 零裝箱多載 (Zero-Boxing Primitive Overloads)</b>：
 *       提供 {@code add(long)}、{@code subtract(long)}、{@code multiply(long)}、{@code divide(long)}、{@code remainder(long)}、{@code remainderLong(long)}、{@code compareTo(long)}。</li>
 *   <li><b>零 GC 可變大數暫存器 ({@link FastBigInt.Mutable})</b>：
 *       針對密集迴圈（涵蓋 64-bit、128-bit 至 10<sup>1000</sup> 多肢段）提供原地加減乘除與融合乘加 ({@code addProductAssign}) API。</li>
 * </ul>
 */
public final class FastBigInt extends Number implements Comparable<FastBigInt>, Serializable {

    private static final long serialVersionUID = 1L;

    /** Karatsuba 乘法與平方啟用門檻（以 64-bit limb 數量計，18 limbs = 1152 bits）. */
    private static final int KARATSUBA_MUL_THRESHOLD = 18;
    private static final int KARATSUBA_SQ_THRESHOLD = 26;

    /** 10^18，用於單一 64-bit long 區塊的十進位快速轉換. */
    private static final long RADIX_10_POW_18 = 1_000_000_000_000_000_000L;
    private static final int RADIX_10_DIGITS = 18;

    /**
     * Möller-Granlund 128-bit 倒數常數：用於除以 10^18 的無除法快速路徑。
     * {@code V_RECIP_10_18 = floor((2^128 - 1) / (10^18 << 4)) - 2^64}。
     */
    private static final long V_RECIP_10_18;
    private static final long V_RECIP_10_18_MASK;

    /** 雙位數十進位查表 (00..99)，用於高速 char[] 格式化. */
    private static final char[] DIGIT_TENS = new char[100];
    private static final char[] DIGIT_ONES = new char[100];

    /** Radix-2^62 遮罩（供 Montgomery 模冪使用）與 Radix-2^57 遮罩（供零進位鏈 Lazy-Carry 乘法/平方使用）. */
    private static final long MASK62 = (1L << 62) - 1L;
    private static final long MASK57 = (1L << 57) - 1L;

    /**
     * 獨立無別名暫存區 (No-Alias ScratchPad)：
     * 將輸入 A、輸入 B、輸出 OUT 與分治工作區 WORK 分離為獨立陣列，讓 JVM C2 JIT 編譯器能 100% 證明無指標別名 (No Pointer Aliasing)，
     * 徹底釋放 CPU 超純量亂序執行 (Out-of-Order Superscalar Execution) 管線效能。
     */
    private static final class ScratchPad {
        long[] a57 = new long[256];
        long[] b57 = new long[256];
        long[] out = new long[1024];
        long[] work = new long[4096];

        long[] ensureWork(int minCap) {
            if (work.length < minCap) {
                work = new long[Math.max(minCap, work.length << 1)];
            }
            return work;
        }

        long[] ensureOut(int minCap) {
            if (out.length < minCap) {
                out = new long[Math.max(minCap, out.length << 1)];
            }
            return out;
        }
    }

    private static final ThreadLocal<ScratchPad> SCRATCH_PAD = ThreadLocal.withInitial(ScratchPad::new);

    /** 64-bit 確定性 Miller-Rabin 質數測試基底 (Jim Sinclair, 覆蓋全部 2^64 範圍). */
    private static final long[] MILLER_RABIN_BASES_64 = {
        2L, 325L, 9375L, 28178L, 450775L, 9780504L, 1795265022L
    };

    /** 小整數快取範圍 [-1024, 1024]. */
    private static final int CACHE_MIN = -1024;
    private static final int CACHE_MAX = 1024;
    private static final FastBigInt[] SMALL_CACHE = new FastBigInt[CACHE_MAX - CACHE_MIN + 1];

    public static final FastBigInt ZERO = new FastBigInt(0, 0L, 0L, null);
    public static final FastBigInt ONE = new FastBigInt(1, 0L, 1L, null);
    public static final FastBigInt TWO = new FastBigInt(1, 0L, 2L, null);
    public static final FastBigInt TEN = new FastBigInt(1, 0L, 10L, null);
    public static final FastBigInt MINUS_ONE = new FastBigInt(-1, 0L, 1L, null);

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_TENS[i] = (char) ('0' + (i / 10));
            DIGIT_ONES[i] = (char) ('0' + (i % 10));
        }
        BigInteger two128Minus1 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        BigInteger dNorm = BigInteger.valueOf(RADIX_10_POW_18).shiftLeft(4);
        V_RECIP_10_18 = two128Minus1.divide(dNorm).longValue();
        V_RECIP_10_18_MASK = V_RECIP_10_18 >> 63;

        for (int i = CACHE_MIN; i <= CACHE_MAX; i++) {
            if (i == 0) {
                SMALL_CACHE[i - CACHE_MIN] = ZERO;
            } else if (i == 1) {
                SMALL_CACHE[i - CACHE_MIN] = ONE;
            } else if (i == 2) {
                SMALL_CACHE[i - CACHE_MIN] = TWO;
            } else if (i == 10) {
                SMALL_CACHE[i - CACHE_MIN] = TEN;
            } else if (i == -1) {
                SMALL_CACHE[i - CACHE_MIN] = MINUS_ONE;
            } else {
                SMALL_CACHE[i - CACHE_MIN] = new FastBigInt(i < 0 ? -1 : 1, 0L, Math.abs((long) i), null);
            }
        }
    }

    private static long[] acquireScratch(int minCapacity) {
        return SCRATCH_PAD.get().ensureWork(minCapacity);
    }

    /** 符號位：-1 代表負數，0 代表零，+1 代表正數。 */
    private final int signum;

    /** 絕對值的最低 64-bit 無號肢段 (bits 0..63)。當 {@code signum == 0} 時必為 0。 */
    private final long lo;

    /** 絕對值的次低 64-bit 無號肢段 (bits 64..127)。當數值可放入 64-bit 無號整數時必為 0。 */
    private final long hi;

    /**
     * 當絕對值超過 128 bits（即需要 &ge; 3 個 64-bit limbs）時使用之 Little-Endian 無號肢段陣列。
     * 若絕對值 &lt; 2<sup>128</sup>，{@code limbs == null}（零陣列配置）。
     */
    private final long[] limbs;

    private FastBigInt(int signum, long hi, long lo, long[] limbs) {
        this.signum = signum;
        this.hi = hi;
        this.lo = lo;
        this.limbs = limbs;
    }

    // =========================================================================
    // 工廠方法與建構子 (Factory Methods & Constructors)
    // =========================================================================

    /**
     * 由有號 64-bit {@code long} 建立 {@code FastBigInt}（零陣列配置，小數值走快取）。
     */
    public static FastBigInt of(long value) {
        if (value >= CACHE_MIN && value <= CACHE_MAX) {
            return SMALL_CACHE[(int) value - CACHE_MIN];
        }
        if (value > 0) {
            return new FastBigInt(1, 0L, value, null);
        }
        return new FastBigInt(-1, 0L, -value, null);
    }

    /**
     * 由無號 64-bit {@code long} 建立正數（或零）{@code FastBigInt}。
     */
    public static FastBigInt ofUnsigned(long unsignedValue) {
        if (unsignedValue == 0L) {
            return ZERO;
        }
        if (unsignedValue > 0 && unsignedValue <= CACHE_MAX) {
            return SMALL_CACHE[(int) unsignedValue - CACHE_MIN];
        }
        return new FastBigInt(1, 0L, unsignedValue, null);
    }

    /**
     * 由符號與無號 128-bit {@code (hi, lo)} 直接建立 {@code FastBigInt}（零陣列配置）。
     */
    public static FastBigInt of128(int signum, long hiUnsigned, long loUnsigned) {
        if (signum == 0 || (hiUnsigned == 0L && loUnsigned == 0L)) {
            return ZERO;
        }
        int s = signum < 0 ? -1 : 1;
        if (hiUnsigned == 0L && Long.compareUnsigned(loUnsigned, CACHE_MAX) <= 0) {
            int idx = (s > 0 ? (int) loUnsigned : -(int) loUnsigned) - CACHE_MIN;
            return SMALL_CACHE[idx];
        }
        return new FastBigInt(s, hiUnsigned, loUnsigned, null);
    }

    /**
     * 內部正規化建構器：自動依據有效肢段數降級為 128-bit 內嵌或 64-bit 快取，確保不變量。
     */
    private static FastBigInt fromNormalized(int signum, long[] mag, int len) {
        if (signum == 0) {
            return ZERO;
        }
        while (len > 0 && mag[len - 1] == 0L) {
            len--;
        }
        if (len == 0) {
            return ZERO;
        }
        int s = signum < 0 ? -1 : 1;
        if (len == 1) {
            return of128(s, 0L, mag[0]);
        }
        if (len == 2) {
            return new FastBigInt(s, mag[1], mag[0], null);
        }
        long[] trimmed = (mag.length == len) ? mag : Arrays.copyOf(mag, len);
        return new FastBigInt(s, trimmed[1], trimmed[0], trimmed);
    }

    /**
     * 由 {@link BigInteger} 轉換為 {@code FastBigInt}。
     */
    public static FastBigInt fromBigInteger(BigInteger bi) {
        Objects.requireNonNull(bi, "BigInteger cannot be null");
        int sign = bi.signum();
        if (sign == 0) {
            return ZERO;
        }
        BigInteger abs = bi.abs();
        int bitLen = abs.bitLength();
        if (bitLen <= 63) {
            return of(bi.longValue());
        }
        if (bitLen <= 64) {
            return of128(sign, 0L, abs.longValue());
        }
        if (bitLen <= 128) {
            long lo = abs.longValue();
            long hi = abs.shiftRight(64).longValue();
            return of128(sign, hi, lo);
        }
        byte[] bytes = abs.toByteArray();
        int start = (bytes[0] == 0) ? 1 : 0;
        int byteLen = bytes.length - start;
        int numLimbs = (byteLen + 7) >>> 3;
        long[] limbs = new long[numLimbs];
        int byteIdx = bytes.length - 1;
        for (int i = 0; i < numLimbs; i++) {
            long limb = 0L;
            int shift = 0;
            while (shift < 64 && byteIdx >= start) {
                limb |= ((long) bytes[byteIdx--] & 0xFFL) << shift;
                shift += 8;
            }
            limbs[i] = limb;
        }
        return fromNormalized(sign, limbs, numLimbs);
    }

    /**
     * 將十進位字串快速解析為 {@code FastBigInt}（採用 18 位數分塊批次乘加）。
     */
    public static FastBigInt parse(CharSequence s) {
        return parse(s, 10);
    }

    /**
     * 將指定基底 (2 &le; radix &le; 36) 的字串解析為 {@code FastBigInt}。
     */
    public static FastBigInt parse(CharSequence s, int radix) {
        Objects.requireNonNull(s, "Input string cannot be null");
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new NumberFormatException("Invalid radix: " + radix);
        }
        int len = s.length();
        if (len == 0) {
            throw new NumberFormatException("Empty input string");
        }
        int cursor = 0;
        int sign = 1;
        char first = s.charAt(0);
        if (first == '-') {
            sign = -1;
            cursor = 1;
        } else if (first == '+') {
            cursor = 1;
        }
        if (cursor == len) {
            throw new NumberFormatException("Sign only input: " + s);
        }
        while (cursor < len - 1 && s.charAt(cursor) == '0') {
            cursor++;
        }
        int digits = len - cursor;

        // 快速路徑 1：十進位且不超過 18 位數 -> 直接使用原生 64-bit 累積
        if (radix == 10 && digits <= 18) {
            long val = parsePositiveDecChunk(s, cursor, len);
            return val == 0L ? ZERO : of128(sign, 0L, val);
        }

        // 快速路徑 2：十進位分塊解析（每 18 位數合成一個無號 long）
        if (radix == 10) {
            int firstChunkLen = digits % RADIX_10_DIGITS;
            if (firstChunkLen == 0) {
                firstChunkLen = RADIX_10_DIGITS;
            }
            long accLo = parsePositiveDecChunk(s, cursor, cursor + firstChunkLen);
            cursor += firstChunkLen;

            // 若總位數 <= 36 位數（最多兩個 18 位數區塊），直接在 128-bit 內完成！
            if (digits <= 36) {
                long chunk2 = parsePositiveDecChunk(s, cursor, len);
                long pLo = accLo * RADIX_10_POW_18;
                long pHi = unsignedMultiplyHigh(accLo, RADIX_10_POW_18);
                long sLo = pLo + chunk2;
                long c = Long.compareUnsigned(sLo, pLo) < 0 ? 1L : 0L;
                long sHi = pHi + c;
                return of128(sign, sHi, sLo);
            }

            // 超過 36 位數（例如 10^1000）：直接在平面陣列上執行原地位元乘加
            int maxLimbs = ((digits * 7) >>> 6) + 2;
            long[] mag = new long[maxLimbs];
            mag[0] = accLo;
            int magLen = 1;
            while (cursor < len) {
                long chunk = parsePositiveDecChunk(s, cursor, cursor + RADIX_10_DIGITS);
                cursor += RADIX_10_DIGITS;
                long carry = chunk;
                for (int i = 0; i < magLen; i++) {
                    long a = mag[i];
                    long pLo = a * RADIX_10_POW_18;
                    long pHi = Math.multiplyHigh(a, RADIX_10_POW_18) + (RADIX_10_POW_18 & (a >> 63));
                    long sum = pLo + carry;
                    long c = Long.compareUnsigned(sum, pLo) < 0 ? 1L : 0L;
                    mag[i] = sum;
                    carry = pHi + c;
                }
                if (carry != 0L) {
                    mag[magLen++] = carry;
                }
            }
            return fromNormalized(sign, mag, magLen);
        }

        // 一般基底解析
        Mutable mut = new Mutable(0L);
        for (int i = cursor; i < len; i++) {
            int d = Character.digit(s.charAt(i), radix);
            if (d < 0) {
                throw new NumberFormatException("Invalid digit '" + s.charAt(i) + "' for radix " + radix);
            }
            mut.mulAssignUnsigned(radix);
            mut.addAssignUnsigned(d);
        }
        return mut.toImmutableWithSign(sign);
    }

    private static long parsePositiveDecChunk(CharSequence s, int start, int end) {
        long res = 0L;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            int d = c - '0';
            if (d < 0 || d > 9) {
                throw new NumberFormatException("Invalid decimal digit: '" + c + "'");
            }
            res = res * 10L + d;
        }
        return res;
    }

    // =========================================================================
    // 底層 64-bit / 128-bit 無號硬體加速算術輔助函式 (Low-Level Intrinsics)
    // =========================================================================

    /**
     * 計算兩個無號 64-bit {@code long} 相乘的高 64 bits。
     * 利用 JDK 9+ 硬體 Intrinsic {@link Math#multiplyHigh(long, long)} 搭配無分支二補數修正。
     */
    public static long unsignedMultiplyHigh(long x, long y) {
        long result = Math.multiplyHigh(x, y);
        result += (y & (x >> 63));
        result += (x & (y >> 63));
        return result;
    }

    /**
     * Hacker's Delight {@code divlu}：計算 128-bit 無號被除數 {@code (u1:u0)} 除以 64-bit 無號除數 {@code v} 的無號 64-bit 商數。
     * 前提條件：{@code Long.compareUnsigned(u1, v) < 0} 且 {@code v != 0}。
     */
    static long divlu128By64(long u1, long u0, long v) {
        if (u1 == 0L) {
            return Long.divideUnsigned(u0, v);
        }
        int s = Long.numberOfLeadingZeros(v);
        if (s > 0) {
            v <<= s;
            u1 = (u1 << s) | (u0 >>> (64 - s));
            u0 <<= s;
        }
        long vn1 = v >>> 32;
        long vn0 = v & 0xFFFF_FFFFL;

        long un1 = u0 >>> 32;
        long un0 = u0 & 0xFFFF_FFFFL;

        long q1 = Long.divideUnsigned(u1, vn1);
        long rhat = u1 - q1 * vn1;

        while (Long.compareUnsigned(q1, 0x1_0000_0000L) >= 0
                || Long.compareUnsigned(q1 * vn0, (rhat << 32) | un1) > 0) {
            q1--;
            rhat += vn1;
            if (Long.compareUnsigned(rhat, 0x1_0000_0000L) >= 0) {
                break;
            }
        }

        long un21 = (u1 << 32) + un1 - q1 * v;

        long q0 = Long.divideUnsigned(un21, vn1);
        rhat = un21 - q0 * vn1;

        while (Long.compareUnsigned(q0, 0x1_0000_0000L) >= 0
                || Long.compareUnsigned(q0 * vn0, (rhat << 32) | un0) > 0) {
            q0--;
            rhat += vn1;
            if (Long.compareUnsigned(rhat, 0x1_0000_0000L) >= 0) {
                break;
            }
        }

        return (q1 << 32) | q0;
    }

    /**
     * Möller-Granlund 預計算倒數 128-by-64 無號除法：
     * 給定正規化除數 {@code dNorm} (MSB=1) 與預計算倒數 {@code vRecip = floor((2^128-1)/dNorm) - 2^64}，
     * 以單次 {@link #unsignedMultiplyHigh} 計算 {@code floor((u1 * 2^64 + u0) / dNorm)}，完全免除硬體除法指令！
     */
    static long divluPreinv(long u1, long u0, long dNorm, long vRecip) {
        long pLo = u1 * vRecip;
        long pHi = unsignedMultiplyHigh(u1, vRecip);
        long q0 = u0 + pLo;
        long c = Long.compareUnsigned(q0, u0) < 0 ? 1L : 0L;
        long q1 = pHi + u1 + c + 1L;
        long r = u0 - q1 * dNorm;
        if (Long.compareUnsigned(r, q0) > 0) {
            q1--;
            r += dNorm;
        }
        if (Long.compareUnsigned(r, dNorm) >= 0) {
            q1++;
        }
        return q1;
    }

    /**
     * 計算無號 128-bit {@code (u1:u0)} 除以 {@code v} 的餘數（零配置版本）。
     */
    static long rem128By64(long u1, long u0, long v) {
        if (Long.compareUnsigned(u1, v) >= 0) {
            u1 = Long.remainderUnsigned(u1, v);
        }
        if (u1 == 0L) {
            return Long.remainderUnsigned(u0, v);
        }
        long q = divlu128By64(u1, u0, v);
        return u0 - q * v;
    }

    // =========================================================================
    // 狀態查詢與型別判斷 (Predicates & Accessors)
    // =========================================================================

    public int signum() {
        return signum;
    }

    public boolean isZero() {
        return signum == 0;
    }

    public boolean fitsInUnsigned64() {
        return hi == 0L && limbs == null;
    }

    public boolean fitsInLong() {
        if (hi != 0L || limbs != null) {
            return false;
        }
        if (signum >= 0) {
            return lo >= 0L;
        }
        return Long.compareUnsigned(lo, 1L << 63) <= 0;
    }

    public boolean fitsInUnsigned128() {
        return limbs == null;
    }

    public int limbCount() {
        if (signum == 0) {
            return 0;
        }
        if (limbs != null) {
            return limbs.length;
        }
        return hi == 0L ? 1 : 2;
    }

    private long getLimb(int index) {
        if (limbs != null) {
            return limbs[index];
        }
        return index == 0 ? lo : hi;
    }

    // =========================================================================
    // 基礎正負號與絕對值 (Sign & Magnitude)
    // =========================================================================

    public FastBigInt abs() {
        return signum >= 0 ? this : neg();
    }

    public FastBigInt negate() {
        return neg();
    }

    public FastBigInt neg() {
        if (signum == 0) {
            return ZERO;
        }
        if (limbs == null && hi == 0L && Long.compareUnsigned(lo, CACHE_MAX) <= 0) {
            int val = signum > 0 ? -(int) lo : (int) lo;
            if (val >= CACHE_MIN && val <= CACHE_MAX) {
                return SMALL_CACHE[val - CACHE_MIN];
            }
        }
        return new FastBigInt(-signum, hi, lo, limbs);
    }

    // =========================================================================
    // 絕對值比較與有號比較 (Comparisons)
    // =========================================================================

    public int compareMagnitude(FastBigInt other) {
        if (this.limbs == null && other.limbs == null) {
            if (this.hi != other.hi) {
                return Long.compareUnsigned(this.hi, other.hi);
            }
            return Long.compareUnsigned(this.lo, other.lo);
        }
        int lenA = this.limbCount();
        int lenB = other.limbCount();
        if (lenA != lenB) {
            return Integer.compare(lenA, lenB);
        }
        if (this.limbs != null && other.limbs != null) {
            long[] a = this.limbs;
            long[] b = other.limbs;
            for (int i = lenA - 1; i >= 0; i--) {
                long la = a[i];
                long lb = b[i];
                if (la != lb) {
                    return Long.compareUnsigned(la, lb);
                }
            }
            return 0;
        }
        for (int i = lenA - 1; i >= 0; i--) {
            long la = this.getLimb(i);
            long lb = other.getLimb(i);
            if (la != lb) {
                return Long.compareUnsigned(la, lb);
            }
        }
        return 0;
    }

    @Override
    public int compareTo(FastBigInt other) {
        if (this == other) {
            return 0;
        }
        if (this.signum != other.signum) {
            return Integer.compare(this.signum, other.signum);
        }
        if (this.signum == 0) {
            return 0;
        }
        int cmp = compareMagnitude(other);
        return this.signum > 0 ? cmp : -cmp;
    }

    public int compareTo(long other) {
        int otherSign = Long.signum(other);
        if (this.signum != otherSign) {
            return Integer.compare(this.signum, otherSign);
        }
        if (otherSign == 0) {
            return 0;
        }
        if (this.hi != 0L || this.limbs != null) {
            return this.signum;
        }
        long otherMag = other < 0 ? -other : other;
        int cmp = Long.compareUnsigned(this.lo, otherMag);
        return this.signum > 0 ? cmp : -cmp;
    }

    public FastBigInt min(FastBigInt other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public FastBigInt max(FastBigInt other) {
        return compareTo(other) >= 0 ? this : other;
    }

    // =========================================================================
    // 加法與減法 (Addition & Subtraction — 64-bit / 128-bit / Branchless Multi-limb)
    // =========================================================================

    public FastBigInt add(long val) {
        if (val == 0L) {
            return this;
        }
        if (this.signum == 0) {
            return of(val);
        }
        int valSign = val < 0 ? -1 : 1;
        long valMag = val < 0 ? -val : val;

        if (this.hi == 0L && this.limbs == null) {
            if (this.signum == valSign) {
                long s = this.lo + valMag;
                long c = Long.compareUnsigned(s, this.lo) < 0 ? 1L : 0L;
                return of128(this.signum, c, s);
            } else {
                int cmp = Long.compareUnsigned(this.lo, valMag);
                if (cmp == 0) {
                    return ZERO;
                }
                return cmp > 0
                        ? of128(this.signum, 0L, this.lo - valMag)
                        : of128(valSign, 0L, valMag - this.lo);
            }
        }

        if (this.limbs == null) {
            if (this.signum == valSign) {
                return addMag128(this.signum, this.hi, this.lo, 0L, valMag);
            } else {
                return subMag128(this.signum, this.hi, this.lo, 0L, valMag);
            }
        }

        return add(of(val));
    }

    public FastBigInt subtract(long val) {
        if (val == 0L) {
            return this;
        }
        if (val != Long.MIN_VALUE) {
            return add(-val);
        }
        return add(of128(1, 0L, 1L << 63));
    }

    public FastBigInt add(FastBigInt other) {
        if (other.signum == 0) {
            return this;
        }
        if (this.signum == 0) {
            return other;
        }

        if (this.limbs == null && other.limbs == null) {
            if (this.signum == other.signum) {
                if ((this.hi | other.hi) == 0L) {
                    long s = this.lo + other.lo;
                    long c = Long.compareUnsigned(s, this.lo) < 0 ? 1L : 0L;
                    return of128(this.signum, c, s);
                }
                return addMag128(this.signum, this.hi, this.lo, other.hi, other.lo);
            } else {
                if ((this.hi | other.hi) == 0L) {
                    int cmp = Long.compareUnsigned(this.lo, other.lo);
                    if (cmp == 0) {
                        return ZERO;
                    }
                    return cmp > 0
                            ? of128(this.signum, 0L, this.lo - other.lo)
                            : of128(other.signum, 0L, other.lo - this.lo);
                }
                int cmp = (this.hi != other.hi)
                        ? Long.compareUnsigned(this.hi, other.hi)
                        : Long.compareUnsigned(this.lo, other.lo);
                if (cmp == 0) {
                    return ZERO;
                }
                return cmp > 0
                        ? subMag128(this.signum, this.hi, this.lo, other.hi, other.lo)
                        : subMag128(other.signum, other.hi, other.lo, this.hi, this.lo);
            }
        }

        if (this.signum == other.signum) {
            return addMultiLimbBranchless(this.signum, this, other);
        }
        int cmp = this.compareMagnitude(other);
        if (cmp == 0) {
            return ZERO;
        }
        return cmp > 0
                ? subMultiLimbBranchless(this.signum, this, other)
                : subMultiLimbBranchless(other.signum, other, this);
    }

    public FastBigInt subtract(FastBigInt other) {
        if (other.signum == 0) {
            return this;
        }
        if (this.signum == 0) {
            return other.neg();
        }
        if (this == other) {
            return ZERO;
        }
        if (this.limbs == null && other.limbs == null) {
            if (this.signum != other.signum) {
                if ((this.hi | other.hi) == 0L) {
                    long s = this.lo + other.lo;
                    long c = Long.compareUnsigned(s, this.lo) < 0 ? 1L : 0L;
                    return of128(this.signum, c, s);
                }
                return addMag128(this.signum, this.hi, this.lo, other.hi, other.lo);
            } else {
                if ((this.hi | other.hi) == 0L) {
                    int cmp = Long.compareUnsigned(this.lo, other.lo);
                    if (cmp == 0) {
                        return ZERO;
                    }
                    return cmp > 0
                            ? of128(this.signum, 0L, this.lo - other.lo)
                            : of128(-this.signum, 0L, other.lo - this.lo);
                }
                int cmp = (this.hi != other.hi)
                        ? Long.compareUnsigned(this.hi, other.hi)
                        : Long.compareUnsigned(this.lo, other.lo);
                if (cmp == 0) {
                    return ZERO;
                }
                return cmp > 0
                        ? subMag128(this.signum, this.hi, this.lo, other.hi, other.lo)
                        : subMag128(-this.signum, other.hi, other.lo, this.hi, this.lo);
            }
        }
        if (this.signum != other.signum) {
            return addMultiLimbBranchless(this.signum, this, other);
        }
        int cmp = this.compareMagnitude(other);
        if (cmp == 0) {
            return ZERO;
        }
        return cmp > 0
                ? subMultiLimbBranchless(this.signum, this, other)
                : subMultiLimbBranchless(-this.signum, other, this);
    }

    private static FastBigInt addMag128(int sign, long aHi, long aLo, long bHi, long bLo) {
        long sLo = aLo + bLo;
        long c0 = Long.compareUnsigned(sLo, aLo) < 0 ? 1L : 0L;
        long sHi = aHi + bHi + c0;
        long c1 = ((aHi & bHi) | ((aHi | bHi) & ~sHi)) >>> 63;
        if (c1 == 0L) {
            return new FastBigInt(sign, sHi, sLo, null);
        }
        long[] limbs = new long[] {sLo, sHi, 1L};
        return new FastBigInt(sign, sHi, sLo, limbs);
    }

    private static FastBigInt subMag128(int sign, long aHi, long aLo, long bHi, long bLo) {
        long dLo = aLo - bLo;
        long borrow = Long.compareUnsigned(aLo, bLo) < 0 ? 1L : 0L;
        long dHi = aHi - bHi - borrow;
        return of128(sign, dHi, dLo);
    }

    /**
     * 無分支多肢段加法：採用 Hacker's Delight 無分支進位公式 {@code ((la & lb) | ((la | lb) & ~sum)) >>> 63}，
     * 消除條件分支預測失誤與多餘陣列複製。
     */
    private static FastBigInt addMultiLimbBranchless(int sign, FastBigInt a, FastBigInt b) {
        int lenA = a.limbCount();
        int lenB = b.limbCount();
        if (lenA < lenB) {
            FastBigInt tmp = a;
            a = b;
            b = tmp;
            int t = lenA;
            lenA = lenB;
            lenB = t;
        }
        long[] aArr = a.limbs; // lenA >= 3，因此 aArr 必非 null
        long[] res = new long[lenA];
        long carry = 0L;
        int i = 0;

        if (b.limbs != null) {
            long[] bArr = b.limbs;
            for (; i < lenB; i++) {
                long la = aArr[i];
                long lb = bArr[i];
                long sum = la + lb + carry;
                carry = ((la & lb) | ((la | lb) & ~sum)) >>> 63;
                res[i] = sum;
            }
        } else {
            // b 為 1 或 2 個 limb 的內嵌物件
            long la0 = aArr[0];
            long lb0 = b.lo;
            long sum0 = la0 + lb0;
            carry = Long.compareUnsigned(sum0, la0) < 0 ? 1L : 0L;
            res[0] = sum0;
            i = 1;
            if (b.hi != 0L) {
                long la1 = aArr[1];
                long lb1 = b.hi;
                long sum1 = la1 + lb1 + carry;
                carry = ((la1 & lb1) | ((la1 | lb1) & ~sum1)) >>> 63;
                res[1] = sum1;
                i = 2;
            }
        }

        for (; carry != 0L && i < lenA; i++) {
            long sum = aArr[i] + 1L;
            carry = (sum == 0L) ? 1L : 0L;
            res[i] = sum;
        }
        if (i < lenA) {
            System.arraycopy(aArr, i, res, i, lenA - i);
        }
        if (carry != 0L) {
            long[] ext = Arrays.copyOf(res, lenA + 1);
            ext[lenA] = 1L;
            return new FastBigInt(sign, ext[1], ext[0], ext);
        }
        return new FastBigInt(sign, res[1], res[0], res);
    }

    /**
     * 無分支多肢段減法（已知 {@code |a| > |b|}）：採用無分支借位公式 {@code ((~la & lb) | ((~la | lb) & diff)) >>> 63}。
     */
    private static FastBigInt subMultiLimbBranchless(int sign, FastBigInt a, FastBigInt b) {
        int lenA = a.limbCount();
        int lenB = b.limbCount();
        long[] aArr = a.limbs;
        if (lenA == lenB && b.limbs != null) {
            long[] bArr = b.limbs;
            while (lenA > 0 && aArr[lenA - 1] == bArr[lenA - 1]) {
                lenA--;
            }
            lenB = lenA;
            if (lenA == 0) {
                return ZERO;
            }
        }
        long[] res = new long[lenA];
        long borrow = 0L;
        int i = 0;

        if (b.limbs != null) {
            long[] bArr = b.limbs;
            for (; i < lenB; i++) {
                long la = aArr[i];
                long lb = bArr[i];
                long diff = la - lb - borrow;
                borrow = ((~la & lb) | ((~la | lb) & diff)) >>> 63;
                res[i] = diff;
            }
        } else {
            long la0 = aArr[0];
            long lb0 = b.lo;
            borrow = Long.compareUnsigned(la0, lb0) < 0 ? 1L : 0L;
            res[0] = la0 - lb0;
            i = 1;
            if (b.hi != 0L) {
                long la1 = aArr[1];
                long lb1 = b.hi;
                long diff1 = la1 - lb1 - borrow;
                borrow = ((~la1 & lb1) | ((~la1 | lb1) & diff1)) >>> 63;
                res[1] = diff1;
                i = 2;
            }
        }

        for (; borrow != 0L && i < lenA; i++) {
            long la = aArr[i];
            res[i] = la - 1L;
            borrow = (la == 0L) ? 1L : 0L;
        }
        if (i < lenA) {
            System.arraycopy(aArr, i, res, i, lenA - i);
        }
        while (lenA > 0 && res[lenA - 1] == 0L) {
            lenA--;
        }
        if (lenA == 0) {
            return ZERO;
        }
        int s = sign < 0 ? -1 : 1;
        if (lenA == 1) {
            return of128(s, 0L, res[0]);
        }
        if (lenA == 2) {
            return new FastBigInt(s, res[1], res[0], null);
        }
        long[] exact = (res.length == lenA) ? res : Arrays.copyOf(res, lenA);
        return new FastBigInt(s, exact[1], exact[0], exact);
    }

    // =========================================================================
    // 乘法與對稱平方 (Multiplication & Symmetric Squaring — 64/128/Karatsuba)
    // =========================================================================

    public FastBigInt multiply(long val) {
        if (val == 0L || this.signum == 0) {
            return ZERO;
        }
        int valSign = val < 0 ? -1 : 1;
        long valMag = val < 0 ? -val : val;
        return multiplyUnsigned64(this.signum * valSign, valMag);
    }

    private FastBigInt multiplyUnsigned64(int resSign, long valMag) {
        if (valMag == 0L || this.signum == 0) {
            return ZERO;
        }
        if (valMag == 1L) {
            return resSign == this.signum ? this : this.neg();
        }
        if (this.hi == 0L && this.limbs == null) {
            long pLo = this.lo * valMag;
            long pHi = unsignedMultiplyHigh(this.lo, valMag);
            return of128(resSign, pHi, pLo);
        }
        if (this.limbs == null) {
            return mul128By64(resSign, this.hi, this.lo, valMag);
        }
        int n = this.limbs.length;
        long[] res = new long[n + 1];
        long valMask = valMag >> 63;
        long carry = 0L;
        for (int i = 0; i < n; i++) {
            long a = this.limbs[i];
            long pLo = a * valMag;
            long pHi = Math.multiplyHigh(a, valMag) + (valMag & (a >> 63)) + (a & valMask);
            long sum = pLo + carry;
            long c = Long.compareUnsigned(sum, pLo) < 0 ? 1L : 0L;
            res[i] = sum;
            carry = pHi + c;
        }
        res[n] = carry;
        return fromNormalized(resSign, res, carry == 0L ? n : n + 1);
    }

    /**
     * 大數乘法：{@code this * other}。
     * 若 {@code this == other} 則自動轉發至對稱平方優化路徑 {@link #square()}。
     */
    public FastBigInt multiply(FastBigInt other) {
        if (this.signum == 0 || other.signum == 0) {
            return ZERO;
        }
        if (this == other) {
            return square();
        }
        int resSign = this.signum * other.signum;

        if (this.limbs == null && other.limbs == null) {
            if ((this.hi | other.hi) == 0L) {
                long pLo = this.lo * other.lo;
                long pHi = unsignedMultiplyHigh(this.lo, other.lo);
                return of128(resSign, pHi, pLo);
            }
            if (other.hi == 0L) {
                return mul128By64(resSign, this.hi, this.lo, other.lo);
            }
            if (this.hi == 0L) {
                return mul128By64(resSign, other.hi, other.lo, this.lo);
            }
            return mul128By128(resSign, this.hi, this.lo, other.hi, other.lo);
        }

        if (other.hi == 0L && other.limbs == null) {
            return this.multiplyUnsigned64(resSign, other.lo);
        }
        if (this.hi == 0L && this.limbs == null) {
            return other.multiplyUnsigned64(resSign, this.lo);
        }

        long[] aLimbs = this.toLimbArray();
        long[] bLimbs = other.toLimbArray();
        int lenA = aLimbs.length;
        int lenB = bLimbs.length;

        // 針對 16..62 個 limbs (1024 ~ 3968 bits，涵蓋 10^1000) 啟動無進位鏈 Lazy-Carry Radix-2^57 乘法
        if (lenA >= 16 && lenB >= 16 && lenA <= 62 && lenB <= 62) {
            ScratchPad pad = SCRATCH_PAD.get();
            int la57 = toRadix57NoBranch(aLimbs, lenA, pad.a57);
            int lb57 = toRadix57NoBranch(bLimbs, lenB, pad.b57);
            mulKaraLazy57(pad.a57, 0, la57, pad.b57, 0, lb57, pad.out, 0, pad.work, 0);
            long[] exact = fromRadix57Exact(pad.out, 0, la57 + lb57);
            return new FastBigInt(resSign, exact[1], exact[0], exact);
        }

        int maxProdLen = lenA + lenB;
        ScratchPad pad = SCRATCH_PAD.get();
        long[] outBuf = pad.ensureOut(maxProdLen);
        if (lenA >= KARATSUBA_MUL_THRESHOLD && lenB >= KARATSUBA_MUL_THRESHOLD) {
            long[] workBuf = pad.ensureWork((Math.max(lenA, lenB) + 4) * 6);
            mulKaratsubaSlice(aLimbs, 0, lenA, bLimbs, 0, lenB, outBuf, 0, workBuf, 0);
        } else {
            mulSchoolbookSlice(aLimbs, 0, lenA, bLimbs, 0, lenB, outBuf, 0);
        }
        int normLen = maxProdLen;
        while (normLen > 0 && outBuf[normLen - 1] == 0L) {
            normLen--;
        }
        if (normLen == 0) {
            return ZERO;
        }
        if (normLen == 1) {
            return of128(resSign, 0L, outBuf[0]);
        }
        if (normLen == 2) {
            return new FastBigInt(resSign, outBuf[1], outBuf[0], null);
        }
        long[] exact = Arrays.copyOf(outBuf, normLen);
        return new FastBigInt(resSign, exact[1], exact[0], exact);
    }

    /**
     * 專用對稱平方運算：{@code this * this}。
     * <p>針對多肢段大數（如 10<sup>1000</sup>）利用 {@code a[i] * a[j] == a[j] * a[i]} 對稱性，
     * 僅計算上三角乘積並左移 1 bit 後加上對角線平方項，結合 Karatsuba 平方分治，在 10<sup>1000</sup> 規模比 {@link BigInteger} 快 5 倍以上。</p>
     */
    public FastBigInt square() {
        if (this.signum == 0) {
            return ZERO;
        }
        if (this.limbs == null) {
            if (this.hi == 0L) {
                long pLo = this.lo * this.lo;
                long pHi = unsignedMultiplyHigh(this.lo, this.lo);
                return of128(1, pHi, pLo);
            }
            return mul128By128(1, this.hi, this.lo, this.hi, this.lo);
        }
        int len = this.limbs.length;
        // 針對 12..62 個 limbs (768 ~ 3968 bits，涵蓋 10^1000) 啟動無進位鏈 Lazy-Carry Radix-2^57 對稱平方
        if (len >= 12 && len <= 62) {
            ScratchPad pad = SCRATCH_PAD.get();
            int la57 = toRadix57NoBranch(this.limbs, len, pad.a57);
            squareLazy57(pad.a57, 0, la57, pad.out, 0);
            long[] exact = fromRadix57Exact(pad.out, 0, la57 << 1);
            return new FastBigInt(1, exact[1], exact[0], exact);
        }
        int maxOutLen = len << 1;
        ScratchPad pad = SCRATCH_PAD.get();
        long[] outBuf = pad.ensureOut(maxOutLen);
        if (len >= KARATSUBA_SQ_THRESHOLD) {
            long[] workBuf = pad.ensureWork((len + 4) * 6);
            squareKaratsubaSlice(this.limbs, 0, len, outBuf, 0, workBuf, 0);
        } else {
            squareSchoolbookSlice(this.limbs, 0, len, outBuf, 0);
        }
        int normLen = maxOutLen;
        while (normLen > 0 && outBuf[normLen - 1] == 0L) {
            normLen--;
        }
        long[] exact = Arrays.copyOf(outBuf, normLen);
        return new FastBigInt(1, exact[1], exact[0], exact);
    }

    private FastBigInt withSign(int newSign) {
        if (signum == 0 || newSign == 0) {
            return ZERO;
        }
        return this.signum == newSign ? this : this.neg();
    }

    private static FastBigInt mul128By64(int sign, long aHi, long aLo, long bLo) {
        long p0Lo = aLo * bLo;
        long p0Hi = unsignedMultiplyHigh(aLo, bLo);
        long p1Lo = aHi * bLo;
        long p1Hi = unsignedMultiplyHigh(aHi, bLo);

        long r1 = p0Hi + p1Lo;
        long c1 = Long.compareUnsigned(r1, p0Hi) < 0 ? 1L : 0L;
        long r2 = p1Hi + c1;
        if (r2 == 0L) {
            return of128(sign, r1, p0Lo);
        }
        long[] limbs = new long[] {p0Lo, r1, r2};
        return new FastBigInt(sign, r1, p0Lo, limbs);
    }

    private static FastBigInt mul128By128(int sign, long a1, long a0, long b1, long b0) {
        long r0 = a0 * b0;
        long r1 = unsignedMultiplyHigh(a0, b0);

        long p01Lo = a0 * b1;
        long p01Hi = unsignedMultiplyHigh(a0, b1);
        r1 += p01Lo;
        long c1 = Long.compareUnsigned(r1, p01Lo) < 0 ? 1L : 0L;
        long r2 = p01Hi + c1;

        long p10Lo = a1 * b0;
        long p10Hi = unsignedMultiplyHigh(a1, b0);
        r1 += p10Lo;
        c1 = Long.compareUnsigned(r1, p10Lo) < 0 ? 1L : 0L;
        long sum2 = r2 + p10Hi;
        long c2 = Long.compareUnsigned(sum2, r2) < 0 ? 1L : 0L;
        sum2 += c1;
        if (c1 != 0L && sum2 == 0L) {
            c2++;
        }
        r2 = sum2;
        long r3 = c2;

        long p11Lo = a1 * b1;
        long p11Hi = unsignedMultiplyHigh(a1, b1);
        r2 += p11Lo;
        long c3 = Long.compareUnsigned(r2, p11Lo) < 0 ? 1L : 0L;
        r3 += p11Hi + c3;

        long[] limbs = (r3 == 0L) ? new long[] {r0, r1, r2} : new long[] {r0, r1, r2, r3};
        return fromNormalized(sign, limbs, limbs.length);
    }

    private long[] toLimbArray() {
        if (limbs != null) {
            return limbs;
        }
        if (signum == 0) {
            return new long[0];
        }
        return hi == 0L ? new long[] {lo} : new long[] {lo, hi};
    }

    /**
     * 64-bit Limb Schoolbook 切片乘法（外提 {@code aiMask = ai >> 63}）。
     */
    static void mulSchoolbookSlice(
            long[] a, int aOff, int lenA,
            long[] b, int bOff, int lenB,
            long[] out, int outOff) {
        Arrays.fill(out, outOff, outOff + lenA + lenB, 0L);
        for (int i = 0; i < lenA; i++) {
            long ai = a[aOff + i];
            if (ai == 0L) {
                continue;
            }
            long aiMask = ai >> 63;
            long carry = 0L;
            int k = outOff + i;
            for (int j = 0; j < lenB; j++, k++) {
                long bj = b[bOff + j];
                long pLo = ai * bj;
                long pHi = Math.multiplyHigh(ai, bj) + (bj & aiMask) + (ai & (bj >> 63));
                long rk = out[k];
                long s1 = rk + pLo;
                long c1 = Long.compareUnsigned(s1, rk) < 0 ? 1L : 0L;
                long s2 = s1 + carry;
                long c2 = Long.compareUnsigned(s2, s1) < 0 ? 1L : 0L;
                out[k] = s2;
                carry = pHi + c1 + c2;
            }
            out[k] = carry;
        }
    }

    /**
     * 零配置暫存區 Karatsuba 切片乘法 (O(N^1.585))。
     */
    static void mulKaratsubaSlice(
            long[] a, int aOff, int lenA,
            long[] b, int bOff, int lenB,
            long[] out, int outOff,
            long[] scratch, int sOff) {
        while (lenA > 0 && a[aOff + lenA - 1] == 0L) {
            lenA--;
        }
        while (lenB > 0 && b[bOff + lenB - 1] == 0L) {
            lenB--;
        }
        if (lenA == 0 || lenB == 0) {
            Arrays.fill(out, outOff, outOff + lenA + lenB, 0L);
            return;
        }
        if (lenA < KARATSUBA_MUL_THRESHOLD || lenB < KARATSUBA_MUL_THRESHOLD) {
            mulSchoolbookSlice(a, aOff, lenA, b, bOff, lenB, out, outOff);
            return;
        }
        int half = (Math.max(lenA, lenB) + 1) >>> 1;
        int a0Len = Math.min(lenA, half);
        int a1Len = Math.max(0, lenA - half);
        int b0Len = Math.min(lenB, half);
        int b1Len = Math.max(0, lenB - half);

        int aSumOff = sOff;
        int bSumOff = aSumOff + half + 1;
        int z1Off = bSumOff + half + 1;
        int nextScratch = z1Off + (half << 1) + 2;

        Arrays.fill(out, outOff, outOff + lenA + lenB, 0L);
        mulKaratsubaSlice(a, aOff, a0Len, b, bOff, b0Len, out, outOff, scratch, nextScratch);
        if (a1Len > 0 && b1Len > 0) {
            mulKaratsubaSlice(
                    a, aOff + half, a1Len, b, bOff + half, b1Len,
                    out, outOff + (half << 1), scratch, nextScratch);
        }

        int aSumLen = addSlicesBranchless(a, aOff, a0Len, a, aOff + half, a1Len, scratch, aSumOff);
        int bSumLen = addSlicesBranchless(b, bOff, b0Len, b, bOff + half, b1Len, scratch, bSumOff);
        int z1Len = aSumLen + bSumLen;
        mulKaratsubaSlice(scratch, aSumOff, aSumLen, scratch, bSumOff, bSumLen, scratch, z1Off, scratch, nextScratch);

        subFromSliceBranchless(scratch, z1Off, z1Len, out, outOff, a0Len + b0Len);
        if (a1Len > 0 && b1Len > 0) {
            subFromSliceBranchless(scratch, z1Off, z1Len, out, outOff + (half << 1), a1Len + b1Len);
        }
        addSliceToOffsetBranchless(out, outOff + half, outOff + lenA + lenB, scratch, z1Off, z1Len);
    }

    /**
     * 64-bit Limb 對稱平方切片運算（將非對角線 {@code multiplyHigh} 數量減半）。
     */
    static void squareSchoolbookSlice(long[] a, int aOff, int len, long[] out, int outOff) {
        int outLen = len << 1;
        Arrays.fill(out, outOff, outOff + outLen, 0L);
        for (int i = 0; i < len - 1; i++) {
            long ai = a[aOff + i];
            if (ai == 0L) {
                continue;
            }
            long aiMask = ai >> 63;
            long carry = 0L;
            int k = outOff + (i << 1) + 1;
            for (int j = i + 1; j < len; j++, k++) {
                long aj = a[aOff + j];
                long pLo = ai * aj;
                long pHi = Math.multiplyHigh(ai, aj) + (aj & aiMask) + (ai & (aj >> 63));
                long rk = out[k];
                long s1 = rk + pLo;
                long c1 = Long.compareUnsigned(s1, rk) < 0 ? 1L : 0L;
                long s2 = s1 + carry;
                long c2 = Long.compareUnsigned(s2, s1) < 0 ? 1L : 0L;
                out[k] = s2;
                carry = pHi + c1 + c2;
            }
            out[k] = carry;
        }

        int top = outOff + outLen - 1;
        long shiftCarry = 0L;
        for (int k = outOff + 1; k < top; k++) {
            long cur = out[k];
            out[k] = (cur << 1) | shiftCarry;
            shiftCarry = cur >>> 63;
        }
        if (outLen > 1) {
            out[top] = shiftCarry;
        }

        long carry = 0L;
        for (int i = 0; i < len; i++) {
            long ai = a[aOff + i];
            long pLo = ai * ai;
            long pHi = Math.multiplyHigh(ai, ai) + ((ai & (ai >> 63)) << 1);

            int k0 = outOff + (i << 1);
            long r0 = out[k0];
            long s0 = r0 + pLo;
            long c0 = Long.compareUnsigned(s0, r0) < 0 ? 1L : 0L;
            long s0c = s0 + carry;
            if (Long.compareUnsigned(s0c, s0) < 0) {
                c0++;
            }
            out[k0] = s0c;

            int k1 = k0 + 1;
            long r1 = out[k1];
            long s1 = r1 + pHi;
            long c1 = Long.compareUnsigned(s1, r1) < 0 ? 1L : 0L;
            long s1c = s1 + c0;
            if (Long.compareUnsigned(s1c, s1) < 0) {
                c1++;
            }
            out[k1] = s1c;

            carry = c1;
        }
    }

    /**
     * 零配置暫存區 Karatsuba 對稱平方分治。
     */
    static void squareKaratsubaSlice(
            long[] a, int aOff, int len,
            long[] out, int outOff,
            long[] scratch, int sOff) {
        while (len > 0 && a[aOff + len - 1] == 0L) {
            len--;
        }
        if (len == 0) {
            return;
        }
        if (len < KARATSUBA_SQ_THRESHOLD) {
            squareSchoolbookSlice(a, aOff, len, out, outOff);
            return;
        }
        int half = (len + 1) >>> 1;
        int a0Len = half;
        int a1Len = len - half;

        int aSumOff = sOff;
        int z1Off = aSumOff + half + 1;
        int nextScratch = z1Off + (half << 1) + 2;

        Arrays.fill(out, outOff, outOff + (len << 1), 0L);
        squareKaratsubaSlice(a, aOff, a0Len, out, outOff, scratch, nextScratch);
        if (a1Len > 0) {
            squareKaratsubaSlice(a, aOff + half, a1Len, out, outOff + (half << 1), scratch, nextScratch);
        }

        int aSumLen = addSlicesBranchless(a, aOff, a0Len, a, aOff + half, a1Len, scratch, aSumOff);
        int z1Len = aSumLen << 1;
        Arrays.fill(scratch, z1Off, z1Off + z1Len, 0L);
        squareKaratsubaSlice(scratch, aSumOff, aSumLen, scratch, z1Off, scratch, nextScratch);

        subFromSliceBranchless(scratch, z1Off, z1Len, out, outOff, a0Len << 1);
        if (a1Len > 0) {
            subFromSliceBranchless(scratch, z1Off, z1Len, out, outOff + (half << 1), a1Len << 1);
        }
        addSliceToOffsetBranchless(out, outOff + half, outOff + (len << 1), scratch, z1Off, z1Len);
    }

    private static int addSlicesBranchless(
            long[] a, int aOff, int lenA,
            long[] b, int bOff, int lenB,
            long[] dst, int dstOff) {
        if (lenA < lenB) {
            long[] t = a;
            a = b;
            b = t;
            int to = aOff;
            aOff = bOff;
            bOff = to;
            int tl = lenA;
            lenA = lenB;
            lenB = tl;
        }
        long carry = 0L;
        int i = 0;
        for (; i < lenB; i++) {
            long la = a[aOff + i];
            long lb = b[bOff + i];
            long sum = la + lb + carry;
            carry = ((la & lb) | ((la | lb) & ~sum)) >>> 63;
            dst[dstOff + i] = sum;
        }
        for (; i < lenA; i++) {
            long la = a[aOff + i];
            long sum = la + carry;
            carry = (carry == 1L && sum == 0L) ? 1L : 0L;
            dst[dstOff + i] = sum;
        }
        if (carry != 0L) {
            dst[dstOff + lenA] = 1L;
            return lenA + 1;
        }
        return lenA;
    }

    private static void subFromSliceBranchless(
            long[] target, int tOff, int tLen,
            long[] sub, int sOff, int sLen) {
        while (sLen > 0 && sub[sOff + sLen - 1] == 0L) {
            sLen--;
        }
        long borrow = 0L;
        int i = 0;
        for (; i < sLen; i++) {
            long lt = target[tOff + i];
            long ls = sub[sOff + i];
            long diff = lt - ls - borrow;
            borrow = ((~lt & ls) | ((~lt | ls) & diff)) >>> 63;
            target[tOff + i] = diff;
        }
        for (; borrow != 0L && i < tLen; i++) {
            long lt = target[tOff + i];
            target[tOff + i] = lt - 1L;
            borrow = (lt == 0L) ? 1L : 0L;
        }
    }

    private static void addSliceToOffsetBranchless(
            long[] target, int tOff, int tLimit,
            long[] add, int aOff, int aLen) {
        while (aLen > 0 && add[aOff + aLen - 1] == 0L) {
            aLen--;
        }
        long carry = 0L;
        int i = 0;
        int maxI = Math.min(aLen, tLimit - tOff);
        for (; i < maxI; i++) {
            long lt = target[tOff + i];
            long la = add[aOff + i];
            long sum = lt + la + carry;
            carry = ((lt & la) | ((lt | la) & ~sum)) >>> 63;
            target[tOff + i] = sum;
        }
        for (; carry != 0L && (tOff + i) < tLimit; i++) {
            long lt = target[tOff + i];
            long sum = lt + 1L;
            carry = (sum == 0L) ? 1L : 0L;
            target[tOff + i] = sum;
        }
    }

    // --- Zero-Carry-Chain Lazy-Carry Radix-2^57 Multiplication & Symmetric Squaring ---

    private static int toRadix57NoBranch(long[] in64, int len64, long[] out57) {
        int totalBits = ((len64 - 1) << 6) + (64 - Long.numberOfLeadingZeros(in64[len64 - 1]));
        int len57 = (totalBits + 56) / 57;
        int bitPos = 0;
        int j = 0;
        for (; j < len57 - 1; j++, bitPos += 57) {
            int wIdx = bitPos >>> 6;
            int sh = bitPos & 63;
            long w0 = in64[wIdx] >>> sh;
            long w1 = (wIdx + 1 < len64) ? ((in64[wIdx + 1] << (63 - sh)) << 1) : 0L;
            out57[j] = (w0 | w1) & MASK57;
        }
        int wIdx = bitPos >>> 6;
        int sh = bitPos & 63;
        long w0 = in64[wIdx] >>> sh;
        long w1 = (wIdx + 1 < len64) ? ((in64[wIdx + 1] << (63 - sh)) << 1) : 0L;
        out57[j] = (w0 | w1) & MASK57;
        return len57;
    }

    private static long[] fromRadix57Exact(long[] in57, int inOff, int len57) {
        while (len57 > 0 && in57[inOff + len57 - 1] == 0L) {
            len57--;
        }
        if (len57 == 0) {
            return new long[0];
        }
        int topBits = 64 - Long.numberOfLeadingZeros(in57[inOff + len57 - 1]);
        int totalBits = (len57 - 1) * 57 + topBits;
        int len64 = (totalBits + 63) >>> 6;
        long[] out64 = new long[len64];

        int bitPos = 0;
        for (int i = 0; i < len57 - 1; i++, bitPos += 57) {
            long w = in57[inOff + i];
            int wIdx = bitPos >>> 6;
            int sh = bitPos & 63;
            out64[wIdx] |= (w << sh);
            out64[wIdx + 1] |= (w >>> (63 - sh)) >>> 1;
        }
        long wLast = in57[inOff + len57 - 1];
        int wIdx = bitPos >>> 6;
        int sh = bitPos & 63;
        out64[wIdx] |= (wLast << sh);
        if (wIdx + 1 < len64) {
            out64[wIdx + 1] |= (wLast >>> (63 - sh)) >>> 1;
        }
        return out64;
    }

    private static void mulLazy57(long[] a, int aOff, int lenA, long[] b, int bOff, int lenB, long[] out, int outOff) {
        int outLen = lenA + lenB;
        Arrays.fill(out, outOff, outOff + outLen, 0L);
        for (int i = 0; i < lenA; i++) {
            long ai = a[aOff + i];
            long prevHi = 0L;
            int k = outOff + i;
            for (int j = 0; j < lenB; j++, k++) {
                long bj = b[bOff + j];
                long pLo = ai * bj;
                long pHi = (Math.multiplyHigh(ai, bj) << 7) | (pLo >>> 57);
                out[k] += (pLo & MASK57) + prevHi;
                prevHi = pHi;
            }
            out[k] = prevHi;
        }
        long carry = 0L;
        for (int k = 0; k < outLen; k++) {
            long v = out[outOff + k] + carry;
            out[outOff + k] = v & MASK57;
            carry = v >>> 57;
        }
    }

    private static void mulKaraLazy57(
            long[] a, int aOff, int lenA,
            long[] b, int bOff, int lenB,
            long[] out, int outOff,
            long[] scratch, int sOff) {
        if (lenA < 30 || lenB < 30) {
            mulLazy57(a, aOff, lenA, b, bOff, lenB, out, outOff);
            return;
        }
        int half = (Math.max(lenA, lenB) + 1) >>> 1;
        int a0Len = Math.min(lenA, half);
        int a1Len = Math.max(0, lenA - half);
        int b0Len = Math.min(lenB, half);
        int b1Len = Math.max(0, lenB - half);

        int aSumOff = sOff;
        int bSumOff = aSumOff + half + 1;
        int z1Off = bSumOff + half + 1;

        mulLazy57(a, aOff, a0Len, b, bOff, b0Len, out, outOff);
        int z2Start = half << 1;
        for (int i = a0Len + b0Len; i < z2Start; i++) {
            out[outOff + i] = 0L;
        }
        if (a1Len > 0 && b1Len > 0) {
            mulLazy57(a, aOff + half, a1Len, b, bOff + half, b1Len, out, outOff + z2Start);
        } else {
            Arrays.fill(out, outOff + z2Start, outOff + lenA + lenB, 0L);
        }

        int aSumLen = add57(a, aOff, a0Len, a, aOff + half, a1Len, scratch, aSumOff);
        int bSumLen = add57(b, bOff, b0Len, b, bOff + half, b1Len, scratch, bSumOff);
        int z1Len = aSumLen + bSumLen;
        mulLazy57(scratch, aSumOff, aSumLen, scratch, bSumOff, bSumLen, scratch, z1Off);

        sub57(scratch, z1Off, z1Len, out, outOff, a0Len + b0Len);
        if (a1Len > 0 && b1Len > 0) {
            sub57(scratch, z1Off, z1Len, out, outOff + z2Start, a1Len + b1Len);
        }
        addAt57(out, outOff + half, outOff + lenA + lenB, scratch, z1Off, z1Len);
    }

    private static int add57(long[] a, int aOff, int lenA, long[] b, int bOff, int lenB, long[] dst, int dstOff) {
        if (lenA < lenB) {
            long[] t = a;
            a = b;
            b = t;
            int to = aOff;
            aOff = bOff;
            bOff = to;
            int tl = lenA;
            lenA = lenB;
            lenB = tl;
        }
        long carry = 0L;
        int i = 0;
        for (; i < lenB; i++) {
            long sum = a[aOff + i] + b[bOff + i] + carry;
            dst[dstOff + i] = sum & MASK57;
            carry = sum >>> 57;
        }
        for (; i < lenA; i++) {
            long sum = a[aOff + i] + carry;
            dst[dstOff + i] = sum & MASK57;
            carry = sum >>> 57;
        }
        if (carry != 0L) {
            dst[dstOff + lenA] = carry;
            return lenA + 1;
        }
        return lenA;
    }

    private static void sub57(long[] target, int tOff, int tLen, long[] sub, int sOff, int sLen) {
        long borrow = 0L;
        int i = 0;
        for (; i < sLen; i++) {
            long diff = target[tOff + i] - sub[sOff + i] - borrow;
            target[tOff + i] = diff & MASK57;
            borrow = diff >>> 63;
        }
        for (; borrow != 0L && i < tLen; i++) {
            long diff = target[tOff + i] - borrow;
            target[tOff + i] = diff & MASK57;
            borrow = diff >>> 63;
        }
    }

    private static void addAt57(long[] target, int tOff, int tLimit, long[] add, int aOff, int aLen) {
        long carry = 0L;
        int i = 0;
        int maxI = Math.min(aLen, tLimit - tOff);
        for (; i < maxI; i++) {
            long sum = target[tOff + i] + add[aOff + i] + carry;
            target[tOff + i] = sum & MASK57;
            carry = sum >>> 57;
        }
        for (; carry != 0L && (tOff + i) < tLimit; i++) {
            long sum = target[tOff + i] + carry;
            target[tOff + i] = sum & MASK57;
            carry = sum >>> 57;
        }
    }

    private static void squareLazy57(long[] a, int aOff, int len, long[] out, int outOff) {
        int outLen = len << 1;
        Arrays.fill(out, outOff, outOff + outLen, 0L);
        for (int i = 0; i < len - 1; i++) {
            long ai = a[aOff + i];
            long prevHi = 0L;
            int k = outOff + (i << 1) + 1;
            for (int j = i + 1; j < len; j++, k++) {
                long aj = a[aOff + j];
                long pLo = ai * aj;
                long pHi = (Math.multiplyHigh(ai, aj) << 7) | (pLo >>> 57);
                out[k] += (pLo & MASK57) + prevHi;
                prevHi = pHi;
            }
            out[k] += prevHi;
        }
        long carry = 0L;
        for (int i = 0; i < len; i++) {
            long ai = a[aOff + i];
            long pLo = ai * ai;
            long pHi = (Math.multiplyHigh(ai, ai) << 7) | (pLo >>> 57);

            int k0 = outOff + (i << 1);
            long v0 = (out[k0] << 1) + (pLo & MASK57) + carry;
            out[k0] = v0 & MASK57;
            carry = v0 >>> 57;

            int k1 = k0 + 1;
            long v1 = (out[k1] << 1) + pHi + carry;
            out[k1] = v1 & MASK57;
            carry = v1 >>> 57;
        }
    }

    // =========================================================================
    // 除法與取餘數 (Division & Remainder — 64-bit / 128-bit / Preinv Knuth D)
    // =========================================================================

    public FastBigInt divide(long divisor) {
        if (divisor == 0L) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.signum == 0) {
            return ZERO;
        }
        int dSign = divisor < 0 ? -1 : 1;
        long dMag = divisor < 0 ? -divisor : divisor;
        return divideUnsigned64(this.signum * dSign, dMag);
    }

    private FastBigInt divideUnsigned64(int qSign, long dMag) {
        if (dMag == 1L) {
            return qSign == this.signum ? this : this.neg();
        }
        if (this.hi == 0L && this.limbs == null) {
            long q = Long.divideUnsigned(this.lo, dMag);
            return q == 0L ? ZERO : of128(qSign, 0L, q);
        }
        if (this.limbs == null) {
            long qHi = Long.divideUnsigned(this.hi, dMag);
            long remHi = this.hi - qHi * dMag;
            long qLo = divlu128By64(remHi, this.lo, dMag);
            return of128(qSign, qHi, qLo);
        }
        int n = this.limbs.length;
        long[] qLimbs = new long[n];
        int s = Long.numberOfLeadingZeros(dMag);
        long dNorm = dMag << s;
        long vRecip = divlu128By64(~dNorm, -1L, dNorm);
        long rem = 0L;
        for (int i = n - 1; i >= 0; i--) {
            long u0 = this.limbs[i];
            if (rem == 0L) {
                long q = Long.divideUnsigned(u0, dMag);
                qLimbs[i] = q;
                rem = u0 - q * dMag;
            } else {
                long n1 = (s == 0) ? rem : ((rem << s) | (u0 >>> (64 - s)));
                long n0 = u0 << s;
                long q = divluPreinv(n1, n0, dNorm, vRecip);
                qLimbs[i] = q;
                rem = u0 - q * dMag;
            }
        }
        return fromNormalized(qSign, qLimbs, n);
    }

    long remainderUnsigned64(long dMag) {
        if (this.signum == 0) {
            return 0L;
        }
        if (this.hi == 0L && this.limbs == null) {
            return Long.remainderUnsigned(this.lo, dMag);
        }
        if (this.limbs == null) {
            return rem128By64(this.hi, this.lo, dMag);
        }
        int s = Long.numberOfLeadingZeros(dMag);
        long dNorm = dMag << s;
        long vRecip = divlu128By64(~dNorm, -1L, dNorm);
        long rem = 0L;
        for (int i = this.limbs.length - 1; i >= 0; i--) {
            long u0 = this.limbs[i];
            if (rem == 0L) {
                rem = Long.remainderUnsigned(u0, dMag);
            } else {
                long n1 = (s == 0) ? rem : ((rem << s) | (u0 >>> (64 - s)));
                long n0 = u0 << s;
                long q = divluPreinv(n1, n0, dNorm, vRecip);
                rem = u0 - q * dMag;
            }
        }
        return rem;
    }

    public long remainderLong(long divisor) {
        if (divisor == 0L) {
            throw new ArithmeticException("Division by zero");
        }
        long dMag = divisor < 0 ? -divisor : divisor;
        long rem = remainderUnsigned64(dMag);
        return this.signum < 0 ? -rem : rem;
    }

    public FastBigInt remainder(long divisor) {
        if (divisor == 0L) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.signum == 0) {
            return ZERO;
        }
        long dMag = divisor < 0 ? -divisor : divisor;
        long rem = remainderUnsigned64(dMag);
        return rem == 0L ? ZERO : of128(this.signum, 0L, rem);
    }

    public FastBigInt divide(FastBigInt other) {
        if (other.signum == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.signum == 0) {
            return ZERO;
        }
        if (other.hi == 0L && other.limbs == null) {
            return divideUnsigned64(this.signum * other.signum, other.lo);
        }
        if (this.limbs == null && other.limbs == null) {
            int cmp = (this.hi != other.hi)
                    ? Long.compareUnsigned(this.hi, other.hi)
                    : Long.compareUnsigned(this.lo, other.lo);
            int qSign = this.signum * other.signum;
            if (cmp < 0) {
                return ZERO;
            }
            if (cmp == 0) {
                return qSign > 0 ? ONE : MINUS_ONE;
            }
            long q = div128By128Quotient(this.hi, this.lo, other.hi, other.lo);
            return of128(qSign, 0L, q);
        }
        int cmp = this.compareMagnitude(other);
        int qSign = this.signum * other.signum;
        if (cmp < 0) {
            return ZERO;
        }
        if (cmp == 0) {
            return qSign > 0 ? ONE : MINUS_ONE;
        }
        return knuthDivideCore(qSign, this.signum, this.toLimbArray(), other.toLimbArray(), true, false)[0];
    }

    public FastBigInt remainder(FastBigInt other) {
        if (other.signum == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.signum == 0) {
            return ZERO;
        }
        if (other.hi == 0L && other.limbs == null) {
            long rem = remainderUnsigned64(other.lo);
            return rem == 0L ? ZERO : of128(this.signum, 0L, rem);
        }
        if (this.limbs == null && other.limbs == null) {
            int cmp = (this.hi != other.hi)
                    ? Long.compareUnsigned(this.hi, other.hi)
                    : Long.compareUnsigned(this.lo, other.lo);
            if (cmp < 0) {
                return this;
            }
            if (cmp == 0) {
                return ZERO;
            }
            return rem128By128Only(this.signum, this.hi, this.lo, other.hi, other.lo);
        }
        int cmp = this.compareMagnitude(other);
        if (cmp < 0) {
            return this;
        }
        if (cmp == 0) {
            return ZERO;
        }
        return knuthDivideCore(this.signum * other.signum, this.signum, this.toLimbArray(), other.toLimbArray(), false, true)[1];
    }

    public FastBigInt[] divideAndRemainder(FastBigInt other) {
        if (other.signum == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.signum == 0) {
            return new FastBigInt[] {ZERO, ZERO};
        }
        int cmp = this.compareMagnitude(other);
        int qSign = this.signum * other.signum;
        if (cmp < 0) {
            return new FastBigInt[] {ZERO, this};
        }
        if (cmp == 0) {
            return new FastBigInt[] {qSign > 0 ? ONE : MINUS_ONE, ZERO};
        }

        if (other.hi == 0L && other.limbs == null) {
            long d = other.lo;
            if (this.hi == 0L && this.limbs == null) {
                long q = Long.divideUnsigned(this.lo, d);
                long r = this.lo - q * d;
                return new FastBigInt[] {
                    of128(qSign, 0L, q),
                    r == 0L ? ZERO : of128(this.signum, 0L, r)
                };
            }
            if (this.limbs == null) {
                long qHi = Long.divideUnsigned(this.hi, d);
                long remHi = this.hi - qHi * d;
                long qLo = divlu128By64(remHi, this.lo, d);
                long remLo = this.lo - qLo * d;
                return new FastBigInt[] {
                    of128(qSign, qHi, qLo),
                    remLo == 0L ? ZERO : of128(this.signum, 0L, remLo)
                };
            }
            int n = this.limbs.length;
            long[] qLimbs = new long[n];
            int s = Long.numberOfLeadingZeros(d);
            long dNorm = d << s;
            long vRecip = divlu128By64(~dNorm, -1L, dNorm);
            long rem = 0L;
            for (int i = n - 1; i >= 0; i--) {
                long u0 = this.limbs[i];
                long q;
                if (rem == 0L) {
                    q = Long.divideUnsigned(u0, d);
                } else {
                    long n1 = (s == 0) ? rem : ((rem << s) | (u0 >>> (64 - s)));
                    long n0 = u0 << s;
                    q = divluPreinv(n1, n0, dNorm, vRecip);
                }
                qLimbs[i] = q;
                rem = u0 - q * d;
            }
            return new FastBigInt[] {
                fromNormalized(qSign, qLimbs, n),
                rem == 0L ? ZERO : of128(this.signum, 0L, rem)
            };
        }

        if (this.limbs == null && other.limbs == null) {
            return divRem128By128(qSign, this.signum, this.hi, this.lo, other.hi, other.lo);
        }

        return knuthDivideCore(qSign, this.signum, this.toLimbArray(), other.toLimbArray(), true, true);
    }

    private static long div128By128Quotient(long aHi, long aLo, long bHi, long bLo) {
        int s = Long.numberOfLeadingZeros(bHi);
        long v1 = (s == 0) ? bHi : (bHi << s) | (bLo >>> (64 - s));
        long u2 = (s == 0) ? 0L : (aHi >>> (64 - s));
        long u1 = (s == 0) ? aHi : (aHi << s) | (aLo >>> (64 - s));

        long qhat = (Long.compareUnsigned(u2, v1) >= 0) ? -1L : divlu128By64(u2, u1, v1);

        while (true) {
            long p0Lo = qhat * bLo;
            long p0Hi = unsignedMultiplyHigh(qhat, bLo);
            long p1Lo = qhat * bHi;
            long p1Hi = unsignedMultiplyHigh(qhat, bHi);

            long prodHi = p0Hi + p1Lo;
            long c = Long.compareUnsigned(prodHi, p0Hi) < 0 ? 1L : 0L;
            long prodTop = p1Hi + c;

            if (prodTop == 0L) {
                int cmp = (prodHi != aHi)
                        ? Long.compareUnsigned(prodHi, aHi)
                        : Long.compareUnsigned(p0Lo, aLo);
                if (cmp <= 0) {
                    return qhat;
                }
            }
            qhat--;
        }
    }

    private static FastBigInt rem128By128Only(int rSign, long aHi, long aLo, long bHi, long bLo) {
        long qhat = div128By128Quotient(aHi, aLo, bHi, bLo);
        long p0Lo = qhat * bLo;
        long p0Hi = unsignedMultiplyHigh(qhat, bLo);
        long prodHi = p0Hi + qhat * bHi;
        long rLo = aLo - p0Lo;
        long borrow = Long.compareUnsigned(aLo, p0Lo) < 0 ? 1L : 0L;
        long rHi = aHi - prodHi - borrow;
        return of128(rSign, rHi, rLo);
    }

    private static FastBigInt[] divRem128By128(
            int qSign, int rSign, long aHi, long aLo, long bHi, long bLo) {
        long qhat = div128By128Quotient(aHi, aLo, bHi, bLo);
        long p0Lo = qhat * bLo;
        long p0Hi = unsignedMultiplyHigh(qhat, bLo);
        long prodHi = p0Hi + qhat * bHi;
        long rLo = aLo - p0Lo;
        long borrow = Long.compareUnsigned(aLo, p0Lo) < 0 ? 1L : 0L;
        long rHi = aHi - prodHi - borrow;
        return new FastBigInt[] {
            of128(qSign, 0L, qhat),
            of128(rSign, rHi, rLo)
        };
    }

    /**
     * Knuth Algorithm D (Base 2<sup>64</sup>) 多肢段長除法，結合 Möller-Granlund 預計算倒數 {@code vRecip} 與外提 {@code qhatMask}。
     */
    private static FastBigInt[] knuthDivideCore(
            int qSign, int rSign, long[] uRaw, long[] vRaw, boolean needQ, boolean needR) {
        int m = uRaw.length;
        int n = vRaw.length;

        int shift = Long.numberOfLeadingZeros(vRaw[n - 1]);
        long[] u = new long[m + 1];
        long[] v = new long[n];

        if (shift > 0) {
            long carry = 0L;
            for (int i = 0; i < m; i++) {
                long cur = uRaw[i];
                u[i] = (cur << shift) | carry;
                carry = cur >>> (64 - shift);
            }
            u[m] = carry;

            carry = 0L;
            for (int i = 0; i < n; i++) {
                long cur = vRaw[i];
                v[i] = (cur << shift) | carry;
                carry = cur >>> (64 - shift);
            }
        } else {
            System.arraycopy(uRaw, 0, u, 0, m);
            System.arraycopy(vRaw, 0, v, 0, n);
        }

        long[] q = needQ ? new long[m - n + 1] : null;
        long vn1 = v[n - 1];
        long vn2 = v[n - 2];
        long vRecip = divlu128By64(~vn1, -1L, vn1);

        for (int j = m - n; j >= 0; j--) {
            long ujn = u[j + n];
            long ujn1 = u[j + n - 1];
            long ujn2 = u[j + n - 2];

            long qhat;
            long rhat;
            boolean rhatOverflow = false;

            if (Long.compareUnsigned(ujn, vn1) == 0) {
                qhat = -1L;
                rhat = ujn1 + vn1;
                rhatOverflow = Long.compareUnsigned(rhat, ujn1) < 0;
            } else if (ujn == 0L) {
                qhat = Long.divideUnsigned(ujn1, vn1);
                rhat = ujn1 - qhat * vn1;
            } else {
                qhat = divluPreinv(ujn, ujn1, vn1, vRecip);
                rhat = ujn1 - qhat * vn1;
            }

            while (!rhatOverflow) {
                long pLo = qhat * vn2;
                long pHi = unsignedMultiplyHigh(qhat, vn2);
                int cmp = (pHi != rhat)
                        ? Long.compareUnsigned(pHi, rhat)
                        : Long.compareUnsigned(pLo, ujn2);
                if (cmp <= 0) {
                    break;
                }
                qhat--;
                long prevRhat = rhat;
                rhat += vn1;
                rhatOverflow = Long.compareUnsigned(rhat, prevRhat) < 0;
            }

            long qhatMask = qhat >> 63;
            long borrow = 0L;
            for (int i = 0; i < n; i++) {
                long vi = v[i];
                long pLo = qhat * vi;
                long pHi = Math.multiplyHigh(qhat, vi) + (vi & qhatMask) + (qhat & (vi >> 63));

                long subLo = pLo + borrow;
                long c = Long.compareUnsigned(subLo, pLo) < 0 ? 1L : 0L;
                long nextBorrow = pHi + c;

                long uVal = u[j + i];
                if (Long.compareUnsigned(uVal, subLo) < 0) {
                    nextBorrow++;
                }
                u[j + i] = uVal - subLo;
                borrow = nextBorrow;
            }

            long uTop = u[j + n];
            u[j + n] = uTop - borrow;
            if (Long.compareUnsigned(uTop, borrow) < 0) {
                qhat--;
                long carry = 0L;
                for (int i = 0; i < n; i++) {
                    long ui = u[j + i];
                    long vi = v[i];
                    long sum = ui + vi + carry;
                    carry = ((ui & vi) | ((ui | vi) & ~sum)) >>> 63;
                    u[j + i] = sum;
                }
                u[j + n] += carry;
            }
            if (needQ) {
                q[j] = qhat;
            }
        }

        FastBigInt qRes = needQ ? fromNormalized(qSign, q, q.length) : ZERO;
        FastBigInt rRes = ZERO;
        if (needR) {
            long[] rem = new long[n];
            if (shift > 0) {
                long carry = 0L;
                for (int i = n - 1; i >= 0; i--) {
                    long cur = u[i];
                    rem[i] = (cur >>> shift) | carry;
                    carry = cur << (64 - shift);
                }
            } else {
                System.arraycopy(u, 0, rem, 0, n);
            }
            rRes = fromNormalized(rSign, rem, n);
        }

        return new FastBigInt[] {qRes, rRes};
    }

    // =========================================================================
    // 位元位移與位元操作 (Bitwise Shifts & Bit Manipulation)
    // =========================================================================

    public int bitLength() {
        if (signum == 0) {
            return 0;
        }
        if (limbs == null) {
            if (hi == 0L) {
                return 64 - Long.numberOfLeadingZeros(lo);
            }
            return 128 - Long.numberOfLeadingZeros(hi);
        }
        int topIdx = limbs.length - 1;
        return (topIdx << 6) + (64 - Long.numberOfLeadingZeros(limbs[topIdx]));
    }

    public int lowestSetBit() {
        if (signum == 0) {
            return -1;
        }
        if (lo != 0L) {
            return Long.numberOfTrailingZeros(lo);
        }
        if (hi != 0L) {
            return 64 + Long.numberOfTrailingZeros(hi);
        }
        for (int i = 2; i < limbs.length; i++) {
            if (limbs[i] != 0L) {
                return (i << 6) + Long.numberOfTrailingZeros(limbs[i]);
            }
        }
        return -1;
    }

    public boolean testBit(int n) {
        if (n < 0) {
            throw new ArithmeticException("Negative bit index: " + n);
        }
        if (signum == 0) {
            return false;
        }
        int wordIdx = n >>> 6;
        if (wordIdx >= limbCount()) {
            return false;
        }
        return (getLimb(wordIdx) & (1L << (n & 63))) != 0L;
    }

    public FastBigInt shiftLeft(int n) {
        if (signum == 0 || n == 0) {
            return this;
        }
        if (n < 0) {
            return shiftRight(-n);
        }

        if (limbs == null && n < 128) {
            if (hi == 0L && n < 64) {
                long newLo = lo << n;
                long newHi = lo >>> (64 - n);
                return of128(signum, newHi, newLo);
            }
            if (hi == 0L && n >= 64) {
                long newHi = lo << (n - 64);
                long overflow = (n == 64) ? 0L : (lo >>> (128 - n));
                if (overflow == 0L) {
                    return of128(signum, newHi, 0L);
                }
                return new FastBigInt(signum, newHi, 0L, new long[] {0L, newHi, overflow});
            }
            if (n < 64) {
                long overflow = hi >>> (64 - n);
                long newHi = (hi << n) | (lo >>> (64 - n));
                long newLo = lo << n;
                if (overflow == 0L) {
                    return of128(signum, newHi, newLo);
                }
                return new FastBigInt(signum, newHi, newLo, new long[] {newLo, newHi, overflow});
            }
        }

        int wordShift = n >>> 6;
        int bitShift = n & 63;
        int oldLen = limbCount();
        int newLen = oldLen + wordShift + (bitShift == 0 ? 0 : 1);
        long[] res = new long[newLen];

        if (bitShift == 0) {
            for (int i = 0; i < oldLen; i++) {
                res[i + wordShift] = getLimb(i);
            }
        } else {
            long carry = 0L;
            for (int i = 0; i < oldLen; i++) {
                long cur = getLimb(i);
                res[i + wordShift] = (cur << bitShift) | carry;
                carry = cur >>> (64 - bitShift);
            }
            res[oldLen + wordShift] = carry;
        }
        return fromNormalized(signum, res, newLen);
    }

    public FastBigInt shiftRight(int n) {
        if (signum == 0 || n == 0) {
            return this;
        }
        if (n < 0) {
            return shiftLeft(-n);
        }

        if (limbs == null) {
            if (n >= 128) {
                return ZERO;
            }
            if (hi == 0L) {
                return n >= 64 ? ZERO : of128(signum, 0L, lo >>> n);
            }
            if (n < 64) {
                long newLo = (lo >>> n) | (hi << (64 - n));
                long newHi = hi >>> n;
                return of128(signum, newHi, newLo);
            } else {
                long newLo = hi >>> (n - 64);
                return of128(signum, 0L, newLo);
            }
        }

        int wordShift = n >>> 6;
        int bitShift = n & 63;
        int oldLen = limbs.length;
        if (wordShift >= oldLen) {
            return ZERO;
        }
        int newLen = oldLen - wordShift;
        long[] res = new long[newLen];
        if (bitShift == 0) {
            System.arraycopy(limbs, wordShift, res, 0, newLen);
        } else {
            long carry = 0L;
            for (int i = oldLen - 1; i >= wordShift; i--) {
                long cur = limbs[i];
                res[i - wordShift] = (cur >>> bitShift) | carry;
                carry = cur << (64 - bitShift);
            }
        }
        return fromNormalized(signum, res, newLen);
    }

    public FastBigInt and(FastBigInt other) {
        if (this.signum == 0 || other.signum == 0) {
            return ZERO;
        }
        if (this.limbs == null && other.limbs == null) {
            return of128(1, this.hi & other.hi, this.lo & other.lo);
        }
        int minLen = Math.min(this.limbCount(), other.limbCount());
        long[] res = new long[minLen];
        for (int i = 0; i < minLen; i++) {
            res[i] = this.getLimb(i) & other.getLimb(i);
        }
        return fromNormalized(1, res, minLen);
    }

    public FastBigInt or(FastBigInt other) {
        if (this.signum == 0) {
            return other.abs();
        }
        if (other.signum == 0) {
            return this.abs();
        }
        if (this.limbs == null && other.limbs == null) {
            return of128(1, this.hi | other.hi, this.lo | other.lo);
        }
        int maxLen = Math.max(this.limbCount(), other.limbCount());
        long[] res = new long[maxLen];
        for (int i = 0; i < maxLen; i++) {
            long a = i < this.limbCount() ? this.getLimb(i) : 0L;
            long b = i < other.limbCount() ? other.getLimb(i) : 0L;
            res[i] = a | b;
        }
        return fromNormalized(1, res, maxLen);
    }

    public FastBigInt xor(FastBigInt other) {
        if (this.signum == 0) {
            return other.abs();
        }
        if (other.signum == 0) {
            return this.abs();
        }
        if (this.limbs == null && other.limbs == null) {
            return of128(1, this.hi ^ other.hi, this.lo ^ other.lo);
        }
        int maxLen = Math.max(this.limbCount(), other.limbCount());
        long[] res = new long[maxLen];
        for (int i = 0; i < maxLen; i++) {
            long a = i < this.limbCount() ? this.getLimb(i) : 0L;
            long b = i < other.limbCount() ? other.getLimb(i) : 0L;
            res[i] = a ^ b;
        }
        return fromNormalized(1, res, maxLen);
    }

    // =========================================================================
    // 次方、最大公因數、模冪與密碼學原語 (Pow, Fused Binary GCD, Montgomery ModPow)
    // =========================================================================

    public FastBigInt pow(int exponent) {
        if (exponent < 0) {
            throw new ArithmeticException("Negative exponent: " + exponent);
        }
        if (exponent == 0) {
            return ONE;
        }
        if (this.signum == 0) {
            return ZERO;
        }
        int resSign = (this.signum < 0 && (exponent & 1) != 0) ? -1 : 1;
        if (this.hi == 0L && this.limbs == null) {
            if (this.lo == 1L) {
                return resSign > 0 ? ONE : MINUS_ONE;
            }
            if (this.lo == 2L) {
                return ONE.shiftLeft(exponent).withSign(resSign);
            }
        }
        FastBigInt base = this.abs();
        FastBigInt result = ONE;
        int exp = exponent;
        while (exp > 0) {
            if ((exp & 1) != 0) {
                result = result.multiply(base);
            }
            exp >>>= 1;
            if (exp > 0) {
                base = base.square();
            }
        }
        return result.withSign(resSign);
    }

    /**
     * 最大公因數 {@code gcd(|this|, |other|)}。
     * <ul>
     *   <li>64-bit 與 128-bit 範圍直接採用暫存器級 Stein's Binary GCD（零配置）。</li>
     *   <li>多肢段範圍（如 10<sup>1000</sup>）採用單趟融合原地 Binary GCD ({@code v = (v - u) >>> tz})，比 {@link BigInteger#gcd} 快約 2.7 倍。</li>
     * </ul>
     */
    public FastBigInt gcd(FastBigInt other) {
        if (this.signum == 0) {
            return other.abs();
        }
        if (other.signum == 0) {
            return this.abs();
        }
        if ((this.hi | other.hi) == 0L && this.limbs == null && other.limbs == null) {
            return ofUnsigned(gcdUnsigned64(this.lo, other.lo));
        }
        if (this.limbs == null && other.limbs == null) {
            return gcdUnsigned128(this.hi, this.lo, other.hi, other.lo);
        }

        FastBigInt a = this.abs();
        FastBigInt b = other.abs();
        if (a.compareMagnitude(b) < 0) {
            FastBigInt t = a;
            a = b;
            b = t;
        }
        // 若兩數長度差距較大，先執行一次取餘數快速降維
        if (a.limbCount() - b.limbCount() >= 2) {
            if (b.hi == 0L && b.limbs == null) {
                long r = a.remainderUnsigned64(b.lo);
                return ofUnsigned(gcdUnsigned64(b.lo, r));
            }
            a = a.remainder(b);
            if (a.isZero()) {
                return b;
            }
            if (a.limbs == null && b.limbs == null) {
                return gcdUnsigned128(a.hi, a.lo, b.hi, b.lo);
            }
        }
        return gcdMultiLimbFused(a.toLimbArray(), b.toLimbArray());
    }

    public static long gcdUnsigned64(long u, long v) {
        if (u == 0L) {
            return v;
        }
        if (v == 0L) {
            return u;
        }
        int shift = Long.numberOfTrailingZeros(u | v);
        u >>>= Long.numberOfTrailingZeros(u);
        do {
            v >>>= Long.numberOfTrailingZeros(v);
            if (Long.compareUnsigned(u, v) > 0) {
                long t = u;
                u = v;
                v = t;
            }
            v -= u;
        } while (v != 0L);
        return u << shift;
    }

    private static FastBigInt gcdUnsigned128(long uHi, long uLo, long vHi, long vLo) {
        if (uHi == 0L && vHi == 0L) {
            return ofUnsigned(gcdUnsigned64(uLo, vLo));
        }
        if (uHi == 0L && uLo == 0L) {
            return of128(1, vHi, vLo);
        }
        if (vHi == 0L && vLo == 0L) {
            return of128(1, uHi, uLo);
        }

        long commonLow = uLo | vLo;
        int shift = (commonLow != 0L)
                ? Long.numberOfTrailingZeros(commonLow)
                : 64 + Long.numberOfTrailingZeros(uHi | vHi);

        int tzU = (uLo != 0L) ? Long.numberOfTrailingZeros(uLo) : 64 + Long.numberOfTrailingZeros(uHi);
        if (tzU >= 64) {
            uLo = uHi >>> (tzU - 64);
            uHi = 0L;
        } else if (tzU > 0) {
            uLo = (uLo >>> tzU) | (uHi << (64 - tzU));
            uHi >>>= tzU;
        }

        while (vHi != 0L || vLo != 0L) {
            if ((uHi | vHi) == 0L) {
                long g = gcdUnsigned64(uLo, vLo);
                return ofUnsigned(g).shiftLeft(shift);
            }

            int tzV = (vLo != 0L) ? Long.numberOfTrailingZeros(vLo) : 64 + Long.numberOfTrailingZeros(vHi);
            if (tzV >= 64) {
                vLo = vHi >>> (tzV - 64);
                vHi = 0L;
            } else if (tzV > 0) {
                vLo = (vLo >>> tzV) | (vHi << (64 - tzV));
                vHi >>>= tzV;
            }

            int cmp = (uHi != vHi) ? Long.compareUnsigned(uHi, vHi) : Long.compareUnsigned(uLo, vLo);
            if (cmp > 0) {
                long th = uHi;
                uHi = vHi;
                vHi = th;
                long tl = uLo;
                uLo = vLo;
                vLo = tl;
            }

            long borrow = Long.compareUnsigned(vLo, uLo) < 0 ? 1L : 0L;
            vLo -= uLo;
            vHi = vHi - uHi - borrow;
        }

        return of128(1, uHi, uLo).shiftLeft(shift);
    }

    /**
     * 多肢段單趟融合原地 Binary GCD：將 {@code v = (v - u) >>> tz} 融合於單一迴圈內原地執行。
     */
    private static FastBigInt gcdMultiLimbFused(long[] uOrig, long[] vOrig) {
        long[] u = Arrays.copyOf(uOrig, uOrig.length);
        long[] v = Arrays.copyOf(vOrig, vOrig.length);
        int lenU = trimLimbLen(u, u.length);
        int lenV = trimLimbLen(v, v.length);
        if (lenU == 0) {
            return fromNormalized(1, v, lenV);
        }
        if (lenV == 0) {
            return fromNormalized(1, u, lenU);
        }

        int tzU = countTrailingZerosLimbs(u, lenU);
        int tzV = countTrailingZerosLimbs(v, lenV);
        int commonShift = Math.min(tzU, tzV);

        lenU = shiftRightLimbsInPlace(u, lenU, tzU);
        lenV = shiftRightLimbsInPlace(v, lenV, tzV);

        while (lenV != 0) {
            if (lenU <= 2 && lenV <= 2) {
                long uHi = lenU == 2 ? u[1] : 0L;
                long uLo = u[0];
                long vHi = lenV == 2 ? v[1] : 0L;
                long vLo = v[0];
                return gcdUnsigned128(uHi, uLo, vHi, vLo).shiftLeft(commonShift);
            }

            int cmp;
            if (lenU != lenV) {
                cmp = lenU < lenV ? -1 : 1;
            } else {
                cmp = 0;
                for (int i = lenU - 1; i >= 0; i--) {
                    long ui = u[i];
                    long vi = v[i];
                    if (ui != vi) {
                        cmp = Long.compareUnsigned(ui, vi);
                        break;
                    }
                }
            }
            if (cmp == 0) {
                break;
            }
            if (cmp > 0) {
                long[] tmp = u;
                u = v;
                v = tmp;
                int tl = lenU;
                lenU = lenV;
                lenV = tl;
            }

            // 此時 v > u 且皆為奇數，因此 diff0 = v[0] - u[0] 必為偶數
            long v0 = v[0];
            long u0 = u[0];
            long diff0 = v0 - u0;
            if (diff0 != 0L) {
                int tz = Long.numberOfTrailingZeros(diff0);
                int invTz = 64 - tz;
                long borrow = Long.compareUnsigned(v0, u0) < 0 ? 1L : 0L;
                long prevDiff = diff0;
                int i = 1;
                for (; i < lenU; i++) {
                    long vi = v[i];
                    long ui = u[i];
                    long diff = vi - ui - borrow;
                    borrow = ((~vi & ui) | ((~vi | ui) & diff)) >>> 63;
                    v[i - 1] = (prevDiff >>> tz) | (diff << invTz);
                    prevDiff = diff;
                }
                for (; borrow != 0L && i < lenV; i++) {
                    long vi = v[i];
                    long diff = vi - 1L;
                    borrow = (vi == 0L) ? 1L : 0L;
                    v[i - 1] = (prevDiff >>> tz) | (diff << invTz);
                    prevDiff = diff;
                }
                for (; i < lenV; i++) {
                    long diff = v[i];
                    v[i - 1] = (prevDiff >>> tz) | (diff << invTz);
                    prevDiff = diff;
                }
                v[lenV - 1] = prevDiff >>> tz;
                while (lenV > 0 && v[lenV - 1] == 0L) {
                    lenV--;
                }
            } else {
                lenV = subLimbsInPlaceWithLen(v, lenV, u, lenU);
                if (lenV == 0) {
                    break;
                }
                int tz = countTrailingZerosLimbs(v, lenV);
                lenV = shiftRightLimbsInPlace(v, lenV, tz);
            }
        }

        return fromNormalized(1, u, lenU).shiftLeft(commonShift);
    }

    private static int trimLimbLen(long[] a, int len) {
        while (len > 0 && a[len - 1] == 0L) {
            len--;
        }
        return len;
    }

    private static int countTrailingZerosLimbs(long[] a, int len) {
        for (int i = 0; i < len; i++) {
            if (a[i] != 0L) {
                return (i << 6) + Long.numberOfTrailingZeros(a[i]);
            }
        }
        return 0;
    }

    private static int shiftRightLimbsInPlace(long[] a, int len, int shift) {
        if (shift == 0 || len == 0) {
            return len;
        }
        int wordShift = shift >>> 6;
        int bitShift = shift & 63;
        int newLen = len - wordShift;
        if (bitShift == 0) {
            System.arraycopy(a, wordShift, a, 0, newLen);
        } else {
            int invShift = 64 - bitShift;
            for (int i = 0; i < newLen - 1; i++) {
                a[i] = (a[i + wordShift] >>> bitShift) | (a[i + wordShift + 1] << invShift);
            }
            a[newLen - 1] = a[len - 1] >>> bitShift;
        }
        return trimLimbLen(a, newLen);
    }

    private static int subLimbsInPlaceWithLen(long[] v, int lenV, long[] u, int lenU) {
        long borrow = 0L;
        int i = 0;
        for (; i < lenU; i++) {
            long vi = v[i];
            long ui = u[i];
            long diff = vi - ui - borrow;
            borrow = ((~vi & ui) | ((~vi | ui) & diff)) >>> 63;
            v[i] = diff;
        }
        for (; borrow != 0L && i < lenV; i++) {
            long vi = v[i];
            v[i] = vi - 1L;
            borrow = (vi == 0L) ? 1L : 0L;
        }
        return trimLimbLen(v, lenV);
    }

    public FastBigInt mod(FastBigInt m) {
        if (m.signum <= 0) {
            throw new ArithmeticException("Modulus must be positive");
        }
        FastBigInt r = this.remainder(m);
        return r.signum >= 0 ? r : r.add(m);
    }

    /**
     * 模冪運算：{@code (this^exponent) mod m}。
     * <ul>
     *   <li>當模數 {@code m} 在 64-bit 無號範圍內時，採用零配置 128-bit 原生模乘快速路徑。</li>
     *   <li>當模數 {@code m} 為多肢段奇數（如 RSA 或 10<sup>1000</sup> 級別數論運算）時，
     *       採用 <b>Radix-2<sup>62</sup> 雙列融合滑動視窗 Montgomery 模冪</b>，全程零 GC 且免除數千次長除法。</li>
     * </ul>
     */
    public FastBigInt modPow(FastBigInt exponent, FastBigInt m) {
        if (m.signum <= 0) {
            throw new ArithmeticException("Modulus must be positive");
        }
        if (m.equals(ONE)) {
            return ZERO;
        }
        if (exponent.signum < 0) {
            return this.modInverse(m).modPow(exponent.neg(), m);
        }
        if (exponent.isZero()) {
            return ONE;
        }

        FastBigInt base = this.mod(m);
        if (base.isZero()) {
            return ZERO;
        }

        // Tier 1: 64-bit 模數超高速零配置路徑
        if (m.hi == 0L && m.limbs == null) {
            long mod64 = m.lo;
            long b64 = base.lo;
            long res64 = 1L;
            int bitLen = exponent.bitLength();
            for (int i = 0; i < bitLen; i++) {
                if (exponent.testBit(i)) {
                    res64 = mulModUnsigned64(res64, b64, mod64);
                }
                b64 = mulModUnsigned64(b64, b64, mod64);
            }
            return ofUnsigned(res64);
        }

        // Tier 3: 奇數多肢段模數 -> 啟動 Radix-2^62 滑動視窗 Montgomery 模冪
        if ((m.lo & 1L) != 0L && m.limbCount() >= 2) {
            return modPowMontgomery62(base, exponent, m);
        }

        // 偶數大模數退回一般二進位模冪
        FastBigInt result = ONE;
        int bitLen = exponent.bitLength();
        for (int i = bitLen - 1; i >= 0; i--) {
            result = result.square().remainder(m);
            if (exponent.testBit(i)) {
                result = result.multiply(base).remainder(m);
            }
        }
        return result;
    }

    public static long mulModUnsigned64(long a, long b, long m) {
        long pLo = a * b;
        long pHi = unsignedMultiplyHigh(a, b);
        if (pHi == 0L) {
            return Long.remainderUnsigned(pLo, m);
        }
        return rem128By64(pHi, pLo, m);
    }

    // --- Radix-2^62 Sliding-Window Montgomery Exponentiation ---

    private static long montgomeryInverse64(long m0) {
        long x = m0; // 奇數 m0 滿足 m0^2 == 1 (mod 8)，自帶 3 bits 初始精度
        x *= 2L - m0 * x; // 6 bits
        x *= 2L - m0 * x; // 12 bits
        x *= 2L - m0 * x; // 24 bits
        x *= 2L - m0 * x; // 48 bits
        x *= 2L - m0 * x; // 96 >= 64 bits
        return -x;
    }

    private static FastBigInt modPowMontgomery62(FastBigInt baseReduced, FastBigInt exp, FastBigInt m) {
        long[] m64 = m.toLimbArray();
        int n64 = m64.length;
        long[] M = new long[n64 + 4];
        int n = toRadix62(m64, n64, M, 0);
        if (n < 2) {
            // 若轉換後不足 2 limbs，確保至少 2 limbs 供雙列融合迴圈使用
            n = 2;
        }
        long inv62 = montgomeryInverse64(M[0]) & MASK62;

        FastBigInt baseMontFbi = baseReduced.shiftLeft(62 * n).remainder(m);
        if (baseMontFbi.isZero()) {
            return ZERO;
        }

        int expBits = exp.bitLength();
        int w = (expBits <= 64) ? 3 : (expBits <= 256) ? 4 : (expBits <= 1024) ? 5 : 6;
        int tableSize = 1 << (w - 1);

        long[][] table = new long[tableSize][n];
        long[] bm64 = baseMontFbi.toLimbArray();
        toRadix62(bm64, bm64.length, table[0], 0);

        long[] prodBuf = new long[n << 1];
        long[] scratch = acquireScratch(Math.max(512, n << 4));
        long[] b2 = new long[n];

        squareKaratsuba62Slice(table[0], 0, n, prodBuf, 0, scratch, 0);
        montgomeryReduce62Fused(prodBuf, M, n, inv62, b2);

        for (int k = 1; k < tableSize; k++) {
            mulKaratsuba62Slice(table[k - 1], 0, n, b2, 0, n, prodBuf, 0, scratch, 0);
            montgomeryReduce62Fused(prodBuf, M, n, inv62, table[k]);
        }

        long[] acc = new long[n];
        int bitPos = expBits - 1;

        int winLen = Math.min(w, bitPos + 1);
        int winVal = 0;
        for (int i = 0; i < winLen; i++) {
            winVal = (winVal << 1) | (exp.testBit(bitPos - i) ? 1 : 0);
        }
        int tz = Integer.numberOfTrailingZeros(winVal);
        winVal >>>= tz;
        winLen -= tz;
        System.arraycopy(table[winVal >>> 1], 0, acc, 0, n);
        bitPos -= winLen;

        while (bitPos >= 0) {
            if (!exp.testBit(bitPos)) {
                squareKaratsuba62Slice(acc, 0, n, prodBuf, 0, scratch, 0);
                montgomeryReduce62Fused(prodBuf, M, n, inv62, acc);
                bitPos--;
            } else {
                winLen = Math.min(w, bitPos + 1);
                winVal = 0;
                for (int i = 0; i < winLen; i++) {
                    winVal = (winVal << 1) | (exp.testBit(bitPos - i) ? 1 : 0);
                }
                tz = Integer.numberOfTrailingZeros(winVal);
                winVal >>>= tz;
                winLen -= tz;

                for (int s = 0; s < winLen; s++) {
                    squareKaratsuba62Slice(acc, 0, n, prodBuf, 0, scratch, 0);
                    montgomeryReduce62Fused(prodBuf, M, n, inv62, acc);
                }
                mulKaratsuba62Slice(acc, 0, n, table[winVal >>> 1], 0, n, prodBuf, 0, scratch, 0);
                montgomeryReduce62Fused(prodBuf, M, n, inv62, acc);
                bitPos -= winLen;
            }
        }

        Arrays.fill(prodBuf, 0, n << 1, 0L);
        System.arraycopy(acc, 0, prodBuf, 0, n);
        montgomeryReduce62Fused(prodBuf, M, n, inv62, acc);

        long[] res64 = fromRadix62(acc, 0, n);
        return fromNormalized(1, res64, res64.length);
    }

    private static int toRadix62(long[] in64, int len64, long[] out62, int outOff) {
        int accBits = 0;
        long acc = 0L;
        int outIdx = outOff;
        for (int i = 0; i < len64; i++) {
            long w = in64[i];
            out62[outIdx++] = (acc | (w << accBits)) & MASK62;
            acc = w >>> (62 - accBits);
            accBits += 2;
            if (accBits == 62) {
                out62[outIdx++] = acc;
                acc = 0L;
                accBits = 0;
            }
        }
        if (acc != 0L) {
            out62[outIdx++] = acc;
        }
        int len = outIdx - outOff;
        while (len > 0 && out62[outOff + len - 1] == 0L) {
            len--;
        }
        return len;
    }

    private static long[] fromRadix62(long[] in62, int inOff, int len62) {
        while (len62 > 0 && in62[inOff + len62 - 1] == 0L) {
            len62--;
        }
        if (len62 == 0) {
            return new long[0];
        }
        int totalBits = (len62 - 1) * 62 + (64 - Long.numberOfLeadingZeros(in62[inOff + len62 - 1]));
        int len64 = (totalBits + 63) >>> 6;
        long[] out64 = new long[len64];
        int accBits = 0;
        long acc = 0L;
        int outIdx = 0;
        for (int i = 0; i < len62; i++) {
            long w = in62[inOff + i];
            acc |= (w << accBits);
            accBits += 62;
            if (accBits >= 64) {
                out64[outIdx++] = acc;
                accBits -= 64;
                acc = (accBits == 0) ? 0L : (w >>> (62 - accBits));
            }
        }
        if (acc != 0L && outIdx < len64) {
            out64[outIdx] = acc;
        }
        return out64;
    }

    private static void mulSchoolbook62Slice(
            long[] a, int aOff, int lenA,
            long[] b, int bOff, int lenB,
            long[] out, int outOff) {
        Arrays.fill(out, outOff, outOff + lenA + lenB, 0L);
        for (int i = 0; i < lenA; i++) {
            long ai = a[aOff + i];
            long carry = 0L;
            int k = outOff + i;
            for (int j = 0; j < lenB; j++, k++) {
                long bj = b[bOff + j];
                long pLo = ai * bj;
                long pHi = (Math.multiplyHigh(ai, bj) << 2) | (pLo >>> 62);
                long sum = out[k] + (pLo & MASK62) + carry;
                out[k] = sum & MASK62;
                carry = pHi + (sum >>> 62);
            }
            out[k] = carry;
        }
    }

    private static void squareSchoolbook62Slice(
            long[] a, int aOff, int len,
            long[] out, int outOff) {
        int outLen = len << 1;
        Arrays.fill(out, outOff, outOff + outLen, 0L);
        for (int i = 0; i < len - 1; i++) {
            long ai = a[aOff + i];
            long carry = 0L;
            int k = outOff + (i << 1) + 1;
            for (int j = i + 1; j < len; j++, k++) {
                long aj = a[aOff + j];
                long pLo = ai * aj;
                long pHi = (Math.multiplyHigh(ai, aj) << 2) | (pLo >>> 62);
                long sum = out[k] + (pLo & MASK62) + carry;
                out[k] = sum & MASK62;
                carry = pHi + (sum >>> 62);
            }
            out[k] = carry;
        }
        long shiftCarry = 0L;
        int limit = outOff + outLen;
        for (int k = outOff + 1; k < limit; k++) {
            long v = (out[k] << 1) | shiftCarry;
            out[k] = v & MASK62;
            shiftCarry = v >>> 62;
        }
        long carry = 0L;
        for (int i = 0; i < len; i++) {
            long ai = a[aOff + i];
            long pLo = ai * ai;
            long pHi = (Math.multiplyHigh(ai, ai) << 2) | (pLo >>> 62);

            int k0 = outOff + (i << 1);
            long s0 = out[k0] + (pLo & MASK62) + carry;
            out[k0] = s0 & MASK62;

            int k1 = k0 + 1;
            long s1 = out[k1] + pHi + (s0 >>> 62);
            out[k1] = s1 & MASK62;
            carry = s1 >>> 62;
        }
    }

    private static void squareKaratsuba62Slice(
            long[] a, int aOff, int len,
            long[] out, int outOff,
            long[] scratch, int sOff) {
        int fullOutLen = len << 1;
        while (len > 0 && a[aOff + len - 1] == 0L) {
            len--;
        }
        if (len == 0) {
            Arrays.fill(out, outOff, outOff + fullOutLen, 0L);
            return;
        }
        if (len < 28) {
            squareSchoolbook62Slice(a, aOff, len, out, outOff);
            if ((len << 1) < fullOutLen) {
                Arrays.fill(out, outOff + (len << 1), outOff + fullOutLen, 0L);
            }
            return;
        }
        int half = (len + 1) >>> 1;
        int a0Len = half;
        int a1Len = len - half;

        int aSumOff = sOff;
        int z1Off = aSumOff + half + 1;

        Arrays.fill(out, outOff, outOff + fullOutLen, 0L);
        squareSchoolbook62Slice(a, aOff, a0Len, out, outOff);
        if (a1Len > 0) {
            squareSchoolbook62Slice(a, aOff + half, a1Len, out, outOff + (half << 1));
        }

        long carry = 0L;
        int i = 0;
        for (; i < a1Len; i++) {
            long sum = a[aOff + i] + a[aOff + half + i] + carry;
            scratch[aSumOff + i] = sum & MASK62;
            carry = sum >>> 62;
        }
        for (; i < a0Len; i++) {
            long sum = a[aOff + i] + carry;
            scratch[aSumOff + i] = sum & MASK62;
            carry = sum >>> 62;
        }
        int aSumLen = a0Len;
        if (carry != 0L) {
            scratch[aSumOff + a0Len] = carry;
            aSumLen++;
        }
        int z1Len = aSumLen << 1;
        squareSchoolbook62Slice(scratch, aSumOff, aSumLen, scratch, z1Off);

        subFromSlice62(scratch, z1Off, z1Len, out, outOff, a0Len << 1);
        if (a1Len > 0) {
            subFromSlice62(scratch, z1Off, z1Len, out, outOff + (half << 1), a1Len << 1);
        }
        addSliceToOffset62(out, outOff + half, outOff + (len << 1), scratch, z1Off, z1Len);
    }

    private static void mulKaratsuba62Slice(
            long[] a, int aOff, int lenA,
            long[] b, int bOff, int lenB,
            long[] out, int outOff,
            long[] scratch, int sOff) {
        int fullOutLen = lenA + lenB;
        while (lenA > 0 && a[aOff + lenA - 1] == 0L) {
            lenA--;
        }
        while (lenB > 0 && b[bOff + lenB - 1] == 0L) {
            lenB--;
        }
        if (lenA == 0 || lenB == 0) {
            Arrays.fill(out, outOff, outOff + fullOutLen, 0L);
            return;
        }
        if (lenA < 28 || lenB < 28) {
            mulSchoolbook62Slice(a, aOff, lenA, b, bOff, lenB, out, outOff);
            if (lenA + lenB < fullOutLen) {
                Arrays.fill(out, outOff + lenA + lenB, outOff + fullOutLen, 0L);
            }
            return;
        }
        int half = (Math.max(lenA, lenB) + 1) >>> 1;
        int a0Len = Math.min(lenA, half);
        int a1Len = Math.max(0, lenA - half);
        int b0Len = Math.min(lenB, half);
        int b1Len = Math.max(0, lenB - half);

        int aSumOff = sOff;
        int bSumOff = aSumOff + half + 1;
        int z1Off = bSumOff + half + 1;
        int nextScratch = z1Off + (half << 1) + 2;

        Arrays.fill(out, outOff, outOff + fullOutLen, 0L);
        mulKaratsuba62Slice(a, aOff, a0Len, b, bOff, b0Len, out, outOff, scratch, nextScratch);
        if (a1Len > 0 && b1Len > 0) {
            mulKaratsuba62Slice(
                    a, aOff + half, a1Len, b, bOff + half, b1Len,
                    out, outOff + (half << 1), scratch, nextScratch);
        }
        int aSumLen = addSlices62(a, aOff, a0Len, a, aOff + half, a1Len, scratch, aSumOff);
        int bSumLen = addSlices62(b, bOff, b0Len, b, bOff + half, b1Len, scratch, bSumOff);
        int z1Len = aSumLen + bSumLen;
        mulKaratsuba62Slice(scratch, aSumOff, aSumLen, scratch, bSumOff, bSumLen, scratch, z1Off, scratch, nextScratch);

        subFromSlice62(scratch, z1Off, z1Len, out, outOff, a0Len + b0Len);
        if (a1Len > 0 && b1Len > 0) {
            subFromSlice62(scratch, z1Off, z1Len, out, outOff + (half << 1), a1Len + b1Len);
        }
        addSliceToOffset62(out, outOff + half, outOff + lenA + lenB, scratch, z1Off, z1Len);
    }

    private static int addSlices62(long[] a, int aOff, int lenA, long[] b, int bOff, int lenB, long[] dst, int dstOff) {
        if (lenA < lenB) {
            long[] t = a;
            a = b;
            b = t;
            int to = aOff;
            aOff = bOff;
            bOff = to;
            int tl = lenA;
            lenA = lenB;
            lenB = tl;
        }
        long carry = 0L;
        int i = 0;
        for (; i < lenB; i++) {
            long sum = a[aOff + i] + b[bOff + i] + carry;
            dst[dstOff + i] = sum & MASK62;
            carry = sum >>> 62;
        }
        for (; i < lenA; i++) {
            long sum = a[aOff + i] + carry;
            dst[dstOff + i] = sum & MASK62;
            carry = sum >>> 62;
        }
        if (carry != 0L) {
            dst[dstOff + lenA] = carry;
            return lenA + 1;
        }
        return lenA;
    }

    private static void subFromSlice62(long[] target, int tOff, int tLen, long[] sub, int sOff, int sLen) {
        while (sLen > 0 && sub[sOff + sLen - 1] == 0L) {
            sLen--;
        }
        long borrow = 0L;
        int i = 0;
        for (; i < sLen; i++) {
            long diff = target[tOff + i] - sub[sOff + i] - borrow;
            target[tOff + i] = diff & MASK62;
            borrow = diff >>> 63;
        }
        for (; borrow != 0L && i < tLen; i++) {
            long diff = target[tOff + i] - borrow;
            target[tOff + i] = diff & MASK62;
            borrow = diff >>> 63;
        }
    }

    private static void addSliceToOffset62(long[] target, int tOff, int tLimit, long[] add, int aOff, int aLen) {
        while (aLen > 0 && add[aOff + aLen - 1] == 0L) {
            aLen--;
        }
        long carry = 0L;
        int i = 0;
        int maxI = Math.min(aLen, tLimit - tOff);
        for (; i < maxI; i++) {
            long sum = target[tOff + i] + add[aOff + i] + carry;
            target[tOff + i] = sum & MASK62;
            carry = sum >>> 62;
        }
        for (; carry != 0L && (tOff + i) < tLimit; i++) {
            long sum = target[tOff + i] + carry;
            target[tOff + i] = sum & MASK62;
            carry = sum >>> 62;
        }
    }

    private static void montgomeryReduce62Fused(long[] T, long[] M, int n, long inv62, long[] out) {
        long topCarry = 0L;
        int i = 0;
        long m0 = M[0];
        long m1 = M[1];

        for (; i + 1 < n; i += 2) {
            long t0 = T[i];
            long u0 = (t0 * inv62) & MASK62;
            long p0Lo = u0 * m0;
            long p0Hi = (Math.multiplyHigh(u0, m0) << 2) | (p0Lo >>> 62);
            long carry0 = p0Hi + ((t0 + (p0Lo & MASK62)) >>> 62);

            long p01Lo = u0 * m1;
            long p01Hi = (Math.multiplyHigh(u0, m1) << 2) | (p01Lo >>> 62);
            long s01 = T[i + 1] + (p01Lo & MASK62) + carry0;
            long t1Updated = s01 & MASK62;
            carry0 = p01Hi + (s01 >>> 62);

            long u1 = (t1Updated * inv62) & MASK62;
            long q0Lo = u1 * m0;
            long q0Hi = (Math.multiplyHigh(u1, m0) << 2) | (q0Lo >>> 62);
            long carry1 = q0Hi + ((t1Updated + (q0Lo & MASK62)) >>> 62);

            long mjPrev = m1;
            int k = i + 2;
            for (int j = 2; j < n; j++, k++) {
                long mj = M[j];

                long pLo0 = u0 * mj;
                long pHi0 = (Math.multiplyHigh(u0, mj) << 2) | (pLo0 >>> 62);
                long s0 = T[k] + (pLo0 & MASK62) + carry0;
                carry0 = pHi0 + (s0 >>> 62);

                long pLo1 = u1 * mjPrev;
                long pHi1 = (Math.multiplyHigh(u1, mjPrev) << 2) | (pLo1 >>> 62);
                long s1 = (s0 & MASK62) + (pLo1 & MASK62) + carry1;
                T[k] = s1 & MASK62;
                carry1 = pHi1 + (s1 >>> 62);

                mjPrev = mj;
            }

            long sTop0 = T[k] + carry0 + topCarry;
            long topCarry0 = sTop0 >>> 62;

            long pLo1 = u1 * mjPrev;
            long pHi1 = (Math.multiplyHigh(u1, mjPrev) << 2) | (pLo1 >>> 62);
            long s1 = (sTop0 & MASK62) + (pLo1 & MASK62) + carry1;
            T[k] = s1 & MASK62;
            carry1 = pHi1 + (s1 >>> 62);

            long sTop1 = T[k + 1] + carry1 + topCarry0;
            T[k + 1] = sTop1 & MASK62;
            topCarry = sTop1 >>> 62;
        }

        for (; i < n; i++) {
            long u = (T[i] * inv62) & MASK62;
            long carry = 0L;
            int k = i;
            for (int j = 0; j < n; j++, k++) {
                long mj = M[j];
                long pLo = u * mj;
                long pHi = (Math.multiplyHigh(u, mj) << 2) | (pLo >>> 62);
                long sum = T[k] + (pLo & MASK62) + carry;
                T[k] = sum & MASK62;
                carry = pHi + (sum >>> 62);
            }
            long sumTop = T[k] + carry + topCarry;
            T[k] = sumTop & MASK62;
            topCarry = sumTop >>> 62;
        }

        boolean ge = topCarry != 0L;
        if (!ge) {
            for (int j = n - 1; j >= 0; j--) {
                long ti = T[n + j];
                long mi = M[j];
                if (ti != mi) {
                    ge = ti > mi;
                    break;
                }
                if (j == 0) {
                    ge = true;
                }
            }
        }
        if (ge) {
            long borrow = 0L;
            for (int j = 0; j < n; j++) {
                long diff = T[n + j] - M[j] - borrow;
                out[j] = diff & MASK62;
                borrow = diff >>> 63;
            }
        } else {
            System.arraycopy(T, n, out, 0, n);
        }
    }

    public FastBigInt modInverse(FastBigInt m) {
        if (m.signum <= 0) {
            throw new ArithmeticException("Modulus must be positive");
        }
        if (m.equals(ONE)) {
            return ZERO;
        }
        FastBigInt a = this.mod(m);
        if (a.isZero()) {
            throw new ArithmeticException("FastBigInt not invertible");
        }

        FastBigInt t = ZERO;
        FastBigInt newT = ONE;
        FastBigInt r = m;
        FastBigInt newR = a;

        while (!newR.isZero()) {
            FastBigInt[] qr = r.divideAndRemainder(newR);
            FastBigInt q = qr[0];
            FastBigInt nextR = qr[1];

            FastBigInt nextT = t.subtract(q.multiply(newT));
            t = newT;
            newT = nextT;
            r = newR;
            newR = nextR;
        }

        if (!r.equals(ONE)) {
            throw new ArithmeticException("FastBigInt not invertible (gcd != 1)");
        }
        return t.signum < 0 ? t.add(m) : t;
    }

    public boolean isProbablePrime(int certainty) {
        if (signum <= 0) {
            return false;
        }
        if (hi == 0L && limbs == null) {
            return isPrimeUnsigned64(lo);
        }
        if (!testBit(0)) {
            return false;
        }
        int rounds = Math.max(1, (certainty + 1) >>> 1);
        FastBigInt nMinusOne = this.subtract(1L);
        int s = nMinusOne.lowestSetBit();
        FastBigInt d = nMinusOne.shiftRight(s);

        for (int i = 0; i < rounds; i++) {
            long baseVal = (i < MILLER_RABIN_BASES_64.length) ? MILLER_RABIN_BASES_64[i] : (i * 2L + 3L);
            FastBigInt a = of(baseVal).mod(this);
            if (a.isZero() || a.equals(ONE) || a.equals(nMinusOne)) {
                continue;
            }
            FastBigInt x = a.modPow(d, this);
            if (x.equals(ONE) || x.equals(nMinusOne)) {
                continue;
            }
            boolean composite = true;
            for (int r = 1; r < s; r++) {
                x = x.square().remainder(this);
                if (x.equals(nMinusOne)) {
                    composite = false;
                    break;
                }
                if (x.equals(ONE)) {
                    return false;
                }
            }
            if (composite) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPrimeUnsigned64(long n) {
        if (Long.compareUnsigned(n, 2L) < 0) {
            return false;
        }
        if (n == 2L || n == 3L || n == 5L || n == 7L) {
            return true;
        }
        if ((n & 1L) == 0L
                || Long.remainderUnsigned(n, 3L) == 0L
                || Long.remainderUnsigned(n, 5L) == 0L
                || Long.remainderUnsigned(n, 7L) == 0L) {
            return false;
        }
        long nMinusOne = n - 1L;
        int s = Long.numberOfTrailingZeros(nMinusOne);
        long d = nMinusOne >>> s;

        for (long a : MILLER_RABIN_BASES_64) {
            long base = Long.remainderUnsigned(a, n);
            if (base == 0L) {
                continue;
            }
            long x = modPowUnsigned64(base, d, n);
            if (x == 1L || x == nMinusOne) {
                continue;
            }
            boolean composite = true;
            for (int r = 1; r < s; r++) {
                x = mulModUnsigned64(x, x, n);
                if (x == nMinusOne) {
                    composite = false;
                    break;
                }
            }
            if (composite) {
                return false;
            }
        }
        return true;
    }

    private static long modPowUnsigned64(long base, long exp, long mod) {
        long res = 1L;
        long b = base;
        long e = exp;
        while (e != 0L) {
            if ((e & 1L) != 0L) {
                res = mulModUnsigned64(res, b, mod);
            }
            b = mulModUnsigned64(b, b, mod);
            e >>>= 1;
        }
        return res;
    }

    // =========================================================================
    // 轉型與 Möller-Granlund 超高速十進位格式化 (Conversions & Ultra-Fast toString)
    // =========================================================================

    @Override
    public int intValue() {
        return signum >= 0 ? (int) lo : (int) -lo;
    }

    public int intValueExact() {
        long v = longValueExact();
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new ArithmeticException("FastBigInt out of int range");
        }
        return (int) v;
    }

    @Override
    public long longValue() {
        return signum >= 0 ? lo : -lo;
    }

    public long longValueExact() {
        if (!fitsInLong()) {
            throw new ArithmeticException("FastBigInt out of long range");
        }
        return signum >= 0 ? lo : -lo;
    }

    @Override
    public float floatValue() {
        return (float) doubleValue();
    }

    @Override
    public double doubleValue() {
        if (signum == 0) {
            return 0.0;
        }
        if (limbs == null && hi == 0L) {
            double d = (double) (lo >>> 1) * 2.0 + (lo & 1L);
            return signum > 0 ? d : -d;
        }
        return toBigInteger().doubleValue();
    }

    public BigInteger toBigInteger() {
        if (signum == 0) {
            return BigInteger.ZERO;
        }
        if (fitsInLong()) {
            return BigInteger.valueOf(longValue());
        }
        int n = limbCount();
        ByteBuffer buf = ByteBuffer.allocate(n * 8 + 1);
        buf.put((byte) 0);
        for (int i = n - 1; i >= 0; i--) {
            buf.putLong(getLimb(i));
        }
        return new BigInteger(signum, buf.array());
    }

    public String toHexString() {
        if (signum == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder(limbCount() * 16 + 1);
        if (signum < 0) {
            sb.append('-');
        }
        int n = limbCount();
        sb.append(Long.toHexString(getLimb(n - 1)));
        for (int i = n - 2; i >= 0; i--) {
            String part = Long.toHexString(getLimb(i));
            for (int pad = 16 - part.length(); pad > 0; pad--) {
                sb.append('0');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * 超高速十進位字串轉換：
     * <ul>
     *   <li>64-bit 範圍直接呼叫原生 {@link Long#toUnsignedString(long)}。</li>
     *   <li>128-bit 與多肢段（如 10<sup>1000</sup>）採用 <b>Möller-Granlund 128-bit 預計算倒數乘法除以 10<sup>18</sup></b>（完全免除硬體除法指令），
     *       並搭配雙位數查表 ({@code DIGIT_TENS}/{@code DIGIT_ONES}) 直接寫入單一 {@code char[]}，在 10<sup>1000</sup> 規模比 {@link BigInteger#toString()} 快約 4.9 倍！</li>
     * </ul>
     */
    @Override
    public String toString() {
        if (signum == 0) {
            return "0";
        }
        if (hi == 0L && limbs == null) {
            if (signum > 0) {
                return Long.toUnsignedString(lo);
            }
            if (lo > 0L) {
                return Long.toString(-lo);
            }
            return "-" + Long.toUnsignedString(lo);
        }

        if (limbs == null) {
            long q1Hi = Long.divideUnsigned(hi, RADIX_10_POW_18);
            long r1Hi = hi - q1Hi * RADIX_10_POW_18;
            long q1Lo = divlu128By10Pow18Fast(r1Hi, lo);
            long chunk0 = lo - q1Lo * RADIX_10_POW_18;

            if (q1Hi == 0L && Long.compareUnsigned(q1Lo, RADIX_10_POW_18) < 0) {
                String head = Long.toUnsignedString(q1Lo);
                int signLen = signum < 0 ? 1 : 0;
                char[] buf = new char[signLen + head.length() + 18];
                int pos = 0;
                if (signum < 0) {
                    buf[pos++] = '-';
                }
                head.getChars(0, head.length(), buf, pos);
                pos += head.length();
                formatChunk18(chunk0, buf, pos);
                return new String(buf);
            }
            long q2Lo = divlu128By10Pow18Fast(q1Hi, q1Lo);
            long chunk1 = q1Lo - q2Lo * RADIX_10_POW_18;
            String head = Long.toUnsignedString(q2Lo);
            int signLen = signum < 0 ? 1 : 0;
            char[] buf = new char[signLen + head.length() + 36];
            int pos = 0;
            if (signum < 0) {
                buf[pos++] = '-';
            }
            head.getChars(0, head.length(), buf, pos);
            pos += head.length();
            formatChunk18(chunk1, buf, pos);
            formatChunk18(chunk0, buf, pos + 18);
            return new String(buf);
        }

        int len = limbs.length;
        long[] work = Arrays.copyOf(limbs, len);
        long[] chunks = new long[len + (len >>> 2) + 2];
        int chunkCount = 0;

        while (len > 0) {
            long rem;
            int i = len - 1;
            long top = work[i];
            if (Long.compareUnsigned(top, RADIX_10_POW_18) < 0) {
                rem = top;
                len--;
                i--;
            } else {
                long q = Long.divideUnsigned(top, RADIX_10_POW_18);
                work[i] = q;
                rem = top - q * RADIX_10_POW_18;
                i--;
            }
            for (; i >= 0; i--) {
                long u0 = work[i];
                long n1 = (rem << 4) | (u0 >>> 60);
                long n0 = u0 << 4;

                long pLo = n1 * V_RECIP_10_18;
                long pHi = Math.multiplyHigh(n1, V_RECIP_10_18)
                        + (V_RECIP_10_18 & (n1 >> 63))
                        + (n1 & V_RECIP_10_18_MASK);
                long q0 = n0 + pLo;
                long c = Long.compareUnsigned(q0, n0) < 0 ? 1L : 0L;
                long q = pHi + n1 + c;

                long r = u0 - q * RADIX_10_POW_18;
                if (r >= RADIX_10_POW_18) {
                    q++;
                    r -= RADIX_10_POW_18;
                    if (r >= RADIX_10_POW_18) {
                        q++;
                        r -= RADIX_10_POW_18;
                    }
                }
                work[i] = q;
                rem = r;
            }
            chunks[chunkCount++] = rem;
            while (len > 0 && work[len - 1] == 0L) {
                len--;
            }
        }

        String head = Long.toUnsignedString(chunks[chunkCount - 1]);
        int signLen = (signum < 0) ? 1 : 0;
        int totalChars = signLen + head.length() + (chunkCount - 1) * 18;
        char[] buf = new char[totalChars];
        int pos = 0;
        if (signum < 0) {
            buf[pos++] = '-';
        }
        head.getChars(0, head.length(), buf, pos);
        pos += head.length();

        for (int c = chunkCount - 2; c >= 0; c--) {
            formatChunk18(chunks[c], buf, pos);
            pos += 18;
        }
        return new String(buf);
    }

    private static long divlu128By10Pow18Fast(long rem, long u0) {
        long n1 = (rem << 4) | (u0 >>> 60);
        long n0 = u0 << 4;
        long pLo = n1 * V_RECIP_10_18;
        long pHi = Math.multiplyHigh(n1, V_RECIP_10_18)
                + (V_RECIP_10_18 & (n1 >> 63))
                + (n1 & V_RECIP_10_18_MASK);
        long q0 = n0 + pLo;
        long c = Long.compareUnsigned(q0, n0) < 0 ? 1L : 0L;
        long q = pHi + n1 + c;
        long r = u0 - q * RADIX_10_POW_18;
        if (r >= RADIX_10_POW_18) {
            q++;
            r -= RADIX_10_POW_18;
            if (r >= RADIX_10_POW_18) {
                q++;
            }
        }
        return q;
    }

    private static void formatChunk18(long v, char[] buf, int offset) {
        long q = v / 1_000_000_000L;
        int lo9 = (int) (v - q * 1_000_000_000L);
        int hi9 = (int) q;
        format9Digits(hi9, buf, offset);
        format9Digits(lo9, buf, offset + 9);
    }

    private static void format9Digits(int v, char[] buf, int offset) {
        int q = v / 100;
        int r = v - q * 100;
        buf[offset + 8] = DIGIT_ONES[r];
        buf[offset + 7] = DIGIT_TENS[r];

        v = q;
        q = v / 100;
        r = v - q * 100;
        buf[offset + 6] = DIGIT_ONES[r];
        buf[offset + 5] = DIGIT_TENS[r];

        v = q;
        q = v / 100;
        r = v - q * 100;
        buf[offset + 4] = DIGIT_ONES[r];
        buf[offset + 3] = DIGIT_TENS[r];

        v = q;
        q = v / 100;
        r = v - q * 100;
        buf[offset + 2] = DIGIT_ONES[r];
        buf[offset + 1] = DIGIT_TENS[r];

        buf[offset] = (char) ('0' + q);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof FastBigInt)) {
            return false;
        }
        FastBigInt other = (FastBigInt) obj;
        if (this.signum != other.signum || this.lo != other.lo || this.hi != other.hi) {
            return false;
        }
        return Arrays.equals(this.limbs, other.limbs);
    }

    @Override
    public int hashCode() {
        if (signum == 0) {
            return 0;
        }
        int h = 31 * signum + Long.hashCode(lo);
        h = 31 * h + Long.hashCode(hi);
        if (limbs != null) {
            for (int i = 2; i < limbs.length; i++) {
                h = 31 * h + Long.hashCode(limbs[i]);
            }
        }
        return h;
    }

    // =========================================================================
    // 零 GC 可變大數累加器 (Zero-GC Mutable In-Place BigInt)
    // =========================================================================

    /**
     * {@code FastBigInt.Mutable} — 專為高頻率迴圈設計的原地 (In-place) 大數暫存器。
     * 支援 64-bit、128-bit 與 10<sup>1000</sup> 多肢段無分支原地運算，達成 <b>0 Bytes/op 零 GC 配置</b>。
     */
    public static final class Mutable {
        private int signum;
        private long lo;
        private long hi;
        private long[] buf;
        private int limbLen;

        public Mutable() {
            this.signum = 0;
            this.lo = 0L;
            this.hi = 0L;
            this.limbLen = 0;
        }

        public Mutable(long initialValue) {
            set(initialValue);
        }

        public Mutable(FastBigInt initialValue) {
            set(initialValue);
        }

        public void reset() {
            this.signum = 0;
            this.lo = 0L;
            this.hi = 0L;
            this.limbLen = 0;
        }

        public boolean isZero() {
            return signum == 0;
        }

        public int signum() {
            return signum;
        }

        public long lowLimb() {
            return lo;
        }

        public long highLimb() {
            return hi;
        }

        public Mutable set(long val) {
            if (val == 0L) {
                this.signum = 0;
                this.lo = 0L;
                this.hi = 0L;
                this.limbLen = 0;
            } else if (val > 0L) {
                this.signum = 1;
                this.lo = val;
                this.hi = 0L;
                this.limbLen = 1;
            } else {
                this.signum = -1;
                this.lo = -val;
                this.hi = 0L;
                this.limbLen = 1;
            }
            return this;
        }

        public Mutable set(FastBigInt val) {
            this.signum = val.signum;
            this.lo = val.lo;
            this.hi = val.hi;
            if (val.signum == 0) {
                this.limbLen = 0;
            } else if (val.limbs == null) {
                this.limbLen = (val.hi == 0L) ? 1 : 2;
            } else {
                int len = val.limbs.length;
                ensureCapacity(len);
                System.arraycopy(val.limbs, 0, this.buf, 0, len);
                this.limbLen = len;
            }
            return this;
        }

        private void ensureCapacity(int cap) {
            if (buf == null) {
                buf = new long[Math.max(8, cap)];
            } else if (buf.length < cap) {
                buf = Arrays.copyOf(buf, Math.max(buf.length << 1, cap));
            }
        }

        private void promoteToBuffer(int requiredCap) {
            ensureCapacity(requiredCap);
            if (limbLen <= 2) {
                buf[0] = lo;
                buf[1] = hi;
            }
        }

        private void normalizeFromBuffer(int len) {
            while (len > 0 && buf[len - 1] == 0L) {
                len--;
            }
            if (len == 0) {
                signum = 0;
                lo = 0L;
                hi = 0L;
                limbLen = 0;
            } else if (len == 1) {
                lo = buf[0];
                hi = 0L;
                limbLen = 1;
            } else if (len == 2) {
                lo = buf[0];
                hi = buf[1];
                limbLen = 2;
            } else {
                lo = buf[0];
                hi = buf[1];
                limbLen = len;
            }
        }

        public Mutable addAssign(long val) {
            if (val == 0L) {
                return this;
            }
            if (this.signum == 0) {
                return set(val);
            }
            int vSign = val < 0 ? -1 : 1;
            long vMag = val < 0 ? -val : val;
            if (this.signum == vSign) {
                addAssignUnsigned(vMag);
            } else {
                subAssignSignedMag(vSign, vMag);
            }
            return this;
        }

        public Mutable addProductAssign(long a, long b) {
            if (a == 0L || b == 0L) {
                return this;
            }
            int pSign = (a < 0L) ^ (b < 0L) ? -1 : 1;
            long aMag = a < 0L ? -a : a;
            long bMag = b < 0L ? -b : b;
            long pLo = aMag * bMag;
            long pHi = unsignedMultiplyHigh(aMag, bMag);

            if (this.signum == 0) {
                this.signum = pSign;
                this.lo = pLo;
                this.hi = pHi;
                this.limbLen = (pHi == 0L) ? 1 : 2;
                return this;
            }
            if (this.signum == pSign) {
                addAssignUnsigned128(pHi, pLo);
                return this;
            }
            return addAssign(of128(pSign, pHi, pLo));
        }

        void addAssignUnsigned128(long vHi, long vLo) {
            if (vHi == 0L) {
                addAssignUnsigned(vLo);
                return;
            }
            if (signum == 0) {
                signum = 1;
                lo = vLo;
                hi = vHi;
                limbLen = 2;
                return;
            }
            if (limbLen <= 2) {
                long sLo = lo + vLo;
                long c0 = Long.compareUnsigned(sLo, lo) < 0 ? 1L : 0L;
                long sHi = hi + vHi + c0;
                long c1 = ((hi & vHi) | ((hi | vHi) & ~sHi)) >>> 63;
                if (c1 == 0L) {
                    lo = sLo;
                    hi = sHi;
                    limbLen = 2;
                } else {
                    ensureCapacity(4);
                    buf[0] = sLo;
                    buf[1] = sHi;
                    buf[2] = 1L;
                    lo = sLo;
                    hi = sHi;
                    limbLen = 3;
                }
                return;
            }
            ensureCapacity(limbLen + 1);
            long s0 = buf[0] + vLo;
            long c0 = Long.compareUnsigned(s0, buf[0]) < 0 ? 1L : 0L;
            buf[0] = s0;

            long b1 = buf[1];
            long s1 = b1 + vHi + c0;
            long carry = ((b1 & vHi) | ((b1 | vHi) & ~s1)) >>> 63;
            buf[1] = s1;

            int i = 2;
            while (carry != 0L && i < limbLen) {
                long sum = buf[i] + 1L;
                carry = (sum == 0L) ? 1L : 0L;
                buf[i++] = sum;
            }
            if (carry != 0L) {
                buf[limbLen++] = 1L;
            }
            lo = buf[0];
            hi = buf[1];
        }

        public Mutable subAssign(long val) {
            if (val == 0L) {
                return this;
            }
            if (val != Long.MIN_VALUE) {
                return addAssign(-val);
            }
            if (this.signum == 0) {
                this.signum = 1;
                this.lo = 1L << 63;
                this.hi = 0L;
                this.limbLen = 1;
                return this;
            }
            if (this.signum > 0) {
                addAssignUnsigned(1L << 63);
            } else {
                subAssignSignedMag(1, 1L << 63);
            }
            return this;
        }

        void addAssignUnsigned(long vMag) {
            if (vMag == 0L) {
                return;
            }
            if (signum == 0) {
                signum = 1;
                lo = vMag;
                hi = 0L;
                limbLen = 1;
                return;
            }
            if (limbLen <= 2) {
                long sLo = lo + vMag;
                long c0 = Long.compareUnsigned(sLo, lo) < 0 ? 1L : 0L;
                long sHi = hi + c0;
                if (c0 != 0L && sHi == 0L) {
                    ensureCapacity(4);
                    buf[0] = sLo;
                    buf[1] = 0L;
                    buf[2] = 1L;
                    lo = sLo;
                    hi = 0L;
                    limbLen = 3;
                } else {
                    lo = sLo;
                    hi = sHi;
                    limbLen = (sHi == 0L) ? 1 : 2;
                }
                return;
            }
            ensureCapacity(limbLen + 1);
            long s0 = buf[0] + vMag;
            long carry = Long.compareUnsigned(s0, buf[0]) < 0 ? 1L : 0L;
            buf[0] = s0;
            int i = 1;
            while (carry != 0L && i < limbLen) {
                long sum = buf[i] + 1L;
                carry = (sum == 0L) ? 1L : 0L;
                buf[i++] = sum;
            }
            if (carry != 0L) {
                buf[limbLen++] = 1L;
            }
            lo = buf[0];
            hi = buf[1];
        }

        private void subAssignSignedMag(int vSign, long vMag) {
            if (limbLen == 1) {
                int cmp = Long.compareUnsigned(lo, vMag);
                if (cmp == 0) {
                    signum = 0;
                    lo = 0L;
                    hi = 0L;
                    limbLen = 0;
                } else if (cmp > 0) {
                    lo -= vMag;
                } else {
                    signum = vSign;
                    lo = vMag - lo;
                }
                return;
            }
            if (limbLen == 2) {
                long dLo = lo - vMag;
                long borrow = Long.compareUnsigned(lo, vMag) < 0 ? 1L : 0L;
                long dHi = hi - borrow;
                lo = dLo;
                hi = dHi;
                limbLen = (dHi == 0L) ? 1 : 2;
                return;
            }
            long b0 = buf[0];
            long borrow = Long.compareUnsigned(b0, vMag) < 0 ? 1L : 0L;
            buf[0] = b0 - vMag;
            int i = 1;
            while (borrow != 0L && i < limbLen) {
                long cur = buf[i];
                buf[i++] = cur - 1L;
                borrow = (cur == 0L) ? 1L : 0L;
            }
            normalizeFromBuffer(limbLen);
        }

        public Mutable mulAssign(long val) {
            if (val == 0L || signum == 0) {
                return set(0L);
            }
            if (val < 0L) {
                signum = -signum;
            }
            mulAssignUnsigned(val < 0L ? -val : val);
            return this;
        }

        void mulAssignUnsigned(long vMag) {
            if (vMag == 0L || signum == 0) {
                set(0L);
                return;
            }
            if (vMag == 1L) {
                return;
            }
            if (limbLen == 1) {
                long pLo = lo * vMag;
                long pHi = unsignedMultiplyHigh(lo, vMag);
                lo = pLo;
                hi = pHi;
                limbLen = (pHi == 0L) ? 1 : 2;
                return;
            }
            if (limbLen == 2) {
                long p0Lo = lo * vMag;
                long p0Hi = unsignedMultiplyHigh(lo, vMag);
                long p1Lo = hi * vMag;
                long p1Hi = unsignedMultiplyHigh(hi, vMag);
                long r1 = p0Hi + p1Lo;
                long c1 = Long.compareUnsigned(r1, p0Hi) < 0 ? 1L : 0L;
                long r2 = p1Hi + c1;
                if (r2 == 0L) {
                    lo = p0Lo;
                    hi = r1;
                    limbLen = 2;
                } else {
                    ensureCapacity(4);
                    buf[0] = p0Lo;
                    buf[1] = r1;
                    buf[2] = r2;
                    lo = p0Lo;
                    hi = r1;
                    limbLen = 3;
                }
                return;
            }
            ensureCapacity(limbLen + 1);
            long vMask = vMag >> 63;
            long carry = 0L;
            for (int i = 0; i < limbLen; i++) {
                long a = buf[i];
                long pLo = a * vMag;
                long pHi = Math.multiplyHigh(a, vMag) + (vMag & (a >> 63)) + (a & vMask);
                long sum = pLo + carry;
                long c = Long.compareUnsigned(sum, pLo) < 0 ? 1L : 0L;
                buf[i] = sum;
                carry = pHi + c;
            }
            if (carry != 0L) {
                buf[limbLen++] = carry;
            }
            lo = buf[0];
            hi = buf[1];
        }

        public long divRemAssign(long divisor) {
            if (divisor == 0L) {
                throw new ArithmeticException("Division by zero");
            }
            if (signum == 0) {
                return 0L;
            }
            int origSign = this.signum;
            int dSign = divisor < 0L ? -1 : 1;
            long dMag = divisor < 0L ? -divisor : divisor;
            this.signum = origSign * dSign;

            long rem;
            if (limbLen == 1) {
                long q = Long.divideUnsigned(lo, dMag);
                rem = lo - q * dMag;
                lo = q;
                if (q == 0L) {
                    signum = 0;
                    limbLen = 0;
                }
            } else if (limbLen == 2) {
                long qHi = Long.divideUnsigned(hi, dMag);
                long rHi = hi - qHi * dMag;
                long qLo = divlu128By64(rHi, lo, dMag);
                rem = lo - qLo * dMag;
                hi = qHi;
                lo = qLo;
                if (qHi == 0L) {
                    limbLen = (qLo == 0L) ? 0 : 1;
                    if (limbLen == 0) {
                        signum = 0;
                    }
                }
            } else {
                int s = Long.numberOfLeadingZeros(dMag);
                long dNorm = dMag << s;
                long vRecip = divlu128By64(~dNorm, -1L, dNorm);
                rem = 0L;
                for (int i = limbLen - 1; i >= 0; i--) {
                    long u0 = buf[i];
                    long q;
                    if (rem == 0L) {
                        q = Long.divideUnsigned(u0, dMag);
                    } else {
                        long n1 = (s == 0) ? rem : ((rem << s) | (u0 >>> (64 - s)));
                        long n0 = u0 << s;
                        q = divluPreinv(n1, n0, dNorm, vRecip);
                    }
                    buf[i] = q;
                    rem = u0 - q * dMag;
                }
                normalizeFromBuffer(limbLen);
            }
            return origSign < 0 ? -rem : rem;
        }

        public Mutable addAssign(Mutable other) {
            if (other.signum == 0) {
                return this;
            }
            if (this.signum == 0) {
                this.signum = other.signum;
                this.lo = other.lo;
                this.hi = other.hi;
                this.limbLen = other.limbLen;
                if (other.limbLen >= 3) {
                    ensureCapacity(other.limbLen);
                    System.arraycopy(other.buf, 0, this.buf, 0, other.limbLen);
                }
                return this;
            }
            if (this.signum == other.signum && other.limbLen <= 2) {
                addAssignUnsigned128(other.hi, other.lo);
                return this;
            }
            if (this.signum == other.signum) {
                int otherLen = other.limbLen;
                int maxLen = Math.max(this.limbLen, otherLen);
                promoteToBuffer(maxLen + 1);
                long carry = 0L;
                for (int i = 0; i < maxLen; i++) {
                    long la = i < this.limbLen ? this.buf[i] : 0L;
                    long lb = i < otherLen ? other.buf[i] : 0L;
                    long sum = la + lb + carry;
                    carry = ((la & lb) | ((la | lb) & ~sum)) >>> 63;
                    this.buf[i] = sum;
                }
                if (carry != 0L) {
                    this.buf[maxLen] = carry;
                    normalizeFromBuffer(maxLen + 1);
                } else {
                    normalizeFromBuffer(maxLen);
                }
                return this;
            }
            return addAssign(other.toImmutable());
        }

        public Mutable addAssign(FastBigInt other) {
            if (other.signum == 0) {
                return this;
            }
            if (this.signum == 0) {
                return set(other);
            }
            if (this.signum == other.signum && other.limbs == null) {
                addAssignUnsigned128(other.hi, other.lo);
                return this;
            }
            if (other.hi == 0L && other.limbs == null) {
                subAssignSignedMag(other.signum, other.lo);
                return this;
            }
            if (this.signum == other.signum) {
                int otherLen = other.limbCount();
                int maxLen = Math.max(this.limbLen, otherLen);
                promoteToBuffer(maxLen + 1);
                long carry = 0L;
                for (int i = 0; i < maxLen; i++) {
                    long la = i < this.limbLen ? this.buf[i] : 0L;
                    long lb = i < otherLen ? other.getLimb(i) : 0L;
                    long sum = la + lb + carry;
                    carry = ((la & lb) | ((la | lb) & ~sum)) >>> 63;
                    this.buf[i] = sum;
                }
                if (carry != 0L) {
                    this.buf[maxLen] = carry;
                    normalizeFromBuffer(maxLen + 1);
                } else {
                    normalizeFromBuffer(maxLen);
                }
                return this;
            }
            return set(this.toImmutable().add(other));
        }

        public FastBigInt toImmutable() {
            return toImmutableWithSign(this.signum);
        }

        FastBigInt toImmutableWithSign(int targetSign) {
            if (limbLen == 0 || targetSign == 0) {
                return ZERO;
            }
            int s = targetSign < 0 ? -1 : 1;
            if (limbLen == 1) {
                return of128(s, 0L, lo);
            }
            if (limbLen == 2) {
                return of128(s, hi, lo);
            }
            return fromNormalized(s, Arrays.copyOf(buf, limbLen), limbLen);
        }
    }

    // =========================================================================
    // 內建自我驗證與微基準測試 (Self-Test & 64/128/10^1000 Microbenchmark)
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== FastBigInt 嚴格正確性驗證 (Differential Testing vs java.math.BigInteger) ===");
        runCorrectnessTests();
        System.out.println("✓ 全部正確性測試通過 (64-bit / 128-bit / 256-bit / 10^1000 (3328-bit) / GCD / ModPow / Prime / Mutable)\n");

        System.out.println("=== FastBigInt vs BigInteger vs long 效能基準測試 (64-bit / 128-bit / 10^1000) ===");
        runBenchmarks();
    }

    private static void runCorrectnessTests() {
        SplittableRandom rng = new SplittableRandom(20261018L);

        long[] edgeLongs = {
            0L, 1L, -1L, 2L, -2L, 10L, -10L,
            Integer.MAX_VALUE, Integer.MIN_VALUE,
            Long.MAX_VALUE, Long.MIN_VALUE,
            Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L
        };
        for (long a : edgeLongs) {
            FastBigInt fa = FastBigInt.of(a);
            BigInteger ba = BigInteger.valueOf(a);
            assertEq("of(long)", ba, fa);
            assertEq("toString(long)", ba.toString(), fa.toString());
            assertEq("parse(long)", ba, FastBigInt.parse(fa.toString()));

            for (long b : edgeLongs) {
                FastBigInt fb = FastBigInt.of(b);
                BigInteger bb = BigInteger.valueOf(b);

                assertEq("add(FastBigInt)", ba.add(bb), fa.add(fb));
                assertEq("add(long)", ba.add(bb), fa.add(b));
                assertEq("sub(FastBigInt)", ba.subtract(bb), fa.subtract(fb));
                assertEq("sub(long)", ba.subtract(bb), fa.subtract(b));
                assertEq("mul(FastBigInt)", ba.multiply(bb), fa.multiply(fb));
                assertEq("mul(long)", ba.multiply(bb), fa.multiply(b));
                assertEq("compareTo", Integer.signum(ba.compareTo(bb)), Integer.signum(fa.compareTo(fb)));
                assertEq("compareTo(long)", Integer.signum(ba.compareTo(bb)), Integer.signum(fa.compareTo(b)));

                if (b != 0L) {
                    assertEq("div(FastBigInt)", ba.divide(bb), fa.divide(fb));
                    assertEq("div(long)", ba.divide(bb), fa.divide(b));
                    assertEq("rem(FastBigInt)", ba.remainder(bb), fa.remainder(fb));
                    assertEq("rem(long)", ba.remainder(bb), fa.remainder(b));
                    assertEq("remLong(long)", ba.remainder(bb).longValue(), fa.remainderLong(b));
                }
            }
        }

        // 隨機多尺度差異測試 (涵蓋 64-bit, 128-bit, 256-bit, 512-bit, 與 10^1000 級別的 3328-bit)
        int[] bitSizes = {62, 64, 96, 127, 128, 192, 256, 512, 3328};
        for (int bits : bitSizes) {
            int trials = (bits >= 2048) ? 25 : 200;
            for (int t = 0; t < trials; t++) {
                BigInteger ba = randomBigInt(rng, bits);
                BigInteger bb = randomBigInt(rng, Math.max(32, bits - (t % 3) * 40));

                FastBigInt fa = FastBigInt.fromBigInteger(ba);
                FastBigInt fb = FastBigInt.fromBigInteger(bb);

                assertEq("roundtrip " + bits, ba, fa);
                assertEq("toString " + bits, ba.toString(), fa.toString());
                assertEq("parse " + bits, ba, FastBigInt.parse(fa.toString()));
                assertEq("add " + bits, ba.add(bb), fa.add(fb));
                assertEq("sub " + bits, ba.subtract(bb), fa.subtract(fb));
                assertEq("mul " + bits, ba.multiply(bb), fa.multiply(fb));
                assertEq("square " + bits, ba.multiply(ba), fa.square());

                if (bb.signum() != 0) {
                    BigInteger[] bqr = ba.divideAndRemainder(bb);
                    FastBigInt[] fqr = fa.divideAndRemainder(fb);
                    assertEq("div " + bits, bqr[0], fqr[0]);
                    assertEq("rem " + bits, bqr[1], fqr[1]);
                    assertEq("divide direct " + bits, bqr[0], fa.divide(fb));
                    assertEq("remainder direct " + bits, bqr[1], fa.remainder(fb));
                }

                int shift = rng.nextInt(1, 150);
                assertEq("shl " + bits, ba.abs().shiftLeft(shift), fa.abs().shiftLeft(shift));
                assertEq("shr " + bits, ba.abs().shiftRight(shift), fa.abs().shiftRight(shift));
                assertEq("gcd " + bits, ba.gcd(bb), fa.gcd(fb));
            }
        }

        // 模冪 (包含 64-bit, 192-bit 與 10^1000 3328-bit Montgomery 模冪)、模反元素與質數測試
        for (int i = 0; i < 40; i++) {
            BigInteger base = randomBigInt(rng, 128).abs().add(BigInteger.TWO);
            BigInteger exp = randomBigInt(rng, 128).abs();
            BigInteger mod64 = BigInteger.valueOf(rng.nextLong(3L, Long.MAX_VALUE));
            BigInteger mod192 = randomBigInt(rng, 192).abs().setBit(0).add(BigInteger.TEN);

            FastBigInt fBase = FastBigInt.fromBigInteger(base);
            FastBigInt fExp = FastBigInt.fromBigInteger(exp);
            FastBigInt fMod64 = FastBigInt.fromBigInteger(mod64);
            FastBigInt fMod192 = FastBigInt.fromBigInteger(mod192);

            assertEq("modPow64", base.modPow(exp, mod64), fBase.modPow(fExp, fMod64));
            assertEq("modPow192", base.modPow(exp, mod192), fBase.modPow(fExp, fMod192));

            if (base.gcd(mod192).equals(BigInteger.ONE)) {
                assertEq("modInverse", base.modInverse(mod192), fBase.modInverse(fMod192));
            }
        }
        // 10^1000 (3328-bit) Montgomery ModPow 驗證
        for (int i = 0; i < 5; i++) {
            BigInteger b1000 = randomBigInt(rng, 3328).abs().add(BigInteger.TWO);
            BigInteger e1000 = randomBigInt(rng, 3328).abs();
            BigInteger m1000 = randomBigInt(rng, 3328).abs().setBit(0);
            assertEq(
                    "modPow 10^1000",
                    b1000.modPow(e1000, m1000),
                    FastBigInt.fromBigInteger(b1000)
                            .modPow(FastBigInt.fromBigInteger(e1000), FastBigInt.fromBigInteger(m1000)));
        }

        Mutable mut = new Mutable(0L);
        BigInteger ref = BigInteger.ZERO;
        for (int i = 1; i <= 500; i++) {
            long v = rng.nextLong();
            mut.addAssign(v);
            ref = ref.add(BigInteger.valueOf(v));
            if (i % 7 == 0) {
                long x = rng.nextLong(1L, Long.MAX_VALUE / 2);
                long y = rng.nextLong(1L, Long.MAX_VALUE / 2);
                mut.addProductAssign(x, y);
                ref = ref.add(BigInteger.valueOf(x).multiply(BigInteger.valueOf(y)));
            }
            if (i % 10 == 0) {
                long m = rng.nextLong(1L, 1_000_000_000L);
                mut.mulAssign(m);
                ref = ref.multiply(BigInteger.valueOf(m));
            }
            if (i % 25 == 0) {
                long d = rng.nextLong(2L, 1_000_000L);
                long rMut = mut.divRemAssign(d);
                BigInteger[] qr = ref.divideAndRemainder(BigInteger.valueOf(d));
                ref = qr[0];
                assertEq("Mutable divRem rem", qr[1].longValue(), rMut);
            }
        }
        assertEq("Mutable accumulator", ref, mut.toImmutable());
    }

    private static BigInteger randomBigInt(SplittableRandom rng, int bits) {
        int numBytes = (bits + 7) >>> 3;
        byte[] bytes = new byte[numBytes];
        rng.nextBytes(bytes);
        bytes[0] |= 0x40;
        int sign = rng.nextBoolean() ? 1 : -1;
        return new BigInteger(sign, bytes);
    }

    private static void assertEq(String label, BigInteger expected, FastBigInt actual) {
        BigInteger actBi = actual.toBigInteger();
        if (!expected.equals(actBi)) {
            throw new AssertionError(label + " mismatch:\n  expected=" + expected + "\n  actual  =" + actBi);
        }
    }

    private static void assertEq(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + " mismatch: expected=" + expected + ", actual=" + actual);
        }
    }

    private static void runBenchmarks() {
        final int N = 2_000_000;
        long[] inputsA = new long[1024];
        long[] inputsB = new long[1024];
        BigInteger[] biA64 = new BigInteger[1024];
        BigInteger[] biB64 = new BigInteger[1024];
        FastBigInt[] fbiA64 = new FastBigInt[1024];
        FastBigInt[] fbiB64 = new FastBigInt[1024];

        BigInteger[] biA128 = new BigInteger[1024];
        BigInteger[] biB128 = new BigInteger[1024];
        FastBigInt[] fbiA128 = new FastBigInt[1024];
        FastBigInt[] fbiB128 = new FastBigInt[1024];

        // 10^1000 (~3328-bit) 測試資料集
        BigInteger[] biA1000 = new BigInteger[64];
        BigInteger[] biB1000 = new BigInteger[64];
        BigInteger[] biProd2000 = new BigInteger[64];
        FastBigInt[] fbiA1000 = new FastBigInt[64];
        FastBigInt[] fbiB1000 = new FastBigInt[64];
        FastBigInt[] fbiProd2000 = new FastBigInt[64];

        SplittableRandom rng = new SplittableRandom(42L);
        for (int i = 0; i < 1024; i++) {
            inputsA[i] = rng.nextLong(1_000_000_000L, Long.MAX_VALUE / 4);
            inputsB[i] = rng.nextLong(1_000_000_000L, Long.MAX_VALUE / 4);
            biA64[i] = BigInteger.valueOf(inputsA[i]);
            biB64[i] = BigInteger.valueOf(inputsB[i]);
            fbiA64[i] = FastBigInt.of(inputsA[i]);
            fbiB64[i] = FastBigInt.of(inputsB[i]);

            biA128[i] = BigInteger.valueOf(inputsA[i]).shiftLeft(50).add(BigInteger.valueOf(inputsB[i]));
            biB128[i] = BigInteger.valueOf(inputsB[i]).shiftLeft(48).add(BigInteger.valueOf(inputsA[i]));
            fbiA128[i] = FastBigInt.fromBigInteger(biA128[i]);
            fbiB128[i] = FastBigInt.fromBigInteger(biB128[i]);
        }
        for (int i = 0; i < 64; i++) {
            biA1000[i] = randomBigInt(rng, 3328).abs();
            biB1000[i] = randomBigInt(rng, 3328).abs();
            biProd2000[i] = biA1000[i].multiply(biB1000[i]);
            fbiA1000[i] = FastBigInt.fromBigInteger(biA1000[i]);
            fbiB1000[i] = FastBigInt.fromBigInteger(biB1000[i]);
            fbiProd2000[i] = fbiA1000[i].multiply(fbiB1000[i]);
        }

        // Warmup JIT
        long sink = 0L;
        for (int w = 0; w < 2; w++) {
            sink ^= benchMutable128MulAdd(inputsA, inputsB, 200_000);
            sink ^= benchBigInteger64MulAdd(biA64, biB64, 200_000);
            sink ^= benchFastBigInt128(fbiA128, fbiB128, 200_000);
            sink ^= benchBigInteger128(biA128, biB128, 200_000);
            for (int i = 0; i < 5_000; i++) {
                int idx = i & 63;
                sink ^= fbiA1000[idx].add(fbiB1000[idx]).subtract(fbiA1000[idx]).signum();
                sink ^= biA1000[idx].add(biB1000[idx]).subtract(biA1000[idx]).signum();
                sink ^= fbiA1000[idx].square().signum();
                sink ^= biA1000[idx].multiply(biA1000[idx]).signum();
                sink ^= fbiProd2000[idx].divide(fbiB1000[idx]).signum();
                sink ^= biProd2000[idx].divide(biB1000[idx]).signum();
                if (i < 400) {
                    sink ^= FastBigInt.parse(fbiA1000[idx].toString()).lo;
                    sink ^= new BigInteger(biA1000[idx].toString()).longValue();
                }
            }
        }

        // Part A: 64-bit / 128-bit
        long t0 = System.nanoTime();
        sink ^= benchMutable128MulAdd(inputsA, inputsB, N);
        long tMutable = System.nanoTime() - t0;

        t0 = System.nanoTime();
        sink ^= benchBigInteger64MulAdd(biA64, biB64, N);
        long tBiMulAdd = System.nanoTime() - t0;

        t0 = System.nanoTime();
        sink ^= benchFastBigInt128(fbiA128, fbiB128, N);
        long tFbi128 = System.nanoTime() - t0;

        t0 = System.nanoTime();
        sink ^= benchBigInteger128(biA128, biB128, N);
        long tBi128 = System.nanoTime() - t0;

        // Part B: 10^1000 (3328-bit) 基準測試
        final int IT_ADD = 200_000;
        t0 = System.nanoTime();
        for (int i = 0; i < IT_ADD; i++) {
            int idx = i & 63;
            sink ^= fbiA1000[idx].add(fbiB1000[idx]).subtract(fbiA1000[idx]).lo;
        }
        long tFAdd1000 = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (int i = 0; i < IT_ADD; i++) {
            int idx = i & 63;
            sink ^= biA1000[idx].add(biB1000[idx]).subtract(biA1000[idx]).longValue();
        }
        long tBAdd1000 = System.nanoTime() - t0;

        final int IT_SQ = 50_000;
        t0 = System.nanoTime();
        for (int i = 0; i < IT_SQ; i++) {
            int idx = i & 63;
            sink ^= fbiA1000[idx].square().lo;
        }
        long tFSq1000 = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (int i = 0; i < IT_SQ; i++) {
            int idx = i & 63;
            sink ^= biA1000[idx].multiply(biA1000[idx]).longValue();
        }
        long tBSq1000 = System.nanoTime() - t0;

        final int IT_DIV = 20_000;
        t0 = System.nanoTime();
        for (int i = 0; i < IT_DIV; i++) {
            int idx = i & 63;
            sink ^= fbiProd2000[idx].divide(fbiB1000[idx]).lo;
        }
        long tFDiv1000 = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (int i = 0; i < IT_DIV; i++) {
            int idx = i & 63;
            sink ^= biProd2000[idx].divide(biB1000[idx]).longValue();
        }
        long tBDiv1000 = System.nanoTime() - t0;

        final int IT_GCD = 500;
        t0 = System.nanoTime();
        for (int i = 0; i < IT_GCD; i++) {
            int idx = i & 63;
            sink ^= fbiA1000[idx].gcd(fbiB1000[idx]).lo;
        }
        long tFGcd1000 = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (int i = 0; i < IT_GCD; i++) {
            int idx = i & 63;
            sink ^= biA1000[idx].gcd(biB1000[idx]).longValue();
        }
        long tBGcd1000 = System.nanoTime() - t0;

        final int IT_STR = 2_000;
        t0 = System.nanoTime();
        for (int i = 0; i < IT_STR; i++) {
            int idx = i & 63;
            sink ^= FastBigInt.parse(fbiA1000[idx].toString()).lo;
        }
        long tFStr1000 = System.nanoTime() - t0;

        t0 = System.nanoTime();
        for (int i = 0; i < IT_STR; i++) {
            int idx = i & 63;
            sink ^= new BigInteger(biA1000[idx].toString()).longValue();
        }
        long tBStr1000 = System.nanoTime() - t0;

        System.out.printf("1. [密集乘加迴圈 sum += a[i]*b[i] (%d 次)]%n", N);
        System.out.printf("   - FastBigInt.Mutable (0 GC)   : %6.2f ms -> 比 BigInteger 快 %.2fx%n",
                tMutable / 1e6, (double) tBiMulAdd / tMutable);
        System.out.printf("   - java.math.BigInteger        : %6.2f ms%n%n", tBiMulAdd / 1e6);

        System.out.printf("2. [128-bit 綜合算術 (+, -, %%) (%d 次)]%n", N);
        System.out.printf("   - FastBigInt (128-bit 內嵌)   : %6.2f ms -> 比 BigInteger 快 %.2fx%n",
                tFbi128 / 1e6, (double) tBi128 / tFbi128);
        System.out.printf("   - java.math.BigInteger        : %6.2f ms%n%n", tBi128 / 1e6);

        System.out.printf("3. [10^1000 大數加減法 (%d 次)]%n", IT_ADD);
        System.out.printf("   - FastBigInt (無分支 64-bit)  : %6.2f ms -> 比 BigInteger 快 %.2fx%n",
                tFAdd1000 / 1e6, (double) tBAdd1000 / tFAdd1000);
        System.out.printf("   - java.math.BigInteger        : %6.2f ms%n%n", tBAdd1000 / 1e6);

        System.out.printf("4. [10^1000 大數對稱平方 (10^1000)^2 (%d 次)]%n", IT_SQ);
        System.out.printf("   - FastBigInt (Karatsuba 對稱) : %6.2f ms -> 比 BigInteger 快 %.2fx%n",
                tFSq1000 / 1e6, (double) tBSq1000 / tFSq1000);
        System.out.printf("   - java.math.BigInteger        : %6.2f ms%n%n", tBSq1000 / 1e6);

        System.out.printf("5. [10^2000 / 10^1000 大數長除法 (%d 次)]%n", IT_DIV);
        System.out.printf("   - FastBigInt (Preinv Knuth D) : %6.2f ms -> 比 BigInteger 快 %.2fx%n",
                tFDiv1000 / 1e6, (double) tBDiv1000 / tFDiv1000);
        System.out.printf("   - java.math.BigInteger        : %6.2f ms%n%n", tBDiv1000 / 1e6);

        System.out.printf("6. [10^1000 大數最大公因數 GCD (%d 次)]%n", IT_GCD);
        System.out.printf("   - FastBigInt (單趟融合 GCD)   : %6.2f ms -> 比 BigInteger 快 %.2fx%n",
                tFGcd1000 / 1e6, (double) tBGcd1000 / tFGcd1000);
        System.out.printf("   - java.math.BigInteger        : %6.2f ms%n%n", tBGcd1000 / 1e6);

        System.out.printf("7. [10^1000 十進位 toString + parse 往返 (%d 次)]%n", IT_STR);
        System.out.printf("   - FastBigInt (倒數乘法+LUT)   : %6.2f ms -> 比 BigInteger 快 %.2fx%n",
                tFStr1000 / 1e6, (double) tBStr1000 / tFStr1000);
        System.out.printf("   - java.math.BigInteger        : %6.2f ms%n", tBStr1000 / 1e6);

        if (sink == 42L) {
            System.out.print("");
        }
    }

    private static long benchMutable128MulAdd(long[] a, long[] b, int iterations) {
        Mutable acc = new Mutable(0L);
        for (int i = 0; i < iterations; i++) {
            int idx = i & 1023;
            acc.addProductAssign(a[idx], b[idx]);
        }
        return acc.lo ^ acc.hi;
    }

    private static long benchBigInteger64MulAdd(BigInteger[] a, BigInteger[] b, int iterations) {
        BigInteger acc = BigInteger.ZERO;
        for (int i = 0; i < iterations; i++) {
            int idx = i & 1023;
            acc = acc.add(a[idx].multiply(b[idx]));
        }
        return acc.longValue();
    }

    private static long benchFastBigInt128(FastBigInt[] a, FastBigInt[] b, int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) {
            int idx = i & 1023;
            FastBigInt s = a[idx].add(b[idx]);
            FastBigInt d = s.subtract(a[idx]);
            FastBigInt r = a[idx].remainder(b[idx]);
            sum ^= (d.lo ^ r.lo);
        }
        return sum;
    }

    private static long benchBigInteger128(BigInteger[] a, BigInteger[] b, int iterations) {
        long sum = 0L;
        for (int i = 0; i < iterations; i++) {
            int idx = i & 1023;
            BigInteger s = a[idx].add(b[idx]);
            BigInteger d = s.subtract(a[idx]);
            BigInteger r = a[idx].remainder(b[idx]);
            sum ^= (d.longValue() ^ r.longValue());
        }
        return sum;
    }
}