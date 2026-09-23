# KApp - Smart Karaoke & Vocal Remover App

An AI-powered desktop karaoke application built with **PyQt6**, **Demucs**, and **Flask**.

## Features
- **YouTube Karaoke Search**: Search karaoke tracks directly or fetch videos by channel.
- **AI Vocal Remover**: Automatically isolates vocals and instrumental tracks using Demucs.
- **Real-Time Vocal Control**: Instant toggle between original singer and instrumental karaoke accompaniment.
- **Mobile Web Remote**: Scan a QR code on the desktop player to order songs and control playback from any phone on the local Wi-Fi.
- **Local Library**: Manage and re-play downloaded songs without re-downloading.
- **Bilingual UI**: Fully supports English and 简体中文 (Chinese), with bidirectional Simplified & Traditional Chinese search matching.

## Prerequisites
- **Python 3.10+** (64-bit recommended)
- **FFmpeg & FFprobe**: Download `ffmpeg.exe` and `ffprobe.exe` and place them in the project root.
- **Deno** (optional, recommended for yt-dlp): Place `deno.exe` in the project root or add to system PATH.

## Installation & Setup

1. **Clone the repository**:
   ```bash
   git clone https://github.com/your-username/KApp.git
   cd KApp
   ```

2. **Create a virtual environment & activate it**:
   ```bash
   python -m venv venv
   # On Windows:
   venv\Scripts\activate
   ```

3. **Install dependencies**:
   ```bash
   pip install -r requirements.txt
   ```

4. **Run the Application**:
   ```bash
   python karaoke_app.py
   ```
   Or double-click `run.bat`.

## Packaging (Windows Executable)
To build a standalone executable:
```bash
build_nocmd.bat
```
*(Requires Microsoft Visual C++ Build Tools for Nuitka)*
