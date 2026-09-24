package com.adlife.dicom.pro;

/** Hex/Base64 helpers without javax.xml.bind (removed from Java 11+). */
final class Hex {
    private Hex() {}
    private static final char[] D = "0123456789ABCDEF".toCharArray();
    static String printHexBinary(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { c[2 * i] = D[(b[i] >> 4) & 15]; c[2 * i + 1] = D[b[i] & 15]; }
        return new String(c);
    }
    static String printBase64Binary(byte[] b) { return java.util.Base64.getEncoder().encodeToString(b); }
    static byte[] parseBase64Binary(String s) { return java.util.Base64.getMimeDecoder().decode(s); }
}
