; Karaoke 软件安装包配置脚本

#define MyAppName "Karaoke Player"
#define MyAppVersion "1.0"
#define MyAppPublisher "KaraokeApp"
#define MyAppExeName "Karaoke_App.exe"

[Setup]
; 基础信息
AppId={{C8E6F5A1-9B2D-4C1F-8E3A-7A5D2C1B9E0F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\{#MyAppName}
DefaultGroupName={#MyAppName}

; 权限设置：安装到用户目录无需管理员权限，避免下载歌曲时出现 Program Files 权限被拒错误
PrivilegesRequired=lowest

; 生成的安装包属性
OutputDir=C:\project\KApp\installer
OutputBaseFilename=Karaoke_Setup_v1.0
SetupIconFile=C:\project\KApp\mic.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: checkedonce

[Files]
; 打包 dist\karaoke_app.dist 下的所有文件和子文件夹
Source: "C:\project\KApp\dist\karaoke_app.dist\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; 开始菜单快捷方式
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\mic.ico"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"

; 桌面快捷方式
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\mic.ico"; Tasks: desktopicon

[Run]
; 安装完成后提示启动
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent