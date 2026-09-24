package com.adlife.dicom.store;

import java.io.File;
import java.io.IOException;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.dcm.UIDs;

/**
 * C-STORE SCP: writes every received object to
 * <archiveDir>/<studyUID>/<seriesUID>/<sopUID>.dcm and indexes it.
 */
public class StorageScp extends BasicCStoreSCP {

    private static final Logger LOG = LoggerFactory.getLogger(StorageScp.class);

    private final File archiveDir;
    private final ArchiveIndex index;

    public StorageScp(File archiveDir, ArchiveIndex index) {
        super(UIDs.STORAGE_SOP_CLASSES);
        this.archiveDir = archiveDir;
        this.index = index;
    }

    @Override
    protected void store(Association as, PresentationContext pc, Attributes rq,
                         PDVInputStream data, Attributes rsp) throws IOException {
        String cuid = rq.getString(Tag.AffectedSOPClassUID);
        String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
        if (cuid == null || iuid == null)
            throw new DicomServiceException(Status.InvalidAttributeValue);

        Attributes ds;
        try {
            ds = data.readDataset(pc.getTransferSyntax());
        } catch (IOException e) {
            throw new DicomServiceException(Status.ProcessingFailure);
        }

        String studyUid = safe(ds.getString(Tag.StudyInstanceUID, "UNKNOWN_STUDY"));
        String seriesUid = safe(ds.getString(Tag.SeriesInstanceUID, "UNKNOWN_SERIES"));
        File dir = new File(new File(archiveDir, studyUid), seriesUid);
        dir.mkdirs();
        File out = new File(dir, safe(iuid) + ".dcm");

        Attributes fmi = ds.createFileMetaInformation(pc.getTransferSyntax());
        DicomOutputStream dos = new DicomOutputStream(out);
        try {
            dos.writeDataset(fmi, ds);
        } finally {
            dos.close();
        }

        index.add(ds, out);
        LOG.info("Stored {} from {}", iuid, as.getCallingAET());
    }

    private static String safe(String uid) {
        return uid.replaceAll("[^0-9A-Za-z._-]", "_");
    }
}
