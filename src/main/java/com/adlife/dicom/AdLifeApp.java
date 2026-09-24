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
        if (hasArg(args, "--issuer")) { Issuer.main(args); return; }
        if (args.length > 0 && args[0].equalsIgnoreCase("--selftest")) {
            try { SelfTest.main(args); } catch (Exception e) { e.printStackTrace(); System.exit(2); }
            return;
        }
        boolean openViewer = hasArg(args, "--viewer");
        boolean kiosk = hasArg(args, "--kiosk");
        boolean console = hasArg(args, "--console") || GraphicsEnvironment.isHeadless();

        final AdLifeConfig config = new AdLifeConfig();
        try {
            config.load();
        } catch (Exception e) {
            LOG.error("Cannot load config: {}", e.toString());
            return;
        }

        teeLog(config);
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
            final Runnable restart = new Runnable() {
                @Override public void run() {
                    servers.stop();
                    try { config.reload(); servers.start(config, index); LOG.info("Listeners restarted. {}", servers.statusText()); }
                    catch (Exception e) { LOG.error("Restart failed: {}", e.toString(), e); }
                }
            };
            final TraySupport tray = new TraySupport(config, servers, new Runnable() {
                @Override public void run() { openViewer(config, index); }
            }).restartWith(restart);
            tray.install();
            // Safety net: if Windows started AdLife as the shell (kiosk.shell) but kiosk
            // mode is off, there would be no desktop at all - start Explorer instead.
            boolean asShell = com.adlife.dicom.pro.KioskMode.runningAsShell();
            if (asShell && !kiosk && !config.getBool("kiosk.enabled", false)) {
                LOG.warn("AdLife is the Windows shell but kiosk mode is off; starting Explorer");
                com.adlife.dicom.pro.KioskMode.startExplorer();
            }
            if (kiosk || config.getBool("kiosk.enabled", false)) {
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        new com.adlife.dicom.ui.KioskWindow(config, servers, new Runnable() {
                            @Override public void run() { new com.adlife.dicom.ui.SettingsDialog(config, restart).setVisible(true); }
                        }).showFullScreen();
                    }
                });
            }
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

    /** Copies console/log output into Logs/adlife-YYYYMMDD.log for the log viewer. */
    private static void teeLog(AdLifeConfig config) {
        try {
            java.io.File f = com.adlife.dicom.ui.LogViewer.appLogFile(config);
            f.getParentFile().mkdirs();
            final java.io.OutputStream file = new java.io.FileOutputStream(f, true);
            final java.io.PrintStream err = System.err;
            System.setErr(new java.io.PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) throws java.io.IOException { err.write(b); file.write(b); }
                @Override public void write(byte[] b, int o, int l) throws java.io.IOException { err.write(b, o, l); file.write(b, o, l); }
                @Override public void flush() throws java.io.IOException { err.flush(); file.flush(); }
            }, true));
        } catch (Exception e) {
            LOG.warn("Cannot open log file: {}", e.toString());
        }
    }

    private static boolean hasArg(String[] args, String want) {
        for (String a : args) if (a.equalsIgnoreCase(want)) return true;
        return false;
    }
}
