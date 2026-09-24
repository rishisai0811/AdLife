package com.adlife.dicom.print;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.Status;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;

/** C-ECHO a DICOM printer (or any SCP) to test the connection from Settings. */
public final class DicomEcho {
    private DicomEcho() {}

    /** @return null on success, else a short human-readable reason. */
    public static String echo(String calledAet, String host, int port, String callingAet) {
        Device device = new Device("adlife-echo-scu");
        Connection local = new Connection();
        device.addConnection(local);
        ApplicationEntity ae = new ApplicationEntity(callingAet == null || callingAet.trim().isEmpty() ? "ADLIFE" : callingAet.trim());
        device.addApplicationEntity(ae);
        ae.addConnection(local);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(exec);
        device.setScheduledExecutor(sched);
        Connection remote = new Connection();
        remote.setHostname(host.trim());
        remote.setPort(port);
        remote.setConnectTimeout(5000);
        remote.setResponseTimeout(8000);
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAet.trim());
        rq.setCallingAET(ae.getAETitle());
        rq.addPresentationContext(new PresentationContext(1, UID.VerificationSOPClass, UID.ImplicitVRLittleEndian));
        Association as = null;
        try {
            as = ae.connect(local, remote, rq);
            DimseRSP rsp = as.cecho();
            rsp.next();
            int status = rsp.getCommand().getInt(org.dcm4che3.data.Tag.Status, -1);
            return status == Status.Success ? null : "printer answered C-ECHO with status 0x" + Integer.toHexString(status);
        } catch (Exception e) {
            String m = e.getMessage();
            return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        } finally {
            if (as != null) try { as.release(); } catch (Exception ignored) {}
            exec.shutdown();
            sched.shutdown();
        }
    }
}
