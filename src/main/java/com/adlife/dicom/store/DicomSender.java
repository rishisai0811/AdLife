package com.adlife.dicom.store;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSPHandler;
import org.dcm4che3.net.IncompatibleConnectionException;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.dcm.UIDs;

/** C-STORE SCU: pushes files to a remote AE (viewer Send, C-MOVE relay). */
public class DicomSender {

    private static final Logger LOG = LoggerFactory.getLogger(DicomSender.class);

    public interface ProgressListener {
        /** called after each instance; remaining counts this one as sent */
        void onProgress(int remaining, int completed, int failed);
    }

    private final String callingAet;

    public DicomSender(String callingAet) {
        this.callingAet = callingAet;
    }

    /**
     * Send files to host:port/remoteAet. Returns number of successfully stored
     * instances. progress may be null.
     */
    public int send(List<File> files, String host, int port, String remoteAet,
                    ProgressListener progress) throws IOException, InterruptedException,
                    IncompatibleConnectionException, GeneralSecurityException {
        Device device = new Device("adlife-sender");
        Connection conn = new Connection();
        device.addConnection(conn);
        ApplicationEntity ae = new ApplicationEntity(callingAet);
        device.addApplicationEntity(ae);
        ae.addConnection(conn);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);

        // one presentation context per transfer syntax is enough; dcm4che
        // matches the dataset's SOP class against the offered abstract syntaxes
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCallingAET(callingAet);
        rq.setCalledAET(remoteAet);
        for (String cuid : UIDs.STORAGE_SOP_CLASSES) {
            rq.addPresentationContext(new PresentationContext(
                    rq.getNumberOfPresentationContexts() * 2 + 1, cuid, UIDs.TRANSFER_SYNTAXES));
        }

        Connection remote = new Connection();
        remote.setHostname(host);
        remote.setPort(port);

        Association as = ae.connect(remote, rq);
        int completed = 0, failed = 0;
        try {
            int total = files.size();
            for (int i = 0; i < total; i++) {
                File f = files.get(i);
                try {
                    Attributes ds;
                    String tsuid;
                    DicomInputStream dis = new DicomInputStream(f);
                    try {
                        dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
                        Attributes fmi = dis.getFileMetaInformation();
                        tsuid = fmi != null
                                ? fmi.getString(org.dcm4che3.data.Tag.TransferSyntaxUID,
                                        org.dcm4che3.data.UID.ImplicitVRLittleEndian)
                                : org.dcm4che3.data.UID.ImplicitVRLittleEndian;
                        ds = dis.readDataset(-1, -1);
                    } finally {
                        dis.close();
                    }
                    String cuid = ds.getString(org.dcm4che3.data.Tag.SOPClassUID);
                    String iuid = ds.getString(org.dcm4che3.data.Tag.SOPInstanceUID);
                    final int[] status = { -1 };
                    as.cstore(cuid, iuid, 0, new DataWriterAdapter(ds), tsuid,
                            new DimseRSPHandler(as.nextMessageID()) {
                                @Override
                                public void onDimseRSP(Association as, Attributes cmd,
                                                       Attributes data) {
                                    super.onDimseRSP(as, cmd, data);
                                    status[0] = cmd.getInt(org.dcm4che3.data.Tag.Status, -1);
                                }
                            });
                    if (status[0] == Status.Success || status[0] == -1) {
                        completed++;
                    } else {
                        failed++;
                        LOG.warn("C-STORE of {} rejected, status 0x{}", f.getName(),
                                Integer.toHexString(status[0]));
                    }
                } catch (Exception e) {
                    failed++;
                    LOG.error("C-STORE of {} failed: {}", f.getName(), e.toString());
                }
                if (progress != null) progress.onProgress(total - i - 1, completed, failed);
            }
        } finally {
            try { as.release(); } catch (IOException ignored) {}
            try { as.waitForSocketClose(); } catch (Exception ignored) {}
            executor.shutdown();
            scheduler.shutdown();
        }
        return completed;
    }
}
