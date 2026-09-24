package com.adlife.dicom.print;

import com.adlife.dicom.config.AdLifeConfig;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Header / footer branding printed on the film (hospital name, address lines,
 * logos, brand text, optional QR code). Keys (overlay.*):
 *
 *   overlay.enabled=false          default for printers without p<N>.overlay
 *   overlay.header1 / header2      top lines   (tokens: {date} {time} {aet} {called} {printer} {film})
 *   overlay.footer1 / footer2      bottom lines
 *   overlay.header.font=SansSerif,BOLD,16    family,PLAIN|BOLD|ITALIC|BOLDITALIC,points
 *   overlay.footer.font=SansSerif,PLAIN,11
 *   overlay.header.color=#FFFFFF   overlay.footer.color=#FFFFFF   overlay.band.color=#000000
 *   overlay.header.align=center    overlay.footer.align=center   (left|center|right)
 *   overlay.logo.header=C:\logo.png   overlay.logo.footer=
 *   overlay.logo.position=left     (left|right|center; the text moves aside)
 *   overlay.band.header.mm=14      overlay.band.footer.mm=10   (0 = auto from content)
 *   overlay.brand=                 small text bottom-right ("Brand name on printout")
 *   overlay.qr=                    text/URL encoded as a QR code in the footer (empty = none)
 */
public final class Overlay {
    public final String h1, h2, f1, f2, brand, qr, logoHeader, logoFooter, logoPos, hAlign, fAlign;
    public final Font hFont, fFont;
    public final Color hColor, fColor, band;
    public final double bandHeaderMm, bandFooterMm;

    public Overlay(AdLifeConfig c) {
        h1 = c.get("overlay.header1", ""); h2 = c.get("overlay.header2", "");
        f1 = c.get("overlay.footer1", ""); f2 = c.get("overlay.footer2", "");
        brand = c.get("overlay.brand", ""); qr = c.get("overlay.qr", "");
        logoHeader = c.get("overlay.logo.header", ""); logoFooter = c.get("overlay.logo.footer", "");
        logoPos = c.get("overlay.logo.position", "left").toLowerCase();
        hAlign = c.get("overlay.header.align", "center").toLowerCase();
        fAlign = c.get("overlay.footer.align", "center").toLowerCase();
        hFont = font(c.get("overlay.header.font", "SansSerif,BOLD,16"));
        fFont = font(c.get("overlay.footer.font", "SansSerif,PLAIN,11"));
        hColor = color(c.get("overlay.header.color", "#FFFFFF"), Color.WHITE);
        fColor = color(c.get("overlay.footer.color", "#FFFFFF"), Color.WHITE);
        band = color(c.get("overlay.band.color", "#000000"), Color.BLACK);
        bandHeaderMm = c.getDouble("overlay.band.header.mm", 0);
        bandFooterMm = c.getDouble("overlay.band.footer.mm", 0);
    }

    public boolean hasHeader() { return !h1.isEmpty() || !h2.isEmpty() || !logoHeader.isEmpty(); }
    public boolean hasFooter() { return !f1.isEmpty() || !f2.isEmpty() || !logoFooter.isEmpty() || !brand.isEmpty() || !qr.isEmpty(); }

    public static Font font(String spec) {
        String[] f = spec.split(",");
        String fam = f.length > 0 && !f[0].trim().isEmpty() ? f[0].trim() : "SansSerif";
        int style = Font.PLAIN;
        if (f.length > 1) {
            String s = f[1].trim().toUpperCase();
            if (s.contains("BOLD")) style |= Font.BOLD;
            if (s.contains("ITALIC")) style |= Font.ITALIC;
        }
        int pt = 12;
        try { if (f.length > 2) pt = Integer.parseInt(f[2].trim()); } catch (NumberFormatException ignored) {}
        return new Font(fam, style, pt);
    }

    public static Color color(String hex, Color def) {
        try { return Color.decode(hex.trim()); } catch (RuntimeException e) { return def; }
    }

    private static int px(double mm, int dpi) { return (int) Math.round(mm / 25.4 * dpi); }
    private static Font scaled(Font f, int dpi) { return f.deriveFont((float) (f.getSize2D() * dpi / 72.0)); }

    /** Height in pixels reserved at the top. */
    public int headerPx(int dpi) {
        if (!hasHeader()) return 0;
        if (bandHeaderMm > 0) return px(bandHeaderMm, dpi);
        int lines = (h1.isEmpty() ? 0 : 1) + (h2.isEmpty() ? 0 : 1);
        int lineH = (int) Math.ceil(hFont.getSize2D() * dpi / 72.0 * 1.3);
        return Math.max(lines * lineH, logoHeader.isEmpty() ? 0 : px(12, dpi)) + px(3, dpi);
    }

    public int footerPx(int dpi) {
        if (!hasFooter()) return 0;
        if (bandFooterMm > 0) return px(bandFooterMm, dpi);
        int lines = (f1.isEmpty() ? 0 : 1) + (f2.isEmpty() ? 0 : 1) + (brand.isEmpty() ? 0 : 1);
        int lineH = (int) Math.ceil(fFont.getSize2D() * dpi / 72.0 * 1.3);
        int min = (!logoFooter.isEmpty() || !qr.isEmpty()) ? px(12, dpi) : 0;
        return Math.max(lines * lineH, min) + px(3, dpi);
    }

    public static String expand(String s, Map<String, String> tokens) {
        String out = s;
        Date now = new Date();
        out = out.replace("{date}", new SimpleDateFormat("dd-MM-yyyy").format(now));
        out = out.replace("{time}", new SimpleDateFormat("HH:mm").format(now));
        if (tokens != null) for (Map.Entry<String, String> e : tokens.entrySet())
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        return out;
    }

    public void paint(Graphics2D g, int wPx, int hPx, int dpi, Map<String, String> tokens) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        int margin = px(4, dpi);
        int hh = headerPx(dpi), fh = footerPx(dpi);
        if (hh > 0) {
            g.setColor(band); g.fillRect(0, 0, wPx, hh);
            int[] textBox = drawLogo(g, logoHeader, 0, hh, wPx, margin, dpi);
            drawLines(g, new String[] { expand(h1, tokens), expand(h2, tokens) }, scaled(hFont, dpi), hColor,
                    textBox[0], textBox[1], 0, hh, hAlign);
        }
        if (fh > 0) {
            int top = hPx - fh;
            g.setColor(band); g.fillRect(0, top, wPx, fh);
            int[] textBox = drawLogo(g, logoFooter, top, fh, wPx, margin, dpi);
            int right = textBox[1];
            if (!qr.isEmpty()) {
                int q = fh - px(2, dpi);
                BufferedImage code = qrImage(expand(qr, tokens), q);
                if (code != null) {
                    g.drawImage(code, right - q, top + (fh - q) / 2, null);
                    right -= q + margin;
                }
            }
            String[] lines = brand.isEmpty() ? new String[] { expand(f1, tokens), expand(f2, tokens) }
                    : new String[] { expand(f1, tokens), expand(f2, tokens), "" };
            drawLines(g, lines, scaled(fFont, dpi), fColor, textBox[0], right, top, fh, fAlign);
            if (!brand.isEmpty()) {
                Font bf = scaled(fFont, dpi).deriveFont(Font.ITALIC, (float) (scaled(fFont, dpi).getSize2D() * 0.8));
                g.setFont(bf); g.setColor(fColor);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(brand, right - fm.stringWidth(brand), top + fh - fm.getDescent() - px(1, dpi));
            }
        }
    }

    /** Draws the logo; returns the remaining horizontal text span {x0, x1}. */
    private int[] drawLogo(Graphics2D g, String path, int top, int bandH, int wPx, int margin, int dpi) {
        int x0 = margin, x1 = wPx - margin;
        if (path.isEmpty()) return new int[] { x0, x1 };
        BufferedImage logo = loadImage(path);
        if (logo == null) return new int[] { x0, x1 };
        int lh = bandH - px(2, dpi);
        int lw = (int) Math.round(logo.getWidth() * (lh / (double) logo.getHeight()));
        int y = top + (bandH - lh) / 2;
        if ("right".equals(logoPos)) {
            g.drawImage(logo, x1 - lw, y, lw, lh, null);
            return new int[] { x0, x1 - lw - margin };
        } else if ("center".equals(logoPos)) {
            g.drawImage(logo, (wPx - lw) / 2, y, lw, lh, null);
            return new int[] { x0, (wPx - lw) / 2 - margin }; // text to the left half
        }
        g.drawImage(logo, x0, y, lw, lh, null);
        return new int[] { x0 + lw + margin, x1 };
    }

    private static void drawLines(Graphics2D g, String[] lines, Font f, Color c, int x0, int x1,
                                  int top, int bandH, String align) {
        int n = 0;
        for (String l : lines) if (l != null && !l.isEmpty()) n++;
        if (n == 0) return;
        g.setFont(f); g.setColor(c);
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight();
        int y = top + (bandH - n * lineH) / 2 + fm.getAscent();
        for (String l : lines) {
            if (l == null || l.isEmpty()) continue;
            int tw = fm.stringWidth(l);
            int x = "left".equals(align) ? x0 : "right".equals(align) ? x1 - tw : x0 + (x1 - x0 - tw) / 2;
            g.drawString(l, Math.max(x0, x), y);
            y += lineH;
        }
    }

    private static final Map<String, BufferedImage> CACHE = new java.util.concurrent.ConcurrentHashMap<String, BufferedImage>();

    public static BufferedImage loadImage(String path) {
        BufferedImage img = CACHE.get(path);
        if (img != null) return img;
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            img = ImageIO.read(f);
            if (img != null) CACHE.put(path, img);
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearCache() { CACHE.clear(); }

    public static BufferedImage qrImage(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++)
                for (int x = 0; x < m.getWidth(); x++)
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            return img;
        } catch (Exception e) {
            return null;
        }
    }
}
