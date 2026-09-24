package com.adlife.dicom.print;

import com.adlife.dicom.config.AdLifeConfig;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.Attribute;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Chromaticity;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.Media;
import javax.print.attribute.standard.MediaSize;
import javax.print.attribute.standard.MediaSizeName;
import javax.print.attribute.standard.MediaTray;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses an output slot for each film and spools it. Up to 6 slots: Windows
 * printers, real DICOM film printers (dicom:AE@host:port) or folders.
 *
 * Routing rules, first match wins (each value is a printer number 1..6):
 *   route.all=N                 every job to printer N
 *   route.called.<AET>=N        jobs that arrived on listener AE <AET>
 *   route.calling.<AET>=N       jobs sent by modality/console AE <AET>
 *   route.size.<FILMSIZE>=N     jobs for that film size (after mapping)
 *   route.color=N / route.grey=N
 *   printer.roundrobin=true     otherwise next idle printer in rotation, else printer 1
 */
public class PrintRouter {
    private static final Logger LOG = LoggerFactory.getLogger(PrintRouter.class);
    public static final int MAX_PRINTERS = 6;

    public static final class Slot {
        public final PrinterProfile profile;
        final PrintService service;   // Windows only
        volatile boolean busy;
        Slot(PrinterProfile profile, PrintService service) { this.profile = profile; this.service = service; }
        public String name() {
            if (service != null) return service.getName();
            return profile.entry.isEmpty() ? "system default" : profile.entry;
        }
        /** False for a Windows printer entry that no installed printer matched. */
        public boolean available() { return !profile.isWindows() || service != null; }
        public boolean isBusy() { return busy; }
    }

    /** What the router needs to know about a job to pick a printer. */
    public static final class JobInfo {
        public String callingAet, calledAet, filmSize;
        public boolean color;
    }

    private final AdLifeConfig cfg;
    private final List<Slot> slots = new ArrayList<Slot>();
    private int rrIndex = 0;

    public PrintRouter(AdLifeConfig cfg) {
        this.cfg = cfg;
        List<String> names = cfg.printerNames();
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        if (names.isEmpty()) {
            PrintService def = PrintServiceLookup.lookupDefaultPrintService();
            if (def != null) slots.add(new Slot(new PrinterProfile(cfg, 1, ""), def));
        } else {
            int idx = 1;
            for (String name : names.subList(0, Math.min(MAX_PRINTERS, names.size()))) {
                PrinterProfile prof = new PrinterProfile(cfg, idx++, name);
                if (prof.isWindows()) {
                    PrintService match = findByName(all, name);
                    if (match == null) {
                        // Never substitute another printer: keep the slot visible under its
                        // configured name, unavailable, so jobs fail loudly instead of
                        // silently printing somewhere else.
                        LOG.warn("Printer {} '{}' is not an installed printer on this PC; slot stays unavailable", prof.index, name);
                        slots.add(new Slot(prof, null));
                    } else {
                        slots.add(new Slot(prof, match));
                    }
                } else {
                    slots.add(new Slot(prof, null));
                }
            }
        }
        LOG.info("PrintRouter: {} printer slot(s) ready", slots.size());
    }

    /** Back-compat constructor used by older call sites. */
    public PrintRouter(List<String> printerNames, boolean roundRobin) {
        this(legacyCfg(printerNames, roundRobin));
    }
    private static AdLifeConfig legacyCfg(List<String> names, boolean rr) {
        AdLifeConfig c = new AdLifeConfig(new File(System.getProperty("java.io.tmpdir"), "adlife-legacy"));
        StringBuilder sb = new StringBuilder();
        for (String n : names) { if (sb.length() > 0) sb.append(','); sb.append(n); }
        c.set("printers", sb.toString());
        c.set("printer.roundrobin", String.valueOf(rr));
        return c;
    }

    private static PrintService findByName(PrintService[] all, String name) {
        for (PrintService s : all) if (s.getName().equalsIgnoreCase(name)) return s;
        for (PrintService s : all) if (s.getName().toLowerCase().contains(name.toLowerCase())) return s;
        return null;
    }

    public boolean hasPrinters() { return !slots.isEmpty(); }
    public List<Slot> slots() { return slots; }

    public Slot slotByIndex(int printerNumber) {
        for (Slot s : slots) if (s.profile.index == printerNumber) return s;
        return null;
    }

    public String statusText() {
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.profile.index).append(": ").append(s.name())
              .append(!s.available() ? " (NOT FOUND on this PC)" : s.busy ? " (busy)" : " (idle)");
        }
        return sb.length() == 0 ? "no printers found" : sb.toString();
    }

    /** Legacy: name of the printer round robin would pick next. */
    public synchronized String peekNextPrinterName() {
        Slot s = choose(new JobInfo(), false);
        return s == null ? null : s.profile.entry;
    }

    public synchronized Slot choose(JobInfo job) { return choose(job, true); }

    private Slot choose(JobInfo job, boolean advance) {
        if (slots.isEmpty()) return null;
        int n;
        if ((n = cfg.getInt("route.all", 0)) > 0 && slotByIndex(n) != null) return rule("all jobs", n);
        if (job.calledAet != null && (n = cfg.getInt("route.called." + job.calledAet, 0)) > 0 && slotByIndex(n) != null)
            return rule("listener AET " + job.calledAet, n);
        if (job.callingAet != null && (n = cfg.getInt("route.calling." + job.callingAet, 0)) > 0 && slotByIndex(n) != null)
            return rule("console AET " + job.callingAet, n);
        if (job.filmSize != null && (n = cfg.getInt("route.size." + job.filmSize, 0)) > 0 && slotByIndex(n) != null)
            return rule("film size " + job.filmSize, n);
        if (job.color && (n = cfg.getInt("route.color", 0)) > 0 && slotByIndex(n) != null) return rule("colour job", n);
        if (!job.color && (n = cfg.getInt("route.grey", 0)) > 0 && slotByIndex(n) != null) return rule("greyscale job", n);
        if (job.color) { // prefer a colour-capable printer
            for (Slot s : slots) if (s.profile.color() && !s.busy) return s;
        }
        if (cfg.roundRobin()) {
            for (int i = 0; i < slots.size(); i++) {
                Slot cand = slots.get((rrIndex + i) % slots.size());
                if (!cand.busy) {
                    if (advance) rrIndex = (rrIndex + i + 1) % slots.size();
                    return cand;
                }
            }
        }
        for (Slot s : slots) if (!s.busy) return s;
        return slots.get(0);
    }

    private Slot rule(String what, int n) {
        LOG.info("PRINT > Rule applied: {} -> printer {}", what, n);
        return slotByIndex(n);
    }

    /** Legacy submit: pick by round robin, print as-is. */
    public synchronized String submit(BufferedImage sheet, double wIn, double hIn, int copies, String jobName) {
        Slot s = choose(new JobInfo());
        if (s == null) { LOG.warn("No printers available; job '{}' dropped", jobName); return null; }
        return submitTo(s, sheet, wIn, hIn, "", copies, jobName, true) ? s.name() : null;
    }

    /** Spool one rendered sheet to a Windows or folder slot (DICOM slots go through DicomPrintForwarder). */
    public boolean submitTo(final Slot slot, final BufferedImage sheet, final double wIn, final double hIn,
                            final String sizeId, final int copies, final String jobName, final boolean color) {
        if (slot.profile.isFolder()) return toFolder(slot, sheet, sizeId, copies, jobName);
        if (slot.service == null) {
            LOG.error("Print job '{}' failed: printer '{}' is not installed on this PC (check Settings > Printers)",
                    jobName, slot.profile.entry);
            return false;
        }
        final PrinterProfile prof = slot.profile;
        final double offX = prof.offXmm(sizeId) / 25.4 * 72, offY = prof.offYmm(sizeId) / 25.4 * 72;
        final double sx = prof.scaleX(sizeId), sy = prof.scaleY(sizeId);
        final boolean full = prof.fullPage();
        slot.busy = true;
        Printable printable = new Printable() {
            @Override
            public int print(Graphics graphics, PageFormat pf, int pageIndex) throws PrinterException {
                if (pageIndex > 0) return NO_SUCH_PAGE;
                Graphics2D g = (Graphics2D) graphics;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                double[] box = placement(pf, sheet.getWidth(), sheet.getHeight(), full, offX, offY, sx, sy);
                g.drawImage(sheet, (int) Math.round(box[0]), (int) Math.round(box[1]),
                        (int) Math.round(box[2]), (int) Math.round(box[3]), null);
                return PAGE_EXISTS;
            }
        };
        double pw = wIn * 72.0, ph = hIn * 72.0;
        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        String paperName = prof.paper(sizeId);
        Media paperMedia = paperName.isEmpty() ? null : findMedia(slot.service, paperName, false);
        if (paperMedia == null) paperMedia = findMediaBySize(slot.service, wIn, hIn);
        if (paperMedia != null) {
            attrs.add(paperMedia);
            if (paperMedia instanceof MediaSizeName) {
                MediaSize ms = MediaSize.getMediaSizeForName((MediaSizeName) paperMedia);
                if (ms != null) {
                    float[] d = ms.getSize(MediaSize.INCH);
                    double a = Math.min(d[0], d[1]) * 72, b = Math.max(d[0], d[1]) * 72;
                    pw = wIn <= hIn ? a : b; ph = wIn <= hIn ? b : a;
                }
            }
            LOG.info("PRINT > Paper for {}: {}", sizeId, paperMedia);
        }
        String trayName = prof.tray(sizeId);
        if (!trayName.isEmpty()) {
            Media tray = findMedia(slot.service, trayName, true);
            if (tray instanceof MediaTray) {
                Attribute alt = alternateMedia((MediaTray) tray);
                if (alt != null) attrs.add(alt); else if (paperMedia == null) attrs.add(tray);
                LOG.info("PRINT > Tray for {}: {}", sizeId, tray);
            } else {
                LOG.warn("PRINT > Tray '{}' not offered by {}", trayName, slot.name());
            }
        }
        PageFormat format = new PageFormat();
        Paper paper = new Paper();
        double a = Math.min(pw, ph), b = Math.max(pw, ph);
        paper.setSize(a, b);
        paper.setImageableArea(0, 0, a, b);
        format.setPaper(paper);
        format.setOrientation(wIn <= hIn ? PageFormat.PORTRAIT : PageFormat.LANDSCAPE);
        if (!color) attrs.add(Chromaticity.MONOCHROME);
        if (copies > 1) attrs.add(new Copies(Math.min(copies, 99)));
        attrs.add(new JobName(jobName, null));
        try {
            java.awt.print.PrinterJob pj = java.awt.print.PrinterJob.getPrinterJob();
            pj.setPrintService(slot.service);
            pj.setJobName(jobName);
            PageFormat validated = pj.validatePage(format);
            pj.setPrintable(printable, full ? format : validated);
            pj.print(attrs);
            LOG.info("Print job '{}' spooled to {}", jobName, slot.name());
            return true;
        } catch (Exception e) {
            LOG.error("Print job '{}' failed on {}: {}", jobName, slot.name(), e.toString());
            // fall back to the plain DocPrintJob path used by v1.0
            try {
                Doc doc = new SimpleDoc(printable, DocFlavor.SERVICE_FORMATTED.PRINTABLE, null);
                DocPrintJob job = slot.service.createPrintJob();
                job.print(doc, attrs);
                return true;
            } catch (Exception e2) {
                LOG.error("Fallback print failed: {}", e2.toString());
                return false;
            }
        } finally {
            slot.busy = false;
        }
    }

    /**
     * Where the sheet lands on the page, in points: {x, y, w, h}.
     * fit  = scaled into the printable area, centred.
     * full = the physical page; hard margins are compensated by shifting back.
     * Then calibration offsets (mm->pt) and scale percentages are applied.
     */
    static double[] placement(PageFormat pf, int imgW, int imgH, boolean full,
                              double offX, double offY, double sx, double sy) {
        double x0, y0, w, h;
        if (full) {
            x0 = 0; y0 = 0; w = pf.getWidth(); h = pf.getHeight();
        } else {
            x0 = pf.getImageableX(); y0 = pf.getImageableY();
            w = pf.getImageableWidth(); h = pf.getImageableHeight();
        }
        double scale = Math.min(w / imgW, h / imgH);
        double dw = imgW * scale * sx, dh = imgH * scale * sy;
        double x = x0 + (w - dw) / 2 + offX, y = y0 + (h - dh) / 2 + offY;
        return new double[] { x, y, dw, dh };
    }

    private static Media findMedia(PrintService svc, String name, boolean trays) {
        Object v = svc.getSupportedAttributeValues(Media.class, null, null);
        if (!(v instanceof Media[])) return null;
        String want = name.trim().toLowerCase();
        for (Media m : (Media[]) v) {
            if (trays != (m instanceof MediaTray)) continue;
            if (m.toString().trim().toLowerCase().equals(want)) return m;
        }
        for (Media m : (Media[]) v) {
            if (trays != (m instanceof MediaTray)) continue;
            if (m.toString().toLowerCase().contains(want)) return m;
        }
        return null;
    }

    /** Closest supported paper to the film size (within 0.3 inch), so the driver doesn't scale to Letter/A4. */
    private static Media findMediaBySize(PrintService svc, double wIn, double hIn) {
        Object v = svc.getSupportedAttributeValues(Media.class, null, null);
        if (!(v instanceof Media[])) return null;
        double a = Math.min(wIn, hIn), b = Math.max(wIn, hIn);
        for (Media m : (Media[]) v) {
            if (!(m instanceof MediaSizeName)) continue;
            MediaSize ms = MediaSize.getMediaSizeForName((MediaSizeName) m);
            if (ms == null) continue;
            float[] d = ms.getSize(MediaSize.INCH);
            double ma = Math.min(d[0], d[1]), mb = Math.max(d[0], d[1]);
            if (Math.abs(ma - a) < 0.3 && Math.abs(mb - b) < 0.3) return m;
        }
        return null;
    }

    /** sun.print.SunAlternateMedia lets Windows pick a tray while Media holds the paper size. */
    private static Attribute alternateMedia(MediaTray tray) {
        try {
            Class<?> c = Class.forName("sun.print.SunAlternateMedia");
            return (Attribute) c.getConstructor(Media.class).newInstance(tray);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean toFolder(Slot slot, BufferedImage sheet, String sizeId, int copies, String jobName) {
        slot.busy = true;
        try {
            File dir = new File(slot.profile.folderPath());
            dir.mkdirs();
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new java.util.Date());
            File out = new File(dir, stamp + "_" + (sizeId == null ? "film" : sizeId) + ".png");
            ImageIO.write(sheet, "png", out);
            LOG.info("Print job '{}' written to {} (copies {})", jobName, out, copies);
            return true;
        } catch (Exception e) {
            LOG.error("Folder output failed: {}", e.toString());
            return false;
        } finally {
            slot.busy = false;
        }
    }

    /** Printer names Windows offers (for the Settings screen). */
    public static List<String> installedPrinters() {
        List<String> out = new ArrayList<String>();
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) out.add(s.getName());
        return out;
    }

    /** Paper and tray names a printer offers (for the Settings screen). */
    public static List<String> mediaNames(String printer, boolean trays) {
        List<String> out = new ArrayList<String>();
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
            if (!s.getName().equalsIgnoreCase(printer)) continue;
            Object v = s.getSupportedAttributeValues(Media.class, null, null);
            if (v instanceof Media[]) for (Media m : (Media[]) v) if (trays == (m instanceof MediaTray)) out.add(m.toString());
        }
        return out;
    }
}
