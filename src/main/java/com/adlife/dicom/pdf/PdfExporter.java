package com.adlife.dicom.pdf;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.edit.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.xobject.PDJpeg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Archives every film sheet as a high-resolution PDF, MediPro-style:
 * <pdfDir>/yyyy-MM-dd/HHmmss_<source AE>.pdf, one film-sized page per job.
 * The dated folder is what the clinic shares over WhatsApp/email.
 */
public class PdfExporter {

    private static final Logger LOG = LoggerFactory.getLogger(PdfExporter.class);

    private final File baseDir;
    private final com.adlife.dicom.config.AdLifeConfig cfg;

    public PdfExporter(File baseDir) {
        this(baseDir, null);
    }

    /**
     * pdf.compress=true     smaller files: JPEG quality pdf.quality (default 0.80)
     *                       and page raster capped at pdf.dpi (default 150)
     * pdf.grey=true         greyscale JPEG for mono films (about 3x smaller)
     */
    public PdfExporter(File baseDir, com.adlife.dicom.config.AdLifeConfig cfg) {
        this.baseDir = baseDir;
        this.cfg = cfg;
    }

    /** Returns the PDF written, or null on failure. */
    public File export(BufferedImage sheet, double wIn, double hIn, String sourceAet) {
        String day = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String stamp = new SimpleDateFormat("HHmmss").format(new Date());
        String ae = sourceAet == null ? "DICOM" : sourceAet.replaceAll("[^A-Za-z0-9_-]", "_");
        File dir = new File(baseDir, day);
        // same-second collisions get a numeric suffix
        File out = unique(new File(dir, stamp + "_" + ae + ".pdf"));
        dir.mkdirs();

        PDDocument doc = new PDDocument();
        try {
            float wPt = (float) (wIn * 72.0), hPt = (float) (hIn * 72.0);
            PDPage page = new PDPage(new PDRectangle(wPt, hPt));
            doc.addPage(page);
            BufferedImage src = sheet;
            float q = 0.95f;
            if (cfg != null && cfg.getBool("pdf.compress", false)) {
                q = (float) cfg.getDouble("pdf.quality", 0.80);
                int maxDpi = cfg.getInt("pdf.dpi", 150);
                double cur = sheet.getWidth() / wIn;
                if (cur > maxDpi) {
                    int nw = (int) Math.round(wIn * maxDpi), nh = (int) Math.round(hIn * maxDpi);
                    BufferedImage small = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = small.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(sheet, 0, 0, nw, nh, null);
                    g.dispose();
                    src = small;
                }
            }
            if (cfg != null && cfg.getBool("pdf.grey", false) && isGrey(src)) {
                BufferedImage g8 = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                g8.getGraphics().drawImage(src, 0, 0, null);
                src = g8;
            }
            PDJpeg img = new PDJpeg(doc, src, q);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            try {
                cs.drawXObject(img, 0, 0, wPt, hPt);
            } finally {
                cs.close();
            }
            doc.save(out);
            LOG.info("PDF archived: {}", out.getAbsolutePath());
            return out;
        } catch (Exception e) {
            LOG.error("PDF export failed: {}", e.toString());
            return null;
        } finally {
            try { doc.close(); } catch (IOException ignored) {}
        }
    }

    private static boolean isGrey(BufferedImage img) {
        int stepX = Math.max(1, img.getWidth() / 64), stepY = Math.max(1, img.getHeight() / 64);
        for (int y = 0; y < img.getHeight(); y += stepY)
            for (int x = 0; x < img.getWidth(); x += stepX) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                if (Math.abs(r - g) > 6 || Math.abs(g - b) > 6) return false;
            }
        return true;
    }

    private static File unique(File f) {
        if (!f.exists()) return f;
        String path = f.getAbsolutePath();
        String base = path.substring(0, path.length() - ".pdf".length());
        for (int i = 1; ; i++) {
            File cand = new File(base + "_" + i + ".pdf");
            if (!cand.exists()) return cand;
        }
    }
}
