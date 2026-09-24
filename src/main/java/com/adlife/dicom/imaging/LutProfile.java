package com.adlife.dicom.imaging;

import com.adlife.dicom.config.AdLifeConfig;

/**
 * Named linear print-look profile. MediPro's "linear DICOM LUT" applied per
 * body part maps here: a gamma curve on the windowed values plus optional
 * inversion. gamma > 1 brightens mid-tones, < 1 darkens them.
 */
public class LutProfile {
    public final String name;
    public final double gamma;
    public final boolean invert;

    public LutProfile(String name, double gamma, boolean invert) {
        this.name = name;
        this.gamma = gamma;
        this.invert = invert;
    }

    public static LutProfile load(AdLifeConfig cfg, String profile) {
        String p = (profile == null || profile.length() == 0) ? "default" : profile;
        return new LutProfile(p, cfg.lutGamma(p), cfg.lutInvert(p));
    }

    /** Build the 256-entry post-window LUT for this profile. */
    public byte[] buildLut() {
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            v = Math.pow(v, 1.0 / gamma);
            int out = (int) Math.round(v * 255.0);
            if (invert) out = 255 - out;
            lut[i] = (byte) Math.max(0, Math.min(255, out));
        }
        return lut;
    }
}
