package com.adlife.dicom.print;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dcm4che3.data.Attributes;

/** One film sheet under construction: box-level attributes plus per-cell image data. */
public class FilmBox {
    public final String boxUID;
    public final String sessionUID;
    public final boolean color;
    public Attributes attrs;                 // box-level (from N-CREATE + later N-SET)
    public final String[] imageBoxUIDs;      // cells, position-1 == index 0
    public final Attributes[] imageData;     // N-SET data per cell
    public String presentationLutUID;        // referenced Presentation LUT instance, or null
    public final Map<Integer, String> annotations = new LinkedHashMap<Integer, String>(); // position -> text

    public FilmBox(String boxUID, String sessionUID, boolean color, Attributes attrs,
                   String[] imageBoxUIDs, String presentationLutUID) {
        this.boxUID = boxUID;
        this.sessionUID = sessionUID;
        this.color = color;
        this.attrs = attrs;
        this.imageBoxUIDs = imageBoxUIDs;
        this.imageData = new Attributes[imageBoxUIDs.length];
        this.presentationLutUID = presentationLutUID;
    }

    public int cellIndexFor(String imageBoxUID) {
        for (int i = 0; i < imageBoxUIDs.length; i++) {
            if (imageBoxUIDs[i].equals(imageBoxUID)) return i;
        }
        return -1;
    }

    public boolean isComplete() {
        for (Attributes a : imageData) if (a != null) return true;
        return false; // a print with no images at all is an error
    }
}
