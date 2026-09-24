package com.adlife.dicom.imaging;

import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

/**
 * Per-printer image tuning applied to each rendered cell before layout:
 * brightness / contrast (-100..100), dark factor (1 = off, 2..5 = progressively
 * darker mid-tones for pale inkjet film), soften (0..10) or sharpen (0..10), invert.
 */
public final class ImageAdjust {
    public final int brightness, contrast, soften, sharpen;
    public final double darkFactor;
    public final boolean invert;

    public ImageAdjust(int brightness, int contrast, double darkFactor, int soften, int sharpen, boolean invert) {
        this.brightness = clamp(brightness, -100, 100);
        this.contrast = clamp(contrast, -100, 100);
        this.darkFactor = Math.max(1.0, Math.min(5.0, darkFactor));
        this.soften = clamp(soften, 0, 10);
        this.sharpen = clamp(sharpen, 0, 10);
        this.invert = invert;
    }

    public static final ImageAdjust NONE = new ImageAdjust(0, 0, 1, 0, 0, false);

    public boolean isIdentity() {
        return brightness == 0 && contrast == 0 && darkFactor == 1.0 && soften == 0 && sharpen == 0 && !invert;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** 256-entry tone curve combining all point operations. */
    public int[] toneCurve() {
        int[] t = new int[256];
        double c = (contrast + 100) / 100.0;           // 0..2
        c = c * c;                                     // perceptual spread
        double gamma = 1.0 + (darkFactor - 1.0) * 0.35; // DF5 -> gamma 2.4
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            v = Math.pow(v, gamma);
            v = (v - 0.5) * c + 0.5;
            v += brightness / 200.0;
            if (invert) v = 1.0 - v;
            t[i] = (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
        }
        return t;
    }

    public BufferedImage apply(BufferedImage src) {
        if (src == null || isIdentity()) return src;
        int[] t = toneCurve();
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage img;
        if (src.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            // Work on the raw grey samples: drawing a TYPE_BYTE_GRAY image into an
            // RGB one goes through Java's linear-grey colour conversion and lifts
            // the dark tones, which washes the film out.
            img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            java.awt.image.Raster in = src.getRaster();
            java.awt.image.WritableRaster out = img.getRaster();
            int[] line = new int[w];
            for (int y = 0; y < h; y++) {
                in.getSamples(0, y, w, 1, 0, line);
                for (int x = 0; x < w; x++) line[x] = t[line[x] & 255];
                out.setSamples(0, y, w, 1, 0, line);
            }
            if (soften > 0) img = convolve(img, blurKernel(soften));
            if (sharpen > 0) img = convolve(img, sharpenKernel(sharpen));
            return img;
        }
        img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.getGraphics().drawImage(src, 0, 0, null);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                row[x] = (t[(p >> 16) & 255] << 16) | (t[(p >> 8) & 255] << 8) | t[p & 255];
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        if (soften > 0) img = convolve(img, blurKernel(soften));
        if (sharpen > 0) img = convolve(img, sharpenKernel(sharpen));
        return img;
    }

    private static Kernel blurKernel(int level) {
        float center = 1f, side = level / 10f;
        float[] k = { side * .5f, side, side * .5f, side, center, side, side * .5f, side, side * .5f };
        float sum = 0; for (float f : k) sum += f;
        for (int i = 0; i < 9; i++) k[i] /= sum;
        return new Kernel(3, 3, k);
    }

    private static Kernel sharpenKernel(int level) {
        float a = level / 10f;
        return new Kernel(3, 3, new float[] { 0, -a, 0, -a, 1 + 4 * a, -a, 0, -a, 0 });
    }

    private static BufferedImage convolve(BufferedImage img, Kernel k) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                img.getType() == BufferedImage.TYPE_BYTE_GRAY ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB);
        new ConvolveOp(k, ConvolveOp.EDGE_NO_OP, null).filter(img, out);
        return out;
    }
}
