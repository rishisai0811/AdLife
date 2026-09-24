package com.adlife.dicom.print;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.imaging.ImageAdjust;

/**
 * Everything configurable for one output slot (p1..p6):
 *
 *   printers = entry1, entry2, ...   entry = Windows printer name
 *                                            | dicom:AE@host:port   (real DICOM film printer)
 *                                            | folder:C:\path        (PNG per page, e.g. for a shared folder)
 *   p<N>.color=true|false            colour capable (colour jobs prefer it)
 *   p<N>.brightness/contrast         -100..100
 *   p<N>.darkfactor                  1..5
 *   p<N>.soften / p<N>.sharpen       0..10
 *   p<N>.invert                      true|false
 *   p<N>.style                       normal|stretch|scatter
 *   p<N>.border.mm                   white/black line around each image (0 = none)
 *   p<N>.gap.mm                      spacing between cells (scatter default 4)
 *   p<N>.background                  black|white
 *   p<N>.overlay                     true|false   header/footer/logo on this printer
 *   p<N>.margins                     fit|full     fit = inside printable area, full = compensate hard margins
 *   p<N>.<SIZE>.offx.mm/offy.mm      position calibration per film size (SIZE e.g. 8INX10IN)
 *   p<N>.<SIZE>.scalex/scaley        size calibration in % (100 = none)
 *   p<N>.<SIZE>.paper                Windows paper name to select for that film size
 *   p<N>.<SIZE>.tray                 Windows tray/bin name for that film size
 *   p<N>.dicom.mode                  auto|sheet|passthrough   (DICOM printers)
 *   p<N>.dicom.dpi                   raster dpi when sending whole sheets (default 300)
 *   p<N>.dicom.medium                BLUE FILM|CLEAR FILM|PAPER
 *   p<N>.dicom.calling               calling AE title used towards the printer
 */
public final class PrinterProfile {
    public final int index;           // 1-based
    public final String entry;
    public final AdLifeConfig cfg;

    public PrinterProfile(AdLifeConfig cfg, int index, String entry) {
        this.cfg = cfg;
        this.index = index;
        this.entry = entry == null ? "" : entry.trim();
    }

    private String k(String s) { return "p" + index + "." + s; }

    public boolean isDicom() { return entry.toLowerCase().startsWith("dicom:"); }
    public boolean isFolder() { return entry.toLowerCase().startsWith("folder:"); }
    public boolean isWindows() { return !isDicom() && !isFolder(); }

    /** dicom:AE@host:port -> {AE, host, port} */
    public String[] dicomTarget() {
        String s = entry.substring("dicom:".length());
        int at = s.indexOf('@'), colon = s.lastIndexOf(':');
        if (at < 1 || colon < at) return null;
        return new String[] { s.substring(0, at).trim(), s.substring(at + 1, colon).trim(), s.substring(colon + 1).trim() };
    }
    public String folderPath() { return entry.substring("folder:".length()).trim(); }

    public String label() { return "Printer " + index + " (" + (entry.isEmpty() ? "system default" : entry) + ")"; }

    public boolean color() { return cfg.getBool(k("color"), isWindows()); }
    public ImageAdjust adjust() {
        return new ImageAdjust(cfg.getInt(k("brightness"), 0), cfg.getInt(k("contrast"), 0),
                cfg.getDouble(k("darkfactor"), 1), cfg.getInt(k("soften"), 0), cfg.getInt(k("sharpen"), 0),
                cfg.getBool(k("invert"), false));
    }
    public String style() { return cfg.get(k("style"), "normal").toLowerCase(); }
    public double borderMm() { return cfg.getDouble(k("border.mm"), 0); }
    public double gapMm() { return cfg.getDouble(k("gap.mm"), "scatter".equals(style()) ? 4 : 0); }
    public boolean whiteBackground() { return "white".equalsIgnoreCase(cfg.get(k("background"), "black")); }
    public boolean overlay() { return cfg.getBool(k("overlay"), cfg.getBool("overlay.enabled", false)); }
    public boolean fullPage() { return "full".equalsIgnoreCase(cfg.get(k("margins"), "fit")); }
    public double offXmm(String size) { return cfg.getDouble(k(size + ".offx.mm"), cfg.getDouble(k("offx.mm"), 0)); }
    public double offYmm(String size) { return cfg.getDouble(k(size + ".offy.mm"), cfg.getDouble(k("offy.mm"), 0)); }
    public double scaleX(String size) { return cfg.getDouble(k(size + ".scalex"), cfg.getDouble(k("scalex"), 100)) / 100.0; }
    public double scaleY(String size) { return cfg.getDouble(k(size + ".scaley"), cfg.getDouble(k("scaley"), 100)) / 100.0; }
    public String paper(String size) { return cfg.get(k(size + ".paper"), cfg.get(k("paper"), "")); }
    public String tray(String size) { return cfg.get(k(size + ".tray"), cfg.get(k("tray"), "")); }
    public String dicomMode() { return cfg.get(k("dicom.mode"), "auto").toLowerCase(); }
    public int dicomDpi() { return cfg.getInt(k("dicom.dpi"), 300); }
    public String dicomMedium() { return cfg.get(k("dicom.medium"), "BLUE FILM"); }
    public String dicomCalling() { return cfg.get(k("dicom.calling"), cfg.callingAet()); }
    public String scheme() { return cfg.get("printer.scheme." + entry, ""); }
}
