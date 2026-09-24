package com.adlife.dicom.dcm;

import org.dcm4che3.data.UID;

/** UID groups AdLife offers on the network. */
public final class UIDs {

    private UIDs() {}

    public static final String[] TRANSFER_SYNTAXES = {
        UID.ImplicitVRLittleEndian,
        UID.ExplicitVRLittleEndian,
        UID.ExplicitVRBigEndianRetired
    };

    public static final String[] PRINT_SOP_CLASSES = {
        UID.BasicGrayscalePrintManagementMetaSOPClass,
        UID.BasicColorPrintManagementMetaSOPClass,
        UID.PresentationLUTSOPClass
    };

    public static final String[] STORAGE_SOP_CLASSES = {
        UID.ComputedRadiographyImageStorage,
        UID.DigitalXRayImageStorageForPresentation,
        UID.DigitalXRayImageStorageForProcessing,
        UID.DigitalMammographyXRayImageStorageForPresentation,
        UID.CTImageStorage,
        UID.EnhancedCTImageStorage,
        UID.MRImageStorage,
        UID.EnhancedMRImageStorage,
        UID.UltrasoundImageStorage,
        UID.UltrasoundMultiFrameImageStorage,
        UID.NuclearMedicineImageStorage,
        UID.PositronEmissionTomographyImageStorage,
        UID.XRayAngiographicImageStorage,
        UID.XRayRadiofluoroscopicImageStorage,
        UID.SecondaryCaptureImageStorage,
        UID.VLPhotographicImageStorage,
        UID.RTImageStorage,
        UID.SegmentationStorage,
        UID.KeyObjectSelectionDocumentStorage,
        UID.GrayscaleSoftcopyPresentationStateStorage,
        UID.BasicTextSRStorage,
        UID.EnhancedSRStorage,
        UID.ComprehensiveSRStorage,
        UID.MammographyCADSRStorage,
        UID.EncapsulatedPDFStorage
    };
}
