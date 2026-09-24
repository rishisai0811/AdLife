package com.adlife.dicom.ui;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.print.DicomEcho;
import com.adlife.dicom.print.PrintRouter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Add-printer wizard for the Settings > Printers tab: pick a Windows printer
 * from the list Windows reports, a DICOM film printer (AET + IP + port, with a
 * C-ECHO test button, mirroring MediPro's printer setup), or a folder. Returns
 * the printers-list entry plus any per-printer keys.
 */
public final class PrinterDialog extends JDialog {
    public static final class Choice {
        public String entry;
        public final Map<String, String> props = new LinkedHashMap<String, String>();
    }

    private final JComboBox<String> type = new JComboBox<String>(new String[] { "DICOM film printer", "Windows printer", "Folder" });
    private final JPanel cards = new JPanel(new CardLayout());

    private final JComboBox<String> winList = new JComboBox<String>();

    private final JTextField aet = new JTextField("DICOMPRINTER", 14);
    private final JTextField host = new JTextField(14);
    private final JTextField port = new JTextField("104", 6);
    private final JTextField calling;
    private final JComboBox<String> mode = new JComboBox<String>(new String[] { "auto", "sheet", "passthrough" });
    private final JComboBox<String> medium = new JComboBox<String>(new String[] { "BLUE FILM", "CLEAR FILM", "PAPER" });
    private final JLabel testResult = new JLabel(" ");

    private final JTextField folder = new JTextField(20);

    private Choice result;

    public static Choice showDialog(Component parent, AdLifeConfig cfg) {
        PrinterDialog d = new PrinterDialog(parent, cfg);
        d.setVisible(true);
        return d.result;
    }

    private PrinterDialog(Component parent, final AdLifeConfig cfg) {
        super((java.awt.Frame) null, "Add printer", true);
        this.calling = new JTextField(cfg.callingAet(), 10);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Type"));
        top.add(type);
        getContentPane().add(top, BorderLayout.NORTH);

        cards.add(windowsCard(), "Windows printer");
        cards.add(dicomCard(), "DICOM film printer");
        cards.add(folderCard(), "Folder");
        getContentPane().add(cards, BorderLayout.CENTER);
        ((CardLayout) cards.getLayout()).show(cards, String.valueOf(type.getSelectedItem()));
        type.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                ((CardLayout) cards.getLayout()).show(cards, String.valueOf(type.getSelectedItem()));
            }
        });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("Add");
        ok.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { onOk(); }
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { result = null; dispose(); }
        });
        south.add(ok);
        south.add(cancel);
        getContentPane().add(south, BorderLayout.SOUTH);
        pack();
        setSize(Math.max(getWidth(), 460), getHeight());
        setLocationRelativeTo(parent);
    }

    private JPanel windowsCard() {
        JPanel p = form();
        refreshPrinters();
        JButton refresh = new JButton("Refresh list");
        refresh.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { refreshPrinters(); }
        });
        row(p, 0, "Printer", winList);
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT));
        r.add(refresh);
        GridBagConstraints c = gbc(1, 1);
        p.add(r, c);
        note(p, 2, "Names exactly as in Windows Settings > Printers.");
        return p;
    }

    private void refreshPrinters() {
        winList.removeAllItems();
        List<String> names = PrintRouter.installedPrinters();
        for (String n : names) winList.addItem(n);
        if (names.isEmpty()) winList.addItem("(no printers found on this PC)");
    }

    private JPanel dicomCard() {
        JPanel p = form();
        row(p, 0, "Printer AET", aet);
        row(p, 1, "IP address", host);
        row(p, 2, "Port", port);
        row(p, 3, "Calling AET (our name)", calling);
        row(p, 4, "Send mode", mode);
        row(p, 5, "Medium", medium);
        JButton test = new JButton("Test printer connection");
        test.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { runEchoTest(test); }
        });
        GridBagConstraints c = gbc(1, 6);
        p.add(test, c);
        GridBagConstraints c2 = gbc(1, 7);
        p.add(testResult, c2);
        note(p, 8, "sheet = AdLife renders the film first; passthrough = forward the modality's film as-is.");
        return p;
    }

    private void runEchoTest(final JButton test) {
        test.setEnabled(false);
        testResult.setText("Testing...");
        new Thread(new Runnable() {
            @Override public void run() {
                String err;
                try {
                    err = DicomEcho.echo(aet.getText(), host.getText(), Integer.parseInt(port.getText().trim()), calling.getText());
                } catch (NumberFormatException nfe) {
                    err = "port must be a number";
                }
                final String msg = err == null ? "OK - printer answered." : "FAILED - " + err;
                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        testResult.setText(msg);
                        test.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private JPanel folderCard() {
        JPanel p = form();
        row(p, 0, "Folder", folder);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                JFileChooser fc = new JFileChooser();
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (fc.showOpenDialog(PrinterDialog.this) == JFileChooser.APPROVE_OPTION)
                    folder.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        GridBagConstraints c = gbc(1, 1);
        p.add(browse, c);
        note(p, 2, "Each film is written here as a PNG - handy for a shared folder.");
        return p;
    }

    private void onOk() {
        String t = String.valueOf(type.getSelectedItem());
        Choice c = new Choice();
        if ("Windows printer".equals(t)) {
            Object sel = winList.getSelectedItem();
            if (sel == null || String.valueOf(sel).startsWith("(no printers")) {
                JOptionPane.showMessageDialog(this, "No Windows printer selected."); return;
            }
            c.entry = String.valueOf(sel);
        } else if ("Folder".equals(t)) {
            String f = folder.getText().trim();
            if (f.isEmpty()) { JOptionPane.showMessageDialog(this, "Pick a folder."); return; }
            if (!new File(f).isDirectory() && !new File(f).mkdirs()) {
                JOptionPane.showMessageDialog(this, "Folder does not exist and could not be created."); return;
            }
            c.entry = "folder:" + f;
        } else {
            String a = aet.getText().trim(), h = host.getText().trim(), po = port.getText().trim();
            if (a.isEmpty() || h.isEmpty() || po.isEmpty()) {
                JOptionPane.showMessageDialog(this, "AET, IP address and port are all required."); return;
            }
            try { Integer.parseInt(po); } catch (NumberFormatException nfe) {
                JOptionPane.showMessageDialog(this, "Port must be a number."); return;
            }
            c.entry = "dicom:" + a + "@" + h + ":" + po;
            c.props.put("dicom.calling", calling.getText().trim());
            c.props.put("dicom.mode", String.valueOf(mode.getSelectedItem()));
            c.props.put("dicom.medium", String.valueOf(medium.getSelectedItem()));
        }
        result = c;
        dispose();
    }

    private static JPanel form() {
        JPanel p = new JPanel(new GridBagLayout());
        return p;
    }

    private static GridBagConstraints gbc(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y;
        c.insets = new Insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private static void row(JPanel p, int y, String label, Component field) {
        p.add(new JLabel(label), gbc(0, y));
        p.add(field, gbc(1, y));
    }

    private static void note(JPanel p, int y, String text) {
        JLabel l = new JLabel("<html><i>" + text + "</i></html>");
        p.add(l, gbc(1, y));
    }
}
