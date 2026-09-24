; AdLife DICOM Workstation installer - Windows XP SP3 through Windows 11 (32-bit)
Unicode true
Name "AdLife DICOM Workstation"
OutFile "AdLife-1.2-Setup.exe"
InstallDir "$PROGRAMFILES32\AdLife"
InstallDirRegKey HKLM "Software\AdLife" "InstallDir"
RequestExecutionLevel admin
SetCompressor /SOLID lzma
ShowInstDetails show

!include "MUI2.nsh"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "AdLife"
  SetOutPath "$INSTDIR"
  File "stage\app\AdLife.exe"
  SetOutPath "$INSTDIR\jre"
  File /r "stage\jre\*.*"

  DetailPrint "Unpacking Java runtime (one-time, can take a minute)..."
  !macro UNPACK rel
    nsExec::ExecToLog '"$INSTDIR\jre\bin\unpack200.exe" -r "$INSTDIR\${rel}.pack" "$INSTDIR\${rel}"'
    Pop $0
    ${If} $0 != 0
      MessageBox MB_OK "Failed to unpack ${rel} (code $0). AdLife may not start - please reinstall."
    ${EndIf}
  !macroend
  !insertmacro UNPACK "jre\lib\rt.jar"
  !insertmacro UNPACK "jre\lib\resources.jar"
  !insertmacro UNPACK "jre\lib\jsse.jar"
  !insertmacro UNPACK "jre\lib\jce.jar"
  !insertmacro UNPACK "jre\lib\ext\dnsns.jar"
  !insertmacro UNPACK "jre\lib\ext\sunec.jar"
  !insertmacro UNPACK "jre\lib\ext\sunjce_provider.jar"
  !insertmacro UNPACK "jre\lib\ext\sunmscapi.jar"
  !insertmacro UNPACK "jre\lib\ext\sunpkcs11.jar"

  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKLM "Software\AdLife" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AdLife" "DisplayName" "AdLife DICOM Workstation"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AdLife" "DisplayVersion" "1.2"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AdLife" "UninstallString" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AdLife" "DisplayIcon" "$INSTDIR\AdLife.exe"
  CreateDirectory "$SMPROGRAMS\AdLife"
  CreateShortcut "$SMPROGRAMS\AdLife\AdLife.lnk" "$INSTDIR\AdLife.exe" "" "$INSTDIR\AdLife.exe" 0
  CreateShortcut "$DESKTOP\AdLife.lnk" "$INSTDIR\AdLife.exe" "" "$INSTDIR\AdLife.exe" 0
SectionEnd

Section "Uninstall"
  RMDir /r "$INSTDIR"
  Delete "$SMPROGRAMS\AdLife\AdLife.lnk"
  RMDir "$SMPROGRAMS\AdLife"
  Delete "$DESKTOP\AdLife.lnk"
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\AdLife"
  DeleteRegKey HKLM "Software\AdLife"
      SectionEnd
