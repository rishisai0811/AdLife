package com.adlife.dicom.pro;

import com.adlife.dicom.config.AdLifeConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;

/**
 * Admin password protecting Settings, Restore, counter resets, licence and
 * kiosk exit (security.admin.hash / security.admin.salt; empty = no password).
 * security.protect.exit=true also asks before the tray Exit.
 */
public final class Security {
    private Security() {}

    public static boolean hasPassword(AdLifeConfig c) { return !c.get("security.admin.hash", "").isEmpty(); }

    public static void setPassword(AdLifeConfig c, String pw) {
        if (pw == null || pw.isEmpty()) { c.set("security.admin.hash", ""); c.set("security.admin.salt", ""); return; }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = Hex.printHexBinary(salt);
        c.set("security.admin.salt", s);
        c.set("security.admin.hash", hash(s, pw));
    }

    public static boolean check(AdLifeConfig c, String pw) {
        if (!hasPassword(c)) return true;
        return hash(c.get("security.admin.salt", ""), pw == null ? "" : pw).equals(c.get("security.admin.hash", ""));
    }

    static String hash(String salt, String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = (salt + "|" + pw).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 20000; i++) h = md.digest(h);
            return Hex.printHexBinary(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Asks for the admin password when one is set. */
    public static boolean ask(AdLifeConfig c, java.awt.Component parent, String what) {
        if (!hasPassword(c)) return true;
        JPasswordField f = new JPasswordField(16);
        int r = JOptionPane.showConfirmDialog(parent, new Object[] { "Admin password for " + what + ":", f },
                "AdLife admin", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return false;
        if (check(c, new String(f.getPassword()))) return true;
        JOptionPane.showMessageDialog(parent, "Wrong password.", "AdLife admin", JOptionPane.ERROR_MESSAGE);
        return false;
    }
}
