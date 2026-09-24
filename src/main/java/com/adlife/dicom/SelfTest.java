package com.adlife.dicom;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.dcm.DicomServers;
import com.adlife.dicom.print.DicomPrintForwarder;
import com.adlife.dicom.print.FilmBox;
import com.adlife.dicom.print.PrinterProfile;
import com.adlife.dicom.store.ArchiveIndex;
import java.io.File;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;

/**
 * End-to-end check without a modality: starts the listeners from the config in
 * <dir>, then sends a 2x2 greyscale film of synthetic test images to them with
 * AdLife's own DICOM print SCU.  Usage: AdLife --selftest <dir> <AE> <port> [color]
 */
public final class SelfTest {
    private SelfTest() {}

    public static void main(String[] args) throws Exception {
        File dir = new File(args[1]);
        String aet = args[2];
        int port = Integer.parseInt(args[3]);
        boolean color = args.length > 4 && "color".equals(args[4]);
        AdLifeConfig cfg = new AdLifeConfig(dir);
        cfg.load();
        DicomServers servers = new DicomServers();
        servers.start(cfg, new ArchiveIndex());

        Attributes fb = new Attributes();
        fb.setString(Tag.ImageDisplayFormat, VR.ST, "STANDARD\\2,2");
        fb.setString(Tag.FilmSizeID, VR.CS, "14INX17IN");
        fb.setString(Tag.FilmOrientation, VR.CS, "PORTRAIT");
        FilmBox box = new FilmBox("1.2.3.4", "1.2.3", color, fb, new String[] { "a", "b", "c", "d" }, null);
        for (int i = 0; i < 4; i++) box.imageData[i] = cell(i, 512, 512, color);

        cfg.set("p9.dicom.mode", "passthrough");
        cfg.set("p9.dicom.calling", "SELFTEST");
        PrinterProfile target = new PrinterProfile(cfg, 9, "dicom:" + aet + "@127.0.0.1:" + port);
        boolean ok = DicomPrintForwarder.forward(target, box, null, null, "14INX17IN", false, 1, color);
        System.out.println("SELFTEST send ok=" + ok);
        Thread.sleep(4000);
        servers.stop();
        System.exit(ok ? 0 : 1);
    }

    static Attributes cell(int kind, int w, int h, boolean color) {
        int spp = color ? 3 : 1;
        byte[] px = new byte[w * h * spp];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v;
            double dx = x - w / 2.0, dy = y - h / 2.0, r = Math.sqrt(dx * dx + dy * dy);
            switch (kind) {
                case 0: v = x * 255 / (w - 1); break;                                   // horizontal ramp
                case 1: v = ((x / 32) + (y / 32)) % 2 == 0 ? 40 : 215; break;           // checkerboard
                case 2: v = r < w * 0.35 ? (int) (255 - r * 255 / (w * 0.35)) : 20; break; // soft disc
                default: v = (y / (h / 8)) * 255 / 7; break;                            // 8 grey steps
            }
            if (color) {
                int o = (y * w + x) * 3;
                px[o] = (byte) v; px[o + 1] = (byte) (kind == 1 ? 255 - v : v / 2); px[o + 2] = (byte) (255 - v);
            } else px[y * w + x] = (byte) v;
        }
        Attributes item = new Attributes();
        item.setInt(Tag.SamplesPerPixel, VR.US, spp);
        item.setString(Tag.PhotometricInterpretation, VR.CS, color ? "RGB" : "MONOCHROME2");
        if (color) item.setInt(Tag.PlanarConfiguration, VR.US, 0);
        item.setInt(Tag.Rows, VR.US, h);
        item.setInt(Tag.Columns, VR.US, w);
        item.setInt(Tag.BitsAllocated, VR.US, 8);
        item.setInt(Tag.BitsStored, VR.US, 8);
        item.setInt(Tag.HighBit, VR.US, 7);
        item.setInt(Tag.PixelRepresentation, VR.US, 0);
        item.setBytes(Tag.PixelData, VR.OB, px);
        Attributes ib = new Attributes();
        ib.setInt(Tag.ImageBoxPosition, VR.US, kind + 1);
        Sequence s = ib.newSequence(color ? Tag.BasicColorImageSequence : Tag.BasicGrayscaleImageSequence, 1);
        s.add(item);
        return ib;
    }
}
