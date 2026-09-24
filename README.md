# AdLife DICOM Workstation

A free DICOM film printer, mini-PACS and viewer for hospitals, diagnostic
centres and clinics that print modality films on ordinary inkjet/laser
printers or dry film, as an alternative to paid tools such as VMS MediPro.

Runs on **Windows XP SP3 through Windows 11**. The Windows installer bundles
its own Java; on other systems any Java 8+ works. No cloud, nothing phones home.

## What it does

**Film printing (DICOM Print SCP)**
- Receives Basic Grayscale and Color print jobs from CT, MRI, ultrasound, DR/CR,
  NM and other modalities.
- Up to **6 listeners** (AE:port), each with its own LUT profile, so each body
  part or modality can get its own print look. Optional *accept any Called AE*
  mode and a calling-AE allow list.
- Up to **6 outputs**: Windows printers, real DICOM film printers
  (`dicom:AET@host:port`, forwarded as a finished sheet or passed through), or
  folders (`folder:C:\path`). Round-robin across idle printers.
- **Routing rules** by listener AE, console AE, film size and colour vs grey.
- **Film sizes** 8x10 to 14x17 in, A4, A3, B4, Letter and a MANUAL size, with
  size remapping (for example print 14x17 jobs on A3).
- **Per-printer calibration**: X/Y offset, scale, fit or edge-to-edge, paper
  and tray per film size.
- **Per-printer image adjustment**: brightness, contrast, dark factor, soften,
  sharpen, invert.
- **Layout styles**: normal, stretch, scatter (gaps), bordered; black or white
  background.
- **Header/footer overlay**: hospital name lines, logos, date/time/AE tokens,
  brand text and a QR code.
- Presentation LUT, VOI window, modality rescale, Min/Max Density and
  MONOCHROME1 polarity honoured; annotation box text printed.
- **PDF of every film** in a dated folder, with optional compression.

**Operations**
- **Jobs / Reprint**: every film is kept (default 15 days) with preview; reprint
  on any printer with any copy count.
- **Logs & counters**: print log (CSV), per-size/per-printer counters, app log
  viewer with filter.
- **Settings screen** with tabs for listeners, printers, routing, film sizes,
  calibration, header/footer, PDF, licence and kiosk.
- **Backup / restore / rescue** (factory reset keeps a backup first).
- **Admin password** protecting Settings, Exit, Kiosk and counter reset.
- **Kiosk mode**: full-screen operator screen (status, counters, recent films,
  Reprint); optional Windows lock policies and Explorer replacement for a
  dedicated print PC. Leaving kiosk needs the admin password.
- Tray pop-ups when a printer fails or a job is suspended.

**Mini-PACS and viewer**
- C-STORE archive, C-FIND, C-MOVE to named destinations, C-ECHO, optional TLS.
- Viewer: window/level, zoom/pan, cine, measurements, PNG export, print,
  DICOM send.

## Optional licensing (off by default)

AdLife is free. If you ever want to hand it to other sites on a per-film or
time-limited basis, it has its own **offline** licensing module. It is
disabled unless `license.enabled=true`, and it never contacts any server.

- Film-count balances per film size (optionally PDFs too), with low-balance
  warnings. At zero, jobs are suspended and kept for reprint.
- Recharge codes and machine-locked licence keys, RSA-signed, one-time use.
- Optional demo period before a key is required.

Issuing codes (on your own PC; keep the private key there):

```
AdLife --issuer keys issuer.key                      # prints license.public.key=... for the site config
AdLife --issuer machine                              # on the site PC: shows its machine ID
AdLife --issuer recharge issuer.key <machineId> 14X17 500
AdLife --issuer key issuer.key <machineId> 2027-12-31 "City Care"
AdLife --issuer apply <configDir> <code>             # apply without the tray (tray: Licence / Recharge)
```

## Install

Download `AdLife-<version>-Setup.exe` from Releases and run it. It installs
AdLife with its own 32-bit Java, Start menu shortcuts, tray autostart and an
uninstaller.

## Build

```
mvn package
```

produces `target/AdLife.jar` (all dependencies shaded).

## Run

```
java -jar AdLife.jar                  # service + system tray
java -jar AdLife.jar --viewer         # also open the viewer
java -jar AdLife.jar --kiosk          # start in kiosk mode
java -jar AdLife.jar --console        # headless, console logging
java -jar AdLife.jar --selftest <configDir> <AE> <port> [color]
                                      # sends a 2x2 test film to a listener
```

## Setup at the clinic

1. Start AdLife once. It creates `AdLife/adlife.properties` in the user home
   and listens as AE `ADLIFE` on port 104.
2. On each modality, add a film printer: host = this PC's IP, port 104,
   AE `ADLIFE`. Print a test film.
3. Open tray > Settings to name printers, add listeners, routing, header/footer,
   calibration and so on. Save restarts the listeners. Every setting is also
   documented in `adlife.properties`.
4. Set an admin password (Settings > Listeners > Admin password) before
   handing the PC to operators.

## Full codec coverage

Every commonly encountered DICOM transfer syntax is decoded in the viewer and
on film with pure-Java decoders (XP SP3-safe, no native libraries):

- RLE lossless
- JPEG baseline 8-bit (grayscale and color)
- JPEG extended sequential 12-bit (1.2.840.10008.1.2.4.51) via AdLife's own
  sequential DCT decoder
- JPEG lossless, selection 1 (8/12/16-bit, 1.2.840.10008.1.2.4.57 and .70)
- JPEG-LS lossless and near-lossless, grayscale and color (including
  sample-interleaved color), via AdLife's own CharLS decoder
- JPEG 2000 lossless and lossy (including 12/16-bit)

All pixel-exact or within-lossy-tolerance against reference decoders in
testing.

## Testing

`--selftest` drives a real DICOM print association against AdLife's own
listeners. v1.1 was checked with 2x2 grey and colour films through header/footer,
layout styles, image adjustment, routing, DICOM forwarding between two
listeners, PDF compression, job storage and licence metering, and the printed
films were inspected visually. It has not been tried on a physical Windows XP
machine.
