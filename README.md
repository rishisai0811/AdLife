# AdLife DICOM Workstation

Open 1:1 alternative to **VMS MediPro DICOM Solution**: a DICOM virtual film
printer plus mini-PACS and viewer, built for hospitals, diagnostic centers and
clinics that want dry-film/paper printing instead of expensive laser film.

Runs on **Windows XP SP3 through Windows 11** (any machine with Java 8+).
One jar, no installer, no cloud.

## What it does

Everything MediPro does:

- **DICOM virtual film printer (Print SCP)** - receives Basic Grayscale *and*
  Color print jobs from CT, MRI, ultrasound, digital X-ray, NM and other
  modalities, simultaneously on multiple AE titles/ports.
- **Prints to ordinary Windows printers** - up to 4 printers at once, with
  round-robin to the next idle printer for fast turnaround.
- **Linear DICOM LUTs per body part** - route each body part to its own
  AE:port listener and give that listener its own LUT profile (gamma/invert),
  MediPro's body-part-wise LUT assignment. VOI windows, modality rescale,
  Presentation LUT (incl. LIN OD) and MONOCHROME1 polarity are honored.
- **Automatic high-resolution PDF of every job** - saved to a dated folder,
  ready to share over WhatsApp/email for instant reporting.
- **PNDT-suitable film sheets** - standard film sizes (8x10 to 14x17 in,
  24x24/24x30 cm, A4/A3), portrait/landscape, STANDARD\c,r layouts,
  magnification filters (REPLICATE/BILINEAR/CUBIC).

And the "extra":

- **Mini-PACS**: C-STORE SCP archive, C-FIND query, C-MOVE relay to named
  destinations, C-ECHO.
- **Viewer**: window/level, zoom/pan, cine, calibrated measurements
  (PixelSpacing), PNG export, Windows print, DICOM send.
- **System tray app**: status, one-click PDF/archive folders, config editor.

## Requirements

- Windows XP SP3 or newer (32/64-bit)
- Java 8 or newer (on XP: Java 8 from Oracle/AdoptOpenJDK archives; on modern
  Windows any current JRE/JDK)
- One or more Windows printers (for hardcopy; PDF archiving works with none)

## Build

```
mvn package
```

produces `target/AdLife.jar` (self-contained, all dependencies shaded).

## Run

```
java -jar target/AdLife.jar            # service + system tray
java -jar target/AdLife.jar --viewer   # also open the viewer
java -jar target/AdLife.jar --console  # headless/console logging
```

## Setup at the clinic

1. Start AdLife once; it creates `AdLife/adlife.properties` in the user home
   and listens as AE `ADLIFE` on port 104.
2. On each modality console, add a **film printer** destination:
   host = the AdLife machine's IP, port 104, AE title `ADLIFE`. Print a test
   film - it lands on the default Windows printer and in `AdLife/PDF/<date>/`.
3. Edit `adlife.properties` (tray menu > Edit Config) to:
   - name your real printers (up to 4, round-robin),
   - add listeners per body part (e.g. `ADLIFE_CT:105:chest, ADLIFE_US:106:soft`)
     with their own LUT gamma,
   - add named DICOM destinations for viewer Send and C-MOVE.
4. Restart AdLife to apply.

## v0.1 limits (honest list)

- Compressed (JPEG/RLE) pixel data is not decoded yet; print traffic from
  modalities is uncompressed, so printing is unaffected - this only limits
  viewing some archived studies.
- Presentation LUT *sequences* are accepted but only IDENTITY / LIN OD shapes
  are applied (sequence application is approximate).
- Min/Max Density calibration and annotation text rendering are not applied.
- No TLS on DICOM associations (XP-era cipher support); run on a trusted LAN.

## Roadmap

- ImageIO-based codec support for compressed studies in the viewer
- Full Presentation LUT sequence application + density calibration
- Windows service wrapper + exe launcher (launch4j)
- Auto WhatsApp share of the PDF folder

License: TBD (private).
