package com.adlife.dicom.imaging;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;

/**
 * Pixel pipeline for print image boxes and local files:
 * raw pixels -> modality rescale -> VOI window -> profile LUT ->
 * Presentation LUT -> polarity -> BufferedImage.
 * Compressed (encapsulated) pixel data is not decoded in v0.1; print traffic
 * from modalities is uncompressed.
 */
public final class PixelRenderer {

    private PixelRenderer() {}

    public static class UnsupportedImageException extends IOException {
        private static final long serialVersionUID = 1L;
        public UnsupportedImageException(String msg) { super(msg); }
    }

    /** Render one image box cell. imgBoxData is the N-SET dataset of the image box. */
    public static BufferedImage renderImageBox(Attributes imgBoxData, boolean colorExpected,
                                               byte[] profileLut, Attributes presentationLut)
            throws IOException {
        Sequence seq = imgBoxData.getSequence(colorExpected
                ? Tag.BasicColorImageSequence : Tag.BasicGrayscaleImageSequence);
        if (seq == null) seq = imgBoxData.getSequence(Tag.BasicGrayscaleImageSequence);
        if (seq == null) seq = imgBoxData.getSequence(Tag.BasicColorImageSequence);
        if (seq == null || seq.isEmpty())
            throw new UnsupportedImageException("image box carries no image");
        Attributes item = seq.get(0);

        String polarity = imgBoxData.getString(Tag.Polarity);
        return renderPixels(item, imgBoxData, polarity, profileLut, presentationLut);
    }

    /**
     * Core render. pixelHolder carries Rows/Columns/Bits/PixelData (and window /
     * rescale hints); overrides carries box- or viewer-level overrides (polarity,
     * window) that win when present.
     */
    public static BufferedImage renderPixels(Attributes pixelHolder, Attributes overrides,
                                             String polarity, byte[] profileLut,
                                             Attributes presentationLut) throws IOException {
        int rows = pixelHolder.getInt(Tag.Rows, -1);
        int cols = pixelHolder.getInt(Tag.Columns, -1);
        int samples = pixelHolder.getInt(Tag.SamplesPerPixel, 1);
        int bitsAllocated = pixelHolder.getInt(Tag.BitsAllocated, 8);
        boolean signed = pixelHolder.getInt(Tag.PixelRepresentation, 0) == 1;
        if (rows <= 0 || cols <= 0)
            throw new UnsupportedImageException("missing Rows/Columns");

        Object pd = pixelHolder.getValue(Tag.PixelData);
        if (pd == null) throw new UnsupportedImageException("no pixel data");
        if (pd instanceof Sequence)
            throw new UnsupportedImageException("compressed pixel data not supported in v0.1");
        if (!(pd instanceof byte[]))
            throw new UnsupportedImageException("unexpected pixel data encoding");
        byte[] px = (byte[]) pd;

        double slope = pixelHolder.getDouble(Tag.RescaleSlope, 1.0);
        double intercept = pixelHolder.getDouble(Tag.RescaleIntercept, 0.0);

        if (samples >= 3) {
            return renderColor(px, rows, cols, pixelHolder, overrides, polarity);
        }

        // grayscale
        double center = windowValue(pixelHolder, overrides, Tag.WindowCenter, Double.NaN);
        double width = windowValue(pixelHolder, overrides, Tag.WindowWidth, Double.NaN);

        int n = rows * cols;
        double[] vals = new double[n];
        if (bitsAllocated > 8) {
            if (px.length < n * 2)
                throw new UnsupportedImageException("short pixel buffer");
            for (int i = 0; i < n; i++) {
                int raw = ((px[2 * i] & 0xFF)) | ((px[2 * i + 1] & 0xFF) << 8);
                if (signed && raw >= 0x8000) raw -= 0x10000;
                vals[i] = raw * slope + intercept;
            }
        } else {
            if (px.length < n)
                throw new UnsupportedImageException("short pixel buffer");
            for (int i = 0; i < n; i++) {
                int raw = px[i] & 0xFF;
                if (signed && raw >= 0x80) raw -= 0x100;
                vals[i] = raw * slope + intercept;
            }
        }

        if (Double.isNaN(center) || Double.isNaN(width) || width <= 0) {
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double v : vals) { if (v < min) min = v; if (v > max) max = v; }
            width = Math.max(1.0, max - min);
            center = min + width / 2.0;
        }

        BufferedImage out = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_GRAY);
        byte[] dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        double low = center - 0.5 - (width - 1) / 2.0;
        double scale = 255.0 / (width - 1);
        boolean invertPolarity = "MONOCHROME1".equalsIgnoreCase(polarity);
        boolean linOd = isLinOd(presentationLut);

        for (int i = 0; i < n; i++) {
            int v = (int) Math.round((vals[i] - low) * scale);
            if (v < 0) v = 0; else if (v > 255) v = 255;
            if (profileLut != null) v = profileLut[v] & 0xFF;
            if (linOd) v = 255 - v;
            if (invertPolarity) v = 255 - v;
            dst[i] = (byte) v;
        }
        return out;
    }

    private static BufferedImage renderColor(byte[] px, int rows, int cols,
                                             Attributes pixelHolder, Attributes overrides,
                                             String polarity) throws IOException {
        int n = rows * cols;
        int planar = pixelHolder.getInt(Tag.PlanarConfiguration, 0);
        BufferedImage out = new BufferedImage(cols, rows, BufferedImage.TYPE_INT_RGB);
        boolean invert = "MONOCHROME1".equalsIgnoreCase(polarity);
        if (planar == 0) {
            if (px.length < n * 3) throw new UnsupportedImageException("short color buffer");
            for (int i = 0; i < n; i++) {
                int r = px[3 * i] & 0xFF, g = px[3 * i + 1] & 0xFF, b = px[3 * i + 2] & 0xFF;
                if (invert) { r = 255 - r; g = 255 - g; b = 255 - b; }
                out.setRGB(i % cols, i / cols, (r << 16) | (g << 8) | b);
            }
        } else {
            if (px.length < n * 3) throw new UnsupportedImageException("short color buffer");
            for (int i = 0; i < n; i++) {
                int r = px[i] & 0xFF, g = px[n + i] & 0xFF, b = px[2 * n + i] & 0xFF;
                if (invert) { r = 255 - r; g = 255 - g; b = 255 - b; }
                out.setRGB(i % cols, i / cols, (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static double windowValue(Attributes pixelHolder, Attributes overrides,
                                      int tag, double def) {
        if (overrides != null) {
            String s = overrides.getString(tag);
            if (s != null) return parseFirstDouble(s, def);
        }
        String s = pixelHolder.getString(tag);
        if (s != null) return parseFirstDouble(s, def);
        return def;
    }

    private static double parseFirstDouble(String multi, double def) {
        try {
            return Double.parseDouble(multi.split("\\\\")[0].trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean isLinOd(Attributes presentationLut) {
        if (presentationLut == null) return false;
        String shape = presentationLut.getString(Tag.PresentationLUTShape);
        return shape != null && shape.trim().equalsIgnoreCase("LIN OD");
    }
}
