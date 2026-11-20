package com.kiemke.qrxlsx;

import java.nio.charset.StandardCharsets;

/**
 * Bộ mã hoá QR Code (ISO/IEC 18004, Model 2) viết thuần Java, không phụ thuộc thư viện ngoài,
 * không truy cập mạng. Hỗ trợ version 1-40, 4 mức sửa lỗi, tự chọn chế độ
 * numeric / alphanumeric / byte (UTF-8) để mã càng nhỏ càng dễ quét.
 */
public final class QrEncoder {

    public enum Ecc {
        L(0, 1), M(1, 0), Q(2, 3), H(3, 2);

        final int idx;
        final int formatBits;

        Ecc(int idx, int formatBits) {
            this.idx = idx;
            this.formatBits = formatBits;
        }
    }

    /** Ma trận QR đã mã hoá. */
    public static final class Qr {
        public final int version;
        public final int size;
        private final boolean[][] modules; // [y][x], true = ô đen

        private Qr(int version, boolean[][] modules) {
            this.version = version;
            this.size = modules.length;
            this.modules = modules;
        }

        public boolean isDark(int x, int y) {
            return modules[y][x];
        }
    }

    private static final int NUMERIC = 0, ALNUM = 1, BYTE = 2;
    private static final int[] MODE_BITS = {0x1, 0x2, 0x4};
    private static final String ALNUM_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private static final int[][] ECC_CODEWORDS_PER_BLOCK = {
            {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
            {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
    };

    private static final int[][] NUM_ERROR_CORRECTION_BLOCKS = {
            {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
            {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
            {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
            {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
    };

    private QrEncoder() {
    }

    public static Qr encode(String text, Ecc ecc) {
        return encode(text, ecc, 1);
    }

    /** minVersion chủ yếu để kiểm thử; app dùng 1 để luôn ra mã nhỏ nhất. */
    public static Qr encode(String text, Ecc ecc, int minVersion) {
        if (text == null) throw new IllegalArgumentException("Chuỗi rỗng");
        int mode;
        byte[] bytes = null;
        int charCount;
        int dataBits;
        if (isNumeric(text)) {
            mode = NUMERIC;
            charCount = text.length();
            dataBits = (charCount / 3) * 10 + (charCount % 3 == 1 ? 4 : charCount % 3 == 2 ? 7 : 0);
        } else if (isAlnum(text)) {
            mode = ALNUM;
            charCount = text.length();
            dataBits = (charCount / 2) * 11 + (charCount % 2) * 6;
        } else {
            mode = BYTE;
            bytes = text.getBytes(StandardCharsets.UTF_8);
            charCount = bytes.length;
            dataBits = charCount * 8;
        }

        int version = -1;
        for (int v = Math.max(1, minVersion); v <= 40; v++) {
            int needed = 4 + countBits(mode, v) + dataBits;
            if (charCount < (1 << countBits(mode, v)) && needed <= numDataCodewords(v, ecc) * 8) {
                version = v;
                break;
            }
        }
        if (version < 0) throw new IllegalArgumentException("Dữ liệu quá dài để tạo QR");

        int capacityBits = numDataCodewords(version, ecc) * 8;
        BitBuffer bb = new BitBuffer(capacityBits + 16);
        bb.append(MODE_BITS[mode], 4);
        bb.append(charCount, countBits(mode, version));
        if (mode == NUMERIC) {
            for (int i = 0; i < charCount; ) {
                int n = Math.min(3, charCount - i);
                bb.append(Integer.parseInt(text.substring(i, i + n)), n * 3 + 1);
                i += n;
            }
        } else if (mode == ALNUM) {
            for (int i = 0; i < charCount; ) {
                if (charCount - i >= 2) {
                    bb.append(ALNUM_CHARS.indexOf(text.charAt(i)) * 45 + ALNUM_CHARS.indexOf(text.charAt(i + 1)), 11);
                    i += 2;
                } else {
                    bb.append(ALNUM_CHARS.indexOf(text.charAt(i)), 6);
                    i++;
                }
            }
        } else {
            for (byte b : bytes) bb.append(b & 0xFF, 8);
        }

        bb.append(0, Math.min(4, capacityBits - bb.length)); // terminator
        bb.append(0, (8 - bb.length % 8) % 8);               // căn byte
        for (int pad = 0xEC; bb.length < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);

        byte[] data = new byte[bb.length / 8];
        for (int i = 0; i < data.length; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) b = (b << 1) | bb.get(i * 8 + j);
            data[i] = (byte) b;
        }

        return new Builder(version, ecc, addEccAndInterleave(data, version, ecc)).build();
    }

    // ---------------------------------------------------------------- phân tích dữ liệu

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isAlnum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (ALNUM_CHARS.indexOf(s.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static int countBits(int mode, int version) {
        int tier = version <= 9 ? 0 : version <= 26 ? 1 : 2;
        switch (mode) {
            case NUMERIC:
                return new int[]{10, 12, 14}[tier];
            case ALNUM:
                return new int[]{9, 11, 13}[tier];
            default:
                return new int[]{8, 16, 16}[tier];
        }
    }

    // ---------------------------------------------------------------- dung lượng

    private static int numRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int numDataCodewords(int ver, Ecc ecc) {
        return numRawDataModules(ver) / 8
                - ECC_CODEWORDS_PER_BLOCK[ecc.idx][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecc.idx][ver];
    }

    // ---------------------------------------------------------------- Reed-Solomon

    private static byte[] addEccAndInterleave(byte[] data, int ver, Ecc ecc) {
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.idx][ver];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.idx][ver];
        int rawCodewords = numRawDataModules(ver) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] rsDiv = rsDivisor(blockEccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            byte[] ecBytes = rsRemainder(dat, rsDiv);
            System.arraycopy(ecBytes, 0, block, shortBlockLen + 1 - blockEccLen, blockEccLen);
            blocks[i] = block;
        }

        byte[] result = new byte[rawCodewords];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                // Block ngắn có 1 byte đệm ở vị trí shortBlockLen - blockEccLen: bỏ qua.
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[k++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= gfMul(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // ---------------------------------------------------------------- bộ đệm bit

    private static final class BitBuffer {
        final byte[] bits;
        int length = 0;

        BitBuffer(int capacity) {
            bits = new byte[capacity];
        }

        void append(int value, int len) {
            for (int i = len - 1; i >= 0; i--) bits[length++] = (byte) ((value >>> i) & 1);
        }

        int get(int i) {
            return bits[i];
        }
    }

    // ---------------------------------------------------------------- dựng ma trận

    private static final class Builder {
        final int version;
        final int size;
        final Ecc ecc;
        final byte[] codewords;
        final boolean[][] modules;
        final boolean[][] isFunction;

        Builder(int version, Ecc ecc, byte[] codewords) {
            this.version = version;
            this.ecc = ecc;
            this.codewords = codewords;
            this.size = version * 4 + 17;
            this.modules = new boolean[size][size];
            this.isFunction = new boolean[size][size];
        }

        Qr build() {
            drawFunctionPatterns();
            drawCodewords();

            int bestMask = 0;
            int minPenalty = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                applyMask(m);
                drawFormatBits(m);
                int p = penaltyScore();
                if (p < minPenalty) {
                    minPenalty = p;
                    bestMask = m;
                }
                applyMask(m); // XOR lần 2 = hoàn tác
            }
            applyMask(bestMask);
            drawFormatBits(bestMask);
            return new Qr(version, modules);
        }

        void setFunction(int x, int y, boolean dark) {
            modules[y][x] = dark;
            isFunction[y][x] = true;
        }

        void drawFunctionPatterns() {
            for (int i = 0; i < size; i++) {
                setFunction(6, i, i % 2 == 0);
                setFunction(i, 6, i % 2 == 0);
            }
            drawFinder(3, 3);
            drawFinder(size - 4, 3);
            drawFinder(3, size - 4);

            int[] pos = alignmentPositions();
            int n = pos.length;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                    drawAlignment(pos[i], pos[j]);
                }
            }
            drawFormatBits(0); // giữ chỗ
            drawVersion();
        }

        void drawFinder(int cx, int cy) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    int x = cx + dx, y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size) {
                        setFunction(x, y, dist != 2 && dist != 4);
                    }
                }
            }
        }

        void drawAlignment(int cx, int cy) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }

        int[] alignmentPositions() {
            if (version == 1) return new int[0];
            int numAlign = version / 7 + 2;
            int step = (version == 32) ? 26
                    : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
            int[] result = new int[numAlign];
            result[0] = 6;
            for (int i = numAlign - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
            return result;
        }

        void drawFormatBits(int mask) {
            int data = ecc.formatBits << 3 | mask;
            int rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
            int bits = (data << 10 | rem) ^ 0x5412;

            for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
            setFunction(8, 7, bit(bits, 6));
            setFunction(8, 8, bit(bits, 7));
            setFunction(7, 8, bit(bits, 8));
            for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));

            for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
            for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
            setFunction(8, size - 8, true); // ô đen cố định
        }

        void drawVersion() {
            if (version < 7) return;
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = bit(bits, i);
                int a = size - 11 + i % 3;
                int c = i / 3;
                setFunction(a, c, b);
                setFunction(c, a, b);
            }
        }

        void drawCodewords() {
            int i = 0;
            int totalBits = codewords.length * 8;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++) {
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        boolean upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!isFunction[y][x] && i < totalBits) {
                            modules[y][x] = bit(codewords[i >>> 3] & 0xFF, 7 - (i & 7));
                            i++;
                        }
                    }
                }
            }
        }

        void applyMask(int mask) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    boolean invert;
                    switch (mask) {
                        case 0: invert = (x + y) % 2 == 0; break;
                        case 1: invert = y % 2 == 0; break;
                        case 2: invert = x % 3 == 0; break;
                        case 3: invert = (x + y) % 3 == 0; break;
                        case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                        case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                        case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                        default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    }
                    if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x];
                }
            }
        }

        int penaltyScore() {
            int result = 0;
            // N1: dãy >= 5 ô cùng màu; N3: mẫu giống finder (hàng và cột)
            for (int pass = 0; pass < 2; pass++) {
                for (int a = 0; a < size; a++) {
                    boolean runColor = false;
                    int runLen = 0;
                    for (int b = 0; b < size; b++) {
                        boolean c = pass == 0 ? modules[a][b] : modules[b][a];
                        if (b == 0 || c != runColor) {
                            runColor = c;
                            runLen = 1;
                        } else {
                            runLen++;
                            if (runLen == 5) result += 3;
                            else if (runLen > 5) result++;
                        }
                    }
                    for (int b = 0; b + 11 <= size; b++) {
                        if (matches(a, b, pass, FINDER_A) || matches(a, b, pass, FINDER_B)) result += 40;
                    }
                }
            }
            // N2: khối 2x2 cùng màu
            for (int y = 0; y < size - 1; y++) {
                for (int x = 0; x < size - 1; x++) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
                }
            }
            // N4: cân bằng đen/trắng
            int dark = 0;
            for (boolean[] row : modules) for (boolean c : row) if (c) dark++;
            int total = size * size;
            int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
            result += Math.max(k, 0) * 10;
            return result;
        }

        private static final boolean[] FINDER_A =
                {true, false, true, true, true, false, true, false, false, false, false};
        private static final boolean[] FINDER_B =
                {false, false, false, false, true, false, true, true, true, false, true};

        private boolean matches(int a, int start, int pass, boolean[] pattern) {
            for (int i = 0; i < 11; i++) {
                boolean c = pass == 0 ? modules[a][start + i] : modules[start + i][a];
                if (c != pattern[i]) return false;
            }
            return true;
        }

        private static boolean bit(int value, int i) {
            return ((value >>> i) & 1) != 0;
        }
    }
}
