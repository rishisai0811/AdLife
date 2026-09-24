package com.adlife.dicom.pro;

import com.adlife.dicom.config.AdLifeConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Configuration backup / restore as one zip: adlife.properties, counters,
 * licence state and every logo / keystore file the settings point at.
 * "Rescue" = back up, then reset settings to factory defaults.
 */
public final class Backup {
    private Backup() {}

    private static final String[] FILE_KEYS = { "overlay.logo.header", "overlay.logo.footer", "dicom.tls.keystore" };

    public static File backup(AdLifeConfig cfg, File zip) throws Exception {
        if (zip == null) {
            File dir = new File(cfg.dir(), "Backups");
            dir.mkdirs();
            zip = new File(dir, "adlife-config-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".zip");
        }
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            add(z, cfg.file(), "adlife.properties");
            add(z, new File(cfg.dir(), "counters.properties"), "counters.properties");
            add(z, new File(cfg.dir(), "license.dat"), "license.dat");
            for (String k : FILE_KEYS) {
                String v = cfg.get(k, "");
                if (!v.isEmpty()) add(z, new File(v), "files/" + k + "/" + new File(v).getName());
            }
        }
        return zip;
    }

    private static void add(ZipOutputStream z, File f, String name) throws Exception {
        if (f == null || !f.isFile()) return;
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) { copy(in, z); }
        z.closeEntry();
    }

    /** Restores a backup; referenced files land in <AdLife>/Restored and the settings are repointed. */
    public static void restore(AdLifeConfig cfg, File zip) throws Exception {
        backup(cfg, null); // safety copy first
        File restored = new File(cfg.dir(), "Restored");
        restored.mkdirs();
        java.util.Map<String, File> files = new java.util.HashMap<String, File>();
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.contains("..")) continue;
                File out;
                if (n.equals("adlife.properties")) out = cfg.file();
                else if (n.equals("counters.properties") || n.equals("license.dat")) out = new File(cfg.dir(), n);
                else if (n.startsWith("files/")) {
                    String[] parts = n.split("/", 3);
                    out = new File(restored, parts[2]);
                    files.put(parts[1], out);
                } else continue;
                try (OutputStream o = new FileOutputStream(out)) { copy(z, o); }
            }
        }
        cfg.reload();
        for (java.util.Map.Entry<String, File> f : files.entrySet()) cfg.set(f.getKey(), f.getValue().getAbsolutePath());
        cfg.save();
    }

    /** Rescue: backup, then reset to defaults. Returns the backup written. */
    public static File rescue(AdLifeConfig cfg) throws Exception {
        File b = backup(cfg, null);
        if (!cfg.file().delete()) throw new java.io.IOException("cannot reset " + cfg.file());
        cfg.reload();
        return b;
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
