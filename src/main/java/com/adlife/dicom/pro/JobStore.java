package com.adlife.dicom.pro;

import com.adlife.dicom.config.AdLifeConfig;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import javax.imageio.ImageIO;

/**
 * Keeps every finished film (PNG + job facts) under Jobs/yyyy-MM-dd/ so it can
 * be reprinted, re-routed or re-exported later. jobs.keep.days (default 30,
 * 0 = forever) prunes old days at start-up.
 */
public final class JobStore {
    public static final class Job {
        public final File png, meta;
        public final Properties p;
        Job(File png, File meta, Properties p) { this.png = png; this.meta = meta; this.p = p; }
        public String get(String k) { return p.getProperty(k, ""); }
        public String title() {
            return get("time") + "  " + get("film") + "  from " + get("aet") + "  -> " + get("printer")
                    + ("SUSPENDED".equals(get("status")) ? "  [suspended]" : "") ;
        }
        public BufferedImage image() {
            try { return ImageIO.read(png); } catch (Exception e) { return null; }
        }
    }

    private final AdLifeConfig cfg;
    public JobStore(AdLifeConfig cfg) { this.cfg = cfg; }

    public Job save(BufferedImage sheet, Properties facts) {
        try {
            Date now = new Date();
            File dir = new File(cfg.jobsDir(), new SimpleDateFormat("yyyy-MM-dd").format(now));
            dir.mkdirs();
            String base = new SimpleDateFormat("HHmmss_SSS").format(now) + "_"
                    + facts.getProperty("aet", "JOB").replaceAll("[^A-Za-z0-9_-]", "_");
            File png = new File(dir, base + ".png");
            File meta = new File(dir, base + ".properties");
            ImageIO.write(sheet, "png", png);
            facts.setProperty("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(now));
            write(meta, facts);
            return new Job(png, meta, facts);
        } catch (Exception e) {
            return null;
        }
    }

    public void update(Job j) {
        try { write(j.meta, j.p); } catch (Exception ignored) {}
    }

    private static void write(File f, Properties p) throws java.io.IOException {
        FileOutputStream out = new FileOutputStream(f);
        try { p.store(out, "AdLife job"); } finally { out.close(); }
    }

    public List<Job> list(int maxDays) {
        List<Job> out = new ArrayList<Job>();
        File[] days = cfg.jobsDir().listFiles();
        if (days == null) return out;
        Arrays.sort(days, Collections.reverseOrder());
        int d = 0;
        for (File day : days) {
            if (!day.isDirectory()) continue;
            if (maxDays > 0 && d++ >= maxDays) break;
            File[] metas = day.listFiles((dir, name) -> name.endsWith(".properties"));
            if (metas == null) continue;
            Arrays.sort(metas, Comparator.comparing(File::getName).reversed());
            for (File m : metas) {
                File png = new File(m.getParentFile(), m.getName().replace(".properties", ".png"));
                if (!png.isFile()) continue;
                Properties p = new Properties();
                try (FileInputStream in = new FileInputStream(m)) { p.load(in); } catch (Exception e) { continue; }
                out.add(new Job(png, m, p));
            }
        }
        return out;
    }

    public void delete(Job j) { j.png.delete(); j.meta.delete(); }

    public int prune() {
        int keep = cfg.getInt("jobs.keep.days", 30);
        if (keep <= 0) return 0;
        long cutoff = System.currentTimeMillis() - keep * 86400000L;
        int n = 0;
        File[] days = cfg.jobsDir().listFiles();
        if (days == null) return 0;
        for (File day : days) {
            try {
                Date d = new SimpleDateFormat("yyyy-MM-dd").parse(day.getName());
                if (d.getTime() < cutoff) {
                    File[] fs = day.listFiles();
                    if (fs != null) for (File f : fs) f.delete();
                    day.delete();
                    n++;
                }
            } catch (Exception ignored) {}
        }
        return n;
    }
}
