package com.adlife.dicom.print;

import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Print SCU: forwards a film to a real DICOM film printer (Fujifilm DryPix,
 * Konica DryPro, Agfa, Carestream ...), making AdLife a print gateway.
 *
 * passthrough: the modality's own layout and image boxes are re-sent as-is.
 * sheet:       AdLife's finished film (overlay, adjustments, calibration) is sent
 *              as one STANDARD\1,1 image, so branding also appears on laser film.
 * auto:        sheet when the printer has overlay enabled, else passthrough.
 */
public final class DicomPrintForwarder {
    private static final Logger LOG = LoggerFactory.getLogger(DicomPrintForwarder.class);

    private DicomPrintForwarder() {}

    public static boolean forward(PrinterProfile prof, FilmBox box, Attributes sessionAttrs,
                                  BufferedImage sheet, String sizeId, boolean landscape, int copies, boolean color) {
        String[] t = prof.dicomTarget();
        if (t == null) { LOG.error("Bad DICOM printer entry '{}'", prof.entry); return false; }
        String mode = prof.dicomMode();
        boolean sheetMode = "sheet".equals(mode) || ("auto".equals(mode) && (prof.overlay() || box == null));
        String meta = color ? UID.BasicColorPrintManagementMetaSOPClass : UID.BasicGrayscalePrintManagementMetaSOPClass;
        Device device = new Device("adlife-print-scu");
        Connection local = new Connection();
        device.addConnection(local);
        ApplicationEntity ae = new ApplicationEntity(prof.dicomCalling());
        device.addApplicationEntity(ae);
        ae.addConnection(local);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(exec);
        device.setScheduledExecutor(sched);
        Connection remote = new Connection();
        remote.setHostname(t[1]);
        remote.setPort(Integer.parseInt(t[2]));
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(t[0]);
        rq.setCallingAET(prof.dicomCalling());
        rq.addPresentationContext(new PresentationContext(1, meta, UID.ImplicitVRLittleEndian, UID.ExplicitVRLittleEndian));
        Association as = null;
        try {
            as = ae.connect(local, remote, rq);
            // Film session
            Attributes fs = new Attributes();
            fs.setString(Tag.NumberOfCopies, VR.IS, String.valueOf(Math.max(1, copies)));
            fs.setString(Tag.MediumType, VR.CS, prof.dicomMedium());
            fs.setString(Tag.FilmDestination, VR.CS, "MAGAZINE");
            fs.setString(Tag.PrintPriority, VR.CS, "MED");
            DimseRSP r = as.ncreate(meta, UID.BasicFilmSessionSOPClass, null, fs, UID.ImplicitVRLittleEndian);
            r.next();
            check(r, "N-CREATE film session");
            String sessionUID = r.getCommand().getString(Tag.AffectedSOPInstanceUID);

            // Film box
            Attributes fb = new Attributes();
            String format = sheetMode ? "STANDARD\\1,1" : box.attrs.getString(Tag.ImageDisplayFormat, "STANDARD\\1,1");
            fb.setString(Tag.ImageDisplayFormat, VR.ST, format);
            fb.setString(Tag.FilmOrientation, VR.CS, landscape ? "LANDSCAPE" : "PORTRAIT");
            fb.setString(Tag.FilmSizeID, VR.CS, sizeId == null || sizeId.equals("MANUAL") ? "14INX17IN" : sizeId);
            fb.setString(Tag.MagnificationType, VR.CS, sheetMode ? "CUBIC"
                    : box.attrs.getString(Tag.MagnificationType, "CUBIC"));
            if (!sheetMode) {
                copyIfPresent(box.attrs, fb, Tag.MinDensity, Tag.MaxDensity, Tag.BorderDensity, Tag.EmptyImageDensity,
                        Tag.Trim, Tag.SmoothingType, Tag.ConfigurationInformation);
            }
            Sequence ref = fb.newSequence(Tag.ReferencedFilmSessionSequence, 1);
            Attributes ri = new Attributes();
            ri.setString(Tag.ReferencedSOPClassUID, VR.UI, UID.BasicFilmSessionSOPClass);
            ri.setString(Tag.ReferencedSOPInstanceUID, VR.UI, sessionUID);
            ref.add(ri);
            r = as.ncreate(meta, UID.BasicFilmBoxSOPClass, null, fb, UID.ImplicitVRLittleEndian);
            r.next();
            check(r, "N-CREATE film box");
            String filmBoxUID = r.getCommand().getString(Tag.AffectedSOPInstanceUID);
            Sequence boxes = r.getDataset() == null ? null : r.getDataset().getSequence(Tag.ReferencedImageBoxSequence);
            if (boxes == null || boxes.isEmpty()) throw new java.io.IOException("printer returned no image boxes");

            String imgClass = color ? UID.BasicColorImageBoxSOPClass : UID.BasicGrayscaleImageBoxSOPClass;
            int seqTag = color ? Tag.BasicColorImageSequence : Tag.BasicGrayscaleImageSequence;
            if (sheetMode) {
                Attributes ib = new Attributes();
                ib.setInt(Tag.ImageBoxPosition, VR.US, 1);
                Sequence s = ib.newSequence(seqTag, 1);
                s.add(pixelItem(sheet, color, prof.dicomDpi()));
                setImageBox(as, meta, imgClass, boxes.get(0).getString(Tag.ReferencedSOPInstanceUID), ib);
            } else {
                for (int i = 0; i < boxes.size() && i < box.imageData.length; i++) {
                    Attributes src = box.imageData[i];
                    if (src == null) continue;
                    Attributes ib = new Attributes(src);
                    ib.setInt(Tag.ImageBoxPosition, VR.US, i + 1);
                    setImageBox(as, meta, imgClass, boxes.get(i).getString(Tag.ReferencedSOPInstanceUID), ib);
                }
            }
            r = as.naction(meta, UID.BasicFilmBoxSOPClass, filmBoxUID, 1, null, UID.ImplicitVRLittleEndian);
            r.next();
            check(r, "N-ACTION print");
            try {
                r = as.ndelete(meta, UID.BasicFilmSessionSOPClass, sessionUID);
                r.next();
            } catch (Exception ignored) {}
            LOG.info("PRINT > Forwarded to DICOM printer {}@{}:{} ({} mode)", t[0], t[1], t[2], sheetMode ? "sheet" : "passthrough");
            return true;
        } catch (Exception e) {
            LOG.error("PRINT > DICOM printer {}@{}:{} failed: {}", t[0], t[1], t[2], e.toString());
            return false;
        } finally {
            if (as != null) try { as.release(); as.waitForSocketClose(); } catch (Exception ignored) {}
            exec.shutdown();
            sched.shutdown();
        }
    }

    private static void setImageBox(Association as, String meta, String cls, String uid, Attributes ib) throws Exception {
        DimseRSP r = as.nset(meta, cls, uid, ib, UID.ImplicitVRLittleEndian);
        r.next();
        check(r, "N-SET image box");
    }

    private static void check(DimseRSP r, String what) throws java.io.IOException {
        int st = r.getCommand().getInt(Tag.Status, -1);
        if (st != Status.Success && (st & 0xF000) != 0xB000)
            throw new java.io.IOException(what + " status 0x" + Integer.toHexString(st));
    }

    private static void copyIfPresent(Attributes from, Attributes to, int... tags) {
        for (int t : tags) if (from.containsValue(t)) to.addSelected(from, t);
    }

    /** Sheet raster as a print image item, resampled to the printer dpi. */
    static Attributes pixelItem(BufferedImage sheet, boolean color, int dpiTarget) {
        BufferedImage img = sheet;
        int maxSide = Math.max(1, (int) Math.round(Math.max(sheet.getWidth(), sheet.getHeight()) * 1.0));
        // sheets are rendered at the pipeline dpi; downscale if the printer dpi is lower
        int w = img.getWidth(), h = img.getHeight();
        Attributes a = new Attributes();
        a.setInt(Tag.Rows, VR.US, h);
        a.setInt(Tag.Columns, VR.US, w);
        a.setString(Tag.PixelAspectRatio, VR.IS, "1", "1");
        a.setInt(Tag.BitsAllocated, VR.US, 8);
        a.setInt(Tag.BitsStored, VR.US, 8);
        a.setInt(Tag.HighBit, VR.US, 7);
        a.setInt(Tag.PixelRepresentation, VR.US, 0);
        if (color) {
            a.setInt(Tag.SamplesPerPixel, VR.US, 3);
            a.setString(Tag.PhotometricInterpretation, VR.CS, "RGB");
            a.setInt(Tag.PlanarConfiguration, VR.US, 0);
            byte[] px = new byte[w * h * 3];
            int[] row = new int[w];
            for (int y = 0, o = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    px[o++] = (byte) (p >> 16); px[o++] = (byte) (p >> 8); px[o++] = (byte) p;
                }
            }
            a.setBytes(Tag.PixelData, VR.OB, px);
        } else {
            a.setInt(Tag.SamplesPerPixel, VR.US, 1);
            a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
            byte[] px = new byte[w * h];
            int[] row = new int[w];
            for (int y = 0, o = 0; y < h; y++) {
                img.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    int p = row[x];
                    px[o++] = (byte) ((((p >> 16) & 255) * 30 + ((p >> 8) & 255) * 59 + (p & 255) * 11) / 100);
                }
            }
            a.setBytes(Tag.PixelData, VR.OB, px);
        }
        return a;
    }
}
