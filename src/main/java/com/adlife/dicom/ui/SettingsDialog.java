package com.adlife.dicom.ui;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.pro.Backup;
import com.adlife.dicom.pro.KioskMode;
import com.adlife.dicom.pro.Security;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * Settings screen. Every AdLife setting lives in adlife.properties; each tab is
 * an editable table of the keys for that area (key, value, what it does). Extra
 * keys (for example route.calling.CT1 or p2.14INX17IN.offx.mm) can be added
 * with "Add key". Save writes the file, keeps a backup and restarts listeners.
 */
public class SettingsDialog extends JDialog {
    private final AdLifeConfig cfg;
    private final Runnable restart;
    private final List<DefaultTableModel> models = new ArrayList<DefaultTableModel>();

    public SettingsDialog(AdLifeConfig cfg, Runnable restart) {
        super((java.awt.Frame) null, "AdLife Settings", true);
        this.cfg = cfg;
        this.restart = restart;
        JTabbedPane tabs = new JTabbedPane();
        for (Map.Entry<String, String[][]> e : sections().entrySet()) {
            tabs.addTab(e.getKey(), tab(e.getKey(), e.getValue()));
        }
        getContentPane().add(tabs, BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(button("Save && Restart Listeners", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { save(); }
        }));
        south.add(button("Close", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { dispose(); }
        }));
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(900, 560);
        setLocationRelativeTo(null);
    }

    private static JButton button(String t, ActionListener l) {
        JButton b = new JButton(t.replace("&&", "&"));
        b.addActionListener(l);
        return b;
    }

    static Map<String, String[][]> sections() {
        Map<String, String[][]> m = new LinkedHashMap<String, String[][]>();
        m.put("Listeners", new String[][] {
            { "listeners", "Up to 6 listeners: AE:PORT[:lutProfile], comma separated" },
            { "listen.any.aet", "true = accept any Called AE title on every port" },
            { "listen.allow.calling", "Only these calling AEs may connect (empty = everyone)" },
            { "calling.aet", "Our AE title when sending (DICOM forward, C-MOVE)" },
            { "dicom.tls", "true = DICOM over TLS" },
        });
        List<String[]> pr = new ArrayList<String[]>();
        pr.add(new String[] { "printers", "Up to 6 outputs, comma separated: Windows printer name, dicom:AET@host:port or folder:C:\\path" });
        pr.add(new String[] { "printer.roundrobin", "true = share jobs across idle printers" });
        for (int i = 1; i <= 6; i++) {
            pr.add(new String[] { "p" + i + ".style", "Printer " + i + " layout: normal (keep aspect), stretch (fill cell), scatter (gaps), bordered (frame per image)" });
            pr.add(new String[] { "p" + i + ".brightness", "-100..100" });
            pr.add(new String[] { "p" + i + ".contrast", "-100..100" });
            pr.add(new String[] { "p" + i + ".darkfactor", "0.5..2.0 (1 = unchanged, above 1 darker)" });
            pr.add(new String[] { "p" + i + ".soften", "0..3" });
            pr.add(new String[] { "p" + i + ".sharpen", "0..3" });
            pr.add(new String[] { "p" + i + ".invert", "true/false" });
            pr.add(new String[] { "p" + i + ".color", "auto, color or grey" });
            pr.add(new String[] { "p" + i + ".overlay", "Header/footer on this printer (true/false)" });
            pr.add(new String[] { "p" + i + ".dicom.mode", "For dicom: printers - sheet (send finished film) or passthrough" });
        }
        m.put("Printers", pr.toArray(new String[0][]));
        m.put("Routing", new String[][] {
            { "route.all", "Send every job to printer N (0 = off)" },
            { "route.called.ADLIFE", "Jobs arriving on listener AE ADLIFE go to printer N" },
            { "route.calling.CT", "Jobs from console AE CT go to printer N" },
            { "route.size.14INX17IN", "Film size 14INX17IN goes to printer N" },
            { "route.color", "Colour jobs go to printer N" },
            { "route.grey", "Greyscale jobs go to printer N" },
            { "mono.aets", "Consoles whose jobs are always printed greyscale" },
            { "plut.ignore.aets", "Consoles whose Presentation LUT is ignored" },
        });
        m.put("Film sizes", new String[][] {
            { "film.sizes.enabled", "Sizes offered (A4, A3, B4, LETTER, 8INX10IN, 10INX12IN, 11INX14IN, 14INX17IN, MANUAL ...)" },
            { "film.size.default", "Used when the console sends no size" },
            { "film.size.fallback", "Used when the size is not enabled" },
            { "film.map.14INX17IN", "Print this received size as another size (e.g. A3)" },
            { "film.manual.width.in", "MANUAL width in inches" },
            { "film.manual.height.in", "MANUAL height in inches" },
            { "film.dpi", "Raster resolution for Windows printers" },
        });
        m.put("Calibration", new String[][] {
            { "p1.offx.mm", "Printer 1 shift right (mm, negative = left)" },
            { "p1.offy.mm", "Printer 1 shift down (mm)" },
            { "p1.scalex", "Printer 1 horizontal scale (1.0 = none)" },
            { "p1.scaley", "Printer 1 vertical scale" },
            { "p1.margins", "fit (inside printable area) or full (edge to edge)" },
            { "p1.paper", "Paper name for printer 1 (e.g. A3, B4)" },
            { "p1.tray", "Tray for printer 1 (e.g. Tray 2, Manual)" },
            { "p1.A3.paper", "Per-size override: paper for A3 films" },
            { "p1.A3.tray", "Per-size override: tray for A3 films" },
            { "p1.A3.offx.mm", "Per-size override: shift for A3 films" },
            { "p1.border.mm", "Outer border (mm)" },
            { "p1.gap.mm", "Gap between images (mm)" },
            { "p1.background", "white or black film background" },
        });
        m.put("Header / Footer", new String[][] {
            { "overlay.enabled", "Print header/footer on every film" },
            { "overlay.header1", "Header line 1 ({aet} {printer} {film} {date} {time} tokens)" },
            { "overlay.header2", "Header line 2" },
            { "overlay.footer1", "Footer line 1" },
            { "overlay.footer2", "Footer line 2" },
            { "overlay.header.align", "left, center, right" },
            { "overlay.footer.align", "left, center, right" },
            { "overlay.header.font", "Font size in pt" },
            { "overlay.footer.font", "Font size in pt" },
            { "overlay.header.color", "Hex colour, e.g. #FFFFFF" },
            { "overlay.footer.color", "Hex colour" },
            { "overlay.band.color", "Band background colour" },
            { "overlay.band.header.mm", "Header band height" },
            { "overlay.band.footer.mm", "Footer band height" },
            { "overlay.logo.header", "Logo image for header (PNG/JPG path)" },
            { "overlay.logo.footer", "Logo image for footer" },
            { "overlay.logo.position", "left or right" },
            { "overlay.brand", "Small brand text in the footer corner" },
            { "overlay.qr", "Text/URL for a QR code in the footer (empty = none)" },
        });
        m.put("PDF", new String[][] {
            { "pdf.enabled", "Save every film as PDF" },
            { "pdf.dir", "PDF folder" },
            { "pdf.compress", "Smaller PDFs" },
            { "pdf.quality", "JPEG quality when compressing (0.3..1.0)" },
            { "pdf.dpi", "Max resolution when compressing" },
            { "pdf.grey", "Greyscale JPEG for mono films" },
            { "jobs.keep.days", "Keep films for reprint this many days" },
        });
        m.put("Licence", new String[][] {
            { "license.enabled", "Your own optional licensing (off by default; nothing is sent anywhere)" },
            { "license.metering", "Count films against recharged balances" },
            { "license.pdf.metering", "Also count PDFs" },
            { "license.require.key", "Require a machine key (demo period first)" },
            { "license.demo.days", "Demo days before a key is needed" },
            { "license.remind.below", "Warn when a balance falls below this" },
            { "license.public.key", "Issuer public key (Base64)" },
        });
        m.put("Kiosk", new String[][] {
            { "kiosk.enabled", "Start in full-screen kiosk window" },
            { "kiosk.shell", "Also replace Explorer with AdLife for this Windows user" },
            { "kiosk.exe", "Program used as shell (default installed AdLife.exe)" },
        });
        return m;
    }

    private JPanel tab(final String name, String[][] rows) {
        final DefaultTableModel model = new DefaultTableModel(new Object[] { "Setting", "Value", "What it does" }, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c == 1 || (c == 0 && "".equals(getValueAt(r, 2))); }
        };
        for (String[] r : rows) model.addRow(new Object[] { r[0], cfg.get(r[0], ""), r[1] });
        models.add(model);
        JTable table = new JTable(model);
        table.getColumnModel().getColumn(0).setPreferredWidth(190);
        table.getColumnModel().getColumn(1).setPreferredWidth(230);
        table.getColumnModel().getColumn(2).setPreferredWidth(460);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(button("Add key", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                String k = JOptionPane.showInputDialog(SettingsDialog.this, "Setting name (e.g. route.calling.MRI)");
                if (k != null && !k.trim().isEmpty()) model.addRow(new Object[] { k.trim(), cfg.get(k.trim(), ""), "" });
            }
        }));
        if ("Printers".equals(name)) {
            bar.add(button("Add printer...", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    PrinterDialog.Choice c = PrinterDialog.showDialog(SettingsDialog.this, cfg);
                    if (c != null) mergePrinter(model, c);
                }
            }));
            bar.add(button("What is configured now?", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    showConfiguredPrinters();
                }
            }));
        }
        if ("Licence".equals(name)) {
            bar.add(button("Licence / Recharge...", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { LicenseDialog.open(); }
            }));
        }
        if ("Kiosk".equals(name)) {
            bar.add(button("Apply Windows lock now", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { confirmLock(); }
            }));
            bar.add(button("Remove Windows lock", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { report(KioskMode.unlock(cfg), "Kiosk unlock"); }
            }));
        }
        if ("Listeners".equals(name)) {
            bar.add(button("Admin password...", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { changePassword(); }
            }));
            bar.add(button("Backup...", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { backup(); }
            }));
            bar.add(button("Restore...", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { restore(); }
            }));
            bar.add(button("Rescue (factory settings)", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { rescue(); }
            }));
        }
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    /** Asks before locking Windows down; replacing Explorer needs a second, explicit yes. */
    private void confirmLock() {
        String msg = "This applies Windows lock policies for the current Windows user\n"
                + "(no Run box, no Win key, no log off, hidden desktop icons).\n"
                + "Use it only on a dedicated print PC. Remove it with \"Remove Windows lock\".\n\nApply now?";
        if (JOptionPane.showConfirmDialog(this, msg, "Kiosk lock", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                != JOptionPane.YES_OPTION) return;
        if (cfg.getBool("kiosk.shell", false)) {
            String shell = "kiosk.shell=true: AdLife will REPLACE the Windows desktop (Explorer)\n"
                    + "for this user from the next logon. There will be no taskbar or Start menu.\n\n"
                    + "To get the desktop back: AdLife > Exit kiosk (admin password), or\n"
                    + "Ctrl+Alt+Del > Task Manager > File > Run new task > explorer.exe.\n\n"
                    + "Replace the Windows desktop with AdLife?";
            if (JOptionPane.showConfirmDialog(this, shell, "Replace Windows desktop?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                    != JOptionPane.YES_OPTION) {
                cfg.set("kiosk.shell", "false");
            }
        }
        report(KioskMode.lock(cfg), "Kiosk lock");
    }

    private void report(List<String[]> cmds, String what) {
        String msg = KioskMode.isWindows() ? what + " applied (" + cmds.size() + " policy values). Takes full effect at next logon."
                : what + ": Windows only; nothing changed on this system.";
        JOptionPane.showMessageDialog(this, msg);
    }

    /** Append the wizard's entry to the printers row and add its per-printer keys. */
    private void mergePrinter(DefaultTableModel model, PrinterDialog.Choice c) {
        int printersRow = -1;
        String current = "";
        for (int r = 0; r < model.getRowCount(); r++) {
            if ("printers".equals(String.valueOf(model.getValueAt(r, 0)).trim())) {
                printersRow = r;
                current = String.valueOf(model.getValueAt(r, 1) == null ? "" : model.getValueAt(r, 1)).trim();
                break;
            }
        }
        List<String> entries = new ArrayList<String>();
        for (String e2 : current.split(",")) if (!e2.trim().isEmpty()) entries.add(e2.trim());
        if (entries.size() >= 6) {
            JOptionPane.showMessageDialog(this, "All 6 printer slots are in use. Remove one from the printers row first.");
            return;
        }
        entries.add(c.entry);
        int slot = entries.size();
        StringBuilder sb = new StringBuilder();
        for (String e2 : entries) { if (sb.length() > 0) sb.append(','); sb.append(e2); }
        if (printersRow >= 0) model.setValueAt(sb.toString(), printersRow, 1);
        else model.addRow(new Object[] { "printers", sb.toString(), "" });
        for (Map.Entry<String, String> prop : c.props.entrySet()) {
            String key = "p" + slot + "." + prop.getKey();
            boolean found = false;
            for (int r = 0; r < model.getRowCount(); r++) {
                if (key.equals(String.valueOf(model.getValueAt(r, 0)).trim())) {
                    model.setValueAt(prop.getValue(), r, 1);
                    found = true;
                    break;
                }
            }
            if (!found) model.addRow(new Object[] { key, prop.getValue(), "" });
        }
        JOptionPane.showMessageDialog(this, "Added as printer " + slot + ": " + c.entry
                + "\nPress Save && Restart Listeners to make it live.".replace("&&", "&"));
    }

    /** Show how the saved printers list resolves on this PC right now. */
    private void showConfiguredPrinters() {
        String text;
        try {
            com.adlife.dicom.pro.Services svc = com.adlife.dicom.pro.Services.current();
            text = svc != null ? svc.router.statusText() : "not available";
        } catch (Throwable t) {
            text = "not available: " + t;
        }
        JOptionPane.showMessageDialog(this, "Printers as AdLife sees them now:\n" + text);
    }

    private void save() {
        for (DefaultTableModel m : models) {
            for (int r = 0; r < m.getRowCount(); r++) {
                String k = String.valueOf(m.getValueAt(r, 0)).trim();
                String v = String.valueOf(m.getValueAt(r, 1) == null ? "" : m.getValueAt(r, 1)).trim();
                if (k.isEmpty()) continue;
                if (v.isEmpty() && cfg.get(k, "").isEmpty()) continue;
                cfg.set(k, v);
            }
        }
        try {
            Backup.backup(cfg, null);
            cfg.save();
            if (restart != null) restart.run();
            JOptionPane.showMessageDialog(this, "Saved. Listeners restarted.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex, "AdLife", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void changePassword() {
        if (Security.hasPassword(cfg) && !Security.ask(cfg, this, "change the admin password")) return;
        JPasswordField a = new JPasswordField(), b = new JPasswordField();
        Object[] f = { "New password (empty = no password)", a, "Repeat", b };
        if (JOptionPane.showConfirmDialog(this, f, "Admin password", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String p1 = new String(a.getPassword()), p2 = new String(b.getPassword());
        if (!p1.equals(p2)) { JOptionPane.showMessageDialog(this, "Passwords do not match."); return; }
        Security.setPassword(cfg, p1);
        try { cfg.save(); } catch (Exception ignored) {}
        JOptionPane.showMessageDialog(this, p1.isEmpty() ? "Password removed." : "Password set.");
    }

    private void backup() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("AdLife-backup.zip"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            File z = Backup.backup(cfg, fc.getSelectedFile());
            JOptionPane.showMessageDialog(this, "Backup saved: " + z);
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Backup failed: " + ex); }
    }

    private void restore() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Backup.restore(cfg, fc.getSelectedFile());
            if (restart != null) restart.run();
            JOptionPane.showMessageDialog(this, "Restored. Reopen Settings to see the restored values.");
            dispose();
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Restore failed: " + ex); }
    }

    private void rescue() {
        if (JOptionPane.showConfirmDialog(this, "Reset all settings to factory defaults? A backup is kept first.",
                "Rescue", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            File z = Backup.rescue(cfg);
            if (restart != null) restart.run();
            JOptionPane.showMessageDialog(this, "Factory settings restored. Previous settings saved in " + z);
            dispose();
        } catch (Exception ex) { JOptionPane.showMessageDialog(this, "Rescue failed: " + ex); }
    }
}
