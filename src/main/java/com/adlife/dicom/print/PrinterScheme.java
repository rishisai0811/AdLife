package com.adlife.dicom.print;

import com.adlife.dicom.imaging.LutProfile;

/**
 * Named per-model printer calibration scheme (MediPro-style "printer scheme"):
 * brand|model|filmSizeId|dpi|minOD|maxOD|gamma|invert. Blank fields fall back
 * to the film box / global config.
 */
public final class PrinterScheme {
    public final String name, brand, model, filmSizeId;
    public final int dpi;          // <=0: use config film.dpi
    public final double minOD, maxOD; // minOD<0: none
    public final double gamma;     // <=0: use LUT profile default
    public final boolean invert;

    private PrinterScheme(String name, String[] f) {
        this.name = name;
        brand = f.length > 0 ? f[0].trim() : "";
        model = f.length > 1 ? f[1].trim() : "";
        filmSizeId = f.length > 2 && !f[2].trim().isEmpty() ? f[2].trim() : null;
        dpi = f.length > 3 && !f[3].trim().isEmpty() ? Integer.parseInt(f[3].trim()) : -1;
        minOD = f.length > 4 && !f[4].trim().isEmpty() ? Double.parseDouble(f[4].trim()) : -1;
        maxOD = f.length > 5 && !f[5].trim().isEmpty() ? Double.parseDouble(f[5].trim()) : -1;
        gamma = f.length > 6 && !f[6].trim().isEmpty() ? Double.parseDouble(f[6].trim()) : -1;
        invert = f.length > 7 && Boolean.parseBoolean(f[7].trim());
    }

    public static PrinterScheme parse(String name, String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        try {
            return new PrinterScheme(name, spec.split("\\|", -1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public double[] densityOD() {
        if (minOD < 0 || maxOD <= minOD) return null;
        return new double[] { minOD, maxOD };
    }

    public LutProfile toLutProfile() {
        return new LutProfile(name, gamma > 0 ? gamma : 1.8, invert);
    }
}
