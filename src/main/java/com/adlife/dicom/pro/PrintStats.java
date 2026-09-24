package com.adlife.dicom.pro;

import com.adlife.dicom.config.AdLifeConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Free print counters (always on, independent of licensing) and the CSV print
 * log: Logs/print-log.csv  time,source AE,listener AE,film,printer,copies,status.
 */
public final class PrintStats {
    private final AdLifeConfig cfg;
    private final File file;
    private final Properties p = new Properties();

    public PrintStats(AdLifeConfig cfg) {
        this.cfg = cfg;
        this.file = new File(cfg.dir(), "counters.properties");
        try (FileInputStream in = new FileInputStream(file)) { p.load(in); } catch (Exception ignored) {}
    }

    public synchronized void count(String bucket, String printer, int copies) {
        add("film." + bucket, copies);
        add("printer." + printer, copies);
        add("total", copies);
        save();
    }

    public synchronized void countPdf() { add("pdf", 1); save(); }

    private void add(String k, int n) {
        long v = 0;
        try { v = Long.parseLong(p.getProperty(k, "0")); } catch (NumberFormatException ignored) {}
        p.setProperty(k, String.valueOf(v + n));
    }

    public synchronized TreeMap<String, String> snapshot() {
        TreeMap<String, String> m = new TreeMap<String, String>();
        for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
        return m;
    }

    public synchronized void reset() {
        p.clear();
        p.setProperty("since", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        save();
    }

    private void save() {
        if (!p.containsKey("since")) p.setProperty("since", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
        try (FileOutputStream out = new FileOutputStream(file)) { p.store(out, "AdLife print counters"); } catch (Exception ignored) {}
    }

    public synchronized void log(String src, String called, String film, String printer, int copies, String status) {
        File dir = cfg.logsDir();
        dir.mkdirs();
        File f = new File(dir, "print-log.csv");
        boolean header = !f.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
            if (header) w.println("time,source_ae,listener_ae,film,printer,copies,status");
            w.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "," + csv(src) + "," + csv(called)
                    + "," + csv(film) + "," + csv(printer) + "," + copies + "," + csv(status));
        } catch (Exception ignored) {}
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
