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
        Options o = new Options();
        o.wIn = landscape ? size[1] : size[0];
        o.hIn = landscape ? size[0] : size[1];
        return renderSheet(box, cells, dpi, o);
    }

    /** Layout options (per printer). */
    public static class Options {
        public double wIn = 14, hIn = 17;
        public String style = "normal";     // normal | stretch (= fullpage) | scatter | bordered
        public double borderMm = 0, gapMm = 0;
        public boolean whiteBackground = false;
        public Overlay overlay;             // null = no header/footer
        public java.util.Map<String, String> tokens;
    }

    public static BufferedImage renderSheet(FilmBox box, BufferedImage[] cells, int dpi, Options o) {
        int wPx = (int) Math.round(o.wIn * dpi);
        int hPx = (int) Math.round(o.hIn * dpi);
        BufferedImage sheet = new BufferedImage(wPx, hPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            java.awt.Color bg = o.whiteBackground ? java.awt.Color.WHITE : java.awt.Color.BLACK;
            g.setColor(bg);
            g.fillRect(0, 0, wPx, hPx);
            int top = o.overlay != null ? o.overlay.headerPx(dpi) : 0;
            int bottom = o.overlay != null ? o.overlay.footerPx(dpi) : 0;
            int areaH = hPx - top - bottom;
            int[] grid = parseStandardFormat(box.attrs.getString(Tag.ImageDisplayFormat));
            int cols = grid[0], rows = grid[1];
            int gap = (int) Math.round(o.gapMm / 25.4 * dpi);
            double bmm = o.borderMm <= 0 && "bordered".equalsIgnoreCase(o.style) ? 1.0 : o.borderMm;
            int border = (int) Math.round(bmm / 25.4 * dpi);
            int cellW = (wPx - gap * (cols + 1)) / cols;
            int cellH = (areaH - gap * (rows + 1)) / rows;
            Object interpolation = interpolationFor(box.attrs.getString(Tag.MagnificationType));
            boolean stretch = "stretch".equalsIgnoreCase(o.style) || "fullpage".equalsIgnoreCase(o.style);
            for (int pos = 1; pos <= rows * cols && pos <= cells.length; pos++) {
                BufferedImage img = cells[pos - 1];
                if (img == null) continue;
                int row = (pos - 1) / cols;
                int col = (pos - 1) % cols;
                int x = gap + col * (cellW + gap);
                int y = top + gap + row * (cellH + gap);
                java.awt.Rectangle r;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
                if (stretch) {
                    g.drawImage(img, x + border, y + border, cellW - 2 * border, cellH - 2 * border, null);
                    r = new java.awt.Rectangle(x, y, cellW, cellH);
                } else {
                    r = drawFitted(g, img, x + border, y + border, cellW - 2 * border, cellH - 2 * border, interpolation);
                    r.grow(border, border);
                }
                if (border > 0) {
                    g.setColor(o.whiteBackground ? java.awt.Color.BLACK : java.awt.Color.WHITE);
                    java.awt.Stroke old = g.getStroke();
                    g.setStroke(new java.awt.BasicStroke(Math.max(1, border / 2f)));
                    g.drawRect(r.x + border / 4, r.y + border / 4, r.width - border / 2, r.height - border / 2);
                    g.setStroke(old);
                }
            }
            drawAnnotations(g, box, wPx, hPx, dpi, top, bottom, o.whiteBackground);
            if (o.overlay != null) o.overlay.paint(g, wPx, hPx, dpi, o.tokens);
        } finally {
            g.dispose();
        }
        return sheet;
    }

    /**
     * Basic Annotation Box texts (DICOM PS3.3 C.11.4): positions 1..n run along
     * the top edge of the film, then continue along the bottom edge.
     */
    private static void drawAnnotations(Graphics2D g, FilmBox box, int wPx, int hPx, int dpi,
                                        int topOff, int bottomOff, boolean white) {
        if (box.annotations.isEmpty()) return;
        int fontH = Math.max(10, (int) Math.round(dpi * 0.14)); // ~0.14in cap height
        g.setColor(white ? java.awt.Color.BLACK : java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, fontH));
        int half = (box.annotations.size() + 1) / 2;
        int i = 0;
        for (java.util.Map.Entry<Integer, String> e : box.annotations.entrySet()) {
            boolean top = i < half;
            int slot = top ? i : i - half;
            int slots = top ? half : box.annotations.size() - half;
            int x = (int) ((slot + 0.5) * wPx / slots);
            int y = top ? topOff + fontH + 2 : hPx - bottomOff - 4;
            int tw = g.getFontMetrics().stringWidth(e.getValue());
            g.drawString(e.getValue(), Math.max(2, x - tw / 2), y);
            i++;
        }
    }

    private static Object interpolationFor(String magnificationType) {
        if (magnificationType == null) return RenderingHints.VALUE_INTERPOLATION_BILINEAR;
        String m = magnificationType.trim().toUpperCase();
        if (m.equals("REPLICATE")) return RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
        if (m.equals("CUBIC"))     return RenderingHints.VALUE_INTERPOLATION_BICUBIC;
        return RenderingHints.VALUE_INTERPOLATION_BILINEAR;
    }

    /** Fit img inside (x,y,w,h) preserving aspect, centered. */
    private static java.awt.Rectangle drawFitted(Graphics2D g, BufferedImage img, int x, int y,
                                   int w, int h, Object interpolation) {
        double scale = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = (int) Math.round(img.getWidth() * scale);
        int dh = (int) Math.round(img.getHeight() * scale);
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.drawImage(img, dx, dy, dw, dh, null);
        return new java.awt.Rectangle(dx, dy, dw, dh);
    }
}
