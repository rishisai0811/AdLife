package com.adlife.dicom;

import com.adlife.dicom.pro.LicenseManager;
import java.io.File;

/**
 * Licence code issuer for AdLife's own optional licensing (run on YOUR PC, never
 * shipped to sites with the private key):
 *   AdLife --issuer machine                                   print this PC's machine ID
 *   AdLife --issuer apply <configDir> <code>                  apply a code on the site PC without the tray
 *   AdLife --issuer keys  <private.key>                       create key pair; prints public key for license.public.key
 *   AdLife --issuer recharge <private.key> <machineId> <bucket> <count>
 *   AdLife --issuer key   <private.key> <machineId> <yyyy-MM-dd|never> [holder]
 */
public final class Issuer {
    private Issuer() {}

    public static void main(String[] argv) {
        java.util.List<String> a = new java.util.ArrayList<String>();
        for (String s : argv) if (!s.equalsIgnoreCase("--issuer")) a.add(s);
        try {
            if (a.size() >= 1 && a.get(0).equals("machine")) {
                System.out.println(LicenseManager.machineId());
            } else if (a.size() >= 3 && a.get(0).equals("apply")) {
                com.adlife.dicom.config.AdLifeConfig c = new com.adlife.dicom.config.AdLifeConfig(new File(a.get(1)));
                c.load();
                System.out.println(new LicenseManager(c).applyCode(a.get(2)));
            } else if (a.size() >= 2 && a.get(0).equals("keys")) {
                System.out.println("license.public.key=" + LicenseManager.createIssuerKeys(new File(a.get(1))));
            } else if (a.size() >= 5 && a.get(0).equals("recharge")) {
                System.out.println(LicenseManager.issueRecharge(new File(a.get(1)), a.get(2), a.get(3), Integer.parseInt(a.get(4))));
            } else if (a.size() >= 4 && a.get(0).equals("key")) {
                System.out.println(LicenseManager.issueKey(new File(a.get(1)), a.get(2), a.get(3), a.size() > 4 ? a.get(4) : ""));
            } else {
                System.out.println("Usage:\n  --issuer machine\n  --issuer apply <configDir> <code>\n  --issuer keys <private.key>\n  --issuer recharge <private.key> <machineId> <bucket> <count>\n  --issuer key <private.key> <machineId> <yyyy-MM-dd|never> [holder]");
            }
        } catch (Exception e) {
            System.err.println("Issuer error: " + e);
        }
    }
}
