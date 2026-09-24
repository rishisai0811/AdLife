package com.adlife.dicom.imaging.jpegls;

import java.io.IOException;

/**
 * Pure-Java JPEG-LS (ISO/IEC 14495-1 / ITU T.87, LOCO-I) decoder.
 * Faithful port of the decode path of CharLS 2.4.2 (BSD-3-Clause, Team CharLS):
 * regular mode, run mode, near-lossless, restart markers, interleave modes
 * none/line/sample, 2-16 bit samples, 1-4 components.
 */
public final class JpegLsDecoder {

    public static class Frame {
        public int width, height, components, bitsPerSample;
        /** component-interleaved samples: pixel i component c at [i*components+c] */
        public int[] pixels;
    }

    private JpegLsDecoder() {}

    // ISO/IEC 14495-1 A.2.1 run-length groups
    private static final int[] J = {0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,5,5,6,6,7,7,8,9,10,11,12,13,14,15};
    private static final int MAX_K = 16;
    /** JPEG-LS sign(): (n >> 31) | 1 -- +1 for zero, never 0. */
    private static int signumJls(int n) { return (n >> 31) | 1; }
    static boolean DEBUG = Boolean.getBoolean("jls.debug");
    private static final int DEFAULT_RESET = 64;

    public static Frame decode(byte[] stream) throws IOException {
        return new Decoder(stream).decode();
    }

    private static final class Decoder {
        private final byte[] s;
        private int pos;

        // frame
        private int width, height, ncomp, bpp;
        private int[][] planes;
        private int scansDone;

        // coding parameters
        private int maxval;        // -1 until set (SOF default or LSE)
        private int t1 = -1, t2 = -1, t3 = -1, reset = DEFAULT_RESET;
        private long restartInterval;

        Decoder(byte[] s) { this.s = s; this.maxval = -1; }

        Frame decode() throws IOException {
            if (readU8() != 0xFF || readU8() != 0xD8)
                throw new IOException("not a JPEG-LS stream (no SOI)");
            int maxvalFromLse = -1;
            for (;;) {
                if (pos >= s.length) break; // tolerate missing EOI
                int marker = readMarker();
                if (marker < 0) break;
                switch (marker) {
                case 0xD8: // SOI (extra)
                    break;
                case 0xD9: // EOI
                    return buildFrame();
                case 0x01: // TEM
                case 0xD0: case 0xD1: case 0xD2: case 0xD3:
                case 0xD4: case 0xD5: case 0xD6: case 0xD7: // stray RSTn
                    break;
                case 0xF7: { // SOF55
                    int len = readU16();
                    readFrameHeader();
                    break;
                }
                case 0xF8: { // LSE: JPEG-LS preset parameters
                    int len = readU16();
                    int end = pos + len - 2;
                    int id = readU8();
                    if (id != 1)
                        throw new IOException("unsupported JPEG-LS preset parameter type " + id);
                    maxvalFromLse = readU16();
                    t1 = readU16();
                    t2 = readU16();
                    t3 = readU16();
                    reset = readU16();
                    pos = end;
                    break;
                }
                case 0xDD: { // DRI
                    int len = readU16();
                    int n = len - 2;
                    long v = 0;
                    for (int i = 0; i < n; i++) v = (v << 8) | readU8();
                    restartInterval = v;
                    break;
                }
                case 0xDA: { // SOS
                    readU16(); // length
                    if (bpp == 0) throw new IOException("SOS before SOF");
                    int ns = readU8();
                    if (ns < 1 || ns > 4 || ns > ncomp)
                        throw new IOException("bad component count in scan");
                    for (int i = 0; i < ns; i++) { readU8(); readU8(); } // Cs, table selector
                    int near = readU8();
                    int ilv = readU8();
                    int pt = readU8() & 0xF;
                    if (pt != 0) throw new IOException("point transform not supported");
                    if (DEBUG) System.err.println("SOS near="+near+" ilv="+ilv+" ns="+ns+" maxval="+maxval+" rst="+restartInterval+" pos="+pos);
                    decodeScan(near, ilv, ns);
                    break;
                }
                default: { // APPn, COM, DNL, everything else: skip by length
                    if ((marker >= 0xC0 && marker <= 0xFE && marker != 0xC4
                            && marker != 0xC8 && marker != 0xCC)) {
                        int len = readU16();
                        pos += len - 2;
                    } else if (marker >= 0xF0) {
                        int len = readU16();
                        pos += len - 2;
                    }
                    // standalone markers (already handled above)
                    break;
                }
                }
            }
            return buildFrame();
        }

        private Frame buildFrame() throws IOException {
            if (planes == null || scansDone == 0)
                throw new IOException("no image data decoded");
            Frame f = new Frame();
            f.width = width; f.height = height; f.components = ncomp; f.bitsPerSample = bpp;
            f.pixels = new int[width * height * ncomp];
            for (int i = 0; i < width * height; i++)
                for (int c = 0; c < ncomp; c++)
                    f.pixels[i * ncomp + c] = planes[c][i];
            return f;
        }

        private void readFrameHeader() throws IOException {
            bpp = readU8();
            if (bpp < 2 || bpp > 16) throw new IOException("bad bits per sample " + bpp);
            height = readU16();
            width = readU16();
            ncomp = readU8();
            if (height <= 0 || width <= 0 || ncomp < 1 || ncomp > 4)
                throw new IOException("bad frame geometry");
            planes = new int[ncomp][width * height];
            for (int i = 0; i < ncomp * 3; i++) readU8(); // component specs (id, sampling, tq)
            if (maxval < 0) maxval = (1 << bpp) - 1;
        }

        // ---- marker / primitive readers ----

        private int readU8() throws IOException {
            if (pos >= s.length) throw new IOException("unexpected end of stream");
            return s[pos++] & 0xFF;
        }

        private int readU16() throws IOException {
            return (readU8() << 8) | readU8();
        }

        /** Position at a marker start: scan to 0xFF, skip fill FFs, return marker code. */
        private int readMarker() throws IOException {
            while (pos < s.length && (s[pos] & 0xFF) != 0xFF) pos++;
            while (pos < s.length && (s[pos] & 0xFF) == 0xFF) pos++;
            if (pos >= s.length) return -1;
            return s[pos++] & 0xFF;
        }

        // ---- scan decoding ----

        private void decodeScan(int near, int ilv, int ns) throws IOException {
            int maxvalEff = maxval;
            if (near > Math.min(255, maxvalEff / 2))
                throw new IOException("bad NEAR " + near);
            Scan scan = new Scan(s, pos, maxvalEff, near, reset, t1, t2, t3);
            int scanComps = (ilv == 0) ? 1 : ns;
            if (ilv == 0 && ns != 1) throw new IOException("ILV=none requires one component per scan");
            int firstPlane = (ilv == 0) ? scansDone : 0;
            scan.run(width, height, ilv, scanComps, restartInterval,
                    planes, firstPlane);
            pos = scan.getCurBytePos();
            scansDone += (ilv == 0) ? 1 : 1; // count scans, not components
        }
    }

    /** One scan (entropy-coded segment). Ports CharLS jls_codec + decoder_strategy. */
    private static final class Scan {
        // traits (general default_traits)
        private final int maxval, near, range, qbpp, bpp, limit, resetThreshold;

        // bit reader (decoder_strategy)
        private final byte[] src;
        private final int end;
        private int pos;
        private long readCache;
        private int validBits;

        // contexts
        private final int[] cA = new int[365], cB = new int[365], cC = new int[365], cN = new int[365];
        private final int[] rA = new int[2], rN = new int[2], rNN = new int[2];
        private int runIndex;
        private int t1, t2, t3;

        // line buffers: [comp][width+4], logical index i -> physical i+1
        private int[][] prev, cur;
        private int comps;      // components handled together in this scan
        private int ilv;
        private int width;

        Scan(byte[] src, int from, int maxval, int near, int reset, int t1, int t2, int t3)
                throws IOException {
            this.src = src; this.end = src.length; this.pos = from;
            this.maxval = maxval; this.near = near;
            this.range = (maxval + 2 * near) / (2 * near + 1) + 1;
            this.qbpp = log2ceil(range);
            this.bpp = log2ceil(maxval);
            this.limit = 2 * (bpp + Math.max(8, bpp));
            this.resetThreshold = reset > 0 ? reset : DEFAULT_RESET;
            if (t1 <= 0 || t2 <= 0 || t3 <= 0) {
                int[] d = defaultThresholds(maxval, near);
                this.t1 = d[0]; this.t2 = d[1]; this.t3 = d[2];
            } else {
                this.t1 = t1; this.t2 = t2; this.t3 = t3;
            }
            resetParameters();
        }

        private void resetParameters() {
            int initA = Math.max(2, (range + 32) / 64);
            for (int i = 0; i < 365; i++) { cA[i] = initA; cB[i] = 0; cC[i] = 0; cN[i] = 1; }
            rA[0] = initA; rA[1] = initA; rN[0] = rN[1] = 1; rNN[0] = rNN[1] = 0;
            runIndex = 0;
        }

        void run(int width, int height, int ilv, int comps, long restartInterval,
                 int[][] planes, int firstPlane) throws IOException {
            this.width = width; this.ilv = ilv; this.comps = comps;
            prev = new int[comps][width + 4];
            cur = new int[comps][width + 4];
            int[] runIndexSaved = new int[comps];
            long interval = restartInterval > 0 ? restartInterval : height;
            int restartCounter = 0;

            initBitReader();

            int line = 0;
            for (;;) {
                int linesInInterval = (int) Math.min(height - line, interval);
                for (int m = 0; m < linesInInterval; m++, line++) {
                    if (ilv == 2) {
                        // ILV_SAMPLE: one pass decodes every component together
                        runIndex = runIndexSaved[0];
                        for (int c = 0; c < comps; c++) {
                            prev[c][width + 1] = prev[c][width];
                            cur[c][0] = prev[c][1];
                        }
                        doLineSample();
                        runIndexSaved[0] = runIndex;
                    } else {
                        for (int c = 0; c < comps; c++) {
                            runIndex = runIndexSaved[c];
                            // edge pixels used for prediction
                            prev[c][width + 1] = prev[c][width];
                            cur[c][0] = prev[c][1];
                            doLinePlain(c);
                            runIndexSaved[c] = runIndex;
                        }
                    }
                    // write row into output planes
                    for (int c = 0; c < comps; c++) {
                        System.arraycopy(cur[c], 1, planes[firstPlane + c], line * width, width);
                    }
                    // advance: decoded line becomes the previous line
                    for (int c = 0; c < comps; c++) {
                        int[] tmp = prev[c]; prev[c] = cur[c]; cur[c] = tmp;
                    }
                }
                if (line >= height) break;
                readRestartMarker(restartCounter);
                restartCounter = (restartCounter + 1) % 8;
                resetBitReader();
                for (int c = 0; c < comps; c++) {
                    java.util.Arrays.fill(prev[c], 0);
                    java.util.Arrays.fill(cur[c], 0);
                    runIndexSaved[c] = 0;
                }
                resetParameters();
            }
        }

        int getCurBytePos() {
            int valid = validBits;
            int compressed = pos;
            for (;;) {
                int lastBits = (compressed > 0 && src[compressed - 1] == (byte) 0xFF) ? 7 : 8;
                if (valid < lastBits) return compressed;
                valid -= lastBits;
                compressed--;
            }
        }

        // ---- scan-line machinery ----

        private void doLinePlain(int c) throws IOException {
            int index = 0;
            int rb = prev[c][0]; // previous_line_[-1]
            int rd = prev[c][1]; // previous_line_[0]
            while (index < width) {
                int ra = cur[c][index];       // current_line_[index-1]
                int rc = rb;
                rb = rd;
                rd = prev[c][index + 2];      // previous_line_[index+1]
                int qs = contextId(quantizeGradient(rd - rb), quantizeGradient(rb - rc),
                        quantizeGradient(rc - ra));
                if (qs != 0) {
                    cur[c][index + 1] = doRegular(qs, getPredictedValue(ra, rb, rc));
                    index++;
                } else {
                    index += doRunMode(index, c);
                    rb = prev[c][index];      // previous_line_[index-1]
                    rd = prev[c][index + 1];  // previous_line_[index]
                }
            }
        }

        private void doLineSample() throws IOException {
            int index = 0;
            dbgLine++;
            int[] ra = new int[comps], rc = new int[comps], rb = new int[comps], rd = new int[comps];
            int[] qs = new int[comps];
            while (index < width) {
                boolean allZero = true;
                for (int c = 0; c < comps; c++) {
                    ra[c] = cur[c][index];
                    rc[c] = prev[c][index];
                    rb[c] = prev[c][index + 1];
                    rd[c] = prev[c][index + 2];
                    qs[c] = contextId(quantizeGradient(rd[c] - rb[c]),
                            quantizeGradient(rb[c] - rc[c]), quantizeGradient(rc[c] - ra[c]));
                    if (qs[c] != 0) allZero = false;
                }
                if (allZero) {
                    if (DEBUG) System.err.println("L"+dbgLine+" x"+index+" RUN");
                    index += doRunMode(index, -1); // -1 = all components
                } else {
                    if (DEBUG && dbgLine<2) System.err.println("L"+dbgLine+" x"+index+" reg qs="+java.util.Arrays.toString(qs));
                    for (int c = 0; c < comps; c++) {
                        cur[c][index + 1] = doRegular(qs[c], getPredictedValue(ra[c], rb[c], rc[c]));
                    }
                    index++;
                }
            }
        }

        private int doRegular(int qs, int predicted) throws IOException {
            int sign = qs >> 31;
            int ctx = (sign ^ qs) - sign; // |qs|
            int k = golombK(cA[ctx], cN[ctx]);
            int predictedValue = correctPrediction(predicted + ((sign ^ cC[ctx]) - sign));
            int errorValue = unmapErrorValue(decodeValue(k, limit, qbpp));
            if (k == 0) {
                errorValue ^= (near != 0) ? 0 : ((2 * cB[ctx] + cN[ctx] - 1) >> 31);
            }
            // update variables and bias (ISO 14495-1 A.12/A.13)
            cA[ctx] += Math.abs(errorValue);
            cB[ctx] += errorValue * (2 * near + 1);
            if (cN[ctx] == resetThreshold) {
                cA[ctx] >>= 1; cB[ctx] >>= 1; cN[ctx] >>= 1;
            }
            cN[ctx]++;
            if (cB[ctx] + cN[ctx] <= 0) {
                cB[ctx] += cN[ctx];
                if (cB[ctx] <= -cN[ctx]) cB[ctx] = -cN[ctx] + 1;
                if (cC[ctx] > -128) cC[ctx]--;
            } else if (cB[ctx] > 0) {
                cB[ctx] -= cN[ctx];
                if (cB[ctx] > 0) cB[ctx] = 0;
                if (cC[ctx] < 127) cC[ctx]++;
            }
            errorValue = (sign ^ errorValue) - sign;
            return computeReconstructedSample(predictedValue, errorValue);
        }

        int dbgLine = -1;
        private int doRunMode(int startIndex, int comp) throws IOException {
            int remaining = width - startIndex;
            int index = 0;
            while (readBit()) {
                int count = Math.min(1 << J[runIndex], remaining - index);
                index += count;
                if (count == (1 << J[runIndex])) runIndex = Math.min(31, runIndex + 1);
                if (index == remaining) break;
            }
            if (index != remaining) {
                index += (J[runIndex] > 0) ? readValue(J[runIndex]) : 0;
            }
            if (index > remaining) throw new IOException("invalid JPEG-LS data (run overrun) start="+startIndex+" idx="+index+" rem="+remaining+" pos="+pos);
            // fill run pixels
            if (comp >= 0) {
                int ra = cur[comp][startIndex]; // current_line_[start_index-1]
                for (int i = 0; i < index; i++) cur[comp][startIndex + 1 + i] = ra;
            } else {
                for (int c = 0; c < comps; c++) {
                    int ra = cur[c][startIndex];
                    for (int i = 0; i < index; i++) cur[c][startIndex + 1 + i] = ra;
                }
            }
            int endIndex = startIndex + index;
            if (endIndex == width) return index;

            // run interruption
            if (comp >= 0) {
                int ra = cur[comp][startIndex];
                int rb = prev[comp][endIndex + 1];
                int rx;
                if (Math.abs(ra - rb) <= near) {
                    int err = decodeRunInterruptionError(1);
                    rx = computeReconstructedSample(ra, err);
                } else {
                    int err = decodeRunInterruptionError(0);
                    rx = computeReconstructedSample(rb, err * signumJls(rb - ra));
                }
                cur[comp][endIndex + 1] = rx;
            } else {
                for (int c = 0; c < comps; c++) {
                    int ra = cur[c][startIndex];
                    int rb = prev[c][endIndex + 1];
                    int err = decodeRunInterruptionError(0);
                    cur[c][endIndex + 1] = computeReconstructedSample(rb, err * signumJls(rb - ra));
                }
            }
            runIndex = Math.max(0, runIndex - 1);
            return index + 1;
        }

        private int decodeRunInterruptionError(int rit) throws IOException {
            int k = runGolombK(rit);
            int eMapped = decodeValue(k, limit - J[runIndex] - 1, qbpp);
            int temp = eMapped + rit;
            int map = temp & 1;
            int errAbs = (temp + map) / 2;
            int error = ((k != 0 || 2 * rNN[rit] >= rN[rit]) == (map != 0)) ? -errAbs : errAbs;
            // update variables (ISO 14495-1 A.23)
            if (error < 0) rNN[rit]++;
            rA[rit] += (eMapped + 1 - rit) >> 1;
            if (rN[rit] == resetThreshold) {
                rA[rit] >>= 1; rN[rit] >>= 1; rNN[rit] >>= 1;
            }
            rN[rit]++;
            return error;
        }

        private int runGolombK(int rit) {
            int temp = rA[rit] + (rN[rit] >> 1) * rit;
            int nTest = rN[rit];
            int k = 0;
            while (nTest < temp) { nTest <<= 1; k++; }
            return k;
        }

        // ---- traits ----

        private int computeReconstructedSample(int predictedValue, int errorValue) {
            int v = predictedValue + errorValue * (2 * near + 1);
            if (v < -near) v += range * (2 * near + 1);
            else if (v > maxval + near) v -= range * (2 * near + 1);
            return correctPrediction(v);
        }

        private int correctPrediction(int p) {
            if ((p & maxval) == p) return p;
            return (~(p >> 31)) & maxval;
        }

        private int quantizeGradient(int di) {
            if (di <= -t3) return -4;
            if (di <= -t2) return -3;
            if (di <= -t1) return -2;
            if (di < -near) return -1;
            if (di <= near) return 0;
            if (di < t1) return 1;
            if (di < t2) return 2;
            if (di < t3) return 3;
            return 4;
        }

        // ---- golomb / error mapping ----

        private int decodeValue(int k, int limit, int qbpp) throws IOException {
            int highBits = readHighBits();
            if (highBits >= limit - (qbpp + 1))
                return readValue(qbpp) + 1;
            if (k == 0) return highBits;
            return (highBits << k) + readValue(k);
        }

        private static int unmapErrorValue(int mapped) {
            int sign = (mapped << 31) >> 31;
            return sign ^ (mapped >> 1);
        }

        private static int golombK(int a, int n) {
            int k = 0;
            while ((n << k) < a && k < MAX_K) k++;
            return k;
        }

        private static int contextId(int q1, int q2, int q3) {
            return (q1 * 9 + q2) * 9 + q3;
        }

        private static int getPredictedValue(int ra, int rb, int rc) {
            int sign = (rb - ra) >> 31;
            if ((sign ^ (rc - ra)) < 0) return rb;
            if ((sign ^ (rb - rc)) < 0) return ra;
            return ra + rb - rc;
        }

        // ---- bit reader (decoder_strategy port, 64-bit cache) ----

        private void initBitReader() throws IOException {
            validBits = 0; readCache = 0;
            fillReadCache();
        }

        private void resetBitReader() throws IOException {
            initBitReader();
        }

        private void fillReadCache() throws IOException {
            while (validBits < 56) {
                if (pos >= end) {
                    if (validBits == 0) throw new IOException("invalid JPEG-LS data (truncated)");
                    return;
                }
                int b = src[pos] & 0xFF;
                if (b == 0xFF && (pos == end - 1 || (src[pos + 1] & 0x80) != 0)) {
                    if (validBits <= 0) throw new IOException("invalid JPEG-LS data (marker) pos="+pos+" end="+end+" near="+near+" ilv="+ilv);
                    return; // marker ahead: stop without consuming
                }
                readCache |= ((long) b) << (56 - validBits);
                validBits += 8;
                pos++;
                if (b == 0xFF) validBits--; // skip stuffed bit after 0xFF
            }
        }

        private void skipBits(int n) {
            validBits -= n;
            readCache <<= n;
        }

        private boolean readBit() throws IOException {
            if (validBits <= 0) fillReadCache();
            boolean set = (readCache & (1L << 63)) != 0;
            skipBits(1);
            return set;
        }

        private int readValue(int length) throws IOException {
            if (validBits < length) {
                fillReadCache();
                if (validBits < length) throw new IOException("invalid JPEG-LS data (bits)");
            }
            int result = (int) (readCache >>> (64 - length));
            skipBits(length);
            return result;
        }

        private int peek0Bits() throws IOException {
            if (validBits < 16) fillReadCache();
            int count = Long.numberOfLeadingZeros(readCache);
            return count < 16 ? count : -1;
        }

        private int readHighBits() throws IOException {
            int count = peek0Bits();
            if (count >= 0) {
                skipBits(count + 1);
                return count;
            }
            skipBits(15);
            for (int c = 15;; c++) {
                if (readBit()) return c;
            }
        }

        private void readRestartMarker(int expected) throws IOException {
            if (pos >= end) throw new IOException("restart marker missing");
            int b = src[pos++] & 0xFF;
            if (b != 0xFF) throw new IOException("restart marker missing");
            do {
                if (pos >= end) throw new IOException("restart marker missing");
                b = src[pos++] & 0xFF;
            } while (b == 0xFF);
            if (b != 0xD0 + (expected % 8)) throw new IOException("unexpected restart marker");
        }
    }

    private static int log2ceil(int n) {
        int x = 0;
        while (n > (1 << x)) x++;
        return x;
    }

    /** ISO/IEC 14495-1 C.2.4.1.1.1 default thresholds (CharLS compute_default). */
    private static int[] defaultThresholds(int maxval, int near) {
        int t1, t2, t3;
        if (maxval >= 128) {
            int factor = (Math.min(maxval, 4095) + 128) / 256;
            t1 = clampT(factor * (3 - 2) + 2 + 3 * near, near + 1, maxval);
            t2 = clampT(factor * (7 - 3) + 3 + 5 * near, t1, maxval);
            t3 = clampT(factor * (21 - 4) + 4 + 7 * near, t2, maxval);
        } else {
            int factor = 256 / (maxval + 1);
            t1 = clampT(Math.max(2, 3 / factor + 3 * near), near + 1, maxval);
            t2 = clampT(Math.max(3, 7 / factor + 5 * near), t1, maxval);
            t3 = clampT(Math.max(4, 21 / factor + 7 * near), t2, maxval);
        }
        return new int[] { t1, t2, t3 };
    }

    private static int clampT(int i, int j, int maxval) {
        return (i > maxval || i < j) ? j : i;
    }
}
