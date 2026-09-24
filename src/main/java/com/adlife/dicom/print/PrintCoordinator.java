package com.adlife.dicom.print;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Association;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adlife.dicom.config.AdLifeConfig;
import com.adlife.dicom.imaging.LutProfile;
import com.adlife.dicom.imaging.PixelRenderer;
import com.adlife.dicom.pdf.PdfExporter;

/**
 * Glue between the Print SCP and the output side: applies the LUT profile for
 * the receiving listener / calling modality, renders the film sheet, then
 * spools to the print router and archives the PDF.
 */
public class PrintCoordinator implements PrintScp.PrintJobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PrintCoordinator.class);

    private final AdLifeConfig config;
    private final PrintRouter router;
    private final PdfExporter pdf;
    private final String listenerLutProfile;
    private LutProfile lutProfile;

    public PrintCoordinator(AdLifeConfig config, PrintRouter router, PdfExporter pdf,
                            String listenerLutProfile) {
        this.config = config;
        this.router = router;
        this.pdf = pdf;
        this.listenerLutProfile = listenerLutProfile;
    }

    /** Min/Max Density (hundredths of OD) from the film box, as {minOD, maxOD}, or null. */
    private static double[] densityRange(FilmBox box) {
        int minD = box.attrs.getInt(Tag.MinDensity, -1);
        int maxD = box.attrs.getInt(Tag.MaxDensity, -1);
        if (minD < 0 && maxD < 0) return null;
        double minOD = (minD >= 0 ? minD : 0) / 100.0;
        double maxOD = (maxD >= 0 ? maxD : 300) / 100.0;
        if (maxOD <= minOD) return null;
        return new double[] { minOD, maxOD };
    }

    @Override
    public void onPrintJob(FilmBox box, Attributes sessionAttrs, Association as,
                           Attributes presentationLut) {
        String sourceAet = as != null ? as.getCallingAET() : null;
        try {
            PrinterScheme scheme = config.printerSchemeFor(router != null ? router.peekNextPrinterName() : null);
            String profileName = config.lutProfileForCallingAet(sourceAet);
            if (profileName == null && scheme != null) {
                lutProfile = scheme.toLutProfile();
                profileName = scheme.name;
            }
            if (profileName == null) profileName = listenerLutProfile;
            LutProfile profile = lutProfile != null ? lutProfile : LutProfile.load(config, profileName);
            byte[] lut = profile.buildLut();

            BufferedImage[] cells = new BufferedImage[box.imageBoxUIDs.length];
            for (int i = 0; i < cells.length; i++) {
                Attributes imgData = box.imageData[i];
                if (imgData == null) continue;
                try {
                    double[] density = densityRange(box);
                    if (density == null && scheme != null) density = scheme.densityOD();
                cells[i] = PixelRenderer.renderImageBox(imgData, box.color, lut, presentationLut, density);
                } catch (IOException e) {
                    LOG.error("Cell {} render failed: {}", i + 1, e.toString());
                }
            }

            int dpi = (scheme != null && scheme.dpi > 0) ? scheme.dpi : config.filmDpi();
            BufferedImage sheet = FilmLayout.renderSheet(box, cells, dpi);

            String sizeId = box.attrs.getString(Tag.FilmSizeID);
            if (sizeId == null && scheme != null) sizeId = scheme.filmSizeId;
            double[] size = FilmLayout.filmSizeInches(sizeId);
            boolean landscape = "LANDSCAPE".equalsIgnoreCase(box.attrs.getString(Tag.FilmOrientation));
            double wIn = landscape ? size[1] : size[0];
            double hIn = landscape ? size[0] : size[1];

            if (config.pdfEnabled() && pdf != null) {
                pdf.export(sheet, wIn, hIn, sourceAet);
            }

            int copies = sessionAttrs != null ? sessionAttrs.getInt(Tag.NumberOfCopies, 1) : 1;
            String jobName = "AdLife film " + box.boxUID;
            router.submit(sheet, wIn, hIn, copies, jobName);
        } catch (Exception e) {
            LOG.error("Print job failed: {}", e.toString(), e);
        }
    }
}
