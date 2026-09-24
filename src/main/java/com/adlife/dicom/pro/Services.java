package com.adlife.dicom.pro;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.pdf.PdfExporter;
import com.adlife.dicom.print.PrintRouter;

/** Shared output-side services, rebuilt whenever the listeners restart. */
public final class Services {
    public final AdLifeConfig cfg;
    public final PrintRouter router;
    public final PdfExporter pdf;
    public final JobStore jobs;
    public final PrintStats stats;
    public final LicenseManager license;

    public Services(AdLifeConfig cfg) {
        this.cfg = cfg;
        this.router = new PrintRouter(cfg);
        this.pdf = new PdfExporter(cfg.pdfDir(), cfg);
        this.jobs = new JobStore(cfg);
        this.stats = new PrintStats(cfg);
        this.license = new LicenseManager(cfg);
    }

    private static volatile Services current;
    public static Services current() { return current; }
    public static void setCurrent(Services s) { current = s; }
}
