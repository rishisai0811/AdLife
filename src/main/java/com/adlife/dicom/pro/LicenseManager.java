package com.adlife.dicom.pro;

import com.adlife.dicom.config.AdLifeConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AdLife's own optional licensing / film metering. OFF by default and never
 * talks to any server: everything is offline and signed with the owner's own
 * RSA key (made with the Issuer tool), so only the owner can mint codes.
 *
 *   license.enabled=false            master switch
 *   license.public.key=              issuer public key (Base64), set by the Issuer tool
 *   license.metering=true            prepaid film counts per size (8X10 10X12 11X14 14X17 A4 A3)
 *   license.pdf.metering=false       also count PDFs
 *   license.require.key=false        machine-locked licence key required
 *   license.demo.days=0              demo period before a key is required (0 = none)
 *   license.remind.below=20          tray reminder when a count drops below this
 *   license.recharge.multiple=50     recharge codes must be multiples of this
 *
 * State lives in license.dat, sealed with an HMAC bound to this machine, so
 * copying the file to another PC or hand-editing counts zeroes them.
 */
public final class LicenseManager {
    public static final String[] BUCKETS = { "8X10", "10X12", "11X14", "14X17", "A4", "A3", "PDF" };

    private final AdLifeConfig cfg;
    private final File file;
    private final Properties st = new Properties();
    private boolean tampered;

    public LicenseManager(AdLifeConfig cfg) {
        this.cfg = cfg;
        this.file = new File(cfg.dir(), "license.dat");
        load();
    }

    public boolean enabled() { return cfg.getBool("license.enabled", false); }
    public boolean metering() { return cfg.getBool("license.metering", true); }

    // ---------------------------------------------------------------- state
    private synchronized void load() {
        st.clear();
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) { st.load(in); } catch (Exception ignored) {}
            String mac = st.getProperty("seal", "");
            st.remove("seal");
            if (!mac.equals(seal())) {
                tampered = true;
                for (String b : BUCKETS) st.setProperty("count." + b, "0");
            }
        }
        if (!st.containsKey("first_run")) st.setProperty("first_run", String.valueOf(System.currentTimeMillis()));
        save();
    }

    private synchronized void save() {
        Properties out = new Properties();
        out.putAll(st);
        out.setProperty("seal", seal());
        try (FileOutputStream o = new FileOutputStream(file)) { out.store(o, "AdLife licence state - do not edit"); } catch (Exception ignored) {}
    }

    private String seal() {
        try {
            List<String> keys = new ArrayList<String>(st.stringPropertyNames());
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder();
            for (String k : keys) sb.append(k).append('=').append(st.getProperty(k)).append('\n');
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("adlife|" + machineId()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.printHexBinary(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    public boolean wasTampered() { return tampered; }

    // ------------------------------------------------------------ machine id
    private static String cachedId;
    public static synchronized String machineId() {
        if (cachedId != null) return cachedId;
        List<String> macs = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                byte[] hw = ni.getHardwareAddress();
                if (hw == null || hw.length != 6 || ni.isLoopback() || ni.isVirtual()) continue;
                String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase();
                if (n.contains("virtual") || n.contains("vmware") || n.contains("vbox") || n.contains("docker")
                        || n.contains("veth") || n.contains("bluetooth") || n.contains("tap") || n.contains("tunnel")) continue;
                macs.add(Hex.printHexBinary(hw));
            }
        } catch (Exception ignored) {}
        Collections.sort(macs);
        String basis = macs.isEmpty() ? System.getProperty("user.name") + "|" + System.getenv("COMPUTERNAME") : macs.get(0);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("adlife-machine|" + basis).getBytes(StandardCharsets.UTF_8));
            String hex = Hex.printHexBinary(h).substring(0, 16);
            cachedId = hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16);
        } catch (Exception e) {
            cachedId = "0000-0000-0000-0000";
        }
        return cachedId;
    }

    // ------------------------------------------------------------- queries
    public synchronized int remaining(String bucket) {
        try { return Integer.parseInt(st.getProperty("count." + bucket, "0")); } catch (NumberFormatException e) { return 0; }
    }

    /** Null when printing is allowed, else the reason it is suspended. */
    public synchronized String blockReason(String bucket, boolean pdf) {
        if (!enabled()) return null;
        String clock = clockCheck();
        if (clock != null) return clock;
        if (cfg.getBool("license.require.key", false) && keyStatus() != null) {
            int days = cfg.getInt("license.demo.days", 0);
            long first = Long.parseLong(st.getProperty("first_run"));
            long left = first + days * 86400000L - System.currentTimeMillis();
            if (days <= 0 || left <= 0) return "Demo period expired - licence key required (" + keyStatus() + ")";
        }
        if (metering() && !pdf) {
            String b = "OTHER".equals(bucket) ? cfg.get("license.other.bucket", "14X17") : bucket;
            if (remaining(b) <= 0) return "Print count " + b + " = 0. Print suspended - recharge required.";
        }
        if (pdf && cfg.getBool("license.pdf.metering", false) && remaining("PDF") <= 0)
            return "PDF count = 0. Recharge required.";
        return null;
    }

    /** Detects the PC clock being set back (a common way to stretch demos). */
    private String clockCheck() {
        long now = System.currentTimeMillis();
        long last = 0;
        try { last = Long.parseLong(st.getProperty("last_seen", "0")); } catch (NumberFormatException ignored) {}
        if (last > 0 && now < last - 15 * 60000L) return "System date changed - licence blocked until the clock is corrected";
        if (now > last) { st.setProperty("last_seen", String.valueOf(now)); save(); }
        return null;
    }

    public synchronized void consume(String bucket, int copies, boolean pdf) {
        if (!enabled()) return;
        if (pdf) {
            if (cfg.getBool("license.pdf.metering", false)) dec("PDF", 1);
            return;
        }
        if (!metering()) return;
        String b = "OTHER".equals(bucket) ? cfg.get("license.other.bucket", "14X17") : bucket;
        dec(b, Math.max(1, copies));
        int left = remaining(b);
        if (left < cfg.getInt("license.remind.below", 20))
            Notifier.warn("AdLife print count", "Print count " + b + " left: " + left + ". Please recharge.");
    }

    private void dec(String b, int n) {
        st.setProperty("count." + b, String.valueOf(Math.max(0, remaining(b) - n)));
        save();
    }

    public synchronized void resetCounts(String... buckets) {
        for (String b : buckets) st.setProperty("count." + b, "0");
        save();
    }

    // ------------------------------------------------------------- codes
    public synchronized String applyCode(String code) {
        String[] parts = parse(code);
        if (parts == null) return "Invalid code.";
        if (!parts[1].equals(machineId())) return "This code was made for another PC (" + parts[1] + ").";
        if ("RC".equals(parts[0])) {
            if (parts.length < 5) return "Invalid code.";
            if (st.containsKey("used." + parts[4])) return "This recharge code was already used.";
            String bucket = parts[2];
            int n = Integer.parseInt(parts[3]);
            int prev = remaining(bucket);
            st.setProperty("count." + bucket, String.valueOf(prev + n));
            st.setProperty("used." + parts[4], new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()));
            save();
            return "Film " + bucket + " recharge success. Previous balance = " + prev + ", recharged = " + n
                    + ", total available = " + (prev + n) + ".";
        }
        if ("LK".equals(parts[0])) {
            st.setProperty("key", code.trim());
            save();
            String s = keyStatus();
            return s == null ? "Licence key accepted (" + parts[3] + ", valid until " + parts[2] + ")." : "Licence key rejected: " + s;
        }
        return "Unknown code type.";
    }

    /** Null when a valid machine-locked key is installed, else why not. */
    public synchronized String keyStatus() {
        String k = st.getProperty("key");
        if (k == null) return "no licence key installed";
        String[] p = parse(k);
        if (p == null || !"LK".equals(p[0])) return "licence key invalid";
        if (!p[1].equals(machineId())) return "licence key belongs to another PC";
        if (!"never".equals(p[2])) {
            try {
                Date exp = new SimpleDateFormat("yyyy-MM-dd").parse(p[2]);
                if (System.currentTimeMillis() > exp.getTime() + 86400000L) return "licence key expired on " + p[2];
            } catch (Exception e) { return "licence key invalid"; }
        }
        return null;
    }

    public synchronized String keyHolder() {
        String[] p = st.getProperty("key") == null ? null : parse(st.getProperty("key"));
        return p == null || p.length < 4 ? "" : p[3];
    }

    public synchronized long demoDaysLeft() {
        int days = cfg.getInt("license.demo.days", 0);
        long first = Long.parseLong(st.getProperty("first_run"));
        return Math.max(0, (first + days * 86400000L - System.currentTimeMillis() + 86399999L) / 86400000L);
    }

    private String[] parse(String code) {
        try {
            String c = code.trim().replaceAll("\\s+", "");
            if (!c.startsWith("ADL1.")) return null;
            String[] f = c.substring(5).split("\\.");
            if (f.length != 2) return null;
            byte[] payload = b64d(f[0]), sig = b64d(f[1]);
            String pub = cfg.get("license.public.key", "");
            if (pub.isEmpty()) return null;
            PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Hex.parseBase64Binary(pub)));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(pk);
            s.update(payload);
            if (!s.verify(sig)) return null;
            return new String(payload, StandardCharsets.UTF_8).split(";");
        } catch (Exception e) {
            return null;
        }
    }

    // --------------------------------------------------------------- issuer
    /** Creates an issuer key pair; writes the private key to privFile, returns the public key (Base64). */
    public static String createIssuerKeys(File privFile) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(privFile)) {
            o.write(Hex.printBase64Binary(kp.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII));
        }
        return Hex.printBase64Binary(kp.getPublic().getEncoded());
    }

    public static String issueRecharge(File privFile, String machineId, String bucket, int count) throws Exception {
        String serial = Long.toString(System.currentTimeMillis(), 36) + Long.toString((long) (Math.random() * 1e6), 36);
        return sign(privFile, "RC;" + machineId.trim().toUpperCase() + ";" + bucket + ";" + count + ";" + serial);
    }

    public static String issueKey(File privFile, String machineId, String expiry, String holder) throws Exception {
        return sign(privFile, "LK;" + machineId.trim().toUpperCase() + ";" + (expiry == null || expiry.isEmpty() ? "never" : expiry)
                + ";" + holder.replace(";", ","));
    }

    private static String sign(File privFile, String payload) throws Exception {
        byte[] der;
        try (FileInputStream in = new FileInputStream(privFile)) {
            byte[] buf = new byte[(int) privFile.length()];
            int n = 0;
            while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; }
            der = Hex.parseBase64Binary(new String(buf, StandardCharsets.US_ASCII).trim());
        }
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(pk);
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        s.update(p);
        return "ADL1." + b64e(p) + "." + b64e(s.sign());
    }

    private static String b64e(byte[] b) {
        return Hex.printBase64Binary(b).replace('+', '-').replace('/', '_').replace("=", "");
    }

    private static byte[] b64d(String s) {
        String t = s.replace('-', '+').replace('_', '/');
        while (t.length() % 4 != 0) t += "=";
        return Hex.parseBase64Binary(t);
    }
}
