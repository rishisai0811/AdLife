package com.adlife.dicom.print;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * Renders one film sheet: page size from Film Size ID + orientation, image
 * cells from Image Display Format (STANDARD\c,r), each cell fit with the
 * negotiated magnification filter.
 */
public final class FilmLayout {

    private FilmLayout() {}

    /** DICOM Image Display Format STANDARD\c,r -> [cols, rows]. */
    public static int[] parseStandardFormat(String format) {
        if (format != null && format.startsWith("STANDARD\\")) {
            String[] f = format.substring("STANDARD\\".length()).split(",");
            if (f.length == 2) {
                try {
                    int c = Integer.parseInt(f[0].trim());
                    int r = Integer.parseInt(f[1].trim());
                    if (c > 0 && r > 0 && c <= 8 && r <= 8) return new int[] { c, r };
                } catch (NumberFormatException ignored) {}
            }
        }
        return new int[] { 1, 1 };
    }

    /** Film Size ID -> [widthInches, heightInches] before orientation. */
    public static double[] filmSizeInches(String sizeId) {
        if (sizeId == null) return new double[] { 14, 17 };
        String s = sizeId.trim().toUpperCase();
        if (s.equals("8INX10IN"))  return new double[] { 8, 10 };
        if (s.equals("10INX12IN")) return new double[] { 10, 12 };
        if (s.equals("10INX14IN")) return new double[] { 10, 14 };
        if (s.equals("11INX14IN")) return new double[] { 11, 14 };
        if (s.equals("14INX14IN")) return new double[] { 14, 14 };
        if (s.equals("14INX17IN")) return new double[] { 14, 17 };
        if (s.equals("24CMX24CM")) return new double[] { cm2in(24), cm2in(24) };
        if (s.equals("24CMX30CM")) return new double[] { cm2in(24), cm2in(30) };
        if (s.equals("A4"))        return new double[] { 8.27, 11.69 };
        if (s.equals("A3"))        return new double[] { 11.69, 16.54 };
        return new double[] { 14, 17 };
    }

    private static double cm2in(double cm) { return cm / 2.54; }

    /**
     * Render the whole film sheet to a raster at the configured DPI.
     * cells[i] is the rendered image for cell i+1 (null = leave black).
     */
    public static BufferedImage renderSheet(FilmBox box, BufferedImage[] cells, int dpi) {
        double[] size = filmSizeInches(box.attrs.getString(Tag.FilmSizeID));
        boolean landscape = "LANDSCAPE".equalsIgnoreCase(box.attrs.getString(Tag.FilmOrientation));
        double wIn = landscape ? size[1] : size[0];
        double hIn = landscape ? size[0] : size[1];
        int wPx = (int) Math.round(wIn * dpi);
        int hPx = (int) Math.round(hIn * dpi);

        BufferedImage sheet = new BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, wPx, hPx);

            int[] grid = parseStandardFormat(box.attrs.getString(Tag.ImageDisplayFormat));
            int cols = grid[0], rows = grid[1];
            int cellW = wPx / cols;
            int cellH = hPx / rows;

            Object interpolation = interpolationFor(box.attrs.getString(Tag.MagnificationType));
            for (int pos = 1; pos <= rows * cols; pos++) {
                BufferedImage img = cells[pos - 1];
                if (img == null) continue;
                int row = (pos - 1) / cols;
                int col = (pos - 1) % cols;
                int x = col * cellW;
                int y = row * cellH;
                drawFitted(g, img, x, y, cellW, cellH, interpolation);
            }
        } finally {
            g.dispose();
        }
        return sheet;
    }

    private static Object interpolationFor(String magnificationType) {
        if (magnificationType == null) return RenderingHints.VALUE_INTERPOLATION_BILINEAR;
        String m = magnificationType.trim().toUpperCase();
        if (m.equals("REPLICATE")) return RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
        if (m.equals("CUBIC"))     return RenderingHints.VALUE_INTERPOLATION_BICUBIC;
        return RenderingHints.VALUE_INTERPOLATION_BILINEAR;
    }

    /** Fit img inside (x,y,w,h) preserving aspect, centered. */
    private static void drawFitted(Graphics2D g, BufferedImage img, int x, int y,
                                   int w, int h, Object interpolation) {
        double scale = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = (int) Math.round(img.getWidth() * scale);
        int dh = (int) Math.round(img.getHeight() * scale);
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.drawImage(img, dx, dy, dw, dh, null);
    }
}
