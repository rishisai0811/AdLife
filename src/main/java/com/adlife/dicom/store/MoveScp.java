package com.adlife.dicom.store;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.dcm4che3.data.Attributes;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.config.AdLifeConfig;

/** C-MOVE SCP: relays matching archive instances to a configured destination. */
public class MoveScp extends AbstractDicomService {

    private static final Logger LOG = LoggerFactory.getLogger(MoveScp.class);

    private final ArchiveIndex index;
    private final AdLifeConfig config;

    public MoveScp(ArchiveIndex index, AdLifeConfig config) {
        super(UID.StudyRootQueryRetrieveInformationModelMOVE,
              UID.PatientRootQueryRetrieveInformationModelMOVE);
        this.index = index;
        this.config = config;
    }

    @Override
    protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                             Attributes cmd, Attributes data) throws IOException {
        if (dimse != Dimse.C_MOVE_RQ)
            throw new DicomServiceException(Status.UnrecognizedOperation);

        String destAet = cmd.getString(Tag.MoveDestination);
        AdLifeConfig.Destination dest = config.destinationByAet(destAet);
        if (dest == null) {
            LOG.warn("C-MOVE to unknown destination '{}'", destAet);
            throw new DicomServiceException(0xA801); // move destination unknown
        }

        String level = data.getString(Tag.QueryRetrieveLevel, "STUDY");
        List<ArchiveIndex.Record> matches = index.query(data, level);
        List<File> files = new ArrayList<File>();
        for (ArchiveIndex.Record r : matches) files.add(r.file);

        final int total = files.size();
        final Association fas = as;
        final PresentationContext fpc = pc;
        final Attributes fcmd = cmd;

        DicomSender sender = new DicomSender(config.callingAet());
        try {
            final int[] counts = { 0, 0 }; // completed, failed
            sender.send(files, dest.host, dest.port, dest.aeTitle,
                    new DicomSender.ProgressListener() {
                        @Override
                        public void onProgress(int remaining, int completed, int failed) {
                            counts[0] = completed; counts[1] = failed;
                            Attributes rsp = Commands.mkCMoveRSP(fcmd, Status.Pending);
                            rsp.setInt(Tag.NumberOfRemainingSuboperations, VR.US, remaining);
                            rsp.setInt(Tag.NumberOfCompletedSuboperations, VR.US, completed);
                            rsp.setInt(Tag.NumberOfFailedSuboperations, VR.US, failed);
                            rsp.setInt(Tag.NumberOfWarningSuboperations, VR.US, 0);
                            fas.tryWriteDimseRSP(fpc, rsp);
                        }
                    });

            Attributes rsp = Commands.mkCMoveRSP(fcmd,
                    counts[0] == total ? Status.Success : 0xB000); // warning if partial
            rsp.setInt(Tag.NumberOfRemainingSuboperations, VR.US, 0);
            rsp.setInt(Tag.NumberOfCompletedSuboperations, VR.US, counts[0]);
            rsp.setInt(Tag.NumberOfFailedSuboperations, VR.US, counts[1]);
            rsp.setInt(Tag.NumberOfWarningSuboperations, VR.US, 0);
            as.tryWriteDimseRSP(pc, rsp);
        } catch (Exception e) {
            LOG.error("C-MOVE relay failed: {}", e.toString());
            throw new DicomServiceException(Status.ProcessingFailure);
        }
    }
}
