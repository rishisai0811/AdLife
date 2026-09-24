# Building the AdLife Windows installer

The installer (`AdLife-<version>-Setup.exe`) is a 32-bit NSIS package: a
launch4j-wrapped `AdLife.exe` plus a trimmed 32-bit Windows JRE 8 whose jars
are pack200-compressed and unpacked at install time. Everything below runs on
Linux. Target: Windows XP SP3 through Windows 11 (32-bit safe).

## Layout

```
installer/
  adlife.nsi          NSIS script (this repo)
  adlife-l4j.xml      launch4j config (this repo; fix the absolute paths inside)
  stage/
    app/AdLife.exe    output of launch4j
    jre/              trimmed 32-bit Windows JRE 8, jars pack200'd
```

## Steps

1. Build the shaded jar (JDK 11 + Maven 3.9):
   `mvn -q package`  ->  `target/AdLife.jar`

2. Wrap it with launch4j (Linux build works fine):
   edit `adlife-l4j.xml` so `<jar>` points at `target/AdLife.jar` and
   `<outfile>` at `installer/stage/app/AdLife.exe`, bump the version fields,
   then run `launch4j adlife-l4j.xml`.
   The config bundles the jar inside the exe and points it at the relative
   `jre` directory the installer lays down next to it.

3. Stage the JRE. Unzip a 32-bit Windows JRE 8 (e.g. AdoptOpenJDK 8u292
   x86-32 Windows) into `installer/stage/jre`. Keep `bin/unpack200.exe`.
   Then pack200 every jar in place and delete the originals:
   `pack200 --no-gzip -E9 --strip-debug <jar>.pack <jar>` (JDK 8's pack200;
   use `-E4` for `lib/rt.jar` - it round-trips more reliably).
   Jars packed for v1.2: lib/rt.jar, lib/resources.jar, lib/jsse.jar,
   lib/jce.jar, lib/ext/{dnsns,sunec,sunjce_provider,sunmscapi,sunpkcs11}.jar
   The NSIS script unpacks them again during install via the bundled
   `unpack200.exe`; keep the UNPACK macro list in `adlife.nsi` in sync with
   whatever you packed.

4. Build the installer. NSIS 3.08 from the Debian package works without
   installing system-wide: extract the .deb, then
   `NSISDIR=<extracted>/usr/share/nsis <extracted>/usr/bin/makensis -V1 adlife.nsi`
   from the `installer/` directory with `stage/` populated.
   Output: `AdLife-1.2-Setup.exe` (~17.6 MB, under GitHub's 25 MB web-upload
   limit for release assets).

## Verifying

- `unzip -l stage/app/AdLife.exe` should list the app's classes (jar is
  embedded in the exe).
- After install on Windows, the log shows each unpack200 line; a non-zero
  code pops a warning and AdLife will not start - repack that jar with `-E4`.
- Bump `OutFile`, `DisplayVersion`, and the launch4j version fields together
  each release.
