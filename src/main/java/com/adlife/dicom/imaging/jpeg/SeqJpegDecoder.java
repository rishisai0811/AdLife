package com.adlife.dicom.imaging.jpeg;

import java.io.IOException;

/**
 * Pure-Java sequential DCT Huffman JPEG decoder (ISO/IEC 10918-1):
 * baseline (SOF0) and extended sequential (SOF1), 8 and 12 bit precision,
 * grayscale and YCbCr color, restart markers. No progressive, no arithmetic,
 * no hierarchical. Written for DICOM transfer syntaxes 1.2.840.10008.1.2.4.50
 * and .51 where no pure-Java ImageIO reader exists for 12-bit.
 */
public final class SeqJpegDecoder {

    public static final class Frame {
        public int width, height, components, precision;
        public int[] pixels; // interleaved, one int per sample (0..2^precision-1)
    }

    private byte[] s; private int pos;
    private int[][] qt = new int[4][64];
    private final HuffTbl[] dcTbl = new HuffTbl[4], acTbl = new HuffTbl[4];
    private int width, height, ncomp, precision, restartInterval;
    private Comp[] comps;
    private byte[] scanData; // entropy data with stuffing removed, RST kept out
    private long bitAcc; private int bitCnt;

    private static final class Comp {
        int id, h, v, tq, td, ta;
        int dcPred;
    }
    private static final class HuffTbl {
        int[] minCode = new int[16], maxCode = new int[16], valPtr = new int[16];
        int[] vals = new int[256];
    }

    private static final int[] ZZ = {
         0, 1, 8,16, 9, 2, 3,10,
        17,24,32,25,18,11, 4, 5,
        12,19,26,33,40,48,41,34,
        27,20,13, 6, 7,14,21,28,
        35,42,49,56,57,50,43,36,
        29,22,15,23,30,37,44,51,
        58,59,52,45,38,31,39,46,
        53,60,61,54,47,55,62,63
    };

    public Frame decode(byte[] jpeg) throws IOException {
        s = jpeg; pos = 0;
        if (readU16() != 0xFFD8) throw new IOException("not JPEG: no SOI");
        for (;;) {
            int m = readMarker();
            if (m < 0) throw new IOException("truncated JPEG header");
            switch (m) {
            case 0xD8: case 0x01: break;
            case 0xD9: throw new IOException("EOI before SOS");
            case 0xDB: readDqt(); break;
            case 0xC4: readDht(); break;
            case 0xDD: {
                int len = readU16();
                if (len == 4) restartInterval = readU16();
                else pos += len - 2;
                break;
            }
            case 0xC0: case 0xC1: readSof(); break;
            case 0xC2: case 0xC3: case 0xC5: case 0xC6: case 0xC7:
            case 0xC9: case 0xCA: case 0xCB: case 0xCD: case 0xCE: case 0xCF:
                throw new IOException("unsupported JPEG SOF marker 0x" + Integer.toHexString(m)
                        + " (only sequential DCT Huffman, SOF0/SOF1)");
            case 0xDA: return readSos();
            default: { // APPn, COM, DNL, etc.
                int len = readU16();
                if (len < 2 || pos + len - 2 > s.length) throw new IOException("bad segment");
                pos += len - 2;
            }
            }
        }
    }

    private void readDqt() throws IOException {
        int len = readU16() - 2;
        while (len > 0) {
            int pqTq = readU8(); len--;
            int pq = pqTq >> 4, tq = pqTq & 15;
            for (int i = 0; i < 64; i++) {
                qt[tq][ZZ[i]] = pq == 0 ? readU8() : readU16();
                len -= pq == 0 ? 1 : 2;
            }
        }
    }

    private void readDht() throws IOException {
        int len = readU16() - 2;
        while (len > 0) {
            int tcTh = readU8(); len--;
            HuffTbl t = new HuffTbl();
            int[] counts = new int[16];
            int total = 0;
            for (int i = 0; i < 16; i++) { counts[i] = readU8(); total += counts[i]; }
            len -= 16 + total;
            for (int i = 0; i < total; i++) t.vals[i] = readU8();
            // canonical code ranges (ITU T.81 C.3)
            int code = 0, k = 0;
            for (int i = 0; i < 16; i++) {
                if (counts[i] == 0) { t.maxCode[i] = -1; t.valPtr[i] = 0; t.minCode[i] = 0; }
                else {
                    t.valPtr[i] = k;
                    t.minCode[i] = code;
                    k += counts[i];
                    t.maxCode[i] = code + counts[i] - 1;
                }
                code = (code + counts[i]) << 1;
            }
            if ((tcTh >> 4) == 0) dcTbl[tcTh & 15] = t; else acTbl[tcTh & 15] = t;
        }
    }

    private void readSof() throws IOException {
        readU16(); // length
        precision = readU8();
        height = readU16(); width = readU16();
        ncomp = readU8();
        if (precision != 8 && precision != 12) throw new IOException("unsupported precision " + precision);
        if (ncomp != 1 && ncomp != 3) throw new IOException("unsupported component count " + ncomp);
        comps = new Comp[ncomp];
        for (int i = 0; i < ncomp; i++) {
            Comp c = new Comp();
            c.id = readU8();
            int hv = readU8(); c.h = hv >> 4; c.v = hv & 15;
            c.tq = readU8();
            comps[i] = c;
        }
    }

    private Frame readSos() throws IOException {
        readU16(); // length
        int ns = readU8();
        if (ns != ncomp) throw new IOException("multi-scan not supported");
        for (int i = 0; i < ns; i++) {
            int cs = readU8(), tdTa = readU8();
            for (Comp c : comps) if (c.id == cs) { c.td = tdTa >> 4; c.ta = tdTa & 15; }
        }
        pos += 3; // Ss, Se, Ah/Al (sequential: 0, 63, 0)
        // gather entropy-coded data up to EOI (or next non-RST marker)
        int start = pos;
        int p = pos;
        while (p < s.length - 1) {
            if (s[p] == (byte) 0xFF && s[p + 1] != 0x00
                    && !(s[p + 1] >= (byte) 0xD0 && s[p + 1] <= (byte) 0xD7) && s[p + 1] != (byte) 0xFF) break;
            p++;
        }
        scanData = new byte[p - start];
        System.arraycopy(s, start, scanData, 0, p - start);
        decodeScanData();
        Frame f = new Frame();
        f.width = width; f.height = height; f.components = ncomp; f.precision = precision;
        f.pixels = outPixels;
        return f;
    }

    private int[] outPixels;

    private void decodeScanData() throws IOException {
        int maxH = 1, maxV = 1;
        for (Comp c : comps) { maxH = Math.max(maxH, c.h); maxV = Math.max(maxV, c.v); }
        int mcusX = (width + 8 * maxH - 1) / (8 * maxH);
        int mcusY = (height + 8 * maxV - 1) / (8 * maxV);
        // per-component sample planes
        int[][] planes = new int[ncomp][];
        int[] pw = new int[ncomp], ph = new int[ncomp];
        for (int i = 0; i < ncomp; i++) {
            pw[i] = mcusX * comps[i].h * 8;
            ph[i] = mcusY * comps[i].v * 8;
            planes[i] = new int[pw[i] * ph[i]];
            comps[i].dcPred = 0;
        }
        bitAcc = 0; bitCnt = 0;
        int mcuCount = 0, rstExpected = 0;
        int[] coef = new int[64];
        for (int my = 0; my < mcusY; my++) {
            for (int mx = 0; mx < mcusX; mx++) {
                if (restartInterval > 0 && mcuCount == restartInterval) {
                    alignToByte();
                    skipRestart(rstExpected);
                    rstExpected = (rstExpected + 1) % 8;
                    for (Comp c : comps) c.dcPred = 0;
                    mcuCount = 0;
                }
                for (int ci = 0; ci < ncomp; ci++) {
                    Comp c = comps[ci];
                    for (int by = 0; by < c.v; by++) {
                        for (int bx = 0; bx < c.h; bx++) {
                            decodeBlock(c, coef);
                            idctDequant(coef, qt[c.tq]);
                            int ox = (mx * c.h + bx) * 8, oy = (my * c.v + by) * 8;
                            for (int y = 0; y < 8; y++)
                                for (int x = 0; x < 8; x++)
                                    planes[ci][(oy + y) * pw[ci] + ox + x] = coef[y * 8 + x];
                        }
                    }
                }
                mcuCount++;
            }
        }
        // assemble output at image size, upsampling by replication
        outPixels = new int[width * height * ncomp];
        int shift = 1 << (precision - 1);
        int maxv = (1 << precision) - 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double yv = 0, cb = 0, cr = 0;
                for (int ci = 0; ci < ncomp; ci++) {
                    Comp c = comps[ci];
                    int sx = x * c.h / maxH, sy = y * c.v / maxV;
                    int v = planes[ci][sy * pw[ci] + sx] + shift;
                    if (v < 0) v = 0; else if (v > maxv) v = maxv;
                    if (ncomp == 3) {
                        if (ci == 0) yv = v; else if (ci == 1) cb = v; else cr = v;
                    } else {
                        outPixels[(y * width + x)] = v;
                    }
                }
                if (ncomp == 3) {
                    // JFIF/libjpeg YCbCr -> RGB, offsets centered at half range
                    double cen = 1 << (precision - 1);
                    int r = (int) Math.round(yv + 1.40200 * (cr - cen));
                    int g = (int) Math.round(yv - 0.344136 * (cb - cen) - 0.714136 * (cr - cen));
                    int b = (int) Math.round(yv + 1.77200 * (cb - cen));
                    int base = (y * width + x) * 3;
                    outPixels[base] = clamp(r, maxv);
                    outPixels[base + 1] = clamp(g, maxv);
                    outPixels[base + 2] = clamp(b, maxv);
                }
            }
        }
    }

    private static int clamp(int v, int maxv) {
        return v < 0 ? 0 : v > maxv ? maxv : v;
    }

    private void decodeBlock(Comp c, int[] coef) throws IOException {
        java.util.Arrays.fill(coef, 0);
        // DC
        int t = decodeHuff(dcTbl[c.td]);
        int diff = t > 0 ? extend(receive(t), t) : 0;
        c.dcPred += diff;
        coef[0] = c.dcPred;
        // AC
        int k = 1;
        while (k < 64) {
            int rs = decodeHuff(acTbl[c.ta]);
            int r = rs >> 4, s2 = rs & 15;
            if (s2 == 0) {
                if (r == 15) { k += 16; continue; }
                break; // EOB
            }
            k += r;
            if (k > 63) throw new IOException("bad AC index");
            coef[ZZ[k]] = extend(receive(s2), s2);
            k++;
        }
    }

    // ---- bit reader over scanData (FF00 unstuffing, RST awareness) ----
    private int scanPos;

    private int nextByte() throws IOException {
        if (scanPos >= scanData.length) throw new IOException("entropy data exhausted");
        int b = scanData[scanPos++] & 0xFF;
        if (b == 0xFF) {
            while (scanPos < scanData.length && (scanData[scanPos] & 0xFF) == 0xFF) scanPos++; // fill bytes
            if (scanPos < scanData.length && (scanData[scanPos] & 0xFF) == 0x00) { scanPos++; }
            else throw new IOException("unexpected marker in entropy data");
        }
        return b;
    }

    private int bit() throws IOException {
        if (bitCnt == 0) { bitAcc = nextByte(); bitCnt = 8; }
        bitCnt--;
        return (int) ((bitAcc >> bitCnt) & 1);
    }

    private int receive(int n) throws IOException {
        int v = 0;
        for (int i = 0; i < n; i++) v = (v << 1) | bit();
        return v;
    }

    private void alignToByte() { bitCnt = 0; }

    private void skipRestart(int expected) throws IOException {
        if (scanPos + 1 > scanData.length) throw new IOException("restart marker missing");
        int b = scanData[scanPos++] & 0xFF;
        if (b == 0xFF) {
            int m = scanData[scanPos++] & 0xFF;
            while (m == 0xFF) m = scanData[scanPos++] & 0xFF;
            if (m != 0xD0 + expected) throw new IOException("unexpected restart marker 0x" + Integer.toHexString(m));
        } else throw new IOException("restart marker missing");
    }

    private int decodeHuff(HuffTbl t) throws IOException {
        int code = 0, len = 0;
        while (len < 16) {
            code = (code << 1) | bit();
            len++;
            if (t.maxCode[len - 1] >= 0 && code <= t.maxCode[len - 1]) {
                return t.vals[t.valPtr[len - 1] + code - t.minCode[len - 1]];
            }
        }
        throw new IOException("bad Huffman code");
    }

    private static int extend(int v, int s) {
        return v < (1 << (s - 1)) ? v - (1 << s) + 1 : v;
    }

    // ---- IDCT: direct 2-pass float IDCT (correctness first; C=cos table) ----
    private static final double[][] COS = new double[8][8];
    private static final double[] CU = new double[8];
    static {
        for (int u = 0; u < 8; u++) {
            CU[u] = u == 0 ? 1.0 / Math.sqrt(2) : 1.0;
            for (int x = 0; x < 8; x++) COS[u][x] = Math.cos((2 * x + 1) * u * Math.PI / 16);
        }
    }

    private static void idctDequant(int[] coef, int[] q) {
        double[] t = new double[64];
        // 1D IDCT over rows (each pass contributes factor 1/2; two passes = 1/4)
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                double sum = 0;
                for (int u = 0; u < 8; u++)
                    sum += CU[u] * (coef[y * 8 + u] * q[y * 8 + u]) * COS[u][x];
                t[y * 8 + x] = sum / 2.0;
            }
        }
        // 1D IDCT over columns
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                double sum = 0;
                for (int u = 0; u < 8; u++)
                    sum += CU[u] * t[u * 8 + x] * COS[u][y];
                coef[y * 8 + x] = (int) Math.round(sum / 2.0);
            }
        }
    }

    private int readU8() throws IOException {
        if (pos >= s.length) throw new IOException("truncated");
        return s[pos++] & 0xFF;
    }
    private int readU16() throws IOException {
        return (readU8() << 8) | readU8();
    }
    private int readMarker() throws IOException {
        while (pos < s.length && (s[pos] & 0xFF) != 0xFF) pos++;
        if (pos >= s.length) return -1;
        while (pos < s.length && (s[pos] & 0xFF) == 0xFF) pos++;
        if (pos >= s.length) return -1;
        return s[pos++] & 0xFF;
    }
}
