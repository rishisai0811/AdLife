package com.adlife.dicom.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * File-backed configuration (<home>/AdLife/adlife.properties). Created with
 * commented defaults on first run. All values are editable while the app is
 * stopped; restart to apply.
 */
public class AdLifeConfig {

    public static class Listener {
        public final String aeTitle;
        public final int port;
        public final String lutProfile;
        public Listener(String aeTitle, int port, String lutProfile) {
            this.aeTitle = aeTitle;
            this.port = port;
            this.lutProfile = lutProfile;
        }
    }

    public static class Destination {
        public final String name;
        public final String aeTitle;
        public final String host;
        public final int port;
        public Destination(String name, String aeTitle, String host, int port) {
            this.name = name;
            this.aeTitle = aeTitle;
            this.host = host;
            this.port = port;
        }
    }

    private final File dir;
    private final File file;
    private final Properties p = new Properties();

    public AdLifeConfig() {
        this.dir = new File(System.getProperty("user.home"), "AdLife");
        this.file = new File(dir, "adlife.properties");
    }

    public File dir() { return dir; }
    public File file() { return file; }

    public void load() throws IOException {
        dir.mkdirs();
        if (!file.exists()) {
            writeDefaults();
        }
        FileInputStream in = new FileInputStream(file);
        try {
            p.load(in);
        } finally {
            in.close();
        }
    }

    private void writeDefaults() throws IOException {
        String nl = System.getProperty("line.separator");
        StringBuilder sb = new StringBuilder();
        sb.append("# AdLife DICOM Workstation configuration").append(nl);
        sb.append("# DICOM print/storage listeners: AE:PORT[:lutProfile], comma separated.").append(nl);
        sb.append("# Give each modality body-part its own AE:port here to get a per-body-part").append(nl);
        sb.append("# print look (MediPro-style body-part-wise LUT assignment).").append(nl);
        sb.append("listeners=ADLIFE:104:default").append(nl);
        sb.append("# AE title AdLife uses when it calls out (viewer Send, C-MOVE)").append(nl);
        sb.append("calling.aet=ADLIFE").append(nl);
        sb.append("# Windows printer names, up to 4, comma separated. Empty = system default.").append(nl);
        sb.append("# Exact names as shown in Control Panel > Printers.").append(nl);
        sb.append("printers=").append(nl);
        sb.append("# true = send each job to the next idle printer in rotation").append(nl);
        sb.append("printer.roundrobin=true").append(nl);
        sb.append(nl);
        sb.append("# Printer schemes: per-model calibration presets (MediPro-style).").append(nl);
        sb.append("# scheme.<NAME> = brand|model|filmSizeId|dpi|minOD|maxOD|gamma|invert").append(nl);
        sb.append("# Bind a Windows printer to a scheme: printer.scheme.<printer name> = <NAME>").append(nl);
        sb.append("# Optional global fallback: scheme.default = <NAME>").append(nl);
        sb.append("scheme.FUJI_DRYPIX=Fujifilm|DryPix 7000||508|0.20|3.00|1.8|false").append(nl);
        sb.append("scheme.KONICA_DRYPRO=Konica Minolta|DryPro 873||500|0.20|3.00|1.8|false").append(nl);
        sb.append("scheme.MINDRAY_GENERIC=Mindray|Generic|||0.20|3.00|1.8|false").append(nl);
        sb.append("scheme.PHILIPS_GENERIC=Philips|Generic|||0.20|3.00|1.8|false").append(nl);
        sb.append("scheme.TRIMAX_GENERIC=Trimax|Generic|||0.20|3.00|1.8|false").append(nl);
        sb.append("pdf.enabled=true").append(nl);
        sb.append("pdf.dir=").append(new File(dir, "PDF").getAbsolutePath()).append(nl);
        sb.append("archive.dir=").append(new File(dir, "Archive").getAbsolutePath()).append(nl);
        sb.append("# Render resolution for film sheets").append(nl);
        sb.append("film.dpi=300").append(nl);
        sb.append("# LUT profiles: lut.<name>.gamma (>1 brightens midtones), lut.<name>.invert").append(nl);
        sb.append("lut.default.gamma=1.0").append(nl);
        sb.append("lut.default.invert=false").append(nl);
        sb.append("# Optional per-calling-AE override: lutmap.<CALLING_AET>=<profile>").append(nl);
        sb.append("#lutmap.CT_CONSOLE=chest").append(nl);
        sb.append("# Optional TLS on DICOM associations (DICOM PS3.15). Requires a Java keystore").append(nl);
        sb.append("# with the server certificate: keytool -genkeypair -alias adlife -keyalg RSA").append(nl);
        sb.append("# -keysize 2048 -keystore adlife.jks -validity 3650").append(nl);
        sb.append("dicom.tls=false").append(nl);
        sb.append("dicom.tls.keystore=").append(nl);
        sb.append("dicom.tls.keystore.password=").append(nl);
        sb.append("dicom.tls.ciphers=TLS_RSA_WITH_AES_128_CBC_SHA").append(nl);
        sb.append("# Named DICOM destinations for viewer Send and C-MOVE:").append(nl);
        sb.append("# destinations=PACS1:PACS_AE:192.168.1.10:104, WORKSTATION2:AE2:192.168.1.11:105").append(nl);
        sb.append("destinations=").append(nl);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(sb.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    public List<Listener> listeners() {
        List<Listener> out = new ArrayList<Listener>();
        for (String part : p.getProperty("listeners", "ADLIFE:104:default").split(",")) {
            String[] f = part.trim().split(":");
            if (f.length >= 2 && f[0].length() > 0) {
                out.add(new Listener(f[0].trim(), Integer.parseInt(f[1].trim()),
                        f.length >= 3 ? f[2].trim() : "default"));
            }
        }
        return out;
    }

    public String callingAet() { return p.getProperty("calling.aet", "ADLIFE").trim(); }

    public List<String> printerNames() {
        List<String> out = new ArrayList<String>();
        for (String n : p.getProperty("printers", "").split(",")) {
            if (n.trim().length() > 0) out.add(n.trim());
        }
        return out;
    }

    public boolean roundRobin() { return Boolean.parseBoolean(p.getProperty("printer.roundrobin", "true")); }

    /** Scheme bound to a Windows printer name, else the global scheme.default, else null. */
    public com.adlife.dicom.print.PrinterScheme printerSchemeFor(String printerName) {
        if (printerName != null) {
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("printer.scheme.")
                        && key.substring("printer.scheme.".length()).equalsIgnoreCase(printerName.trim())) {
                    String schemeName = p.getProperty(key).trim();
                    com.adlife.dicom.print.PrinterScheme sc =
                            com.adlife.dicom.print.PrinterScheme.parse(schemeName, p.getProperty("scheme." + schemeName));
                    if (sc != null) return sc;
                }
            }
        }
        String def = p.getProperty("scheme.default", "").trim();
        if (!def.isEmpty())
            return com.adlife.dicom.print.PrinterScheme.parse(def, p.getProperty("scheme." + def));
        return null;
    }
    public boolean pdfEnabled() { return Boolean.parseBoolean(p.getProperty("pdf.enabled", "true")); }
    public File pdfDir() { return new File(p.getProperty("pdf.dir", new File(dir, "PDF").getAbsolutePath())); }
    public File archiveDir() { return new File(p.getProperty("archive.dir", new File(dir, "Archive").getAbsolutePath())); }
    public int filmDpi() { return Integer.parseInt(p.getProperty("film.dpi", "300").trim()); }

    public double lutGamma(String profile) {
        return Double.parseDouble(p.getProperty("lut." + profile + ".gamma", "1.0").trim());
    }

    public boolean lutInvert(String profile) {
        return Boolean.parseBoolean(p.getProperty("lut." + profile + ".invert", "false"));
    }

    /** LUT profile override keyed by the calling (modality) AE title; null if none. */
    public String lutProfileForCallingAet(String callingAet) {
        if (callingAet == null) return null;
        String v = p.getProperty("lutmap." + callingAet.trim());
        return (v == null || v.trim().length() == 0) ? null : v.trim();
    }

    public List<Destination> destinations() {
        List<Destination> out = new ArrayList<Destination>();
        for (String part : p.getProperty("destinations", "").split(",")) {
            String[] f = part.trim().split(":");
            if (f.length == 4) {
                out.add(new Destination(f[0].trim(), f[1].trim(), f[2].trim(), Integer.parseInt(f[3].trim())));
            }
        }
        return out;
    }

    public boolean tlsEnabled() { return Boolean.parseBoolean(p.getProperty("dicom.tls", "false")); }
    public String tlsKeystore() { return p.getProperty("dicom.tls.keystore", "").trim(); }
    public String tlsKeystorePassword() { return p.getProperty("dicom.tls.keystore.password", ""); }
    public String[] tlsCiphers() {
        String v = p.getProperty("dicom.tls.ciphers", "TLS_RSA_WITH_AES_128_CBC_SHA").trim();
        return v.isEmpty() ? new String[] { "TLS_RSA_WITH_AES_128_CBC_SHA" } : v.split("\\s*,\\s*");
    }

    public Destination destinationByAet(String aet) {
        for (Destination d : destinations()) {
            if (d.aeTitle.equalsIgnoreCase(aet) || d.name.equalsIgnoreCase(aet)) return d;
        }
        return null;
    }
}
