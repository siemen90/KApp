"""
Application Update Manager for KApp.
Checks GitHub Releases for updates and alerts the user.
"""
import os
import re
import json
import urllib.request
from urllib.error import HTTPError, URLError
from PyQt6.QtCore import QThread, pyqtSignal, QUrl
from PyQt6.QtGui import QIcon, QDesktopServices, QFont
from PyQt6.QtWidgets import (
    QDialog,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QTextEdit,
    QPushButton,
    QFrame,
    QApplication,
)

from i18n import t

GITHUB_REPO = "siemen90/KApp"
LATEST_RELEASE_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
ALL_RELEASES_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases"
RELEASES_PAGE = f"https://github.com/{GITHUB_REPO}/releases"


def parse_version_tuple(version_str: str) -> tuple:
    """
    Parses a version string into a tuple of integers for comparison.
    Examples:
        'v1.0' -> (1, 0)
        'v1.0.1' -> (1, 0, 1)
        '20261011' -> (2026, 10, 11)
        'v20261011' -> (2026, 10, 11)
        'v2026.10.11' -> (2026, 10, 11)
    """
    clean = re.sub(r'^[vV]', '', str(version_str).strip())
    # 8-digit YYYYMMDD date format
    if len(clean) == 8 and clean.isdigit():
        return (int(clean[:4]), int(clean[4:6]), int(clean[6:8]))
    # 12-digit YYYYMMDDHHMM date format
    if len(clean) == 12 and clean.isdigit():
        return (int(clean[:4]), int(clean[4:6]), int(clean[6:8]), int(clean[8:12]))
    numbers = re.findall(r'\d+', clean)
    if numbers:
        return tuple(int(n) for n in numbers)
    return ()


def is_newer_version(latest_tag: str, current_version: str) -> bool:
    """
    Determines if latest_tag is newer than current_version.
    """
    t_latest = parse_version_tuple(latest_tag)
    t_current = parse_version_tuple(current_version)

    if t_latest and t_current:
        max_len = max(len(t_latest), len(t_current))
        t_l_padded = t_latest + (0,) * (max_len - len(t_latest))
        t_c_padded = t_current + (0,) * (max_len - len(t_current))
        return t_l_padded > t_c_padded

    clean_latest = re.sub(r'^[vV]', '', str(latest_tag).strip())
    clean_current = re.sub(r'^[vV]', '', str(current_version).strip())
    return clean_latest != clean_current and clean_latest > clean_current


def check_for_updates(repo: str = GITHUB_REPO, timeout: int = 6) -> dict:
    """
    Fetches the latest release from GitHub API.
    Returns a dict with release details.
    """
    headers = {
        "User-Agent": "KApp-Karaoke-Updater",
        "Accept": "application/vnd.github.v3+json"
    }

    url = f"https://api.github.com/repos/{repo}/releases/latest"
    req = urllib.request.Request(url, headers=headers)

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            return _parse_release_data(data)
    except HTTPError as e:
        # If /releases/latest returned 404, check /releases in case first release is draft/not marked latest
        if e.code == 404:
            try:
                alt_url = f"https://api.github.com/repos/{repo}/releases"
                req_alt = urllib.request.Request(alt_url, headers=headers)
                with urllib.request.urlopen(req_alt, timeout=timeout) as resp_alt:
                    releases = json.loads(resp_alt.read().decode('utf-8'))
                    if releases and isinstance(releases, list):
                        return _parse_release_data(releases[0])
            except Exception:
                pass
        return {"success": False, "error": f"HTTP {e.code}: {e.reason}"}
    except URLError as e:
        return {"success": False, "error": str(e.reason)}
    except Exception as e:
        return {"success": False, "error": str(e)}


def _parse_release_data(data: dict) -> dict:
    tag = data.get("tag_name", "").strip()
    name = data.get("name") or tag
    body = data.get("body", "").strip()
    html_url = data.get("html_url", RELEASES_PAGE)
    published_at = data.get("published_at", "")

    # Look for installer or zip in assets
    assets = data.get("assets", [])
    download_url = html_url
    installer_name = ""

    for asset in assets:
        asset_name = asset.get("name", "")
        if asset_name.lower().endswith(".exe") or asset_name.lower().endswith(".zip"):
            download_url = asset.get("browser_download_url", html_url)
            installer_name = asset_name
            break

    return {
        "success": True,
        "tag_name": tag,
        "name": name,
        "body": body,
        "html_url": html_url,
        "download_url": download_url,
        "installer_name": installer_name,
        "published_at": published_at,
    }


class AppUpdateCheckThread(QThread):
    """Background worker for checking GitHub updates without blocking UI."""
    update_checked = pyqtSignal(dict)

    def __init__(self, current_version: str, is_manual: bool = False, parent=None):
        super().__init__(parent)
        self.current_version = current_version
        self.is_manual = is_manual

    def run(self):
        res = check_for_updates()
        if res.get("success"):
            latest_tag = res.get("tag_name", "")
            has_update = is_newer_version(latest_tag, self.current_version)
            res["has_update"] = has_update
            res["current_version"] = self.current_version
            res["is_manual"] = self.is_manual
            self.update_checked.emit(res)
        else:
            res["has_update"] = False
            res["current_version"] = self.current_version
            res["is_manual"] = self.is_manual
            self.update_checked.emit(res)


class UpdateDialog(QDialog):
    """Styled dialog presenting the update to the user."""
    def __init__(self, release_info: dict, parent=None):
        super().__init__(parent)
        self.release_info = release_info
        self.setWindowTitle(t("update_available_title"))
        self.setWindowIcon(QIcon("mic.svg"))
        self.resize(520, 420)
        self.setStyleSheet("""
            QDialog {
                background-color: #1a1a2e;
                color: #eaeaea;
                font-family: 'Segoe UI', Arial, sans-serif;
            }
            QLabel {
                color: #eaeaea;
            }
            QTextEdit {
                background-color: #16213e;
                border: 1px solid #0f3460;
                border-radius: 6px;
                color: #e0e0e0;
                padding: 8px;
                font-size: 13px;
            }
            QPushButton {
                border-radius: 5px;
                padding: 8px 18px;
                font-weight: bold;
                font-size: 13px;
            }
        """)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 24, 24, 24)
        layout.setSpacing(14)

        # Header Title
        title_label = QLabel(t("update_available_header"))
        title_font = QFont()
        title_font.setPointSize(16)
        title_font.setBold(True)
        title_label.setFont(title_font)
        title_label.setStyleSheet("color: #6c63ff;")
        layout.addWidget(title_label)

        # Version Information Card
        card = QFrame()
        card.setStyleSheet("""
            QFrame {
                background-color: #16213e;
                border: 1px solid #0f3460;
                border-radius: 8px;
                padding: 10px;
            }
        """)
        card_layout = QHBoxLayout(card)

        cur_v = release_info.get("current_version", "1.0")
        lat_v = release_info.get("tag_name", "Latest")

        lbl_cur = QLabel(f"<b>{t('current_version_label')}:</b> <span style='color: #8888aa;'>{cur_v}</span>")
        lbl_cur.setStyleSheet("font-size: 13px;")
        card_layout.addWidget(lbl_cur)

        lbl_arrow = QLabel("➔")
        lbl_arrow.setStyleSheet("color: #6c63ff; font-weight: bold; font-size: 16px;")
        card_layout.addWidget(lbl_arrow)

        lbl_new = QLabel(f"<b>{t('latest_version_label')}:</b> <span style='color: #00c853; font-weight: bold;'>{lat_v}</span>")
        lbl_new.setStyleSheet("font-size: 13px;")
        card_layout.addWidget(lbl_new)
        card_layout.addStretch()

        layout.addWidget(card)

        # Release Title
        rel_name = release_info.get("name") or lat_v
        lbl_rel_name = QLabel(f"<b>{rel_name}</b>")
        lbl_rel_name.setStyleSheet("font-size: 14px; color: #ffffff;")
        layout.addWidget(lbl_rel_name)

        # Release Notes / Description
        notes_title = QLabel(t("release_notes"))
        notes_title.setStyleSheet("font-size: 12px; color: #8888aa; font-weight: bold;")
        layout.addWidget(notes_title)

        body_text = release_info.get("body", "").strip() or "No release notes provided."
        self.notes_box = QTextEdit()
        self.notes_box.setReadOnly(True)
        self.notes_box.setPlainText(body_text)
        layout.addWidget(self.notes_box)

        # Action Buttons
        btn_layout = QHBoxLayout()
        btn_layout.setSpacing(12)

        self.btn_later = QPushButton(t("btn_later"))
        self.btn_later.setStyleSheet("background-color: #2e3856; color: #d0d0d0;")
        self.btn_later.clicked.connect(self.reject)
        btn_layout.addWidget(self.btn_later)

        btn_layout.addStretch()

        self.btn_update = QPushButton(t("btn_update_now"))
        self.btn_update.setStyleSheet("background-color: #00c853; color: #000000; padding: 8px 22px;")
        self.btn_update.clicked.connect(self.on_update_clicked)
        btn_layout.addWidget(self.btn_update)

        layout.addLayout(btn_layout)

    def on_update_clicked(self):
        target_url = self.release_info.get("download_url") or self.release_info.get("html_url") or RELEASES_PAGE
        QDesktopServices.openUrl(QUrl(target_url))
        self.accept()
