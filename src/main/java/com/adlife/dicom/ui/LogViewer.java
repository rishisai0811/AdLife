package com.adlife.dicom.ui;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.pro.PrintStats;
import com.adlife.dicom.pro.Services;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

/** Print log (Logs/print-log.csv), counters and the application log, with a filter. */
public class LogViewer extends JFrame {
    private final AdLifeConfig cfg;
    private final JTextField filter = new JTextField(20);
    private final DefaultTableModel prints = new DefaultTableModel();
    private final DefaultTableModel counters = new DefaultTableModel(new Object[] { "Counter", "Value" }, 0);
    private final JTextArea app = new JTextArea();

    public LogViewer(AdLifeConfig cfg) {
        super("AdLife - Logs");
        this.cfg = cfg;
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Print log", new JScrollPane(new JTable(prints)));
        tabs.addTab("Counters", new JScrollPane(new JTable(counters)));
        app.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        app.setEditable(false);
        tabs.addTab("Application log", new JScrollPane(app));
        getContentPane().add(tabs, BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Filter"));
        bar.add(filter);
        filter.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { reload(); } });
        JButton r = new JButton("Refresh");
        r.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { reload(); } });
        bar.add(r);
        JButton reset = new JButton("Reset counters");
        reset.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                Services s = Services.current();
                if (s == null || !com.adlife.dicom.pro.Security.ask(LogViewer.this.cfg, LogViewer.this, "reset counters")) return;
                s.stats.reset();
                reload();
            }
        });
        bar.add(reset);
        JButton open = new JButton("Open Logs folder");
        open.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                try { java.awt.Desktop.getDesktop().open(LogViewer.this.cfg.logsDir()); }
                catch (Exception ex) { JOptionPane.showMessageDialog(LogViewer.this, ex.toString()); }
            }
        });
        bar.add(open);
        getContentPane().add(bar, BorderLayout.SOUTH);
        setSize(1000, 600);
        setLocationRelativeTo(null);
        reload();
    }

    static List<String> readLines(File f, int max) {
        List<String> out = new ArrayList<String>();
        if (!f.isFile()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try { String s; while ((s = r.readLine()) != null) { out.add(s); if (out.size() > max) out.remove(0); } }
            finally { r.close(); }
        } catch (Exception ignored) {}
        return out;
    }

    private void reload() {
        String q = filter.getText().trim().toLowerCase();
        List<String> lines = readLines(new File(cfg.logsDir(), "print-log.csv"), 5000);
        prints.setRowCount(0);
        prints.setColumnCount(0);
        if (!lines.isEmpty()) {
            for (String h : lines.get(0).split(",", -1)) prints.addColumn(h);
            for (int i = lines.size() - 1; i >= 1; i--) {
                if (!q.isEmpty() && !lines.get(i).toLowerCase().contains(q)) continue;
                prints.addRow((Object[]) lines.get(i).split(",", -1));
            }
        }
        counters.setRowCount(0);
        Services s = Services.current();
        if (s != null) for (Map.Entry<String, String> e : s.stats.snapshot().entrySet()) counters.addRow(new Object[] { e.getKey(), e.getValue() });
        StringBuilder sb = new StringBuilder();
        List<String> al = readLines(appLogFile(cfg), 4000);
        for (int i = al.size() - 1; i >= 0; i--) if (q.isEmpty() || al.get(i).toLowerCase().contains(q)) sb.append(al.get(i)).append('\n');
        app.setText(sb.toString());
        app.setCaretPosition(0);
    }

    public static File appLogFile(AdLifeConfig cfg) {
        return new File(cfg.logsDir(), "adlife-" + new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date()) + ".log");
    }
}
