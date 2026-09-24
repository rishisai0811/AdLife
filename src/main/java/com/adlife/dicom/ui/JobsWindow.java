package com.adlife.dicom.ui;

import com.adlife.dicom.print.PrintCoordinator;
import com.adlife.dicom.print.PrintRouter;
import com.adlife.dicom.pro.JobStore;
import com.adlife.dicom.pro.Services;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/** Recent films with preview; reprint on any printer with a copy count. */
public class JobsWindow extends JFrame {
    private final DefaultListModel<JobStore.Job> model = new DefaultListModel<JobStore.Job>();
    private final JList<JobStore.Job> list = new JList<JobStore.Job>(model);
    private final JComboBox<String> printer = new JComboBox<String>();
    private final JSpinner copies = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));
    private final JComboBox<String> days = new JComboBox<String>(new String[] { "Today", "7 days", "30 days", "All" });
    private BufferedImage preview;
    private final JPanel canvas = new JPanel() {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (preview == null) return;
            double s = Math.min((getWidth() - 20.0) / preview.getWidth(), (getHeight() - 20.0) / preview.getHeight());
            int w = (int) (preview.getWidth() * s), h = (int) (preview.getHeight() * s);
            g.drawImage(preview, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
        }
    };

    public JobsWindow() {
        super("AdLife - Jobs / Reprint");
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, ((JobStore.Job) v).title(), i, s, f);
            }
        });
        list.addListSelectionListener(new ListSelectionListener() {
            @Override public void valueChanged(ListSelectionEvent e) {
                JobStore.Job j = list.getSelectedValue();
                preview = j == null ? null : j.image();
                canvas.repaint();
            }
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), canvas);
        split.setDividerLocation(360);
        getContentPane().add(split, BorderLayout.CENTER);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Show"));
        bar.add(days);
        days.setSelectedIndex(1);
        days.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { reload(); } });
        bar.add(new JLabel("   Printer"));
        bar.add(printer);
        bar.add(new JLabel("Copies"));
        bar.add(copies);
        bar.add(btn("Reprint", new ActionListener() { @Override public void actionPerformed(ActionEvent e) { reprint(); } }));
        bar.add(btn("Open PDF", new ActionListener() { @Override public void actionPerformed(ActionEvent e) { openPdf(); } }));
        bar.add(btn("Delete", new ActionListener() { @Override public void actionPerformed(ActionEvent e) { delete(); } }));
        bar.add(btn("Refresh", new ActionListener() { @Override public void actionPerformed(ActionEvent e) { reload(); } }));
        getContentPane().add(bar, BorderLayout.SOUTH);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        reload();
    }

    private static JButton btn(String t, ActionListener l) { JButton b = new JButton(t); b.addActionListener(l); return b; }

    private void reload() {
        Services svc = Services.current();
        printer.removeAllItems();
        model.clear();
        if (svc == null) return;
        for (PrintRouter.Slot s : svc.router.slots()) printer.addItem(s.profile.index + ": " + s.name());
        int[] map = { 1, 7, 30, 100000 };
        List<JobStore.Job> jobs = svc.jobs.list(map[Math.max(0, days.getSelectedIndex())]);
        for (JobStore.Job j : jobs) model.addElement(j);
        if (!model.isEmpty()) list.setSelectedIndex(0);
    }

    private void reprint() {
        Services svc = Services.current();
        JobStore.Job j = list.getSelectedValue();
        if (svc == null || j == null || printer.getSelectedItem() == null) return;
        int n = Integer.parseInt(String.valueOf(printer.getSelectedItem()).split(":")[0]);
        boolean ok = PrintCoordinator.reprint(svc, j, n, (Integer) copies.getValue());
        JOptionPane.showMessageDialog(this, ok ? "Sent to printer." : "Reprint failed - see Logs.");
        list.repaint();
    }

    private void openPdf() {
        JobStore.Job j = list.getSelectedValue();
        if (j == null || j.get("pdf").isEmpty()) { JOptionPane.showMessageDialog(this, "No PDF for this film."); return; }
        try { Desktop.getDesktop().open(new File(j.get("pdf"))); } catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.toString()); }
    }

    private void delete() {
        Services svc = Services.current();
        JobStore.Job j = list.getSelectedValue();
        if (svc == null || j == null) return;
        svc.jobs.delete(j);
        reload();
    }
}
