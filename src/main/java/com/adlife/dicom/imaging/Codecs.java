package com.adlife.dicom.imaging;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.Value;

import com.adlife.dicom.imaging.jpegls.JpegLsDecoder;

/**
 * Decodes encapsulated (compressed) DICOM pixel data to raw frames using pure
 * Java codecs only, so it runs identically on Windows XP through 11:
 *   RLE Lossless            -> dcm4che RLEImageReader
 *   JPEG baseline/extended  -> TwelveMonkeys ImageIO JPEG
 *   JPEG lossless (14/14SV1)-> TwelveMonkeys ImageIO JPEG
 *   JPEG-LS (lossless/near) -> built-in JpegLsDecoder (CharLS port)
 *   JPEG 2000 (lossless/y)  -> jai-imageio-jpeg2000 (JJ2000)
 */
public final class Codecs {

    private Codecs() {}

    public static class Decoded {
        public byte[] data;        // little-endian, interleaved samples
        public int bitsAllocated;
        public int samples;
        public boolean rgb;        // true when decoded output is RGB
    }

    /** Transfer syntax UID carried on viewer datasets via a private tag. */
    public static final int TAG_TRANSFER_SYNTAX = 0x77770001;
    /** Current frame index on viewer datasets via a private tag. */
    public static final int TAG_FRAME_INDEX = 0x77770003;

    public static boolean isCompressed(String tsuid) {
        return tsuid != null && (tsuid.startsWith("1.2.840.10008.1.2.4.")
                || UID.RLELossless.equals(tsuid));
    }

    /** Decode one frame of an encapsulated PixelData element to raw pixels. */
    public static Decoded decodeFrame(Attributes ds, String tsuid, int frame) throws IOException {
        Object pd = ds.getValue(Tag.PixelData);
        if (!(pd instanceof Value) || !(pd instanceof java.util.List))
            throw new IOException("pixel data is not encapsulated");
        List<?> frags = (java.util.List<?>) pd;
        if (frags.isEmpty()) throw new IOException("empty pixel data");

        int numFrames = ds.getInt(Tag.NumberOfFrames, 1);
        List<byte[]> data = new ArrayList<byte[]>(frags.size());
        for (int i = 1; i < frags.size(); i++) {  // item 0 = Basic Offset Table
            Object o = frags.get(i);
            if (o instanceof byte[]) data.add((byte[]) o);
        }
        byte[] bot = frags.get(0) instanceof byte[] ? (byte[]) frags.get(0) : null;

        byte[] frameBytes = extractFrame(data, bot, numFrames, frame);
        return decodeStream(frameBytes, tsuid, ds);
    }

    private static byte[] extractFrame(List<byte[]> frags, byte[] bot, int numFrames, int frame)
            throws IOException {
        if (frame < 0 || frame >= numFrames) throw new IOException("bad frame index");
        if (numFrames == 1) return concat(frags, 0, frags.size());
        if (frags.size() == numFrames) return frags.get(frame);

        // group by Basic Offset Table offsets (relative to first fragment after BOT)
        if (bot == null || bot.length < 4 * numFrames)
            throw new IOException("cannot map fragments to frames");
        int[] offs = new int[numFrames];
        for (int i = 0; i < numFrames; i++) {
            offs[i] = (bot[4 * i] & 0xFF) | ((bot[4 * i + 1] & 0xFF) << 8)
                    | ((bot[4 * i + 2] & 0xFF) << 16) | ((bot[4 * i + 3] & 0xFF) << 24);
        }
        // cumulative fragment offsets, each fragment contributes length + 8 header
        int start = offs[frame];
        int end = frame + 1 < numFrames ? offs[frame + 1] : Integer.MAX_VALUE;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int cum = 0;
        for (byte[] f : frags) {
            int fragStart = cum;
            cum += f.length + 8;
            if (fragStart >= start && fragStart < end) out.write(f, 0, f.length);
        }
        if (out.size() == 0) throw new IOException("no fragments for frame " + frame);
        return out.toByteArray();
    }

    private static byte[] concat(List<byte[]> frags, int from, int to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = from; i < to; i++) out.write(frags.get(i), 0, frags.get(i).length);
        return out.toByteArray();
    }

    private static Decoded decodeStream(byte[] stream, String tsuid, Attributes ds)
            throws IOException {
        int rows = ds.getInt(Tag.Rows, 0);
        int cols = ds.getInt(Tag.Columns, 0);
        int bits = ds.getInt(Tag.BitsAllocated, 8);

        if (UID.JPEGLSLossless.equals(tsuid) || UID.JPEGLSLossyNearLossless.equals(tsuid)) {
            return fromJpegLs(stream, rows, cols, bits);
        }
        if (UID.RLELossless.equals(tsuid)) {
            // dcm4che's RLE reader needs an explicit destination; RLE carries no
            // sample metadata, so build it from the DICOM pixel header.
            int samples = ds.getInt(Tag.SamplesPerPixel, 1);
            int type = samples == 3 ? BufferedImage.TYPE_3BYTE_BGR
                    : bits > 8 ? BufferedImage.TYPE_USHORT_GRAY : BufferedImage.TYPE_BYTE_GRAY;
            ImageReader reader = new org.dcm4che3.imageio.plugins.rle.RLEImageReaderSpi().createReaderInstance();
            ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(stream));
            try {
                reader.setInput(iis, false, true);
                ImageReadParam param = reader.getDefaultReadParam();
                param.setDestination(new BufferedImage(cols, rows, type));
                return fromBufferedImage(reader.read(0, param), bits);
            } finally {
                try { iis.close(); } catch (IOException ignored) {}
                reader.dispose();
            }
        }
        if (UID.JPEG2000LosslessOnly.equals(tsuid) || UID.JPEG2000.equals(tsuid)) {
            return fromImageIO(stream, bits,
                    new com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi());
        }
        // JPEG family: baseline, extended, lossless, lossless SV1
        if (tsuid != null && tsuid.startsWith("1.2.840.10008.1.2.4.5")
                || UID.JPEGLosslessNonHierarchical14.equals(tsuid)
                || UID.JPEGLossless.equals(tsuid)) {
            ImageIO.scanForPlugins(); // registers TwelveMonkeys SPIs from the classpath
            java.util.Iterator<ImageReader> jrs = ImageIO.getImageReadersByFormatName("jpeg");
            while (jrs.hasNext()) {
                ImageReader r = jrs.next();
                if (r.getClass().getName().startsWith("com.twelvemonkeys")) {
                    return fromReader(stream, bits, r);
                }
            }
            throw new IOException("TwelveMonkeys JPEG reader not registered");
        }
        throw new IOException("unsupported transfer syntax " + tsuid);
    }

    private static Decoded fromJpegLs(byte[] stream, int rows, int cols, int bits)
            throws IOException {
        JpegLsDecoder.Frame f = JpegLsDecoder.decode(stream);
        Decoded d = new Decoded();
        d.samples = f.components;
        d.bitsAllocated = f.bitsPerSample > 8 ? 16 : 8;
        d.rgb = f.components >= 3;
        int n = f.width * f.height * f.components;
        if (f.width != cols || f.height != rows)
            throw new IOException("JPEG-LS geometry mismatch: " + f.width + "x" + f.height);
        if (d.bitsAllocated > 8) {
            d.data = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                d.data[2 * i] = (byte) f.pixels[i];
                d.data[2 * i + 1] = (byte) (f.pixels[i] >> 8);
            }
        } else {
            d.data = new byte[n];
            for (int i = 0; i < n; i++) d.data[i] = (byte) f.pixels[i];
        }
        return d;
    }

    private static Decoded fromImageIO(byte[] stream, int bits,
                                       javax.imageio.spi.ImageReaderSpi spi) throws IOException {
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(stream));
        ImageReader reader = spi.createReaderInstance();
        try {
            reader.setInput(iis, false, true);
            ImageReadParam param = reader.getDefaultReadParam();
            // dcm4che's RLE reader requires an explicit destination type
            if (spi instanceof org.dcm4che3.imageio.plugins.rle.RLEImageReaderSpi) {
                java.util.Iterator<javax.imageio.ImageTypeSpecifier> types = reader.getImageTypes(0);
                if (!types.hasNext()) throw new IOException("RLE reader exposes no image types");
                param.setDestinationType(types.next());
            }
            BufferedImage bi = reader.read(0, param);
            return fromBufferedImage(bi, bits);
        } finally {
            try { iis.close(); } catch (IOException ignored) {}
            reader.dispose();
        }
    }


    private static Decoded fromReader(byte[] stream, int bits, ImageReader reader) throws IOException {
        // Strip a leading JFIF APP0: it makes TwelveMonkeys delegate to the JDK
        // reader, which cannot decode 12-bit extended or lossless JPEG.
        stream = stripJfifApp0(stream);
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(stream));
        try {
            reader.setInput(iis, false, true);
            BufferedImage bi = reader.read(0, null);
            return fromBufferedImage(bi, bits);
        } finally {
            try { iis.close(); } catch (IOException ignored) {}
            reader.dispose();
        }
    }


    private static byte[] stripJfifApp0(byte[] in) {
        if (in.length > 6 && (in[0] & 0xFF) == 0xFF && (in[1] & 0xFF) == 0xD8
                && (in[2] & 0xFF) == 0xFF && (in[3] & 0xFF) == 0xE0) {
            int len = ((in[4] & 0xFF) << 8) | (in[5] & 0xFF);
            if (len >= 2 && 4 + len <= in.length) {
                byte[] out = new byte[in.length - len - 2];
                out[0] = in[0]; out[1] = in[1];
                System.arraycopy(in, 2 + len + 2, out, 2, in.length - len - 4);
                return out;
            }
        }
        return in;
    }

    private static Decoded fromBufferedImage(BufferedImage bi, int bits) throws IOException {
        Raster r = bi.getRaster();
        int w = r.getWidth(), h = r.getHeight(), bands = r.getNumBands();
        Decoded d = new Decoded();
        d.samples = bands >= 3 ? 3 : 1;
        d.rgb = bands >= 3;
        int n = w * h;

        if (bands >= 3) {
            // use ARGB conversion to guarantee channel order regardless of storage layout
            d.bitsAllocated = 8;
            d.data = new byte[n * 3];
            int[] rgb = bi.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < n; i++) {
                d.data[3 * i] = (byte) (rgb[i] >> 16);
                d.data[3 * i + 1] = (byte) (rgb[i] >> 8);
                d.data[3 * i + 2] = (byte) rgb[i];
            }
            return d;
        }

        int[] smp = r.getSamples(0, 0, w, h, 0, (int[]) null);
        if (bits > 8 || r.getDataBuffer().getDataType() == java.awt.image.DataBuffer.TYPE_USHORT
                || r.getDataBuffer().getDataType() == java.awt.image.DataBuffer.TYPE_INT) {
            d.bitsAllocated = 16;
            d.data = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                d.data[2 * i] = (byte) smp[i];
                d.data[2 * i + 1] = (byte) (smp[i] >> 8);
            }
        } else {
            d.bitsAllocated = 8;
            d.data = new byte[n];
            for (int i = 0; i < n; i++) d.data[i] = (byte) smp[i];
        }
        return d;
    }
}
