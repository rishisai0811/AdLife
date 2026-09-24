package com.adlife.dicom.print;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.imaging.ImageAdjust;
import com.adlife.dicom.imaging.LutProfile;
import com.adlife.dicom.imaging.PixelRenderer;
import com.adlife.dicom.pro.JobStore;
import com.adlife.dicom.pro.Notifier;
import com.adlife.dicom.pro.Services;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Association;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The print pipeline for one listener:
 *   film size mapping -> printer routing -> LUT / density -> per-printer image
 *   adjustment -> layout style + header/footer overlay -> licence check ->
 *   PDF -> job store (reprint) -> Windows / DICOM / folder output -> counters + log.
 */
public class PrintCoordinator implements PrintScp.PrintJobHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PrintCoordinator.class);

    private final AdLifeConfig config;
    private final Services svc;
    private final String listenerLutProfile;

    public PrintCoordinator(AdLifeConfig config, Services svc, String listenerLutProfile) {
        this.config = config;
        this.svc = svc;
        this.listenerLutProfile = listenerLutProfile;
    }

    private static double[] densityRange(FilmBox box) {
        int minD = box.attrs.getInt(Tag.MinDensity, -1);
        int maxD = box.attrs.getInt(Tag.MaxDensity, -1);
        if (minD < 0 && maxD < 0) return null;
        double minOD = (minD >= 0 ? minD : 0) / 100.0;
        double maxOD = (maxD >= 0 ? maxD : 300) / 100.0;
        if (maxOD <= minOD) return null;
        return new double[] { minOD, maxOD };
    }

    private static boolean listed(AdLifeConfig c, String key, String aet) {
        if (aet == null) return false;
        for (String s : c.getList(key)) if (s.equalsIgnoreCase(aet.trim())) return true;
        return false;
    }

    @Override
    public void onPrintJob(FilmBox box, Attributes sessionAttrs, Association as, Attributes presentationLut) {
        String src = as != null ? as.getCallingAET() : null;
        String called = as != null ? as.getCalledAET() : null;
        try {
            process(box, sessionAttrs, src, called, presentationLut);
        } catch (Exception e) {
            LOG.error("Print job failed: {}", e.toString(), e);
        }
    }

    /** Runs the whole pipeline; returns the stored job (or null). Public for the self-test. */
    public JobStore.Job process(FilmBox box, Attributes sessionAttrs, String src, String called,
                                Attributes presentationLut) throws Exception {
        LOG.info("------------------------------ NEW JOB from {} on {} ------------------------------", src, called);
        String sizeId = FilmSizes.resolve(config, box.attrs.getString(Tag.FilmSizeID));
        boolean landscape = "LANDSCAPE".equalsIgnoreCase(box.attrs.getString(Tag.FilmOrientation));
        boolean color = box.color && !listed(config, "mono.aets", src);
        if (listed(config, "plut.ignore.aets", src)) presentationLut = null;
        int copies = sessionAttrs != null ? sessionAttrs.getInt(Tag.NumberOfCopies, 1) : 1;

        PrintRouter.JobInfo info = new PrintRouter.JobInfo();
        info.callingAet = src; info.calledAet = called; info.filmSize = sizeId; info.color = color;
        PrintRouter.Slot slot = svc.router.choose(info);
        PrinterProfile prof = slot != null ? slot.profile : new PrinterProfile(config, 1, "");
        LOG.info("PRINT > film {} {} {} -> {}", sizeId, landscape ? "LANDSCAPE" : "PORTRAIT",
                color ? "colour" : "greyscale", slot == null ? "no printer (PDF only)" : prof.label());

        // LUT: explicit lutmap per console > printer scheme > listener default
        PrinterScheme scheme = config.printerSchemeFor(prof.entry.isEmpty() ? null : prof.entry);
        String profileName = config.lutProfileForCallingAet(src);
        LutProfile profile;
        if (profileName != null) profile = LutProfile.load(config, profileName);
        else if (scheme != null) profile = scheme.toLutProfile();
        else profile = LutProfile.load(config, listenerLutProfile);
        byte[] lut = profile.buildLut();
        double[] density = densityRange(box);
        if (density == null && scheme != null) density = scheme.densityOD();

        ImageAdjust adj = prof.adjust();
        BufferedImage[] cells = new BufferedImage[box.imageBoxUIDs.length];
        for (int i = 0; i < cells.length; i++) {
            Attributes imgData = box.imageData[i];
            if (imgData == null) continue;
            try {
                cells[i] = adj.apply(PixelRenderer.renderImageBox(imgData, color, lut, presentationLut, density));
            } catch (Exception e) {
                LOG.error("Cell {} render failed: {}", i + 1, e.toString());
            }
        }

        int dpi = prof.isDicom() ? prof.dicomDpi()
                : (scheme != null && scheme.dpi > 0) ? scheme.dpi : config.filmDpi();
        double[] size = FilmSizes.inches(config, sizeId);
        FilmLayout.Options o = new FilmLayout.Options();
        o.wIn = landscape ? size[1] : size[0];
        o.hIn = landscape ? size[0] : size[1];
        o.style = prof.style();
        o.borderMm = prof.borderMm();
        o.gapMm = prof.gapMm();
        o.whiteBackground = prof.whiteBackground();
        if (prof.overlay()) o.overlay = new Overlay(config);
        Map<String, String> tokens = new HashMap<String, String>();
        tokens.put("aet", src == null ? "" : src);
        tokens.put("called", called == null ? "" : called);
        tokens.put("printer", slot == null ? "" : slot.name());
        tokens.put("film", sizeId);
        o.tokens = tokens;
        BufferedImage sheet = FilmLayout.renderSheet(box, cells, dpi, o);

        String bucket = FilmSizes.bucket(sizeId);
        Properties facts = new Properties();
        facts.setProperty("aet", src == null ? "" : src);
        facts.setProperty("called", called == null ? "" : called);
        facts.setProperty("film", sizeId);
        facts.setProperty("bucket", bucket);
        facts.setProperty("w.in", String.valueOf(o.wIn));
        facts.setProperty("h.in", String.valueOf(o.hIn));
        facts.setProperty("copies", String.valueOf(copies));
        facts.setProperty("color", String.valueOf(color));
        facts.setProperty("printer", slot == null ? "" : String.valueOf(prof.index));
        facts.setProperty("dpi", String.valueOf(dpi));

        // PDF (optionally metered)
        if (config.pdfEnabled() && svc.pdf != null) {
            String pdfBlock = svc.license.blockReason(bucket, true);
            if (pdfBlock == null) {
                File pdf = svc.pdf.export(sheet, o.wIn, o.hIn, src);
                if (pdf != null) {
                    facts.setProperty("pdf", pdf.getAbsolutePath());
                    svc.license.consume(bucket, 1, true);
                    svc.stats.countPdf();
                }
            } else {
                LOG.warn("PDF > {}", pdfBlock);
            }
        }

        String block = svc.license.blockReason(bucket, false);
        if (block != null) {
            facts.setProperty("status", "SUSPENDED");
            JobStore.Job job = svc.jobs.save(sheet, facts);
            LOG.warn("PRINT > {}", block);
            Notifier.warn("AdLife - print suspended", block + " The film is saved under Jobs for reprint.");
            svc.stats.log(src, called, sizeId, "-", copies, "SUSPENDED");
            return job;
        }

        boolean ok;
        String printerName;
        if (slot == null) {
            ok = false; printerName = "-";
            LOG.warn("PRINT > No printer configured; PDF only");
        } else if (prof.isDicom()) {
            ok = DicomPrintForwarder.forward(prof, box, sessionAttrs, sheet, sizeId, landscape, copies, color);
            printerName = prof.entry;
        } else {
            ok = svc.router.submitTo(slot, sheet, o.wIn, o.hIn, sizeId, copies, "AdLife film " + box.boxUID, color);
            printerName = slot.name();
        }
        facts.setProperty("status", ok ? "PRINTED" : (slot == null ? "PDF_ONLY" : "FAILED"));
        JobStore.Job job = svc.jobs.save(sheet, facts);
        if (ok) {
            svc.license.consume(bucket, copies, false);
            svc.stats.count(bucket, printerName, copies);
        } else if (slot != null) {
            Notifier.warn("AdLife - print failed", "Printer " + printerName + " did not take the film. It is saved under Jobs for reprint.");
        }
        svc.stats.log(src, called, sizeId, printerName, copies, facts.getProperty("status"));
        return job;
    }

    /** Reprint a stored job on a chosen printer (manual print / reprint screen). */
    public static boolean reprint(Services svc, JobStore.Job job, int printerNumber, int copies) {
        BufferedImage sheet = job.image();
        if (sheet == null) return false;
        String sizeId = job.get("film");
        String bucket = job.get("bucket");
        String block = svc.license.blockReason(bucket, false);
        if (block != null) { Notifier.warn("AdLife - print suspended", block); return false; }
        PrintRouter.Slot slot = svc.router.slotByIndex(printerNumber);
        if (slot == null) return false;
        double w = Double.parseDouble(job.get("w.in")), h = Double.parseDouble(job.get("h.in"));
        boolean color = Boolean.parseBoolean(job.get("color"));
        boolean ok = slot.profile.isDicom()
                ? DicomPrintForwarder.forward(slot.profile, null, null, sheet, sizeId, w > h, copies, color)
                : svc.router.submitTo(slot, sheet, w, h, sizeId, copies, "AdLife reprint", color);
        if (ok) {
            svc.license.consume(bucket, copies, false);
            svc.stats.count(bucket, slot.name(), copies);
            job.p.setProperty("status", "REPRINTED");
            svc.jobs.update(job);
        }
        svc.stats.log(job.get("aet"), "REPRINT", sizeId, slot.name(), copies, ok ? "REPRINTED" : "FAILED");
        return ok;
    }
}
