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

    public PdfExporter(File baseDir) {
        this.baseDir = baseDir;
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
            PDJpeg img = new PDJpeg(doc, sheet, 0.95f);
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
