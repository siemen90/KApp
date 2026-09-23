@echo off
cd /d %~dp0
call venv\Scripts\activate

echo ===================================================
echo 0. 正在检查并更新 Nuitka...
echo ===================================================
python -m pip install --upgrade nuitka

echo ===================================================
echo 1. 正在将 mic.svg 转换为 Windows 程序图标 mic.ico...
echo ===================================================
python -c "from PyQt6.QtWidgets import QApplication; from PyQt6.QtGui import QIcon; from PIL import Image; import sys; app=QApplication(sys.argv); QIcon('mic.svg').pixmap(256, 256).save('temp_icon.png'); Image.open('temp_icon.png').save('mic.ico', format='ICO', sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])"
if exist temp_icon.png del temp_icon.png

echo ===================================================
echo 2. 正在清理旧构建...
echo ===================================================
if exist dist rmdir /s /q dist

echo ===================================================
echo 3. 正在打包（已恢复 torch._decomp 支持）...
echo ===================================================

python -m nuitka ^
    --standalone ^
    --low-memory ^
    --msvc=latest ^
    --enable-plugin=pyqt6 ^
    --include-qt-plugins=multimedia,imageformats ^
    --enable-plugin=torch ^
    --windows-icon-from-ico=mic.ico ^
    --no-deployment-flag=excluded-module-usage ^
    --nofollow-import-to=sympy ^
    --nofollow-import-to=torch._dynamo ^
    --nofollow-import-to=yt_dlp.extractor.lazy_extractors ^
    --include-package-data=yt_dlp ^
    --include-module=yt_dlp ^
    --include-package=demucs ^
    --include-package-data=demucs ^
    --include-package=numpy ^
    --include-package-data=numpy ^
    --include-package=jinja2 ^
    --include-package=werkzeug ^
    --include-package=flask ^
    --include-package-data=flask ^
    --include-package=qrcode ^
    --include-package-data=qrcode ^
    --include-package=zhconv ^
    --include-package-data=zhconv ^
    --include-package=yt_dlp_ejs ^
    --include-package-data=yt_dlp_ejs ^
    --include-data-dir=templates=templates ^
    --include-data-files=mic.svg=mic.svg ^
    --include-data-files=mic.ico=mic.ico ^
    --include-data-files=ffmpeg.exe=ffmpeg.exe ^
    --include-data-files=ffprobe.exe=ffprobe.exe ^
    --include-data-files=deno.exe=deno.exe ^
    --windows-console-mode=disable ^
    --output-dir=dist ^
    karaoke_app.py

echo ===================================================
echo 4. 正在补充多媒体与模型运行依赖...
echo ===================================================
if not exist "dist\karaoke_app.dist\deno.exe" (
    if exist "deno.exe" copy /Y "deno.exe" "dist\karaoke_app.dist\" >nul 2>&1
)
if not exist "dist\karaoke_app.dist\ffmpeg.exe" (
    if exist "ffmpeg.exe" copy /Y "ffmpeg.exe" "dist\karaoke_app.dist\" >nul 2>&1
)
if not exist "dist\karaoke_app.dist\ffprobe.exe" (
    if exist "ffprobe.exe" copy /Y "ffprobe.exe" "dist\karaoke_app.dist\" >nul 2>&1
)
if not exist "dist\karaoke_app.dist\PyQt6\Qt6\plugins\multimedia" (
    xcopy /E /I /Y "venv\Lib\site-packages\PyQt6\Qt6\plugins\multimedia" "dist\karaoke_app.dist\PyQt6\Qt6\plugins\multimedia"
)
if not exist "dist\karaoke_app.dist\PyQt6\Qt6\plugins\imageformats" (
    xcopy /E /I /Y "venv\Lib\site-packages\PyQt6\Qt6\plugins\imageformats" "dist\karaoke_app.dist\PyQt6\Qt6\plugins\imageformats"
)
if not exist "dist\karaoke_app.dist\numpy\core" (
    xcopy /E /I /Y "venv\Lib\site-packages\numpy\core" "dist\karaoke_app.dist\numpy\core"
)
if not exist "dist\karaoke_app.dist\numpy\_core" (
    xcopy /E /I /Y "venv\Lib\site-packages\numpy\_core" "dist\karaoke_app.dist\numpy\_core"
)
if not exist "dist\karaoke_app.dist\demucs\remote" (
    xcopy /E /I /Y "venv\Lib\site-packages\demucs\remote" "dist\karaoke_app.dist\demucs\remote"
)
copy /Y "venv\Lib\site-packages\PyQt6\Qt6\bin\av*.dll" "dist\karaoke_app.dist\" >nul 2>&1
copy /Y "venv\Lib\site-packages\PyQt6\Qt6\bin\sw*.dll" "dist\karaoke_app.dist\" >nul 2>&1

echo 打包完成！
pause