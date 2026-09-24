package com.adlife.dicom.print;

import com.adlife.dicom.config.AdLifeConfig;
import java.util.List;

/**
 * Film size handling beyond the DICOM defaults:
 *   film.sizes.enabled = 8INX10IN,10INX12IN,...   sizes used at this site (empty = all)
 *   film.size.fallback = 14INX17IN                 used when a received size is not enabled
 *   film.map.<RECEIVED> = <SIZE>                   remap, e.g. film.map.14INX17IN=A3
 *   film.manual.width.in / film.manual.height.in   the MANUAL size
 */
public final class FilmSizes {
    private FilmSizes() {}

    public static final String[] KNOWN = { "8INX10IN", "10INX12IN", "10INX14IN", "11INX14IN", "14INX14IN",
            "14INX17IN", "24CMX24CM", "24CMX30CM", "A4", "A3", "B4", "MANUAL" };

    public static String resolve(AdLifeConfig cfg, String received) {
        String s = received == null || received.trim().isEmpty() ? cfg.get("film.size.default", "14INX17IN")
                : received.trim().toUpperCase();
        String mapped = cfg.get("film.map." + s, "");
        if (!mapped.isEmpty()) s = mapped.toUpperCase();
        List<String> enabled = cfg.getList("film.sizes.enabled");
        if (!enabled.isEmpty()) {
            boolean ok = false;
            for (String e : enabled) if (e.equalsIgnoreCase(s)) ok = true;
            if (!ok) s = cfg.get("film.size.fallback", enabled.get(0)).toUpperCase();
        }
        return s;
    }

    public static double[] inches(AdLifeConfig cfg, String sizeId) {
        if ("MANUAL".equalsIgnoreCase(sizeId)) {
            return new double[] { cfg.getDouble("film.manual.width.in", 10), cfg.getDouble("film.manual.height.in", 12) };
        }
        if ("B4".equalsIgnoreCase(sizeId)) return new double[] { 257 / 25.4, 364 / 25.4 };
        return FilmLayout.filmSizeInches(sizeId);
    }

    /** Licensing / statistics bucket: 8X10, 10X12, 11X14, 14X17, A4, A3, OTHER. */
    public static String bucket(String sizeId) {
        String s = sizeId == null ? "" : sizeId.toUpperCase();
        if (s.equals("8INX10IN")) return "8X10";
        if (s.equals("10INX12IN") || s.equals("10INX14IN")) return "10X12";
        if (s.equals("11INX14IN")) return "11X14";
        if (s.equals("14INX17IN") || s.equals("14INX14IN")) return "14X17";
        if (s.equals("A4")) return "A4";
        if (s.equals("A3") || s.equals("B4")) return "A3";
        return "OTHER";
    }
}
