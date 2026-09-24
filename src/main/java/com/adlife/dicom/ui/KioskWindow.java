package com.adlife.dicom.ui;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.dcm.DicomServers;
import com.adlife.dicom.pro.JobStore;
import com.adlife.dicom.pro.KioskMode;
import com.adlife.dicom.pro.Security;
import com.adlife.dicom.pro.Services;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Full-screen, always-on-top operator screen for a dedicated print PC. Shows
 * listener status, today's counters and recent films; offers Reprint. Leaving
 * kiosk mode (or opening settings) needs the admin password.
 */
public class KioskWindow extends JFrame {
    private final AdLifeConfig cfg;
    private final DicomServers servers;
    private final JLabel status = new JLabel();
    private final JTextArea recent = new JTextArea();

    public KioskWindow(final AdLifeConfig cfg, DicomServers servers, final Runnable openSettings) {
        super("AdLife");
        this.cfg = cfg;
        this.servers = servers;
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowIconified(WindowEvent e) { setExtendedState(NORMAL); toFront(); }
            @Override public void windowDeactivated(WindowEvent e) { toFront(); }
        });
        JPanel root = new JPanel(new BorderLayout(20, 20));
        root.setBackground(new Color(20, 28, 40));
        root.setBorder(BorderFactory.createEmptyBorder(30, 40, 30, 40));
        JLabel title = new JLabel("AdLife DICOM Print");
        title.setForeground(Color.WHITE);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        status.setForeground(new Color(160, 220, 170));
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(title, BorderLayout.NORTH);
        top.add(status, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        recent.setEditable(false);
        recent.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        recent.setBackground(new Color(30, 40, 56));
        recent.setForeground(Color.WHITE);
        root.add(new JScrollPane(recent), BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        bar.setOpaque(false);
        bar.add(big("Reprint", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                JobsWindow w = new JobsWindow();
                w.setAlwaysOnTop(true);
                w.setVisible(true);
            }
        }));
        bar.add(big("Settings", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (Security.ask(cfg, KioskWindow.this, "open settings")) { setAlwaysOnTop(false); openSettings.run(); setAlwaysOnTop(true); }
            }
        }));
        bar.add(big("Exit kiosk", new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!Security.ask(cfg, KioskWindow.this, "leave kiosk mode")) return;
                KioskMode.unlock(cfg);
                dispose();
            }
        }));
        root.add(bar, BorderLayout.SOUTH);
        setContentPane(root);
        new Timer(3000, new ActionListener() { @Override public void actionPerformed(ActionEvent e) { refresh(); } }).start();
        refresh();
    }

    private static JButton big(String t, ActionListener l) {
        JButton b = new JButton(t);
        b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        b.addActionListener(l);
        return b;
    }

    private void refresh() {
        status.setText(servers.statusText());
        Services svc = Services.current();
        StringBuilder sb = new StringBuilder();
        if (svc != null) {
            sb.append("Counters: ");
            for (Map.Entry<String, String> e : svc.stats.snapshot().entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append("  ");
            sb.append("\n\nRecent films:\n");
            List<JobStore.Job> jobs = svc.jobs.list(1);
            for (int i = 0; i < Math.min(25, jobs.size()); i++) sb.append("  ").append(jobs.get(i).title()).append('\n');
        }
        String s = sb.toString();
        if (!s.equals(recent.getText())) recent.setText(s);
    }

    public void showFullScreen() {
        if (cfg.getBool("kiosk.enabled", false)) KioskMode.lock(cfg);
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(this);
        setVisible(true);
    }
}
