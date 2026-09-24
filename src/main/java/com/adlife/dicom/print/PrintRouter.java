package com.adlife.dicom.print;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.util.ArrayList;
import java.util.List;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import javax.print.event.PrintJobAdapter;
import javax.print.event.PrintJobEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes rendered film sheets to up to 4 Windows printers, MediPro-style:
 * each job goes to the next idle printer in rotation. If a named printer is
 * missing, the system default takes the job.
 */
public class PrintRouter {

    private static final Logger LOG = LoggerFactory.getLogger(PrintRouter.class);

    private static class PrinterSlot {
        final String configuredName;
        final PrintService service;
        volatile boolean busy;
        PrinterSlot(String configuredName, PrintService service) {
            this.configuredName = configuredName;
            this.service = service;
        }
    }

    private final List<PrinterSlot> slots = new ArrayList<PrinterSlot>();
    private final boolean roundRobin;
    private int rrIndex = 0;

    public PrintRouter(List<String> printerNames, boolean roundRobin) {
        this.roundRobin = roundRobin;
        PrintService[] all = PrintServiceLookup.lookupPrintServices(null, null);
        if (printerNames.isEmpty()) {
            PrintService def = PrintServiceLookup.lookupDefaultPrintService();
            if (def != null) slots.add(new PrinterSlot(def.getName(), def));
        } else {
            for (String name : printerNames.subList(0, Math.min(4, printerNames.size()))) {
                PrintService match = findByName(all, name);
                if (match == null) {
                    LOG.warn("Configured printer '{}' not found; falling back to system default", name);
                    match = PrintServiceLookup.lookupDefaultPrintService();
                }
                if (match != null) slots.add(new PrinterSlot(name, match));
            }
        }
        LOG.info("PrintRouter: {} printer slot(s) ready", slots.size());
    }

    private static PrintService findByName(PrintService[] all, String name) {
        for (PrintService s : all) {
            if (s.getName().equalsIgnoreCase(name)) return s;
        }
        for (PrintService s : all) {
            if (s.getName().toLowerCase().contains(name.toLowerCase())) return s;
        }
        return null;
    }

    public boolean hasPrinters() { return !slots.isEmpty(); }

    public String statusText() {
        StringBuilder sb = new StringBuilder();
        for (PrinterSlot s : slots) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.service.getName()).append(s.busy ? " (busy)" : " (idle)");
        }
        return sb.length() == 0 ? "no printers found" : sb.toString();
    }

    /** Submit one film sheet. Returns the printer name that took the job, or null. */
    public synchronized String submit(final BufferedImage sheet, final double wIn, final double hIn,
                                      final int copies, final String jobName) {
        if (slots.isEmpty()) {
            LOG.warn("No printers available; job '{}' dropped (PDF still archived)", jobName);
            return null;
        }
        PrinterSlot pick = null;
        if (roundRobin) {
            for (int i = 0; i < slots.size(); i++) {
                PrinterSlot cand = slots.get((rrIndex + i) % slots.size());
                if (!cand.busy) { pick = cand; rrIndex = (rrIndex + i + 1) % slots.size(); break; }
            }
        }
        if (pick == null) {
            for (PrinterSlot s : slots) if (!s.busy) { pick = s; break; }
        }
        if (pick == null) pick = slots.get(0); // all busy: queue on first

        final PrinterSlot slot = pick;
        slot.busy = true;

        Printable printable = new Printable() {
            @Override
            public int print(Graphics graphics, PageFormat pageFormat, int pageIndex)
                    throws PrinterException {
                if (pageIndex > 0) return NO_SUCH_PAGE;
                Graphics2D g = (Graphics2D) graphics;
                g.translate(pageFormat.getImageableX(), pageFormat.getImageableY());
                double iw = pageFormat.getImageableWidth();
                double ih = pageFormat.getImageableHeight();
                double scale = Math.min(iw / sheet.getWidth(), ih / sheet.getHeight());
                int dw = (int) Math.round(sheet.getWidth() * scale);
                int dh = (int) Math.round(sheet.getHeight() * scale);
                g.drawImage(sheet, (int) (iw - dw) / 2, (int) (ih - dh) / 2, dw, dh, null);
                return PAGE_EXISTS;
            }
        };

        PageFormat format = new PageFormat();
        Paper paper = new Paper();
        double wPt = wIn * 72.0, hPt = hIn * 72.0;
        paper.setSize(wPt, hPt);
        paper.setImageableArea(0, 0, wPt, hPt);
        format.setPaper(paper);
        format.setOrientation(wIn <= hIn ? PageFormat.PORTRAIT : PageFormat.LANDSCAPE);

        Doc doc = new SimpleDoc(printable, DocFlavor.SERVICE_FORMATTED.PRINTABLE, null);
        DocPrintJob job = slot.service.createPrintJob();
        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        if (copies > 1) attrs.add(new Copies(Math.min(copies, 99)));

        job.addPrintJobListener(new PrintJobAdapter() {
            private void done(String what) {
                slot.busy = false;
                LOG.info("Print job '{}' on {}: {}", jobName, slot.service.getName(), what);
            }
            @Override public void printJobCompleted(PrintJobEvent e) { done("completed"); }
            @Override public void printJobFailed(PrintJobEvent e) { done("failed"); }
            @Override public void printJobCanceled(PrintJobEvent e) { done("canceled"); }
        });

        try {
            job.print(doc, attrs);
            LOG.info("Print job '{}' spooled to {}", jobName, slot.service.getName());
            return slot.service.getName();
        } catch (Exception e) {
            slot.busy = false;
            LOG.error("Print job '{}' failed on {}: {}", jobName, slot.service.getName(), e.toString());
            return null;
        }
    }
}
