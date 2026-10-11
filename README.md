# KApp - Smart AI Karaoke Player & Vocal Remover
### 智能 AI 卡拉OK播放器与人声分离工作室

[English](#-english) | [中文说明](#-中文说明)

---

## 🌐 English

An all-in-one AI-powered desktop karaoke player and vocal separation studio built with **PyQt6**, **Demucs**, and **Flask**.

### 🌟 Key Features

#### 🎤 1. Intelligent Dual-Window Karaoke Player
- **Dual-Window Experience**: A dedicated **Song Queue Window** for managing tracks and a borderless/full-screen **Video Player Window**.
- **Real-Time Vocal Toggling**: Switch instantly between **Original Singer** (`🎙 Vocals: ON`) and **Karaoke Accompaniment** (`🎙 Vocals: OFF`) with zero delay, preserving exact playback position.
- **Synchronized Audio & Video**: Automatically matches video stream with original audio and AI-separated instrumental track.
- **Smart Queue Management**: Reorder songs, priority "Move to Play Next", replay previous tracks, remove items, and clear already-played songs.

#### 🧠 2. AI & Fast Vocal Remover
- **Demucs High-Quality Separation**: Uses Facebook's state-of-the-art Demucs model (`htdemucs`) to separate vocals and accompaniment with studio clarity.
- **Fast Phase Inversion Mode**: Instant vocal cancellation option for fast playback when AI model processing is not needed.

#### 🔎 3. YouTube Search & Channel Explorer
- **Direct YouTube Search**: Search songs by title or artist directly within the app.
- **Featured Channels Browser**: Browse and fetch videos from designated karaoke channels.
- **Thumbnail & Metadata Caching**: Automatically downloads and stores video thumbnails (`thumbnail.jpg`) and track metadata (`metadata.json`).
- **Flexible Video Quality**: Choose download resolution from 480p up to 4K in Settings.

#### 📱 4. Mobile Phone Remote Control (QR Code)
- **Zero-Configuration Connect**: Scan the QR code displayed on the desktop player with any smartphone on the same local Wi-Fi.
- **Full Web App Capabilities**:
  - Search YouTube & local song library from your phone.
  - Add songs to queue or priority-insert them as next to play.
  - Remote playback controls: Play, Pause, Stop, Skip, Replay.
  - Live vocal toggle, volume slider, and track seekbar.
  - Real-time two-way synchronization powered by Server-Sent Events (SSE).
- **Customizable Web Port**: Default port `8080` can be customized anytime in the Settings menu.

#### 📂 5. Local Song Library ("本地曲库")
- **Instant Replay**: Re-play previously downloaded songs without re-downloading or re-processing vocals.
- **Automatic Thumbnail Generation**: Automatically displays high-resolution thumbnails for all local songs.
- **Bilingual & Pinyin Search**: Fast fuzzy matching supporting English, Simplified Chinese, Traditional Chinese, and Pinyin.
- **Clean Song Management**: Safely delete local songs with automated file cleanup and player handle release.

#### 🔄 6. GitHub Auto-Update Mechanism
- **Startup Check**: Automatically checks for new releases on your GitHub page in the background upon launch.
- **Update Dialog**: Displays version comparison (`current ➔ latest`), release title, and changelog with a 1-click download button.
- **Manual Check**: Check for updates anytime in the Settings dialog.
- **Standardized Versioning**: Versions follow the date format: `YYYYMMDD` (e.g. `20261011`).

#### 📝 7. Comprehensive Event Logging
- **Automated Log Files**: Every session generates a timestamped log in `logs/YYYYMMDDHHMM.log` (24-hour time).
- **Full Activity Capture**: Records application events, console output (stdout/stderr), download activity, and uncaught exceptions.
- **1-Click Log Folder Access**: Open the log folder directly from the Settings dialog.

#### 🛠️ 8. Built-in Dependency Manager
- Automatically detects status and versions for **FFmpeg**, **FFprobe**, and **Deno**.
- Includes 1-click update/download tools directly inside the Settings window.

---

### 📦 Downloads & Releases

Pre-built binaries are available on the [GitHub Releases Page](https://github.com/siemen90/KApp/releases):
- **`Karaoke_Setup_YYYYMMDD.exe`**: Windows desktop installer with desktop and start menu shortcuts (Recommended).
- **`Karaoke_App_Portable_YYYYMMDD.zip`**: Standalone portable version. Extract and run directly without installation.

---

### 📁 Project Structure

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

### 🚀 Getting Started

#### Prerequisites
- **Python 3.10+** (64-bit Windows)
- **Git**

#### 1. Installation
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

#### 2. Run Application
Run directly with Python:
```bash
python karaoke_app.py
```
Or simply double-click `run.bat`.

*(Note: On first run, open Settings to download FFmpeg and Deno with 1 click if they are not already installed.)*

#### 3. Building
To compile a standalone Windows executable:
```bash
build_nocmd.bat
```
The compiled standalone build will be generated in `dist\karaoke_app.dist\`.

To generate a portable ZIP package:
```bash
create_portable_zip.bat
```

---
---

## 🇨🇳 中文说明

一款基于 **PyQt6**、**Demucs AI 深度学习** 与 **Flask** 构建的全功能桌面智能卡拉OK播放器及人声分离点歌系统。

### 🌟 核心功能亮点

#### 🎤 1. 智能双窗口卡拉OK播放体验
- **专业双屏双窗口**：独立的**主控点歌台窗口**（歌曲检索、已点列表、状态调度）与无边框/全屏的**专属视频播放大屏窗口**。
- **原唱/伴奏零延迟秒切**：实时在**原唱**（`🎙 原唱: 开`）与**伴奏**（`🎙 原唱: 关`）之间无缝切换，播放进度精准对齐不中断。
- **音视频实时同步**：自动对齐视频画面、原声音频与 AI 分离伴奏音轨。
- **智能点歌列表调度**：支持自由拖拽排序、插播（优先下一首播放）、重新播放、移除单曲及一键清空已播曲目。

#### 🧠 2. AI 深度学习与快速人声消除
- **Demucs 高品质人声分离**：集成 Facebook 前沿的 Demucs 深度学习模型（`htdemucs`），高保真分离纯净人声与母带级伴奏。
- **极速相位消除模式**：对于无需 AI 模型处理的立体声音频，支持毫秒级声道相位反转人声消除。

#### 🔎 3. YouTube 智能检索与精选频道
- **内置 YouTube 搜索**：直接在软件内搜索歌曲名或歌手名。
- **精选点歌频道浏览**：快速获取精选热门伴奏频道曲目。
- **封面缩略图与元数据缓存**：自动下载并缓存高清视频缩略图（`thumbnail.jpg`）与歌曲详情（`metadata.json`）。
- **多种画质自选**：在设置页面可随心设定 480p 至 4K 超清下载画质。

#### 📱 4. 手机无线扫码点歌（移动端遥控台）
- **零配置即扫即连**：手机连接同一局域网 Wi-Fi，扫描播放器界面二维码即可秒进点歌网页。
- **完整的移动端点歌体验**：
  - 手机端搜索 YouTube 歌曲与本地歌曲曲库。
  - 支持手机端点歌与优先插播。
  - 手机遥控播放：播放、暂停、停止、切歌、重播。
  - 实时伴奏原唱切换、音量调节与播放进度条拖动。
  - 基于 Server-Sent Events (SSE) 的桌面端与移动端双向低延迟状态同步。
- **可自定义 Web 端口**：默认端口 `8080`，可在设置页面根据网络环境自由修改。

#### 📂 5. 本地歌曲曲库管理
- **秒开离线重播**：已下载歌曲存入本地曲库，重播无需重复下载与处理人声。
- **自动缩略图生成与加载**：本地歌曲自动提取并展示高清封面。
- **中英双语与拼音搜索**：支持英文、简体中文、繁体中文以及全拼/首字母拼音模糊搜索。
- **安全曲库清理**：支持安全删除本地歌曲，自动清理关联磁盘文件并解除播放器文件占用。

#### 🔄 6. GitHub 自动更新检测
- **启动自动检查**：每次启动应用时，在后台静默检查 GitHub Releases 最新版本。
- **更新提示对话框**：直观展示版本号对比（`当前版本 ➔ 最新版本`）、更新标题与更新日志，并附带一键直达下载按钮。
- **手动检查更新**：设置对话框内随时支持手动点击检查。
- **规范化版本格式**：采用 `YYYYMMDD` 日期版本规范（例如 `20261011`）。

#### 📝 7. 全方位会话事件日志
- **独立时间戳日志**：每个运行会话自动在 `logs/` 目录下生成 `YYYYMMDDHHMM.log` 文件（24小时制）。
- **完整运行轨迹记录**：自动捕获软件运行状态、控制台输出（stdout/stderr）、下载进度及未捕获异常崩溃堆栈。
- **一键打开日志目录**：设置窗口中提供“打开日志目录”快捷按钮。

#### 🛠️ 8. 内置多媒体依赖管理器
- 自动检测并显示 **FFmpeg**、 **FFprobe** 以及 **Deno** 的安装状态与版本号。
- 设置界面内支持一键下载/更新依赖工具。

---

### 📦 安装包与便携版下载

编译好的现成程序包可在 [GitHub Releases 页面](https://github.com/siemen90/KApp/releases) 下载：
- **`Karaoke_Setup_YYYYMMDD.exe`**：Windows 桌面独立安装程序（推荐，自动在桌面和开始菜单创建快捷方式）。
- **`Karaoke_App_Portable_YYYYMMDD.zip`**：绿色免安装便携版（解压即用，适合存入 U 盘随身携带）。

---

### 🚀 快速上手与运行

#### 环境要求
- **Python 3.10+** (64位 Windows 操作系统)
- **Git**

#### 1. 克隆与安装依赖
```bash
git clone https://github.com/siemen90/KApp.git
cd KApp
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
```

#### 2. 启动程序
通过 Python 启动：
```bash
python karaoke_app.py
```
或直接双击运行 `run.bat`。

*(注：初次运行时，若未检测到 FFmpeg 与 Deno，可进入设置窗口一键下载安装。)*

#### 3. 打包可执行文件
通过 Nuitka 编译生成无控制台的独立桌面程序：
```bash
build_nocmd.bat
```
生成的程序目录位于 `dist\karaoke_app.dist\`。

生成免安装便携 ZIP 压缩包：
```bash
create_portable_zip.bat
```

---

## 📄 License
本项目仅供个人娱乐、学习与技术研究使用。
