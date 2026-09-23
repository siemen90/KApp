import os
import json
import shutil
import subprocess
import datetime

SETTINGS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "settings.json")

QUALITY_OPTIONS = ["480p", "720p", "1080p", "2K", "4K"]
DEFAULT_QUALITY = "720p"
DEFAULT_LANGUAGE = "en"
DEFAULT_DOWNLOAD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "downloads")

# Version format: vYYYYMMDD-HHMM (24-hour format)
APP_VERSION = "v20260921-0132"

def get_app_version() -> str:
    """Returns application version formatted as vYYYYMMDD-HHMM based on latest code changes."""
    try:
        app_dir = os.path.dirname(os.path.abspath(__file__))
        py_files = [
            os.path.join(app_dir, f)
            for f in os.listdir(app_dir)
            if f.endswith((".py", ".bat", ".iss"))
        ]
        if py_files:
            latest_mtime = max(os.path.getmtime(p) for p in py_files)
            dt = datetime.datetime.fromtimestamp(latest_mtime)
            dynamic_ver = "v" + dt.strftime("%Y%m%d-%H%M")
            return max(dynamic_ver, APP_VERSION)
    except Exception:
        pass
    return APP_VERSION

class SettingsManager:
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super(SettingsManager, cls).__new__(cls)
            cls._instance._load()
        return cls._instance

    def _load(self):
        self.settings = {
            "language": DEFAULT_LANGUAGE,
            "video_quality": DEFAULT_QUALITY,
            "download_dir": DEFAULT_DOWNLOAD_DIR,
        }
        if os.path.exists(SETTINGS_FILE):
            try:
                with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    if isinstance(data, dict):
                        self.settings.update(data)
            except Exception as e:
                print(f"Error loading settings: {e}")

        # Ensure download directory is absolute and exists
        dl = self.settings.get("download_dir", DEFAULT_DOWNLOAD_DIR)
        if not os.path.isabs(dl):
            dl = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), dl))
            self.settings["download_dir"] = dl
        try:
            os.makedirs(dl, exist_ok=True)
        except Exception:
            pass

    def save(self):
        try:
            with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
                json.dump(self.settings, f, indent=4, ensure_ascii=False)
        except Exception as e:
            print(f"Error saving settings: {e}")

    def get_language(self) -> str:
        lang = self.settings.get("language", DEFAULT_LANGUAGE)
        return lang if lang in ("en", "zh") else DEFAULT_LANGUAGE

    def set_language(self, lang: str):
        if lang in ("en", "zh"):
            self.settings["language"] = lang
            self.save()

    def get_video_quality(self) -> str:
        q = self.settings.get("video_quality", DEFAULT_QUALITY)
        return q if q in QUALITY_OPTIONS else DEFAULT_QUALITY

    def set_video_quality(self, quality: str):
        if quality in QUALITY_OPTIONS:
            self.settings["video_quality"] = quality
            self.save()

    def get_download_dir(self) -> str:
        dl = self.settings.get("download_dir", DEFAULT_DOWNLOAD_DIR)
        if not os.path.isabs(dl):
            dl = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), dl))
        return dl

    def set_download_dir(self, path: str):
        if path:
            abs_path = os.path.abspath(path)
            try:
                os.makedirs(abs_path, exist_ok=True)
                self.settings["download_dir"] = abs_path
                self.save()
            except Exception as e:
                print(f"Failed to create download dir {abs_path}: {e}")

    @staticmethod
    def get_max_height(quality: str) -> int:
        q = (quality or "").strip().upper()
        if q == "480P":
            return 480
        elif q == "720P":
            return 720
        elif q == "1080P":
            return 1080
        elif q in ("1440P", "2K"):
            return 1440
        elif q in ("2160P", "4K"):
            return 2160
        return 720

    @staticmethod
    def get_storage_space(path: str = None) -> str:
        target = path or SettingsManager().get_download_dir()
        try:
            if not os.path.exists(target):
                target = os.path.dirname(os.path.abspath(__file__))
            total, used, free = shutil.disk_usage(target)
            free_gb = free / (1024 ** 3)
            total_gb = total / (1024 ** 3)
            return f"{free_gb:.1f} GB / {total_gb:.1f} GB"
        except Exception:
            return "N/A"

    @staticmethod
    def get_ffmpeg_info() -> dict:
        base_dir = os.path.dirname(os.path.abspath(__file__))
        ffmpeg_path = os.path.join(base_dir, "ffmpeg.exe")
        if not os.path.exists(ffmpeg_path):
            ffmpeg_path = shutil.which("ffmpeg") or ""

        if not ffmpeg_path or not os.path.exists(ffmpeg_path):
            return {"installed": False, "version": "Not found", "path": ""}

        try:
            res = subprocess.run(
                [ffmpeg_path, "-version"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=3
            )
            first_line = res.stdout.splitlines()[0] if res.stdout else "Installed"
            return {
                "installed": True,
                "version": first_line,
                "path": ffmpeg_path
            }
        except Exception as e:
            return {"installed": True, "version": f"Installed ({e})", "path": ffmpeg_path}

    @staticmethod
    def get_version() -> str:
        return get_app_version()
