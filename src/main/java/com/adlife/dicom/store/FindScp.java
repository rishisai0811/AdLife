package com.adlife.dicom.store;

import java.io.IOException;
import java.util.List;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Commands;
import org.dcm4che3.net.Dimse;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.AbstractDicomService;
import org.dcm4che3.net.service.DicomServiceException;

/** Study/patient-root C-FIND over the local archive index. */
public class FindScp extends AbstractDicomService {

    private final ArchiveIndex index;

    public FindScp(ArchiveIndex index) {
        super(UID.StudyRootQueryRetrieveInformationModelFIND,
              UID.PatientRootQueryRetrieveInformationModelFIND);
        this.index = index;
    }

    @Override
    protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                             Attributes cmd, Attributes data) throws IOException {
        if (dimse != Dimse.C_FIND_RQ)
            throw new DicomServiceException(Status.UnrecognizedOperation);
        String level = data.getString(Tag.QueryRetrieveLevel, "STUDY");
        List<ArchiveIndex.Record> matches = index.query(data, level);
        for (ArchiveIndex.Record r : matches) {
            Attributes rspAttrs = responseFor(r.attrs, data, level);
            if (!as.tryWriteDimseRSP(pc, Commands.mkCFindRSP(cmd, Status.Pending), rspAttrs))
                return;
        }
        as.tryWriteDimseRSP(pc, Commands.mkCFindRSP(cmd, Status.Success));
    }

    /** Echo back requested attributes (plus keys a viewer needs) at the right level. */
    private static Attributes responseFor(Attributes src, Attributes keys, String level) {
        Attributes out = new Attributes();
        copy(keys, src, out, Tag.PatientID);
        copy(keys, src, out, Tag.PatientName);
        copy(keys, src, out, Tag.PatientBirthDate);
        copy(keys, src, out, Tag.PatientSex);
        copy(keys, src, out, Tag.StudyInstanceUID);
        copy(keys, src, out, Tag.StudyDate);
        copy(keys, src, out, Tag.StudyTime);
        copy(keys, src, out, Tag.StudyDescription);
        copy(keys, src, out, Tag.AccessionNumber);
        copy(keys, src, out, Tag.ModalitiesInStudy);
        copy(keys, src, out, Tag.ReferringPhysicianName);
        if (!"STUDY".equalsIgnoreCase(level)) {
            copy(keys, src, out, Tag.SeriesInstanceUID);
            copy(keys, src, out, Tag.SeriesDescription);
            copy(keys, src, out, Tag.Modality);
            copy(keys, src, out, Tag.SeriesNumber);
            copy(keys, src, out, Tag.SOPInstanceUID);
            copy(keys, src, out, Tag.SOPClassUID);
            copy(keys, src, out, Tag.InstanceNumber);
        }
        return out;
    }

    private static void copy(Attributes keys, Attributes src, Attributes out, int tag) {
        if (keys.contains(tag) && src.contains(tag)) {
            out.setValue(tag, src.getVR(tag), src.getValue(tag));
        }
    }
}
