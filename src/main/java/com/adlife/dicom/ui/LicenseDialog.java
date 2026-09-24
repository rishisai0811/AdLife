package com.adlife.dicom.ui;

import com.adlife.dicom.pro.LicenseManager;
import com.adlife.dicom.pro.Services;
import java.awt.datatransfer.StringSelection;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

/** Shows machine ID and balances; accepts recharge / key codes. */
public final class LicenseDialog {
    private LicenseDialog() {}

    public static void open() {
        Services svc = Services.current();
        if (svc == null) return;
        LicenseManager lm = svc.license;
        StringBuilder sb = new StringBuilder();
        sb.append("Licensing: ").append(lm.enabled() ? "ON" : "OFF (all printing free)").append('\n');
        sb.append("Machine ID: ").append(LicenseManager.machineId()).append('\n');
        sb.append("Key: ").append(lm.keyStatus()).append('\n');
        if (lm.enabled()) {
            for (String b : new String[] { "8x10", "10x12", "11x14", "14x17", "a4", "a3", "other", "pdf" }) {
                sb.append("  ").append(b).append(": ").append(lm.remaining(b)).append(" left\n");
            }
        }
        if (lm.wasTampered()) sb.append("WARNING: licence file failed its seal check.\n");
        JTextField code = new JTextField(40);
        Object[] body = { sb.toString(), "Recharge or key code:", code };
        Object[] opts = { "Apply code", "Copy machine ID", "Close" };
        int r = JOptionPane.showOptionDialog(null, body, "AdLife Licence", JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
        if (r == 0 && !code.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(null, lm.applyCode(code.getText().trim()));
        } else if (r == 1) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(LicenseManager.machineId()), null);
        }
    }
}
