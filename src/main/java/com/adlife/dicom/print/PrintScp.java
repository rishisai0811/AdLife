package com.adlife.dicom.print;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.AbstractDicomService;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.util.UIDUtils;

/**
 * DICOM Print SCP: Basic Grayscale/Color Print Management, Presentation LUT,
 * Annotation Box (accepted, content ignored), Printer N-GET.
 *
 * State machine per association: Film Session -> Film Box(es) -> Image Box N-SETs
 * -> N-ACTION print. On print, the assembled FilmBox is handed to the
 * PrintJobHandler which renders, prints and archives the sheet.
 */
public class PrintScp extends AbstractDicomService {

    public interface PrintJobHandler {
        void onPrintJob(FilmBox box, Attributes sessionAttrs, Association as,
                        Attributes presentationLut);
    }

    private final PrintJobHandler handler;
    private final String printerName;

    private final Map<String, Attributes> sessions = new HashMap<String, Attributes>();
    private final Map<String, FilmBox> boxes = new HashMap<String, FilmBox>();
    private final Map<String, Attributes> presentationLuts = new HashMap<String, Attributes>();
    private final Map<String, FilmBox> annotationToBox = new HashMap<String, FilmBox>();

    public PrintScp(PrintJobHandler handler, String printerName) {
        // dcm4che's registry dispatches on the command's Affected/Requested
        // SOP Class UID (not the negotiated meta class), so register every
        // print SOP class we implement.
        super(UID.BasicFilmSessionSOPClass,
              UID.BasicFilmBoxSOPClass,
              UID.BasicGrayscaleImageBoxSOPClass,
              UID.BasicColorImageBoxSOPClass,
              UID.PrinterSOPClass,
              UID.BasicAnnotationBoxSOPClass,
              UID.PresentationLUTSOPClass);
        this.handler = handler;
        this.printerName = printerName;
    }

    static String metaClass(Association as, PresentationContext pc) {
        if (pc.getAbstractSyntax() != null) return pc.getAbstractSyntax();
        PresentationContext rq = as.getAAssociateRQ().getPresentationContext(pc.getPCID());
        return rq != null ? rq.getAbstractSyntax() : null;
    }

    @Override
    protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                             Attributes cmd, Attributes data) throws IOException {
        String sopClass = metaClass(as, pc);
        switch (dimse) {
        case N_GET_RQ:
            onPrinterGet(as, pc, cmd);
            break;
        case N_CREATE_RQ:
            onCreate(as, pc, cmd, data);
            break;
        case N_SET_RQ:
            onSet(as, pc, cmd, data);
            break;
        case N_ACTION_RQ:
            onAction(as, pc, cmd);
            break;
        case N_DELETE_RQ:
            onDelete(as, pc, cmd);
            break;
        case N_EVENT_REPORT_RQ:
            as.tryWriteDimseRSP(pc, Commands.mkNEventReportRSP(cmd, Status.Success));
            break;
        default:
            throw new DicomServiceException(Status.UnrecognizedOperation);
        }
    }

    private void onPrinterGet(Association as, PresentationContext pc, Attributes cmd)
            throws IOException {
        Attributes rsp = Commands.mkNGetRSP(cmd, Status.Success);
        rsp.setString(Tag.AffectedSOPInstanceUID, VR.UI, UID.PrinterSOPInstance);
        Attributes info = new Attributes();
        info.setString(Tag.PrinterStatus, VR.CS, "NORMAL");
        info.setString(Tag.PrinterStatusInfo, VR.CS, "NORMAL");
        info.setString(Tag.PrinterName, VR.LO,
                printerName == null ? "AdLife" : printerName);
        as.tryWriteDimseRSP(pc, rsp, info);
    }

    private void onCreate(Association as, PresentationContext pc, Attributes cmd,
                          Attributes data) throws IOException {
        String sopClass = cmd.getString(Tag.AffectedSOPClassUID);
        String rqUid = cmd.getString(Tag.AffectedSOPInstanceUID);
        Attributes rsp = Commands.mkNCreateRSP(cmd, Status.Success);

        if (UID.BasicFilmSessionSOPClass.equals(sopClass)) {
            String uid = rqUid != null ? rqUid : UIDUtils.createUID();
            sessions.put(uid, data != null ? new Attributes(data) : new Attributes());
            rsp.setString(Tag.AffectedSOPInstanceUID, VR.UI, uid);
            as.tryWriteDimseRSP(pc, rsp);
            return;
        }

        if (UID.PresentationLUTSOPClass.equals(sopClass)) {
            String uid = rqUid != null ? rqUid : UIDUtils.createUID();
            presentationLuts.put(uid, data != null ? new Attributes(data) : new Attributes());
            rsp.setString(Tag.AffectedSOPInstanceUID, VR.UI, uid);
            as.tryWriteDimseRSP(pc, rsp);
            return;
        }

        if (UID.BasicFilmBoxSOPClass.equals(sopClass)) {
            createFilmBox(as, pc, cmd, data, rqUid, rsp);
            return;
        }

        // Unknown N-CREATE target
        throw new DicomServiceException(Status.NoSuchSOPclass);
    }

    private void createFilmBox(Association as, PresentationContext pc, Attributes cmd,
                               Attributes data, String rqUid, Attributes rsp)
            throws IOException {
        if (data == null)
            throw new DicomServiceException(Status.InvalidAttributeValue);

        String sessionUID = referencedUID(data, Tag.ReferencedFilmSessionSequence);
        String lutUID = referencedUID(data, Tag.ReferencedPresentationLUTSequence);
        String format = data.getString(Tag.ImageDisplayFormat, "STANDARD\\1,1");
        int[] grid = FilmLayout.parseStandardFormat(format);
        int cells = grid[0] * grid[1];

        // On the accepting side the A-ASSOCIATE-AC context has no abstract
        // syntax; the negotiated meta SOP class lives in the request's context.
        boolean color = UID.BasicColorPrintManagementMetaSOPClass.equals(metaClass(as, pc));

        String boxUID = rqUid != null ? rqUid : UIDUtils.createUID();
        String[] imageBoxUIDs = new String[cells];
        for (int i = 0; i < cells; i++) imageBoxUIDs[i] = UIDUtils.createUID();

        FilmBox box = new FilmBox(boxUID, sessionUID, color, new Attributes(data),
                imageBoxUIDs, lutUID);
        boxes.put(boxUID, box);

        rsp.setString(Tag.AffectedSOPInstanceUID, VR.UI, boxUID);
        Attributes rspData = new Attributes();

        if (sessionUID != null) {
            Sequence refSession = rspData.newSequence(Tag.ReferencedFilmSessionSequence, 1);
            Attributes item = new Attributes();
            item.setString(Tag.ReferencedSOPClassUID, VR.UI, UID.BasicFilmSessionSOPClass);
            item.setString(Tag.ReferencedSOPInstanceUID, VR.UI, sessionUID);
            refSession.add(item);
        }

        Sequence refBoxes = rspData.newSequence(Tag.ReferencedImageBoxSequence, cells);
        String imageBoxClass = color ? UID.BasicColorImageBoxSOPClass
                                     : UID.BasicGrayscaleImageBoxSOPClass;
        for (int i = 0; i < cells; i++) {
            Attributes item = new Attributes();
            item.setString(Tag.ReferencedSOPClassUID, VR.UI, imageBoxClass);
            item.setString(Tag.ReferencedSOPInstanceUID, VR.UI, imageBoxUIDs[i]);
            refBoxes.add(item);
        }

        // Echo annotation box references if the SCU asked for them
        Sequence rqAnnot = data.getSequence(Tag.ReferencedBasicAnnotationBoxSequence);
        if (rqAnnot != null) {
            Sequence refAnnot = rspData.newSequence(Tag.ReferencedBasicAnnotationBoxSequence,
                    rqAnnot.size());
            for (int i = 0; i < rqAnnot.size(); i++) {
                Attributes item = new Attributes();
                item.setString(Tag.ReferencedSOPClassUID, VR.UI, UID.BasicAnnotationBoxSOPClass);
                String annotUID = UIDUtils.createUID();
                item.setString(Tag.ReferencedSOPInstanceUID, VR.UI, annotUID);
                annotationToBox.put(annotUID, box);
                refAnnot.add(item);
            }
        }

        as.tryWriteDimseRSP(pc, rsp, rspData);
    }

    private void onSet(Association as, PresentationContext pc, Attributes cmd,
                       Attributes data) throws IOException {
        String uid = cmd.getString(Tag.RequestedSOPInstanceUID);
        if (data == null || uid == null)
            throw new DicomServiceException(Status.InvalidAttributeValue);

        // Image box?
        for (FilmBox box : boxes.values()) {
            int idx = box.cellIndexFor(uid);
            if (idx >= 0) {
                box.imageData[idx] = new Attributes(data);
                as.tryWriteDimseRSP(pc, Commands.mkNSetRSP(cmd, Status.Success));
                return;
            }
        }

        // Film box attribute update?
        FilmBox box = boxes.get(uid);
        if (box != null) {
            box.attrs.addAll(data);
            String lutUID = referencedUID(data, Tag.ReferencedPresentationLUTSequence);
            if (lutUID != null) box.presentationLutUID = lutUID;
            as.tryWriteDimseRSP(pc, Commands.mkNSetRSP(cmd, Status.Success));
            return;
        }

        // Presentation LUT content update?
        if (presentationLuts.containsKey(uid)) {
            presentationLuts.get(uid).addAll(data);
            as.tryWriteDimseRSP(pc, Commands.mkNSetRSP(cmd, Status.Success));
            return;
        }

        // Annotation box: record position + text for the film sheet renderer
        if (data.contains(Tag.AnnotationPosition) || data.contains(Tag.TextString)) {
            FilmBox owner = annotationToBox.get(uid);
            if (owner != null) {
                int position = data.getInt(Tag.AnnotationPosition, -1);
                String text = data.getString(Tag.TextString);
                if (position >= 0 && text != null && text.trim().length() > 0) {
                    owner.annotations.put(position, text.trim());
                }
            }
            as.tryWriteDimseRSP(pc, Commands.mkNSetRSP(cmd, Status.Success));
            return;
        }

        throw new DicomServiceException(Status.NoSuchObjectInstance);
    }

    private void onAction(Association as, PresentationContext pc, Attributes cmd)
            throws IOException {
        String uid = cmd.getString(Tag.RequestedSOPInstanceUID);
        Attributes sessionAttrs = null;

        if (sessions.containsKey(uid)) {
            sessionAttrs = sessions.get(uid);
            for (FilmBox box : boxes.values()) {
                if (uid.equals(box.sessionUID)) firePrint(box, sessionAttrs, as);
            }
        } else {
            FilmBox box = boxes.get(uid);
            if (box == null)
                throw new DicomServiceException(Status.NoSuchObjectInstance);
            if (!box.isComplete())
                throw new DicomServiceException(Status.ProcessingFailure);
            sessionAttrs = box.sessionUID != null ? sessions.get(box.sessionUID) : null;
            firePrint(box, sessionAttrs, as);
        }

        as.tryWriteDimseRSP(pc, Commands.mkNActionRSP(cmd, Status.Success));
    }

    private void firePrint(FilmBox box, Attributes sessionAttrs, Association as) {
        Attributes lut = box.presentationLutUID != null
                ? presentationLuts.get(box.presentationLutUID) : null;
        handler.onPrintJob(box, sessionAttrs, as, lut);
    }

    private void onDelete(Association as, PresentationContext pc, Attributes cmd)
            throws IOException {
        String uid = cmd.getString(Tag.RequestedSOPInstanceUID);
        if (uid != null) {
            sessions.remove(uid);
            boxes.remove(uid);
            presentationLuts.remove(uid);
        }
        as.tryWriteDimseRSP(pc, Commands.mkNDeleteRSP(cmd, Status.Success));
    }

    @Override
    public void onClose(Association as) {
        sessions.clear();
        boxes.clear();
        presentationLuts.clear();
    }

    private static String referencedUID(Attributes attrs, int seqTag) {
        Sequence seq = attrs.getSequence(seqTag);
        if (seq == null || seq.isEmpty()) return null;
        return seq.get(0).getString(Tag.ReferencedSOPInstanceUID);
    }
}
