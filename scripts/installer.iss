; CloudStreamPC installer for Windows (Inno Setup 6), built from the slimmed app image.
; LZMA2 solid compression makes it much smaller than an MSI. Installed for the current user only
; (no administrator rights). An older MSI installation (versions up to 2.6) is removed first.
;
; iscc /DAppVersion=2.7.0 /DSourceDir=<app image> /DOutputDir=<dist> scripts\installer.iss

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{B6A3C1E2-7F4D-4E8A-9C11-3D5E2F8A6B70}
AppName=CloudStreamPC
AppVersion={#AppVersion}
AppVerName=CloudStreamPC {#AppVersion}
AppPublisher=CloudStreamPC
AppPublisherURL=https://github.com/Nikola17/CloudStreamPC
AppSupportURL=https://github.com/Nikola17/CloudStreamPC/issues
DefaultDirName={localappdata}\Programs\CloudStreamPC
DefaultGroupName=CloudStreamPC
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=CloudStreamPC-{#AppVersion}-setup
SetupIconFile=..\icons\icon.ico
UninstallDisplayIcon={app}\CloudStreamPC.exe
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
LZMANumBlockThreads=4
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "fr"; MessagesFile: "compiler:Languages\French.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
; Files of the previous version that this one no longer has.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Icons]
Name: "{group}\CloudStreamPC"; Filename: "{app}\CloudStreamPC.exe"
Name: "{autodesktop}\CloudStreamPC"; Filename: "{app}\CloudStreamPC.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\CloudStreamPC.exe"; Description: "{cm:LaunchProgram,CloudStreamPC}"; Flags: nowait postinstall skipifsilent
; Update from inside the app (silent install): start it again once installed.
Filename: "{app}\CloudStreamPC.exe"; Flags: nowait; Check: WizardSilent

[Code]
// Versions up to 2.6 were MSI packages (upgrade code below): remove them so only one copy stays.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -ExecutionPolicy Bypass -Command "$i = New-Object -ComObject WindowsInstaller.Installer; ' +
    'foreach ($p in $i.RelatedProducts(''{6F1B3F0E-2B8E-4C39-9D8E-6C1C2F5A7E41}'')) { ' +
    'Start-Process msiexec.exe -ArgumentList ''/x'', $p, ''/qn'' -Wait }"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;
