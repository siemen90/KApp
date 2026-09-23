import os
import re
import shutil
import zipfile
import subprocess
import urllib.request
import json
from PyQt6.QtCore import QThread, pyqtSignal, QObject

DENO_LATEST_API = "https://api.github.com/repos/denoland/deno/releases/latest"
DENO_DOWNLOAD_URL = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip"

FFMPEG_VERSION_URL = "https://www.gyan.dev/ffmpeg/builds/release-version"
FFMPEG_DOWNLOAD_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"


def get_app_dir() -> str:
    return os.path.dirname(os.path.abspath(__file__))


def get_deno_info() -> dict:
    """Returns status, version, and path for Deno."""
    app_dir = get_app_dir()
    local_path = os.path.join(app_dir, "deno.exe")
    deno_path = local_path if os.path.exists(local_path) else (shutil.which("deno") or "")

    if not deno_path or not os.path.exists(deno_path):
        return {"installed": False, "version": "Not installed", "version_num": "", "path": ""}

    try:
        res = subprocess.run([deno_path, "--version"], capture_output=True, text=True, timeout=4)
        output = res.stdout or ""
        first_line = output.splitlines()[0] if output else "Installed"
        # Extract version number (e.g. 2.9.7)
        match = re.search(r"deno\s+([\d\.]+)", first_line, re.IGNORECASE)
        version_num = match.group(1) if match else ""
        return {
            "installed": True,
            "version": first_line,
            "version_num": version_num,
            "path": deno_path
        }
    except Exception as e:
        return {"installed": True, "version": f"Installed ({e})", "version_num": "", "path": deno_path}


def get_ffmpeg_info() -> dict:
    """Returns status, version, and path for FFmpeg."""
    app_dir = get_app_dir()
    local_path = os.path.join(app_dir, "ffmpeg.exe")
    ffmpeg_path = local_path if os.path.exists(local_path) else (shutil.which("ffmpeg") or "")

    if not ffmpeg_path or not os.path.exists(ffmpeg_path):
        return {"installed": False, "version": "Not installed", "version_num": "", "path": ""}

    try:
        res = subprocess.run([ffmpeg_path, "-version"], capture_output=True, text=True, timeout=4)
        output = res.stdout or ""
        first_line = output.splitlines()[0] if output else "Installed"
        # Extract version number or date (e.g. 7.1 or 2026-07-27)
        match = re.search(r"version\s+([\d\.\-]+)", first_line, re.IGNORECASE)
        version_num = match.group(1) if match else ""
        return {
            "installed": True,
            "version": first_line,
            "version_num": version_num,
            "path": ffmpeg_path
        }
    except Exception as e:
        return {"installed": True, "version": f"Installed ({e})", "version_num": "", "path": ffmpeg_path}


def check_deno_online_version() -> dict:
    """Queries GitHub for the latest Deno release."""
    try:
        req = urllib.request.Request(DENO_LATEST_API, headers={"User-Agent": "KApp-Karaoke"})
        with urllib.request.urlopen(req, timeout=6) as resp:
            data = json.loads(resp.read().decode())
            tag = data.get("tag_name", "").lstrip("v")
            return {"success": True, "latest_version": tag, "download_url": DENO_DOWNLOAD_URL}
    except Exception as e:
        return {"success": False, "error": str(e)}


def check_ffmpeg_online_version() -> dict:
    """Queries Gyan.dev for the latest FFmpeg release."""
    try:
        req = urllib.request.Request(FFMPEG_VERSION_URL, headers={"User-Agent": "KApp-Karaoke"})
        with urllib.request.urlopen(req, timeout=6) as resp:
            ver = resp.read().decode().strip()
            return {"success": True, "latest_version": ver, "download_url": FFMPEG_DOWNLOAD_URL}
    except Exception as e:
        return {"success": False, "error": str(e)}


class DependencyCheckThread(QThread):
    """Background worker to check online versions for Deno and FFmpeg."""
    check_finished = pyqtSignal(str, dict)  # dep_name ("deno" or "ffmpeg"), result_dict

    def __init__(self, dep_name: str, parent=None):
        super().__init__(parent)
        self.dep_name = dep_name

    def run(self):
        if self.dep_name == "deno":
            res = check_deno_online_version()
        else:
            res = check_ffmpeg_online_version()
        self.check_finished.emit(self.dep_name, res)


class DependencyDownloadThread(QThread):
    """Background worker to download and extract FFmpeg or Deno."""
    progress_updated = pyqtSignal(int, str)  # percent, status_text
    download_finished = pyqtSignal(bool, str)  # success, message

    def __init__(self, dep_name: str, download_url: str = None, parent=None):
        super().__init__(parent)
        self.dep_name = dep_name
        self.download_url = download_url or (DENO_DOWNLOAD_URL if dep_name == "deno" else FFMPEG_DOWNLOAD_URL)
        self._is_cancelled = False

    def cancel(self):
        self._is_cancelled = True

    def run(self):
        app_dir = get_app_dir()
        temp_zip = os.path.join(app_dir, f"_temp_{self.dep_name}.zip")
        try:
            self.progress_updated.emit(0, "Connecting...")
            req = urllib.request.Request(self.download_url, headers={"User-Agent": "KApp-Karaoke"})
            with urllib.request.urlopen(req, timeout=30) as response:
                total_size = int(response.headers.get("Content-Length", 0))
                downloaded = 0
                chunk_size = 64 * 1024

                with open(temp_zip, "wb") as out_file:
                    while True:
                        if self._is_cancelled:
                            out_file.close()
                            if os.path.exists(temp_zip):
                                os.remove(temp_zip)
                            self.download_finished.emit(False, "Cancelled")
                            return

                        chunk = response.read(chunk_size)
                        if not chunk:
                            break
                        out_file.write(chunk)
                        downloaded += len(chunk)

                        if total_size > 0:
                            pct = int((downloaded / total_size) * 85)
                            self.progress_updated.emit(pct, f"Downloading ({downloaded // (1024*1024)}MB / {total_size // (1024*1024)}MB)...")
                        else:
                            self.progress_updated.emit(50, f"Downloading ({downloaded // (1024*1024)}MB)...")

            # Extraction phase
            self.progress_updated.emit(88, "Extracting...")
            with zipfile.ZipFile(temp_zip, "r") as zip_ref:
                if self.dep_name == "deno":
                    # Look for deno.exe inside zip
                    found = False
                    for member in zip_ref.namelist():
                        if os.path.basename(member).lower() == "deno.exe":
                            target_dest = os.path.join(app_dir, "deno.exe")
                            with zip_ref.open(member) as src, open(target_dest, "wb") as dst:
                                shutil.copyfileobj(src, dst)
                            found = True
                            break
                    if not found:
                        raise RuntimeError("deno.exe not found in archive")
                else:
                    # FFmpeg: look for ffmpeg.exe and ffprobe.exe
                    found_ffmpeg = False
                    found_ffprobe = False
                    for member in zip_ref.namelist():
                        base = os.path.basename(member).lower()
                        if base == "ffmpeg.exe":
                            target_dest = os.path.join(app_dir, "ffmpeg.exe")
                            with zip_ref.open(member) as src, open(target_dest, "wb") as dst:
                                shutil.copyfileobj(src, dst)
                            found_ffmpeg = True
                        elif base == "ffprobe.exe":
                            target_dest = os.path.join(app_dir, "ffprobe.exe")
                            with zip_ref.open(member) as src, open(target_dest, "wb") as dst:
                                shutil.copyfileobj(src, dst)
                            found_ffprobe = True

                    if not found_ffmpeg:
                        raise RuntimeError("ffmpeg.exe not found in archive")

            self.progress_updated.emit(100, "Installation complete")
            self.download_finished.emit(True, "Successfully installed")
        except Exception as e:
            self.download_finished.emit(False, str(e))
        finally:
            if os.path.exists(temp_zip):
                try:
                    os.remove(temp_zip)
                except Exception:
                    pass


class DependencyManager(QObject):
    """Manages background downloads so they continue running even if UI dialogs are closed."""
    _instance = None
    download_completed = pyqtSignal(str, bool, str)  # dep_name, success, message

    def __init__(self):
        super().__init__()
        self.active_threads = {}      # dep_name -> DependencyDownloadThread
        self.current_progress = {}    # dep_name -> (percent, status_text)
        self.last_result = {}         # dep_name -> (success, message)

    @classmethod
    def get_instance(cls):
        if cls._instance is None:
            cls._instance = DependencyManager()
        return cls._instance

    def is_downloading(self, dep_name: str) -> bool:
        thread = self.active_threads.get(dep_name)
        return thread is not None and thread.isRunning()

    def get_progress(self, dep_name: str):
        return self.current_progress.get(dep_name, (0, ""))

    def get_thread(self, dep_name: str):
        return self.active_threads.get(dep_name)

    def start_download(self, dep_name: str, download_url: str = None):
        if self.is_downloading(dep_name):
            return self.active_threads[dep_name]

        thread = DependencyDownloadThread(dep_name, download_url)
        self.active_threads[dep_name] = thread
        self.current_progress[dep_name] = (0, "Connecting...")

        def _on_progress(pct, txt):
            self.current_progress[dep_name] = (pct, txt)

        def _on_finished(ok, msg):
            self.last_result[dep_name] = (ok, msg)
            if ok:
                self.current_progress[dep_name] = (100, "Installation complete")
            if dep_name in self.active_threads and self.active_threads[dep_name] is thread:
                del self.active_threads[dep_name]
            self.download_completed.emit(dep_name, ok, msg)

        thread.progress_updated.connect(_on_progress)
        thread.download_finished.connect(_on_finished)
        thread.start()
        return thread

    def cancel_all(self):
        for thread in list(self.active_threads.values()):
            if thread.isRunning():
                thread.cancel()
        self.active_threads.clear()

