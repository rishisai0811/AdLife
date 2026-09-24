package com.adlife.dicom.store;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory index over the archive folder. Rebuilt by scanning at startup;
 * small-center scale makes a scan cheap and keeps the index always correct.
 * Instance-level records; study-level responses are aggregated on demand.
 */
public class ArchiveIndex {

    private static final Logger LOG = LoggerFactory.getLogger(ArchiveIndex.class);

    public static class Record {
        public final Attributes attrs;   // full dataset header (no bulk data)
        public final File file;
        public Record(Attributes attrs, File file) {
            this.attrs = attrs;
            this.file = file;
        }
    }

    private final List<Record> records = new ArrayList<Record>();

    public synchronized void add(Attributes attrs, File file) {
        records.add(new Record(attrs, file));
    }

    public synchronized List<Record> all() {
        return new ArrayList<Record>(records);
    }

    public synchronized int size() { return records.size(); }

    /** Scan an archive tree (study/series/instance.dcm) and index everything readable. */
    public void scan(File archiveDir) {
        if (archiveDir == null || !archiveDir.isDirectory()) return;
        int before = size();
        scanDir(archiveDir, 0);
        LOG.info("Archive scan: {} instance(s) indexed ({} new)", size(), size() - before);
    }

    private void scanDir(File dir, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 4) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                scanDir(f, depth + 1);
            } else if (f.getName().toLowerCase().endsWith(".dcm")) {
                try {
                    DicomInputStream dis = new DicomInputStream(f);
                    try {
                        dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);
                        Attributes ds = dis.readDataset(-1, Tag.PixelData);
                        add(ds, f);
                    } finally {
                        dis.close();
                    }
                } catch (IOException e) {
                    LOG.debug("Skipping unreadable file {}: {}", f, e.toString());
                }
            }
        }
    }

    /**
     * Simple study-root matching: every key in the query must match.
     * Empty value = universal (return attribute in response);
     * '*'/'?' wildcards on strings; exact otherwise.
     */
    public synchronized List<Record> query(Attributes keys, String level) {
        List<Record> out = new ArrayList<Record>();
        List<Record> snapshot = all();
        for (Record r : snapshot) {
            if (matches(r.attrs, keys)) {
                if ("STUDY".equalsIgnoreCase(level)) {
                    if (!containsStudy(out, r.attrs.getString(Tag.StudyInstanceUID))) out.add(r);
                } else {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static boolean containsStudy(List<Record> list, String studyUid) {
        for (Record r : list) {
            String s = r.attrs.getString(Tag.StudyInstanceUID);
            if (studyUid == null ? s == null : studyUid.equals(s)) return true;
        }
        return false;
    }

    private static boolean matches(Attributes ds, Attributes keys) {
        for (int tag : keys.tags()) {
            if (tag == Tag.QueryRetrieveLevel) continue;
            String want = keys.getString(tag);
            if (want == null || want.length() == 0) continue;
            String have = ds.getString(tag);
            if (have == null) return false;
            if (!wildcardMatch(want.toUpperCase(), have.toUpperCase())) return false;
        }
        return true;
    }

    private static boolean wildcardMatch(String pattern, String value) {
        StringBuilder rx = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') rx.append(".*");
            else if (c == '?') rx.append('.');
            else rx.append(java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return value.matches(rx.toString());
    }
}
