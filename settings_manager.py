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
DEFAULT_WEB_PORT = 8080

# Version format: YYYYMMDD
APP_VERSION = "20261011"

def get_app_version() -> str:
    """Returns application version formatted as YYYYMMDD."""
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
            dynamic_ver = dt.strftime("%Y%m%d")
            return max(dynamic_ver, APP_VERSION[:8])
    except Exception:
        pass
    return APP_VERSION[:8]

def get_pe_version(ver_str: str = None) -> str:
    """Converts YYYYMMDD version string to X.X.X.X suitable for Windows PE / Nuitka."""
    if ver_str is None:
        ver_str = get_app_version()
    ver_str = str(ver_str).strip()
    if ver_str.count(".") == 3 and all(p.isdigit() and 0 <= int(p) <= 65535 for p in ver_str.split(".")):
        return ver_str
    if len(ver_str) >= 8 and ver_str[:8].isdigit():
        y = int(ver_str[0:4])
        m = int(ver_str[4:6])
        d = int(ver_str[6:8])
        return f"{y}.{m}.{d}.0"
    return "1.0.0.0"

def sync_version_files(target_version: str = None) -> dict:
    """
    Synchronizes APP_VERSION in settings_manager.py and MyAppVersion in installer.iss
    with target_version (defaulting to current date YYYYMMDD).
    Returns dict with 'app_version' and 'pe_version'.
    """
    app_dir = os.path.dirname(os.path.abspath(__file__))
    today_str = datetime.datetime.now().strftime("%Y%m%d")
    if target_version:
        new_version = target_version[:8]
    else:
        new_version = max(today_str, get_app_version()[:8])

    pe_ver = get_pe_version(new_version)

    # 1. Update settings_manager.py APP_VERSION
    sm_path = os.path.join(app_dir, "settings_manager.py")
    if os.path.exists(sm_path):
        try:
            with open(sm_path, "r", encoding="utf-8") as f:
                content = f.read()
            import re
            new_content = re.sub(
                r'APP_VERSION\s*=\s*"[^"]*"',
                f'APP_VERSION = "{new_version}"',
                content,
                count=1
            )
            if new_content != content:
                with open(sm_path, "w", encoding="utf-8") as f:
                    f.write(new_content)
        except Exception as e:
            print(f"Warning: Failed to update APP_VERSION in settings_manager.py: {e}")

    # 2. Update installer.iss #define MyAppVersion
    iss_path = os.path.join(app_dir, "installer.iss")
    if os.path.exists(iss_path):
        try:
            with open(iss_path, "r", encoding="utf-8") as f:
                content = f.read()
            import re
            new_content = re.sub(
                r'#define MyAppVersion\s*"[^"]*"',
                f'#define MyAppVersion "{new_version}"',
                content,
                count=1
            )
            if new_content != content:
                with open(iss_path, "w", encoding="utf-8") as f:
                    f.write(new_content)
        except Exception as e:
            print(f"Warning: Failed to update MyAppVersion in installer.iss: {e}")

    return {"app_version": new_version, "pe_version": pe_ver}

def print_sync_versions():
    """Prints app_version and pe_version separated by space for batch script consumption."""
    res = sync_version_files()
    print(f"{res['app_version']} {res['pe_version']}")

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
            "web_port": DEFAULT_WEB_PORT,
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

    def get_web_port(self) -> int:
        try:
            return int(self.settings.get("web_port", DEFAULT_WEB_PORT))
        except (ValueError, TypeError):
            return DEFAULT_WEB_PORT

    def set_web_port(self, port: int):
        try:
            port = int(port)
            if 1024 <= port <= 65535:
                self.settings["web_port"] = port
                self.save()
        except (ValueError, TypeError):
            pass

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
