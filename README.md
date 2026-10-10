# KApp - Smart AI Karaoke Player & Vocal Remover

An all-in-one AI-powered desktop karaoke player and vocal separation studio built with **PyQt6**, **Demucs**, and **Flask**.

---

## 🌟 Key Features

### 🎤 1. Intelligent Dual-Window Karaoke Player
- **Dual-Window Experience**: A dedicated **Song Queue Window** for managing tracks and a borderless/full-screen **Video Player Window**.
- **Real-Time Vocal Toggling**: Switch instantly between **Original Singer** (`🎙 Vocals: ON`) and **Karaoke Accompaniment** (`🎙 Vocals: OFF`) with zero delay, preserving exact playback position.
- **Synchronized Audio & Video**: Automatically matches video stream with original audio and AI-separated instrumental track.
- **Smart Queue Management**: Reorder songs, priority "Move to Play Next", replay previous tracks, remove items, and clear already-played songs.

### 🧠 2. AI & Fast Vocal Remover
- **Demucs High-Quality Separation**: Uses Facebook's state-of-the-art Demucs model (`htdemucs`) to separate vocals and accompaniment with studio clarity.
- **Fast Phase Inversion Mode**: Instant vocal cancellation option for fast playback when AI model processing is not needed.

### 🔎 3. YouTube Search & Channel Explorer
- **Direct YouTube Search**: Search songs by title or artist directly within the app.
- **Featured Channels Browser**: Browse and fetch videos from designated karaoke channels.
- **Thumbnail & Metadata Caching**: Automatically downloads and stores video thumbnails (`thumbnail.jpg`) and track metadata (`metadata.json`).
- **Flexible Video Quality**: Choose download resolution from 480p up to 4K in Settings.

### 📱 4. Mobile Phone Remote Control (QR Code)
- **Zero-Configuration Connect**: Scan the QR code displayed on the desktop player with any smartphone on the same local Wi-Fi.
- **Full Web App Capabilities**:
  - Search YouTube & local song library from your phone.
  - Add songs to queue or priority-insert them as next to play.
  - Remote playback controls: Play, Pause, Stop, Skip, Replay.
  - Live vocal toggle, volume slider, and track seekbar.
  - Real-time two-way synchronization powered by Server-Sent Events (SSE).
- **Customizable Web Port**: Default port `8080` can be customized anytime in the Settings menu.

### 📂 5. Local Song Library ("本地曲库")
- **Instant Replay**: Re-play previously downloaded songs without re-downloading or re-processing vocals.
- **Automatic Thumbnail Generation**: Automatically displays high-resolution thumbnails for all local songs.
- **Bilingual & Pinyin Search**: Fast fuzzy matching supporting English, Simplified Chinese, Traditional Chinese, and Pinyin.
- **Clean Song Management**: Safely delete local songs with automated file cleanup and player handle release.

### 🔄 6. GitHub Auto-Update Mechanism
- **Startup Check**: Automatically checks for new releases on your GitHub page in the background upon launch.
- **Update Dialog**: Displays version comparison (`current ➔ latest`), release title, and changelog with a 1-click download button.
- **Manual Check**: Check for updates anytime in the Settings dialog.
- **Standardized Versioning**: Versions follow the 24-hour timestamp format: `YYYYMMDDHHMM` (e.g. `202610110508`).

### 📝 7. Comprehensive Event Logging
- **Automated Log Files**: Every session generates a timestamped log in `logs/YYYYMMDDHHMM.log`.
- **Full Activity Capture**: Records application events, console output (stdout/stderr), download activity, and uncaught exceptions.
- **1-Click Log Folder Access**: Open the log folder directly from the Settings dialog.

### 🛠️ 8. Built-in Dependency Manager
- Automatically detects status and versions for **FFmpeg**, **FFprobe**, and **Deno**.
- Includes 1-click update/download tools directly inside the Settings window.

---

## 📁 Project Structure

```
KApp/
├── karaoke_app.py            # Main application entry point & PyQt6 GUI
├── app_updater.py            # GitHub auto-update checker and prompt dialog
├── app_logger.py             # Event logger (logs/YYYYMMDDHHMM.log)
├── settings_manager.py       # Configuration manager & version generator
├── dependency_manager.py     # FFmpeg & Deno installer and version checker
├── vocal_remover.py          # Demucs AI & phase-inversion vocal separation
├── youtube_fetcher.py        # YouTube search, channel fetcher & downloader
├── web_server.py             # Flask server for mobile phone remote control
├── chinese_utils.py          # Chinese conversion (Pinyin, Simplified, Traditional)
├── audio_mixer.py            # Audio stream mixing helper
├── lyrics_fetcher.py         # Lyrics retrieval helper
├── i18n.py                   # Internationalization (English & Simplified Chinese)
│
├── templates/
│   └── index.html            # Mobile remote control responsive web page
│
├── installer/
│   ├── Karaoke_Setup_*.exe   # Inno Setup Windows installer
│   └── Karaoke_App_*.zip     # Standalone portable zip package
│
├── installer.iss             # Inno Setup compilation script
├── build.bat                 # Nuitka executable builder (with console)
├── build_nocmd.bat           # Nuitka executable builder (windowed mode)
├── create_portable_zip.bat   # 1-Click portable zip packager
├── run.bat                   # Quick launcher script
├── git_backup.bat            # Automated Git sync and backup tool
├── requirements.txt          # Python package dependencies
├── mic.ico / mic.svg         # Application icons
├── .gitignore                # Git ignore rules
└── logs/                     # Application event logs (excluded from Git)
```

---

## 🚀 Getting Started

### Prerequisites
- **Python 3.10+** (64-bit Windows)
- **Git** (for version control)

### 1. Installation
Clone the repository:
```bash
git clone https://github.com/siemen90/KApp.git
cd KApp
```

Set up a virtual environment:
```bash
python -m venv venv
venv\Scripts\activate
```

Install dependencies:
```bash
pip install -r requirements.txt
```

### 2. Run Application
Run directly with Python:
```bash
python karaoke_app.py
```
Or simply double-click `run.bat`.

*(Note: On first run, open Settings to download FFmpeg and Deno with 1 click if they are not already installed.)*

---

## 📦 Building & Publishing Releases

### 1. Build Standalone Executable
Compile using Nuitka:
```bash
build_nocmd.bat
```
The compiled standalone build will be generated in `dist\karaoke_app.dist\`.

### 2. Generate Installer & Portable ZIP
- **Installer (`.exe`)**: Open `installer.iss` in Inno Setup Compiler and click **Compile** (output is saved to `installer/`).
- **Portable ZIP (`.zip`)**: Double-click `create_portable_zip.bat` to automatically bundle `dist\karaoke_app.dist\` into `installer/Karaoke_App_Portable_<version>.zip`.

### 3. Publish to GitHub Releases
1. Navigate to: `https://github.com/siemen90/KApp/releases`
2. Click **"Draft a new release"**.
3. Choose a tag matching your build version (e.g., `202610110508`).
4. Drag and drop both the **Installer (`.exe`)** and **Portable ZIP (`.zip`)** from the `installer/` directory.
5. Click **Publish release**. All connected users will be notified on their next app startup!

---

## 📄 License
This project is for personal and educational use.
