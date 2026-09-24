package com.adlife.dicom.viewer;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.store.ArchiveIndex;
import com.adlife.dicom.store.DicomSender;

/**
 * DICOM viewer: open files/folders or browse the local archive; window/level,
 * zoom, pan, cine, measurements, PNG export, Windows print, DICOM send.
 */
public class ViewerFrame extends JFrame {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ViewerFrame.class);

    private static class Instance {
        Attributes ds;   // full dataset incl. pixel data
        File file;       // archive backing file, null if opened ad hoc
        String label;
    }

    private final AdLifeConfig config;
    private final ArchiveIndex index;
    private final ImageCanvas canvas = new ImageCanvas();

    private final DefaultListModel<String> seriesModel = new DefaultListModel<String>();
    private final JList<String> seriesList = new JList<String>(seriesModel);
    private final Map<String, List<Instance>> seriesMap = new LinkedHashMap<String, List<Instance>>();
    private final JComboBox<String> destCombo = new JComboBox<String>();

    public ViewerFrame(AdLifeConfig config, ArchiveIndex index) {
        super("AdLife Viewer");
        this.config = config;
        this.index = index;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);

        seriesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        seriesList.addListSelectionListener(new ListSelectionListener() {
            @Override public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) return;
                String key = seriesList.getSelectedValue();
                if (key != null) showSeries(key);
            }
        });

        JButton openFiles = new JButton("Open Files");
        openFiles.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { openFiles(); }
        });
        JButton openFolder = new JButton("Open Folder");
        openFolder.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { openFolder(); }
        });
        JButton loadArchive = new JButton("Load Archive");
        loadArchive.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { loadArchive(); }
        });
        JButton exportPng = new JButton("Export PNG");
        exportPng.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { canvas.exportPng(ViewerFrame.this); }
        });
        JButton print = new JButton("Print");
        print.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { canvas.printCurrent(); }
        });
        JButton measure = new JButton("Measure");
        measure.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { canvas.toggleMeasure(); }
        });
        JButton send = new JButton("Send To");
        send.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { sendCurrent(); }
        });

        for (AdLifeConfig.Destination d : config.destinations()) {
            destCombo.addItem(d.name + " (" + d.aeTitle + "@" + d.host + ":" + d.port + ")");
        }

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(openFiles);
        toolbar.add(openFolder);
        toolbar.add(loadArchive);
        toolbar.add(measure);
        toolbar.add(exportPng);
        toolbar.add(print);
        toolbar.add(destCombo);
        toolbar.add(send);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(seriesList), canvas);
        split.setDividerLocation(260);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolbar, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
    }

    private void openFiles() {
        JFileChooser fc = new JFileChooser(config.dir());
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        List<File> files = new ArrayList<File>();
        for (File f : fc.getSelectedFiles()) files.add(f);
        loadFiles(files, false);
    }

    private void openFolder() {
        JFileChooser fc = new JFileChooser(config.dir());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        List<File> files = new ArrayList<File>();
        collect(fc.getSelectedFile(), files, 0);
        loadFiles(files, false);
    }

    private void loadArchive() {
        index.scan(config.archiveDir());
        List<File> files = new ArrayList<File>();
        for (ArchiveIndex.Record r : index.all()) files.add(r.file);
        loadFiles(files, true);
    }

    private static void collect(File dir, List<File> out, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 5) return;
        for (File f : kids) {
            if (f.isDirectory()) collect(f, out, depth + 1);
            else out.add(f);
        }
    }

    private void loadFiles(List<File> files, boolean fromArchive) {
        seriesMap.clear();
        seriesModel.clear();
        int loaded = 0;
        for (File f : files) {
            try {
                DicomInputStream dis = new DicomInputStream(f);
                Attributes ds;
                try {
                    ds = dis.readDataset(-1, -1);
                    Attributes fmi = dis.getFileMetaInformation();
                    String ts = fmi != null ? fmi.getString(Tag.TransferSyntaxUID)
                            : org.dcm4che3.data.UID.ImplicitVRLittleEndian;
                    ds.setString(com.adlife.dicom.imaging.Codecs.TAG_TRANSFER_SYNTAX,
                            org.dcm4che3.data.VR.LO, ts);
                } finally {
                    dis.close();
                }
                if (ds.getString(Tag.SOPInstanceUID) == null) continue;
                Instance inst = new Instance();
                inst.ds = ds;
                inst.file = fromArchive ? f : null;
                inst.label = label(ds);
                String series = ds.getString(Tag.SeriesInstanceUID, "NO_SERIES");
                List<Instance> list = seriesMap.get(series);
                if (list == null) {
                    list = new ArrayList<Instance>();
                    seriesMap.put(series, list);
                    seriesModel.addElement(series);
                }
                list.add(inst);
                loaded++;
            } catch (IOException ignored) {
                // not a DICOM file; skip
            }
        }
        if (loaded == 0) {
            JOptionPane.showMessageDialog(this, "No DICOM images found.", "AdLife Viewer",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // friendlier display: study desc / series desc
        for (int i = 0; i < seriesModel.size(); i++) {
            String key = seriesModel.get(i);
            List<Instance> list = seriesMap.get(key);
            if (list != null && !list.isEmpty()) {
                seriesModel.set(i, key + "  [" + list.get(0).label + ", " + list.size() + " img]");
            }
        }
        seriesList.setSelectedIndex(0);
    }

    private void showSeries(String displayKey) {
        // display entries carry suffix; strip back to the UID key
        String key = displayKey;
        int cut = key.indexOf("  [");
        if (cut > 0) key = key.substring(0, cut);
        List<Instance> list = seriesMap.get(key);
        if (list == null || list.isEmpty()) return;
        List<Attributes> frames = new ArrayList<Attributes>();
        for (Instance i : list) frames.add(i.ds);
        canvas.setSeries(frames);
        setTitle("AdLife Viewer - " + list.get(0).label);
    }

    private void sendCurrent() {
        int di = destCombo.getSelectedIndex();
        if (di < 0) {
            JOptionPane.showMessageDialog(this,
                    "No DICOM destinations configured. Add them in adlife.properties (destinations=...).",
                    "AdLife Viewer", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        AdLifeConfig.Destination dest = config.destinations().get(di);
        String key = seriesList.getSelectedValue();
        if (key == null) {
            JOptionPane.showMessageDialog(this, "Select a series first.");
            return;
        }
        int cut = key.indexOf("  [");
        if (cut > 0) key = key.substring(0, cut);
        List<Instance> list = seriesMap.get(key);
        if (list == null) return;

        // Archive instances send the original file; ad-hoc instances are written
        // to a temp spool first.
        final List<File> files = new ArrayList<File>();
        try {
            for (Instance inst : list) {
                if (inst.file != null) {
                    files.add(inst.file);
                } else {
                    File tmp = File.createTempFile("adlife-send-", ".dcm");
                    tmp.deleteOnExit();
                    org.dcm4che3.io.DicomOutputStream dos =
                            new org.dcm4che3.io.DicomOutputStream(tmp);
                    try {
                        Attributes fmi = inst.ds.createFileMetaInformation(
                                org.dcm4che3.data.UID.ExplicitVRLittleEndian);
                        dos.writeDataset(fmi, inst.ds);
                    } finally {
                        dos.close();
                    }
                    files.add(tmp);
                }
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Spool failed: " + e.getMessage());
            return;
        }

        final AdLifeConfig.Destination fdest = dest;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int sent = new DicomSender(config.callingAet())
                            .send(files, fdest.host, fdest.port, fdest.aeTitle, null);
                    info("Sent " + sent + " of " + files.size() + " images to " + fdest.name);
                } catch (Exception e) {
                    info("Send failed: " + e.getMessage());
                }
            }
        }, "adlife-send").start();
    }

    private void info(final String msg) {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                JOptionPane.showMessageDialog(ViewerFrame.this, msg);
            }
        });
    }

    private static String label(Attributes ds) {
        String desc = ds.getString(Tag.SeriesDescription);
        if (desc == null) desc = ds.getString(Tag.StudyDescription);
        if (desc == null) desc = ds.getString(Tag.Modality, "DICOM");
        String patient = ds.getString(Tag.PatientName, "");
        return patient.length() > 0 ? patient + " / " + desc : desc;
    }
}
