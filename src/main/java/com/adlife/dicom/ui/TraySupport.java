package com.adlife.dicom.ui;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.dcm.DicomServers;

/** System tray presence: status, quick folders, config file, viewer, exit. */
public class TraySupport {

    private static final Logger LOG = LoggerFactory.getLogger(TraySupport.class);

    private final AdLifeConfig config;
    private final DicomServers servers;
    private final Runnable openViewer;

    public TraySupport(AdLifeConfig config, DicomServers servers, Runnable openViewer) {
        this.config = config;
        this.servers = servers;
        this.openViewer = openViewer;
    }

    public void install() {
        if (!SystemTray.isSupported()) {
            LOG.warn("System tray not supported on this desktop; running without tray");
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            add(menu, "Open Viewer", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { openViewer.run(); }
            });
            add(menu, "Open PDF Folder", openDir(config.pdfDir()));
            add(menu, "Open Archive Folder", openDir(config.archiveDir()));
            add(menu, "Edit Config", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    openFile(config.file());
                }
            });
            menu.addSeparator();
            add(menu, "Status", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) {
                    TrayIcon[] icons = SystemTray.getSystemTray().getTrayIcons();
                    if (icons.length > 0) {
                        icons[0].displayMessage("AdLife DICOM",
                                servers.statusText(), TrayIcon.MessageType.INFO);
                    }
                }
            });
            menu.addSeparator();
            add(menu, "Exit", new ActionListener() {
                @Override public void actionPerformed(ActionEvent e) { System.exit(0); }
            });

            TrayIcon icon = new TrayIcon(makeIcon(), "AdLife DICOM", menu);
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
            icon.displayMessage("AdLife DICOM", servers.statusText(),
                    TrayIcon.MessageType.INFO);
        } catch (AWTException e) {
            LOG.error("Tray install failed: {}", e.toString());
        }
    }

    private static void add(PopupMenu menu, String label, ActionListener l) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(l);
        menu.add(item);
    }

    private static ActionListener openDir(final File dir) {
        return new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                dir.mkdirs();
                openFile(dir);
            }
        };
    }

    private static void openFile(File f) {
        try {
            Desktop.getDesktop().open(f);
        } catch (Exception ex) {
            LOG.error("Cannot open {}: {}", f, ex.toString());
        }
    }

    private static BufferedImage makeIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(20, 90, 160));
            g.fillRoundRect(0, 0, 16, 16, 4, 4);
            g.setColor(Color.WHITE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.drawString("A", 4, 12);
        } finally {
            g.dispose();
        }
        return img;
    }
}
