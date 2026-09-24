package com.adlife.dicom.dcm;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.print.PrintCoordinator;
import com.adlife.dicom.print.PrintRouter;
import com.adlife.dicom.print.PrintScp;
import com.adlife.dicom.pdf.PdfExporter;
import com.adlife.dicom.store.ArchiveIndex;
import com.adlife.dicom.store.FindScp;
import com.adlife.dicom.store.MoveScp;
import com.adlife.dicom.store.StorageScp;

/**
 * Builds and binds one DICOM listener per config entry. Each listener offers
 * C-ECHO, print management (with its own LUT profile), storage and
 * query/retrieve.
 */
public class DicomServers {

    private static final Logger LOG = LoggerFactory.getLogger(DicomServers.class);

    private final List<Device> devices = new ArrayList<Device>();
    private final List<ExecutorService> pools = new ArrayList<ExecutorService>();

    public void start(AdLifeConfig config, ArchiveIndex index)
            throws IOException, GeneralSecurityException {
        PrintRouter router = new PrintRouter(config.printerNames(), config.roundRobin());
        PdfExporter pdf = new PdfExporter(config.pdfDir());

        for (AdLifeConfig.Listener l : config.listeners()) {
            Device device = new Device("adlife-" + l.aeTitle.toLowerCase());
            Connection conn = new Connection();
            conn.setPort(l.port);
            device.addConnection(conn);
            if (config.tlsEnabled()) {
                if (config.tlsKeystore().isEmpty()) {
                    throw new IOException("dicom.tls=true needs dicom.tls.keystore (see config comments)");
                }
                device.setKeyStoreURL("file:" + config.tlsKeystore());
                device.setKeyStoreType("JKS");
                device.setKeyStorePin(config.tlsKeystorePassword());
                conn.setTlsProtocols(Connection.DEFAULT_TLS_PROTOCOLS);
                conn.setTlsCipherSuites(config.tlsCiphers());
                conn.setTlsNeedClientAuth(false);
            }

            ApplicationEntity ae = new ApplicationEntity(l.aeTitle);
            device.addApplicationEntity(ae);
            ae.addConnection(conn);
            ae.setAssociationAcceptor(true);

            for (String cuid : UIDs.PRINT_SOP_CLASSES) {
                ae.addTransferCapability(new TransferCapability(null, cuid,
                        TransferCapability.Role.SCP, UIDs.TRANSFER_SYNTAXES));
            }
            for (String cuid : UIDs.STORAGE_SOP_CLASSES) {
                ae.addTransferCapability(new TransferCapability(null, cuid,
                        TransferCapability.Role.SCP, UIDs.TRANSFER_SYNTAXES));
            }
            ae.addTransferCapability(new TransferCapability(null, UID.VerificationSOPClass,
                    TransferCapability.Role.SCP, UIDs.TRANSFER_SYNTAXES));
            ae.addTransferCapability(new TransferCapability(null,
                    UID.StudyRootQueryRetrieveInformationModelFIND,
                    TransferCapability.Role.SCP, UIDs.TRANSFER_SYNTAXES));
            ae.addTransferCapability(new TransferCapability(null,
                    UID.PatientRootQueryRetrieveInformationModelFIND,
                    TransferCapability.Role.SCP, UIDs.TRANSFER_SYNTAXES));
            ae.addTransferCapability(new TransferCapability(null,
                    UID.StudyRootQueryRetrieveInformationModelMOVE,
                    TransferCapability.Role.SCP, UIDs.TRANSFER_SYNTAXES));
            ae.addTransferCapability(new TransferCapability(null,
                    UID.PatientRootQueryRetrieveInformationModelMOVE,
                    TransferCapability.Role.SCP, UIDs.TRANSFER_SYNTAXES));

            PrintCoordinator coordinator =
                    new PrintCoordinator(config, router, pdf, l.lutProfile);
            String printerLabel = router.hasPrinters() ? "AdLife" : null;

            DicomServiceRegistry registry = new DicomServiceRegistry();
            registry.addDicomService(new BasicCEchoSCP());
            registry.addDicomService(new PrintScp(coordinator, printerLabel));
            registry.addDicomService(new StorageScp(config.archiveDir(), index));
            registry.addDicomService(new FindScp(index));
            registry.addDicomService(new MoveScp(index, config));
            ae.setDimseRQHandler(registry);

            ExecutorService executor = Executors.newCachedThreadPool();
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            device.setExecutor(executor);
            device.setScheduledExecutor(scheduler);
            pools.add(executor);
            pools.add(scheduler);

            device.bindConnections();
            devices.add(device);
            LOG.info("AdLife listening: AE {} on port {} (LUT profile '{}')",
                    l.aeTitle, l.port, l.lutProfile);
        }
    }

    public void stop() {
        for (Device d : devices) {
            try { d.unbindConnections(); } catch (Exception ignored) {}
        }
        devices.clear();
        for (ExecutorService p : pools) p.shutdown();
        pools.clear();
    }

    public String statusText() {
        StringBuilder sb = new StringBuilder();
        for (Device d : devices) {
            for (ApplicationEntity ae : d.getApplicationEntities()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("AE ").append(ae.getAETitle()).append(" listening");
            }
        }
        return sb.length() == 0 ? "no listeners" : sb.toString();
    }
}
