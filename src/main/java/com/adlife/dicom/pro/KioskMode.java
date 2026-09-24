package com.adlife.dicom.pro;

import com.adlife.dicom.config.AdLifeConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin-client / kiosk lock for a dedicated print PC (Windows XP - 11), pure
 * Java via reg.exe - no native code. Applies per-user policies:
 *   DisableTaskMgr, DisableLockWorkstation, DisableChangePassword, NoLogoff,
 *   NoClose, NoRun, NoDesktop, NoWinKeys, NoTrayContextMenu, HideFastUserSwitching
 * and optionally (kiosk.shell=true) replaces Explorer with AdLife for this user,
 * so there is no taskbar, desktop or Alt-Tab target at all after the next logon.
 * Safety: nothing here runs at install time or on a normal start; the shell is
 * only replaced when kiosk.shell=true and the exe exists, it is started with
 * --kiosk so a window always shows, Task Manager stays available with a replaced
 * shell, and AdLife starts explorer.exe itself if it cannot show its window.
 * The on-screen part (full-screen always-on-top status window) is KioskWindow.
 * Unlocking removes exactly these values. Settings: kiosk.enabled, kiosk.shell,
 * kiosk.exe (path used as shell, default the installed AdLife.exe).
 */
public final class KioskMode {
    private static final Logger LOG = LoggerFactory.getLogger(KioskMode.class);
    private KioskMode() {}

    private static final String SYS = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System";
    private static final String EXP = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\Explorer";
    private static final String WL = "HKCU\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon";

    private static final String[][] VALUES = {
        { SYS, "DisableTaskMgr" }, { SYS, "DisableLockWorkstation" }, { SYS, "DisableChangePassword" },
        { EXP, "NoLogoff" }, { EXP, "NoClose" }, { EXP, "NoRun" }, { EXP, "NoDesktop" }, { EXP, "NoWinKeys" },
        { EXP, "NoTrayContextMenu" }, { SYS, "HideFastUserSwitching" },
    };

    public static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("windows"); }

    /** Returns the reg.exe commands that would run (applied only on Windows). */
    public static List<String[]> lock(AdLifeConfig cfg) {
        List<String[]> cmds = new ArrayList<String[]>();
        boolean shell = shellRequested(cfg);
        for (String[] v : VALUES) {
            // Never block Task Manager when Explorer is replaced: it is the only way back
            // to a desktop (Ctrl+Alt+Del > Task Manager > Run explorer.exe) if AdLife fails.
            if (shell && "DisableTaskMgr".equals(v[1])) continue;
            cmds.add(new String[] { "reg", "add", v[0], "/v", v[1], "/t", "REG_DWORD", "/d", "1", "/f" });
        }
        if (shell) {
            // --kiosk makes AdLife open its full-screen window when started as the shell,
            // so the user never lands on an empty black desktop.
            cmds.add(new String[] { "reg", "add", WL, "/v", "Shell", "/t", "REG_SZ", "/d", "\\\"" + shellExe(cfg) + "\\\" --kiosk", "/f" });
        }
        run(cmds);
        cfg.set("kiosk.enabled", "true");
        persist(cfg);
        return cmds;
    }

    /**
     * Explorer is replaced only when kiosk.shell=true was set on purpose AND the
     * program that would become the shell really exists on this PC. Anything else
     * would leave Windows with a black screen at the next logon.
     */
    public static boolean shellRequested(AdLifeConfig cfg) {
        if (!cfg.getBool("kiosk.shell", false)) return false;
        File exe = new File(shellExe(cfg));
        if (!exe.isFile()) {
            LOG.warn("kiosk.shell ignored: {} not found, Explorer stays the shell", exe);
            return false;
        }
        return true;
    }

    public static String shellExe(AdLifeConfig cfg) {
        String exe = cfg.get("kiosk.exe", "");
        return exe == null || exe.trim().isEmpty() ? defaultExe() : exe.trim();
    }

    public static List<String[]> unlock(AdLifeConfig cfg) {
        boolean wasShell = runningAsShell();
        List<String[]> cmds = new ArrayList<String[]>();
        for (String[] v : VALUES) cmds.add(new String[] { "reg", "delete", v[0], "/v", v[1], "/f" });
        cmds.add(new String[] { "reg", "delete", WL, "/v", "Shell", "/f" });
        run(cmds);
        cfg.set("kiosk.enabled", "false");
        persist(cfg);
        // Give the desktop back right away instead of at the next logon.
        if (wasShell) startExplorer();
        return cmds;
    }

    /** True when this Windows user's shell has been replaced by AdLife (HKCU Winlogon Shell). */
    public static boolean runningAsShell() {
        if (!isWindows()) return false;
        try {
            Process p = new ProcessBuilder("reg", "query", WL, "/v", "Shell").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line; boolean hit = false;
            while ((line = r.readLine()) != null) if (line.toLowerCase().contains("adlife")) hit = true;
            p.waitFor();
            return hit;
        } catch (Exception e) {
            return false;
        }
    }

    /** Starts Windows Explorer (taskbar + desktop). Safe to call when it is already running. */
    public static void startExplorer() {
        if (!isWindows()) return;
        try {
            String win = System.getenv("SystemRoot");
            new ProcessBuilder(new File(win == null ? "C:\\Windows" : win, "explorer.exe").getAbsolutePath()).start();
            LOG.info("Started explorer.exe");
        } catch (Exception e) {
            LOG.warn("Could not start explorer.exe: {}", e.toString());
        }
    }

    private static void persist(AdLifeConfig cfg) {
        try { cfg.save(); } catch (Exception e) { LOG.warn("Could not save kiosk setting: {}", e.toString()); }
    }

    private static String defaultExe() {
        String pf = System.getenv("ProgramFiles(x86)");
        if (pf == null) pf = System.getenv("ProgramFiles");
        return new File(pf == null ? "C:\\Program Files" : pf, "AdLife\\AdLife.exe").getAbsolutePath();
    }

    private static void run(List<String[]> cmds) {
        if (!isWindows()) { LOG.info("Kiosk policies skipped (not Windows)"); return; }
        for (String[] c : cmds) {
            try {
                Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
                p.getInputStream().close();
                p.waitFor();
            } catch (Exception e) {
                LOG.warn("reg command failed: {}", e.toString());
            }
        }
        LOG.info("Kiosk policies updated; some take effect after the next logon");
    }
}
