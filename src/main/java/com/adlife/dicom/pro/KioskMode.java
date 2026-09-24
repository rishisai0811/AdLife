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
        for (String[] v : VALUES) cmds.add(new String[] { "reg", "add", v[0], "/v", v[1], "/t", "REG_DWORD", "/d", "1", "/f" });
        if (cfg.getBool("kiosk.shell", false)) {
            String exe = cfg.get("kiosk.exe", defaultExe());
            cmds.add(new String[] { "reg", "add", WL, "/v", "Shell", "/t", "REG_SZ", "/d", exe, "/f" });
        }
        run(cmds);
        cfg.set("kiosk.enabled", "true");
        return cmds;
    }

    public static List<String[]> unlock(AdLifeConfig cfg) {
        List<String[]> cmds = new ArrayList<String[]>();
        for (String[] v : VALUES) cmds.add(new String[] { "reg", "delete", v[0], "/v", v[1], "/f" });
        cmds.add(new String[] { "reg", "delete", WL, "/v", "Shell", "/f" });
        run(cmds);
        cfg.set("kiosk.enabled", "false");
        return cmds;
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
