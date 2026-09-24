package com.adlife.dicom;

import java.awt.GraphicsEnvironment;
import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.dcm.DicomServers;
import com.adlife.dicom.store.ArchiveIndex;
import com.adlife.dicom.ui.TraySupport;
import com.adlife.dicom.viewer.ViewerFrame;

/**
 * AdLife DICOM Workstation - open 1:1 alternative to VMS MediPro.
 *
 * Starts the DICOM listeners (print SCP, storage SCP, query/retrieve), sits
 * in the system tray, and opens the viewer on request.
 *
 *   java -jar AdLife.jar            service + tray (default)
 *   java -jar AdLife.jar --viewer   also open the viewer immediately
 *   java -jar AdLife.jar --console  no tray (log to console, Ctrl+C to stop)
 */
public class AdLifeApp {

    private static final Logger LOG = LoggerFactory.getLogger(AdLifeApp.class);

    public static void main(String[] args) {
        boolean openViewer = hasArg(args, "--viewer");
        boolean console = hasArg(args, "--console") || GraphicsEnvironment.isHeadless();

        final AdLifeConfig config = new AdLifeConfig();
        try {
            config.load();
        } catch (Exception e) {
            LOG.error("Cannot load config: {}", e.toString());
            return;
        }

        final ArchiveIndex index = new ArchiveIndex();
        index.scan(config.archiveDir());

        final DicomServers servers = new DicomServers();
        try {
            servers.start(config, index);
        } catch (Exception e) {
            LOG.error("Cannot start DICOM listeners: {}", e.toString(), e);
            return;
        }
        LOG.info("AdLife up. {}", servers.statusText());

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { servers.stop(); }
        }));

        if (!console) {
            TraySupport tray = new TraySupport(config, servers, new Runnable() {
                @Override public void run() { openViewer(config, index); }
            });
            tray.install();
        }

        if (openViewer) openViewer(config, index);

        // Keep the main thread alive; listeners run on their own pools.
        if (console) {
            System.out.println("AdLife running (console mode). Ctrl+C to stop.");
            synchronized (AdLifeApp.class) {
                try { AdLifeApp.class.wait(); } catch (InterruptedException ignored) {}
            }
        }
    }

    private static void openViewer(AdLifeConfig config, ArchiveIndex index) {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                ViewerFrame frame = new ViewerFrame(config, index);
                frame.setVisible(true);
            }
        });
    }

    private static boolean hasArg(String[] args, String want) {
        for (String a : args) if (a.equalsIgnoreCase(want)) return true;
        return false;
    }
}
