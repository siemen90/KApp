import sys
import os
import re
import shutil
import queue
from PyQt6.QtWidgets import *
from PyQt6.QtCore import *
from PyQt6.QtMultimedia import QMediaPlayer, QAudioOutput
from PyQt6.QtMultimediaWidgets import QVideoWidget
import socket
import qrcode
from PyQt6.QtGui import QKeySequence, QShortcut, QPixmap, QImage, QIcon, QColor, QBrush, QFont, QPainter

def make_thumbnail_placeholder(text="YT", bg="#1a1a2e", fg="#6c63ff", w=80, h=48):
    pix = QPixmap(w, h)
    pix.fill(QColor(bg))
    painter = QPainter(pix)
    painter.setPen(QColor(fg))
    font = painter.font()
    font.setPixelSize(14)
    font.setBold(True)
    painter.setFont(font)
    painter.drawText(pix.rect(), Qt.AlignmentFlag.AlignCenter, text)
    painter.end()
    return QIcon(pix)

class ThumbnailLoaderThread(QThread):
    thumb_ready = pyqtSignal(object, object)  # (QListWidgetItem, QPixmap)

    def __init__(self, tasks):
        super().__init__()
        self.tasks = tasks  # list of (item, url)
        self.is_running = True

    def stop(self):
        self.is_running = False

    def run(self):
        import urllib.request
        for item, url in self.tasks:
            if not self.is_running:
                break
            if not url:
                continue
            try:
                req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
                with urllib.request.urlopen(req, timeout=3) as resp:
                    data = resp.read()
                img = QImage.fromData(data)
                if not img.isNull() and self.is_running:
                    pix = QPixmap.fromImage(img)
                    self.thumb_ready.emit(item, pix)
            except Exception:
                pass

def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

from youtube_fetcher import YouTubeKaraokeSource
from vocal_remover import VocalRemover
from web_server import WebServerThread
from settings_manager import SettingsManager, QUALITY_OPTIONS
from i18n import t, get_current_language
from chinese_utils import (
    match_chinese,
    normalize_song_title,
    extract_video_id,
    to_simplified,
    to_traditional
)
from dependency_manager import (
    get_deno_info,
    get_ffmpeg_info,
    DependencyCheckThread,
    DependencyDownloadThread,
    DependencyManager,
)

class AddSongDialog(QDialog):
    song_selected = pyqtSignal(str, str, str) # url, title, output_path
    
    local_song_selected = pyqtSignal(str, str, str, str) # video, audio, inst, title
    local_song_deleted = pyqtSignal(str) # audio_path of deleted song
    
    def __init__(self, youtube, parent=None):
        super().__init__(parent)
        self.youtube = youtube
        self.setWindowTitle(t("dialog_title"))
        self.setWindowIcon(QIcon("mic.svg"))
        self.resize(800, 600)
        self.all_videos = []
        self.thumb_thread = None
        self.init_ui()
        
    def init_ui(self):
        layout = QVBoxLayout(self)
        
        self.tabs = QTabWidget()
        self.tabs.addTab(self.create_youtube_tab(), t("tab_youtube"))
        self.tabs.addTab(self.create_local_library_tab(), t("tab_local"))
        layout.addWidget(self.tabs)
        
        btn_close = QPushButton(t("btn_close"))
        btn_close.clicked.connect(self.reject)
        layout.addWidget(btn_close)
        
    def create_youtube_tab(self):
        widget = QWidget()
        h_layout = QHBoxLayout(widget)
        
        # Channels side
        channel_group = QGroupBox(t("channels_title"))
        channel_layout = QVBoxLayout(channel_group)
        
        self.channel_input = QLineEdit()
        self.channel_input.setPlaceholderText(t("search_channels"))
        channel_layout.addWidget(self.channel_input)
        
        btn_add_channel = QPushButton(t("add_song"))
        btn_add_channel.clicked.connect(self.add_channel)
        channel_layout.addWidget(btn_add_channel)
        
        self.channel_list = QListWidget()
        channel_layout.addWidget(self.channel_list)
        
        btn_fetch = QPushButton(t("fetch_videos"))
        btn_fetch.clicked.connect(self.fetch_channel_videos)
        channel_layout.addWidget(btn_fetch)
        
        self.fetch_progress_bar = QProgressBar()
        self.fetch_progress_bar.setRange(0, 0)
        self.fetch_progress_bar.setVisible(False)
        channel_layout.addWidget(self.fetch_progress_bar)
        
        # Search side (also inside Channels/Search for global search)
        search_group = QGroupBox(t("tab_youtube"))
        search_layout = QVBoxLayout(search_group)
        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText(t("search_placeholder"))
        search_layout.addWidget(self.search_input)
        btn_search = QPushButton(t("search_btn"))
        btn_search.clicked.connect(self.search_youtube)
        search_layout.addWidget(btn_search)
        
        left_layout = QVBoxLayout()
        left_layout.addWidget(channel_group)
        left_layout.addWidget(search_group)
        
        h_layout.addLayout(left_layout, stretch=1)
        
        # Videos side
        video_group = QGroupBox(t("tab_youtube"))
        video_layout = QVBoxLayout(video_group)
        
        search_layout2 = QHBoxLayout()
        self.video_search_input = QLineEdit()
        self.video_search_input.setPlaceholderText(t("filter_videos_placeholder"))
        self.video_search_input.textChanged.connect(self.filter_videos)
        search_layout2.addWidget(self.video_search_input)
        
        btn_clear_filter = QPushButton("Clear")
        btn_clear_filter.setMaximumWidth(60)
        btn_clear_filter.clicked.connect(self.clear_video_filter)
        search_layout2.addWidget(btn_clear_filter)
        video_layout.addLayout(search_layout2)
        
        self.video_count_label = QLabel("0 videos")
        video_layout.addWidget(self.video_count_label)
        
        self.video_list = QListWidget()
        self.video_list.setIconSize(QSize(80, 48))
        self.video_list.setWordWrap(True)
        self.video_list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.video_list.setStyleSheet("""
            QListWidget::item {
                padding: 6px 4px;
                border-bottom: 1px solid rgba(255, 255, 255, 0.07);
            }
            QListWidget::item:selected {
                background: #2a2a48;
                border-radius: 4px;
            }
        """)
        video_layout.addWidget(self.video_list)
        
        btn_add_to_list = QPushButton(t("add_to_list"))
        btn_add_to_list.clicked.connect(self.add_to_list)
        video_layout.addWidget(btn_add_to_list)
        
        h_layout.addWidget(video_group, stretch=2)
        return widget
        
    def create_local_library_tab(self):
        widget = QWidget()
        layout = QVBoxLayout(widget)
        
        # Search layout
        search_layout = QHBoxLayout()
        self.local_search_input = QLineEdit()
        self.local_search_input.setPlaceholderText(t("filter_local_placeholder"))
        self.local_search_input.textChanged.connect(self.filter_local_library)
        search_layout.addWidget(self.local_search_input)
        
        btn_refresh = QPushButton(t("btn_refresh"))
        btn_refresh.clicked.connect(self.load_local_library)
        search_layout.addWidget(btn_refresh)
        
        layout.addLayout(search_layout)
        
        self.local_list = QListWidget()
        self.local_list.setIconSize(QSize(80, 48))
        self.local_list.setWordWrap(True)
        self.local_list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.local_list.setStyleSheet("""
            QListWidget::item {
                padding: 6px 4px;
                border-bottom: 1px solid rgba(255, 255, 255, 0.07);
            }
            QListWidget::item:selected {
                background: #2a2a48;
                border-radius: 4px;
            }
        """)
        layout.addWidget(self.local_list)
        
        btn_layout = QHBoxLayout()
        btn_add = QPushButton(t("add_to_list"))
        btn_add.clicked.connect(self.add_local_to_list)
        btn_layout.addWidget(btn_add)
        
        btn_delete = QPushButton(t("btn_delete"))
        btn_delete.clicked.connect(self.delete_local_song)
        btn_delete.setStyleSheet("color: red;")
        btn_layout.addWidget(btn_delete)
        
        layout.addLayout(btn_layout)
        
        self.load_local_library()
        return widget

    def load_local_library(self):
        self.local_list.clear()
        downloads_dir = SettingsManager().get_download_dir()
        if not os.path.exists(downloads_dir):
            return

        # Walk all subdirectories recursively to find _instrumental.wav files
        for root, dirs, files in os.walk(downloads_dir):
            for filename in files:
                if filename.endswith("_instrumental.wav"):
                    base = filename[:-17]  # remove "_instrumental.wav"

                    audio_path = os.path.join(root, f"{base}.wav")
                    inst_path  = os.path.join(root, filename)

                    # Video could be .mp4, .mkv, .webm, .m4a
                    video_path = None
                    for ext in [".mp4", ".mkv", ".webm", ".m4a"]:
                        possible_video = os.path.join(root, f"{base}{ext}")
                        if os.path.exists(possible_video):
                            video_path = possible_video
                            break

                    if os.path.exists(audio_path):
                        # Use folder name as display title if song is in a subfolder
                        if root != downloads_dir:
                            display_title = os.path.basename(root)
                        else:
                            display_title = base
                        
                        quality = ""
                        duration = 0
                        meta_file = os.path.join(root, "metadata.json")
                        if os.path.exists(meta_file):
                            try:
                                with open(meta_file, "r", encoding="utf-8") as mf:
                                    meta = json.load(mf)
                                    if meta.get("title"):
                                        display_title = meta["title"]
                                    if meta.get("quality"):
                                        quality = meta["quality"]
                                    elif meta.get("height"):
                                        quality = f"{meta['height']}p"
                                    if meta.get("duration"):
                                        duration = meta["duration"]
                            except Exception:
                                pass

                        # Fallback for duration: read audio WAV header
                        if not duration and os.path.exists(audio_path):
                            try:
                                import wave
                                with wave.open(audio_path, 'rb') as wf:
                                    frames = wf.getnframes()
                                    rate = wf.getframerate()
                                    if rate > 0:
                                        duration = int(frames / float(rate))
                            except Exception:
                                pass

                        # Fallback for quality: probe video file using ffprobe
                        if not quality and video_path and os.path.exists(video_path):
                            quality = self._probe_video_quality(video_path)

                        # Format badges on 2nd line
                        quality_tag = f"[{quality}]" if quality else ""
                        duration_str = ""
                        if duration:
                            mins, secs = divmod(int(duration), 60)
                            duration_str = f"[{mins}:{secs:02d}]"

                        meta_parts = []
                        if quality_tag:
                            meta_parts.append(quality_tag)
                        if duration_str:
                            meta_parts.append(duration_str)
                        meta_line = "  ".join(meta_parts)

                        display_text = f" {display_title}\n  {meta_line}" if meta_line else f" {display_title}"
                        local_icon = make_thumbnail_placeholder("🎵", bg="#14261d", fg="#00c853", w=80, h=48)
                        item = QListWidgetItem(local_icon, display_text)
                        item.setData(257, video_path)
                        item.setData(258, audio_path)
                        item.setData(259, inst_path)
                        item.setData(260, root)  # store folder path for deletion
                        item.setData(261, display_title) # clean title
                        item.setData(262, quality)
                        item.setData(263, duration)
                        self.local_list.addItem(item)
                    
    @staticmethod
    def _probe_video_quality(video_path):
        """Probes video height using ffprobe if available"""
        if not video_path or not os.path.exists(video_path):
            return ""
        try:
            import subprocess
            app_dir = os.path.dirname(os.path.abspath(__file__))
            ffprobe = os.path.join(app_dir, "ffprobe.exe")
            if not os.path.exists(ffprobe):
                ffprobe = "ffprobe"
            cmd = [
                ffprobe, "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=height",
                "-of", "csv=p=0",
                video_path
            ]
            startupinfo = None
            if os.name == 'nt':
                startupinfo = subprocess.STARTUPINFO()
                startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            out = subprocess.check_output(cmd, stderr=subprocess.DEVNULL, timeout=2, startupinfo=startupinfo).decode().strip()
            if out and out.isdigit():
                return f"{out}p"
        except Exception:
            pass
        return ""

    def delete_local_song(self):
        current = self.local_list.currentItem()
        if not current:
            QMessageBox.warning(self, "Error", "Select a local song to delete first")
            return
        
        title = current.data(261) or current.text().strip()
        reply = QMessageBox.question(
            self, t("confirm_delete_title"),
            f"{t('confirm_delete_msg')}\n\n{title}",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No
        )
        if reply != QMessageBox.StandardButton.Yes:
            return
        
        video_path = current.data(257)
        audio_path = current.data(258)
        inst_path  = current.data(259)
        song_folder = current.data(260)
        
        # Check if the song being deleted is the one currently loaded/playing
        parent = self.parent()
        is_current_song = False
        if parent and hasattr(parent, 'player_window') and getattr(parent.player_window, 'current_audio_path', None):
            try:
                curr_audio = os.path.normpath(os.path.abspath(parent.player_window.current_audio_path))
                if audio_path and os.path.normpath(os.path.abspath(audio_path)) == curr_audio:
                    is_current_song = True
                elif inst_path and getattr(parent.player_window, 'instrumental_audio_path', None):
                    curr_inst = os.path.normpath(os.path.abspath(parent.player_window.instrumental_audio_path))
                    if os.path.normpath(os.path.abspath(inst_path)) == curr_inst:
                        is_current_song = True
                elif song_folder:
                    abs_folder = os.path.normpath(os.path.abspath(song_folder))
                    if curr_audio.startswith(abs_folder):
                        is_current_song = True
            except Exception:
                pass
        
        # Only stop playback and release media handles if it IS the current song
        if is_current_song:
            parent.player_window.release_media()
            parent.player_window.current_audio_path = None
            parent.player_window.instrumental_audio_path = None
            parent.player_window.current_title = ""
            if hasattr(parent, 'current_playing_row'):
                parent.current_playing_row = -1
            if hasattr(parent, 'sync_player_state_to_web'):
                parent.sync_player_state_to_web()
        
        vocals_path = inst_path.replace("_instrumental.wav", "_vocals.wav") if inst_path else None
        
        errors = []
        for path in [video_path, audio_path, inst_path, vocals_path]:
            if path and os.path.exists(path):
                try:
                    os.remove(path)
                except Exception as e:
                    errors.append(f"{path}: {e}")
        
        # Delete the song folder if it's a subfolder of downloads (not the root)
        downloads_dir = os.path.abspath(SettingsManager().get_download_dir())
        if song_folder:
            abs_folder = os.path.abspath(song_folder)
            if abs_folder != downloads_dir and abs_folder.startswith(downloads_dir):
                try:
                    shutil.rmtree(abs_folder, ignore_errors=True)
                except Exception as e:
                    errors.append(f"{song_folder}: {e}")
        
        if errors:
            QMessageBox.warning(self, "Delete Warning",
                "Some files could not be deleted:\n" + "\n".join(errors))
        
        # Emit signal to remove it from the queue if present
        self.local_song_deleted.emit(audio_path or "")
        
        self.load_local_library()

    def filter_local_library(self, text):
        text = text.strip()
        for i in range(self.local_list.count()):
            item = self.local_list.item(i)
            item.setHidden(not match_chinese(text, item.text()))
            
    def add_local_to_list(self):
        current = self.local_list.currentItem()
        if not current:
            QMessageBox.warning(self, "Error", "Select a local song first")
            return
        video_path = current.data(257)
        audio_path = current.data(258)
        inst_path = current.data(259)
        title = current.data(261) or current.text().strip()

        main_win = self.parent()
        if main_win and hasattr(main_win, "find_duplicate_in_queue"):
            dup = main_win.find_duplicate_in_queue(title=title, audio_path=audio_path, video_path=video_path)
            if dup:
                dup_title = dup.get("title", title)
                dup_status = dup.get("status", "")
                status_suffix = f" ({dup_status})" if dup_status else ""
                reply = QMessageBox.question(
                    self,
                    t("duplicate_song_title"),
                    t("duplicate_song_msg").format(title=dup_title, status=status_suffix).strip(),
                    QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                    QMessageBox.StandardButton.No
                )
                if reply != QMessageBox.StandardButton.Yes:
                    return

        self.local_song_selected.emit(video_path or "", audio_path, inst_path, title)
        self.accept()
        
    def add_channel(self):
        channel = self.channel_input.text().strip()
        if channel:
            self.channel_list.addItem(channel)
            self.channel_input.clear()
            
    def fetch_channel_videos(self):
        current = self.channel_list.currentItem()
        if not current:
            QMessageBox.warning(self, "Error", "Select a channel first")
            return
        
        if self.thumb_thread and self.thumb_thread.isRunning():
            self.thumb_thread.stop()
            self.thumb_thread.wait(300)

        channel = current.text()
        self.video_list.clear()
        self.video_count_label.setText("Loading...")
        self.fetch_progress_bar.setVisible(True)
        
        self.thread = VideoFetchThread(self.youtube, channel)
        self.thread.videos_found.connect(self.populate_video_list)
        self.thread.start()
        
    def search_youtube(self):
        query = self.search_input.text().strip()
        if not query:
            return

        if self.thumb_thread and self.thumb_thread.isRunning():
            self.thumb_thread.stop()
            self.thumb_thread.wait(300)

        self.video_list.clear()
        self.video_count_label.setText("Searching...")

        # --- Show local library matches first ---
        local_matches = []
        for i in range(self.local_list.count()):
            item = self.local_list.item(i)
            if match_chinese(query, item.text()):
                local_matches.append(item)

        if local_matches:
            hdr_key = "local_match_header" if len(local_matches) == 1 else "local_matches_header"
            sep = QListWidgetItem(t(hdr_key).format(count=len(local_matches)))
            sep.setFlags(Qt.ItemFlag.NoItemFlags)  # not selectable
            sep.setForeground(QColor("#00c853"))
            sep.setData(Qt.ItemDataRole.UserRole + 10, "separator")
            self.video_list.addItem(sep)

            local_icon = make_thumbnail_placeholder("🎵", bg="#14261d", fg="#00c853", w=80, h=48)
            for src_item in local_matches:
                new_item = QListWidgetItem(local_icon, f" [Local] {src_item.text().strip()}")
                new_item.setForeground(QColor("#00c853"))
                new_item.setData(Qt.ItemDataRole.UserRole, src_item.data(257))       # video
                clean_title = src_item.data(261) or src_item.text().strip()
                new_item.setData(Qt.ItemDataRole.UserRole + 1, clean_title)          # title
                new_item.setData(Qt.ItemDataRole.UserRole + 2, src_item.data(258))   # audio
                new_item.setData(Qt.ItemDataRole.UserRole + 3, src_item.data(259))   # inst
                new_item.setData(Qt.ItemDataRole.UserRole + 10, "local")
                self.video_list.addItem(new_item)

            yt_sep = QListWidgetItem(t("youtube_header"))
            yt_sep.setFlags(Qt.ItemFlag.NoItemFlags)
            yt_sep.setForeground(QColor("#6c63ff"))
            yt_sep.setData(Qt.ItemDataRole.UserRole + 10, "separator")
            self.video_list.addItem(yt_sep)

        self.thread = VideoSearchThread(self.youtube, query)
        self.thread.videos_found.connect(self.populate_video_list)
        self.thread.start()
        
    def populate_video_list(self, videos):
        if self.thumb_thread and self.thumb_thread.isRunning():
            self.thumb_thread.stop()
            self.thumb_thread.wait(300)

        self.all_videos = videos
        # Count existing local items already in the list (don't clear them)
        existing_count = self.video_list.count()

        total_videos = len(videos)
        self.fetch_progress_bar.setRange(0, total_videos)
        self.fetch_progress_bar.setVisible(True)

        tasks = []
        default_yt_icon = make_thumbnail_placeholder("YT", bg="#1a1a2e", fg="#6c63ff", w=80, h=48)

        for idx, video in enumerate(videos):
            title = video.get('title', 'Unknown')
            duration = video.get('duration', 0)
            thumbnail_url = video.get('thumbnail', '')

            if duration:
                mins, secs = divmod(duration, 60)
                duration_str = f" [{mins}:{int(secs):02d}]"
            else:
                duration_str = ""

            item_text = f" {title}{duration_str}"
            item = QListWidgetItem(default_yt_icon, item_text)
            item.setData(Qt.ItemDataRole.UserRole, video.get('url'))
            item.setData(Qt.ItemDataRole.UserRole + 1, title)
            item.setData(Qt.ItemDataRole.UserRole + 10, "youtube")
            self.video_list.addItem(item)

            if thumbnail_url:
                tasks.append((item, thumbnail_url))

            if idx % 50 == 0:
                self.fetch_progress_bar.setValue(idx)
                self.video_count_label.setText(f"Importing: {idx} / {total_videos} videos")
                QCoreApplication.processEvents()

        self.fetch_progress_bar.setVisible(False)
        self.fetch_progress_bar.setRange(0, 0)
        
        # Count actual local songs, NOT separators
        local_count = sum(
            1 for i in range(self.video_list.count())
            if self.video_list.item(i).data(Qt.ItemDataRole.UserRole + 10) == "local"
        )
        total_yt = total_videos
        if local_count > 0:
            self.video_count_label.setText(t("search_summary_both").format(yt=total_yt, local=local_count))
        else:
            self.video_count_label.setText(t("search_summary_yt_only").format(yt=total_yt))

        # Launch background thumbnail loader for YouTube results
        if tasks:
            self.thumb_thread = ThumbnailLoaderThread(tasks)
            self.thumb_thread.thumb_ready.connect(self.on_thumbnail_ready)
            self.thumb_thread.start()

    def on_thumbnail_ready(self, item, pixmap):
        try:
            if item and not pixmap.isNull():
                scaled = pixmap.scaled(80, 48, Qt.AspectRatioMode.KeepAspectRatioByExpanding, Qt.TransformationMode.SmoothTransformation)
                item.setIcon(QIcon(scaled))
        except RuntimeError:
            pass

    def closeEvent(self, event):
        if self.thumb_thread and self.thumb_thread.isRunning():
            self.thumb_thread.stop()
            self.thumb_thread.wait(300)
        super().closeEvent(event)

    def reject(self):
        if self.thumb_thread and self.thumb_thread.isRunning():
            self.thumb_thread.stop()
            self.thumb_thread.wait(300)
        super().reject()
        
    def filter_videos(self, search_text):
        search_text = search_text.strip()
        has_filter = bool(search_text)
        
        has_visible_local = False
        has_visible_yt = False
        local_sep_item = None
        yt_sep_item = None

        for i in range(self.video_list.count()):
            item = self.video_list.item(i)
            role = item.data(Qt.ItemDataRole.UserRole + 10)
            if role == "separator":
                text = item.text()
                if "local" in text.lower() or "本地" in text:
                    local_sep_item = item
                else:
                    yt_sep_item = item
                continue

            matches = match_chinese(search_text, item.text()) if has_filter else True
            item.setHidden(not matches)
            if matches:
                if role == "local":
                    has_visible_local = True
                elif role == "youtube":
                    has_visible_yt = True

        if local_sep_item:
            local_sep_item.setHidden(not has_visible_local)
        if yt_sep_item:
            yt_sep_item.setHidden(not has_visible_yt)

        visible_count = sum(
            1 for i in range(self.video_list.count())
            if not self.video_list.item(i).isHidden() and self.video_list.item(i).data(Qt.ItemDataRole.UserRole + 10) != "separator"
        )
        total_songs = sum(
            1 for i in range(self.video_list.count())
            if self.video_list.item(i).data(Qt.ItemDataRole.UserRole + 10) != "separator"
        )
        self.video_count_label.setText(t("filter_summary").format(visible=visible_count, total=total_songs))
        
    def clear_video_filter(self):
        self.video_search_input.clear()
        self.filter_videos("")
        
    def add_to_list(self):
        current = self.video_list.currentItem()
        if not current:
            QMessageBox.warning(self, "Error", "Select a video first")
            return

        source = current.data(Qt.ItemDataRole.UserRole + 10)

        if source == "separator":
            QMessageBox.warning(self, "Error", "Please select a song, not a section header")
            return

        title = current.data(Qt.ItemDataRole.UserRole + 1)
        main_win = self.parent()

        if source == "local":
            # Local library item — add directly, no download needed
            video_path = current.data(Qt.ItemDataRole.UserRole)       # video
            audio_path = current.data(Qt.ItemDataRole.UserRole + 2)   # audio
            inst_path  = current.data(Qt.ItemDataRole.UserRole + 3)   # inst

            if main_win and hasattr(main_win, "find_duplicate_in_queue"):
                dup = main_win.find_duplicate_in_queue(title=title, audio_path=audio_path, video_path=video_path)
                if dup:
                    dup_title = dup.get("title", title)
                    dup_status = dup.get("status", "")
                    status_suffix = f" ({dup_status})" if dup_status else ""
                    reply = QMessageBox.question(
                        self,
                        t("duplicate_song_title"),
                        t("duplicate_song_msg").format(title=dup_title, status=status_suffix).strip(),
                        QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                        QMessageBox.StandardButton.No
                    )
                    if reply != QMessageBox.StandardButton.Yes:
                        return

            self.local_song_selected.emit(video_path or "", audio_path, inst_path, title)
        else:
            # YouTube item — download required
            url = current.data(Qt.ItemDataRole.UserRole)

            if main_win and hasattr(main_win, "find_duplicate_in_queue"):
                dup = main_win.find_duplicate_in_queue(title=title, url=url)
                if dup:
                    dup_title = dup.get("title", title)
                    dup_status = dup.get("status", "")
                    status_suffix = f" ({dup_status})" if dup_status else ""
                    reply = QMessageBox.question(
                        self,
                        t("duplicate_song_title"),
                        t("duplicate_song_msg").format(title=dup_title, status=status_suffix).strip(),
                        QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                        QMessageBox.StandardButton.No
                    )
                    if reply != QMessageBox.StandardButton.Yes:
                        return

            safe_title = re.sub(r'[\\/:*?"<>|]', '_', title).strip(" .")
            if not safe_title:
                safe_title = "youtube_song"
            output_path = os.path.join("downloads", safe_title)
            self.song_selected.emit(url, title, output_path)

        self.accept()

class VideoFetchThread(QThread):
    videos_found = pyqtSignal(list)
    
    def __init__(self, youtube, channel_input):
        super().__init__()
        self.youtube = youtube
        self.channel_input = channel_input
    
    def run(self):
        videos = self.youtube.get_channel_videos(self.channel_input)
        self.videos_found.emit(videos)

class VideoSearchThread(QThread):
    videos_found = pyqtSignal(list)
    
    def __init__(self, youtube, query):
        super().__init__()
        self.youtube = youtube
        self.query = query
    
    def run(self):
        videos = self.youtube.search_karaoke_videos(self.query)
        self.videos_found.emit(videos)

class SongProcessorThread(QThread):
    progress_update = pyqtSignal(int, str, int) # task_id, status, progress_value
    finished_processing = pyqtSignal(int, str, str, str) # task_id, video_path, audio_path, inst_path
    error = pyqtSignal(int, str)
    
    def __init__(self, youtube, vocal_remover, url, task_id, output_path=None, quality=None):
        super().__init__()
        self.youtube = youtube
        self.vocal_remover = vocal_remover
        self.url = url
        self.task_id = task_id
        self.output_path = output_path
        self.quality = quality
        
    def run(self):
        try:
            self.progress_update.emit(self.task_id, t("status_downloading"), 0)
            
            def dl_progress(percent):
                self.progress_update.emit(self.task_id, t("status_downloading"), int(percent * 50))
                
            video_path, audio_path = self.youtube.download_video(
                self.url, output_path=self.output_path, quality=self.quality, progress_callback=dl_progress
            )
            
            self.progress_update.emit(self.task_id, t("status_removing_vocals"), 50)

            def vr_progress(fraction):
                # Scale vocal removal from 50% to 99%
                val = 50 + int(fraction * 49)
                self.progress_update.emit(self.task_id, t("status_removing_vocals"), val)

            instrumental_path = self.vocal_remover.remove_vocals(audio_path, progress_callback=vr_progress)
            
            self.progress_update.emit(self.task_id, t("status_ready"), 100)
            self.finished_processing.emit(self.task_id, video_path, audio_path, instrumental_path)
        except Exception as e:
            self.error.emit(self.task_id, str(e))

class QueueManager(QObject):
    item_updated = pyqtSignal(int, str, int)
    item_ready = pyqtSignal(int, str, str, str)
    
    def __init__(self, youtube, vocal_remover):
        super().__init__()
        self.youtube = youtube
        self.vocal_remover = vocal_remover
        self.task_queue = queue.Queue()
        self.is_processing = False
        self.worker = None
        
    def add_task(self, url, task_id, output_path=None, quality=None):
        self.task_queue.put((url, task_id, output_path, quality))
        if not self.is_processing:
            self.process_next()
            
    def process_next(self):
        if self.task_queue.empty():
            self.is_processing = False
            return
            
        self.is_processing = True
        item = self.task_queue.get()
        url = item[0]
        task_id = item[1]
        output_path = item[2] if len(item) > 2 else None
        quality = item[3] if len(item) > 3 else None
        self.worker = SongProcessorThread(self.youtube, self.vocal_remover, url, task_id, output_path, quality)
        self.worker.progress_update.connect(self.item_updated.emit)
        self.worker.finished_processing.connect(self.on_worker_finished)
        self.worker.error.connect(self.on_worker_error)
        self.worker.start()
        
    def on_worker_finished(self, task_id, video_path, audio_path, inst_path):
        self.item_ready.emit(task_id, video_path, audio_path, inst_path)
        self.process_next()
        
    def on_worker_error(self, task_id, error_msg):
        self.item_updated.emit(task_id, f"{t('status_error')}: {error_msg}", 0)
        self.process_next()

class SettingsDialog(QDialog):
    settings_saved = pyqtSignal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.settings = SettingsManager()
        self.setWindowTitle(t("settings_title"))
        self.setWindowIcon(QIcon("mic.svg"))
        self.resize(600, 540)
        self.init_ui()

    def init_ui(self):
        layout = QVBoxLayout(self)
        
        form_layout = QFormLayout()
        form_layout.setSpacing(14)
        
        # 1. Language
        self.lang_combo = QComboBox()
        self.lang_combo.addItem(t("language_en"), "en")
        self.lang_combo.addItem(t("language_zh"), "zh")
        current_lang = self.settings.get_language()
        idx = 1 if current_lang == "zh" else 0
        self.lang_combo.setCurrentIndex(idx)
        form_layout.addRow(t("language") + ":", self.lang_combo)
        
        # 2. Video Quality
        self.quality_combo = QComboBox()
        for q in QUALITY_OPTIONS:
            self.quality_combo.addItem(q, q)
        current_q = self.settings.get_video_quality()
        if current_q in QUALITY_OPTIONS:
            self.quality_combo.setCurrentText(current_q)
        quality_layout = QVBoxLayout()
        quality_layout.addWidget(self.quality_combo)
        quality_hint = QLabel(t("video_quality_hint"))
        quality_hint.setStyleSheet("color: #8888aa; font-size: 11px;")
        quality_layout.addWidget(quality_hint)
        form_layout.addRow(t("video_quality") + ":", quality_layout)
        
        # 3. Download Directory
        dl_layout = QHBoxLayout()
        self.dl_edit = QLineEdit(self.settings.get_download_dir())
        self.dl_edit.setReadOnly(True)
        btn_browse = QPushButton(t("btn_browse"))
        btn_browse.clicked.connect(self.browse_download_dir)
        dl_layout.addWidget(self.dl_edit)
        dl_layout.addWidget(btn_browse)
        form_layout.addRow(t("download_dir") + ":", dl_layout)
        
        # 4. Storage Space
        self.storage_label = QLabel(SettingsManager.get_storage_space(self.settings.get_download_dir()))
        self.storage_label.setStyleSheet("color: #00c853; font-weight: bold;")
        form_layout.addRow(t("storage_free") + ":", self.storage_label)
        
        layout.addLayout(form_layout)
        
        # 5. Dependencies & External Tools (FFmpeg & Deno)
        self.setup_dependencies_ui(layout)
        layout.addStretch()
        
        # Bottom row: Version on the left, action buttons on the right
        btn_box = QHBoxLayout()
        self.version_label = QLabel(SettingsManager.get_version())
        self.version_label.setStyleSheet("color: #888899; font-size: 11px; font-family: Consolas, monospace;")
        btn_box.addWidget(self.version_label)

        btn_box.addStretch()
        btn_cancel = QPushButton(t("btn_cancel"))
        btn_cancel.clicked.connect(self.reject)
        btn_save = QPushButton(t("btn_save"))
        btn_save.setStyleSheet("background-color: #6c63ff; color: white; padding: 6px 18px; font-weight: bold; border-radius: 4px;")
        btn_save.clicked.connect(self.save_settings)
        btn_box.addWidget(btn_cancel)
        btn_box.addWidget(btn_save)
        layout.addLayout(btn_box)

    def browse_download_dir(self):
        cur = self.dl_edit.text()
        chosen = QFileDialog.getExistingDirectory(self, t("download_dir"), cur)
        if chosen:
            self.dl_edit.setText(chosen)
            self.storage_label.setText(SettingsManager.get_storage_space(chosen))

    def save_settings(self):
        new_lang = self.lang_combo.currentData()
        new_q = self.quality_combo.currentText()
        new_dl = self.dl_edit.text().strip()
        
        self.settings.set_language(new_lang)
        self.settings.set_video_quality(new_q)
        if new_dl:
            self.settings.set_download_dir(new_dl)
            
        self.settings_saved.emit()
        self.accept()

    def setup_dependencies_ui(self, parent_layout):
        tools_group = QGroupBox(t("tools_title"))
        tools_layout = QVBoxLayout(tools_group)
        tools_layout.setSpacing(8)

        # 1. FFmpeg Item
        ffmpeg_card = QWidget()
        ffmpeg_vbox = QVBoxLayout(ffmpeg_card)
        ffmpeg_vbox.setContentsMargins(0, 2, 0, 2)
        ffmpeg_vbox.setSpacing(4)

        ff_hdr = QHBoxLayout()
        ff_title = QLabel(f"<b>{t('ffmpeg_tool_title')}</b>")
        self.btn_ffmpeg_action = QPushButton(t("btn_check_update"))
        self.btn_ffmpeg_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
        self.btn_ffmpeg_action.clicked.connect(self.handle_ffmpeg_action)
        ff_hdr.addWidget(ff_title)
        ff_hdr.addStretch()
        ff_hdr.addWidget(self.btn_ffmpeg_action)
        ffmpeg_vbox.addLayout(ff_hdr)

        self.ffmpeg_ver_label = QLabel()
        self.ffmpeg_ver_label.setWordWrap(True)
        ffmpeg_vbox.addWidget(self.ffmpeg_ver_label)

        self.ffmpeg_status_label = QLabel()
        self.ffmpeg_status_label.setWordWrap(True)
        self.ffmpeg_status_label.setVisible(False)
        ffmpeg_vbox.addWidget(self.ffmpeg_status_label)

        self.ffmpeg_progress = QProgressBar()
        self.ffmpeg_progress.setFixedHeight(12)
        self.ffmpeg_progress.setTextVisible(False)
        self.ffmpeg_progress.setVisible(False)
        ffmpeg_vbox.addWidget(self.ffmpeg_progress)

        tools_layout.addWidget(ffmpeg_card)

        # Divider
        line = QFrame()
        line.setFrameShape(QFrame.Shape.HLine)
        line.setFrameShadow(QFrame.Shadow.Sunken)
        line.setStyleSheet("color: rgba(255, 255, 255, 0.15);")
        tools_layout.addWidget(line)

        # 2. Deno Item
        deno_card = QWidget()
        deno_vbox = QVBoxLayout(deno_card)
        deno_vbox.setContentsMargins(0, 2, 0, 2)
        deno_vbox.setSpacing(4)

        deno_hdr = QHBoxLayout()
        deno_title = QLabel(f"<b>{t('deno_tool_title')}</b>")
        self.btn_deno_action = QPushButton(t("btn_check_update"))
        self.btn_deno_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
        self.btn_deno_action.clicked.connect(self.handle_deno_action)
        deno_hdr.addWidget(deno_title)
        deno_hdr.addStretch()
        deno_hdr.addWidget(self.btn_deno_action)
        deno_vbox.addLayout(deno_hdr)

        self.deno_ver_label = QLabel()
        self.deno_ver_label.setWordWrap(True)
        deno_vbox.addWidget(self.deno_ver_label)

        self.deno_status_label = QLabel()
        self.deno_status_label.setWordWrap(True)
        self.deno_status_label.setVisible(False)
        deno_vbox.addWidget(self.deno_status_label)

        self.deno_progress = QProgressBar()
        self.deno_progress.setFixedHeight(12)
        self.deno_progress.setTextVisible(False)
        self.deno_progress.setVisible(False)
        deno_vbox.addWidget(self.deno_progress)

        tools_layout.addWidget(deno_card)
        parent_layout.addWidget(tools_group)

        self.refresh_dependency_labels()

    def refresh_dependency_labels(self):
        dm = DependencyManager.get_instance()

        # FFmpeg
        ff_info = get_ffmpeg_info()
        if dm.is_downloading("ffmpeg"):
            pct, txt = dm.get_progress("ffmpeg")
            self.ffmpeg_progress.setVisible(True)
            self.ffmpeg_progress.setValue(pct)
            self.ffmpeg_status_label.setVisible(True)
            self.ffmpeg_status_label.setStyleSheet("color: #6c63ff; font-size: 11px;")
            self.ffmpeg_status_label.setText(txt or t("status_checking"))
            self.btn_ffmpeg_action.setEnabled(False)
            self.btn_ffmpeg_action.setText(t("status_checking"))
            thread = dm.get_thread("ffmpeg")
            if thread:
                try:
                    thread.progress_updated.disconnect(self.on_ffmpeg_progress_wrapper)
                    thread.download_finished.disconnect(self.on_ffmpeg_finished_wrapper)
                except Exception:
                    pass
                thread.progress_updated.connect(self.on_ffmpeg_progress_wrapper)
                thread.download_finished.connect(self.on_ffmpeg_finished_wrapper)
        elif ff_info["installed"]:
            self.ffmpeg_ver_label.setText(f"🟢 {ff_info['version']}")
            self.ffmpeg_ver_label.setStyleSheet("color: #00c853; font-size: 11px;")
            self.btn_ffmpeg_action.setEnabled(True)
            self.btn_ffmpeg_action.setText(t("btn_check_update"))
            self.btn_ffmpeg_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
            self.ffmpeg_action_mode = "check"
        else:
            self.ffmpeg_ver_label.setText(f"🔴 {t('status_not_installed')}")
            self.ffmpeg_ver_label.setStyleSheet("color: #e94560; font-size: 11px;")
            self.btn_ffmpeg_action.setEnabled(True)
            self.btn_ffmpeg_action.setText(t("btn_download"))
            self.btn_ffmpeg_action.setStyleSheet("background-color: #00c853; color: #000; font-weight: bold; padding: 4px 12px; font-size: 11px; border-radius: 3px;")
            self.ffmpeg_action_mode = "download"

        # Deno
        deno_info = get_deno_info()
        if dm.is_downloading("deno"):
            pct, txt = dm.get_progress("deno")
            self.deno_progress.setVisible(True)
            self.deno_progress.setValue(pct)
            self.deno_status_label.setVisible(True)
            self.deno_status_label.setStyleSheet("color: #6c63ff; font-size: 11px;")
            self.deno_status_label.setText(txt or t("status_checking"))
            self.btn_deno_action.setEnabled(False)
            self.btn_deno_action.setText(t("status_checking"))
            thread = dm.get_thread("deno")
            if thread:
                try:
                    thread.progress_updated.disconnect(self.on_deno_progress_wrapper)
                    thread.download_finished.disconnect(self.on_deno_finished_wrapper)
                except Exception:
                    pass
                thread.progress_updated.connect(self.on_deno_progress_wrapper)
                thread.download_finished.connect(self.on_deno_finished_wrapper)
        elif deno_info["installed"]:
            self.deno_ver_label.setText(f"🟢 {deno_info['version']}")
            self.deno_ver_label.setStyleSheet("color: #00c853; font-size: 11px;")
            self.btn_deno_action.setEnabled(True)
            self.btn_deno_action.setText(t("btn_check_update"))
            self.btn_deno_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
            self.deno_action_mode = "check"
        else:
            self.deno_ver_label.setText(f"🔴 {t('status_not_installed')}")
            self.deno_ver_label.setStyleSheet("color: #e94560; font-size: 11px;")
            self.btn_deno_action.setEnabled(True)
            self.btn_deno_action.setText(t("btn_download"))
            self.btn_deno_action.setStyleSheet("background-color: #00c853; color: #000; font-weight: bold; padding: 4px 12px; font-size: 11px; border-radius: 3px;")
            self.deno_action_mode = "download"

    def handle_ffmpeg_action(self):
        if self.ffmpeg_action_mode in ("download", "update"):
            self.start_download("ffmpeg", getattr(self, "ffmpeg_download_url", None))
        else:
            self.btn_ffmpeg_action.setEnabled(False)
            self.btn_ffmpeg_action.setText(t("status_checking"))
            self.ffmpeg_status_label.setVisible(True)
            self.ffmpeg_status_label.setStyleSheet("color: #6c63ff; font-size: 11px;")
            self.ffmpeg_status_label.setText(t("status_checking"))
            self.ffmpeg_check_thread = DependencyCheckThread("ffmpeg", self)
            self.ffmpeg_check_thread.check_finished.connect(self.on_ffmpeg_check_finished)
            self.ffmpeg_check_thread.start()

    def on_ffmpeg_check_finished(self, dep, res):
        self.btn_ffmpeg_action.setEnabled(True)
        if res.get("success"):
            latest = res["latest_version"]
            self.ffmpeg_download_url = res.get("download_url")
            ff_info = get_ffmpeg_info()
            curr = ff_info.get("version_num", "")
            if curr and latest in curr:
                self.ffmpeg_status_label.setText(t("status_up_to_date").format(ver=latest))
                self.ffmpeg_status_label.setStyleSheet("color: #00c853; font-size: 11px;")
                self.btn_ffmpeg_action.setText(t("btn_check_update"))
                self.btn_ffmpeg_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
                self.ffmpeg_action_mode = "check"
            else:
                self.ffmpeg_status_label.setText(t("status_update_available").format(ver=curr or "Unknown", latest=latest))
                self.ffmpeg_status_label.setStyleSheet("color: #ff9800; font-size: 11px; font-weight: bold;")
                self.btn_ffmpeg_action.setText(t("btn_update"))
                self.btn_ffmpeg_action.setStyleSheet("background-color: #ff9800; color: #000; font-weight: bold; padding: 4px 12px; font-size: 11px; border-radius: 3px;")
                self.ffmpeg_action_mode = "update"
        else:
            self.ffmpeg_status_label.setText(t("status_check_failed").format(err=res.get("error", "")))
            self.ffmpeg_status_label.setStyleSheet("color: #e94560; font-size: 11px;")
            self.btn_ffmpeg_action.setText(t("btn_check_update"))
            self.btn_ffmpeg_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
            self.ffmpeg_action_mode = "check"

    def handle_deno_action(self):
        if self.deno_action_mode in ("download", "update"):
            self.start_download("deno", getattr(self, "deno_download_url", None))
        else:
            self.btn_deno_action.setEnabled(False)
            self.btn_deno_action.setText(t("status_checking"))
            self.deno_status_label.setVisible(True)
            self.deno_status_label.setStyleSheet("color: #6c63ff; font-size: 11px;")
            self.deno_status_label.setText(t("status_checking"))
            self.deno_check_thread = DependencyCheckThread("deno", self)
            self.deno_check_thread.check_finished.connect(self.on_deno_check_finished)
            self.deno_check_thread.start()

    def on_deno_check_finished(self, dep, res):
        self.btn_deno_action.setEnabled(True)
        if res.get("success"):
            latest = res["latest_version"]
            self.deno_download_url = res.get("download_url")
            deno_info = get_deno_info()
            curr = deno_info.get("version_num", "")
            if curr and curr == latest:
                self.deno_status_label.setText(t("status_up_to_date").format(ver=latest))
                self.deno_status_label.setStyleSheet("color: #00c853; font-size: 11px;")
                self.btn_deno_action.setText(t("btn_check_update"))
                self.btn_deno_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
                self.deno_action_mode = "check"
            else:
                self.deno_status_label.setText(t("status_update_available").format(ver=curr or "Unknown", latest=latest))
                self.deno_status_label.setStyleSheet("color: #ff9800; font-size: 11px; font-weight: bold;")
                self.btn_deno_action.setText(t("btn_update"))
                self.btn_deno_action.setStyleSheet("background-color: #ff9800; color: #000; font-weight: bold; padding: 4px 12px; font-size: 11px; border-radius: 3px;")
                self.deno_action_mode = "update"
        else:
            self.deno_status_label.setText(t("status_check_failed").format(err=res.get("error", "")))
            self.deno_status_label.setStyleSheet("color: #e94560; font-size: 11px;")
            self.btn_deno_action.setText(t("btn_check_update"))
            self.btn_deno_action.setStyleSheet("padding: 4px 10px; font-size: 11px;")
            self.deno_action_mode = "check"

    def on_ffmpeg_progress_wrapper(self, pct, txt):
        self.on_download_progress("ffmpeg", pct, txt)

    def on_ffmpeg_finished_wrapper(self, ok, msg):
        self.on_download_finished("ffmpeg", ok, msg)

    def on_deno_progress_wrapper(self, pct, txt):
        self.on_download_progress("deno", pct, txt)

    def on_deno_finished_wrapper(self, ok, msg):
        self.on_download_finished("deno", ok, msg)

    def start_download(self, dep_name, download_url=None):
        btn = self.btn_ffmpeg_action if dep_name == "ffmpeg" else self.btn_deno_action
        lbl = self.ffmpeg_status_label if dep_name == "ffmpeg" else self.deno_status_label
        pbar = self.ffmpeg_progress if dep_name == "ffmpeg" else self.deno_progress

        btn.setEnabled(False)
        btn.setText(t("status_checking"))
        lbl.setVisible(True)
        lbl.setStyleSheet("color: #6c63ff; font-size: 11px;")
        lbl.setText("0%...")
        pbar.setVisible(True)
        pbar.setValue(0)

        # Start persistent download managed independently by DependencyManager
        thread = DependencyManager.get_instance().start_download(dep_name, download_url)
        if dep_name == "ffmpeg":
            thread.progress_updated.connect(self.on_ffmpeg_progress_wrapper)
            thread.download_finished.connect(self.on_ffmpeg_finished_wrapper)
        else:
            thread.progress_updated.connect(self.on_deno_progress_wrapper)
            thread.download_finished.connect(self.on_deno_finished_wrapper)

    def on_download_progress(self, dep_name, pct, txt):
        pbar = self.ffmpeg_progress if dep_name == "ffmpeg" else self.deno_progress
        lbl = self.ffmpeg_status_label if dep_name == "ffmpeg" else self.deno_status_label
        pbar.setValue(pct)
        lbl.setText(txt)

    def on_download_finished(self, dep_name, ok, msg):
        btn = self.btn_ffmpeg_action if dep_name == "ffmpeg" else self.btn_deno_action
        lbl = self.ffmpeg_status_label if dep_name == "ffmpeg" else self.deno_status_label
        pbar = self.ffmpeg_progress if dep_name == "ffmpeg" else self.deno_progress

        btn.setEnabled(True)
        if ok:
            pbar.setValue(100)
            lbl.setStyleSheet("color: #00c853; font-size: 11px; font-weight: bold;")
            lbl.setText(t("status_download_success"))
            self.refresh_dependency_labels()
        else:
            lbl.setStyleSheet("color: #e94560; font-size: 11px;")
            lbl.setText(t("status_download_failed").format(err=msg))
            btn.setText(t("btn_download"))

    def reject(self):
        # We intentionally do not cancel the background download thread here
        # so it continues running in the background even if settings is closed.
        super().reject()

class PlayerWindow(QMainWindow):
    vocal_toggled = pyqtSignal(bool)
    song_finished = pyqtSignal()
    
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle(t("player_title"))
        self.setWindowIcon(QIcon("mic.svg"))
        self.resize(800, 600)
        self.current_audio_path = None
        self.instrumental_audio_path = None
        self.is_karaoke_mode = False
        self.current_title = ""
        self.qr_label = None
        
        self.init_ui()
        self.setup_player()
        self.setup_qr_code()
        
    def init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)
        
        # Video widget
        self.video_widget = QVideoWidget()
        self.video_widget.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        layout.addWidget(self.video_widget, stretch=1)
        
        # Time bar
        time_layout = QHBoxLayout()
        self.time_label_current = QLabel("00:00")
        self.time_slider = QSlider(Qt.Orientation.Horizontal)
        self.time_slider.setRange(0, 0)
        self.time_slider.sliderMoved.connect(self.set_position)
        self.time_label_total = QLabel("00:00")
        
        time_layout.addWidget(self.time_label_current)
        time_layout.addWidget(self.time_slider)
        time_layout.addWidget(self.time_label_total)
        layout.addLayout(time_layout)
        
        # Controls
        controls = QWidget()
        controls_layout = QHBoxLayout(controls)
        
        self.btn_play = QPushButton(t("play"))
        self.btn_play.clicked.connect(self.toggle_play)
        controls_layout.addWidget(self.btn_play)
        
        self.btn_stop = QPushButton(t("stop"))
        self.btn_stop.clicked.connect(self.stop_playback)
        controls_layout.addWidget(self.btn_stop)
        
        self.btn_next = QPushButton(t("next"))
        self.btn_next.clicked.connect(self.skip_playback)
        controls_layout.addWidget(self.btn_next)
        
        self.btn_toggle_vocals = QPushButton(t("vocals_on"))
        self.btn_toggle_vocals.setCheckable(True)
        self.btn_toggle_vocals.setEnabled(False)
        self.btn_toggle_vocals.clicked.connect(self.toggle_vocals_clicked)
        controls_layout.addWidget(self.btn_toggle_vocals)
        
        self.volume_slider = QSlider(Qt.Orientation.Horizontal)
        self.volume_slider.setRange(0, 100)
        self.volume_slider.setValue(50)
        self.volume_slider.valueChanged.connect(self.set_volume)
        controls_layout.addWidget(QLabel("Vol:"))
        controls_layout.addWidget(self.volume_slider)
        
        layout.addWidget(controls)
        
    def setup_player(self):
        self.player = QMediaPlayer()
        self.video_audio_output = QAudioOutput()
        self.player.setAudioOutput(self.video_audio_output)
        self.player.setVideoOutput(self.video_widget)
        self.video_audio_output.setVolume(0.0)
        
        self.audio_player_original = QMediaPlayer()
        self.audio_output_original = QAudioOutput()
        self.audio_player_original.setAudioOutput(self.audio_output_original)
        self.audio_output_original.setVolume(0.5)
        
        self.audio_player_instrumental = QMediaPlayer()
        self.audio_output_instrumental = QAudioOutput()
        self.audio_player_instrumental.setAudioOutput(self.audio_output_instrumental)
        self.audio_output_instrumental.setVolume(0.0)
        
        self.player.positionChanged.connect(self.position_changed)
        self.player.durationChanged.connect(self.duration_changed)
        self.player.mediaStatusChanged.connect(self.media_status_changed)
        
    def media_status_changed(self, status):
        if status == QMediaPlayer.MediaStatus.EndOfMedia:
            self.song_finished.emit()
            
    def position_changed(self, position):
        if not self.time_slider.isSliderDown():
            self.time_slider.setValue(position)
        seconds = (position // 1000) % 60
        minutes = (position // 60000)
        self.time_label_current.setText(f"{minutes:02d}:{seconds:02d}")

    def duration_changed(self, duration):
        self.time_slider.setRange(0, duration)
        seconds = (duration // 1000) % 60
        minutes = (duration // 60000)
        self.time_label_total.setText(f"{minutes:02d}:{seconds:02d}")

    def set_position(self, position):
        self.player.setPosition(position)
        self.audio_player_original.setPosition(position)
        self.audio_player_instrumental.setPosition(position)
        
    def set_volume(self, value):
        volume = value / 100.0
        if self.is_karaoke_mode:
            self.audio_output_instrumental.setVolume(volume)
            self.audio_output_original.setVolume(0.0)
        else:
            self.audio_output_original.setVolume(volume)
            self.audio_output_instrumental.setVolume(0.0)
            
    def play_song(self, video_path, audio_path, inst_path):
        self.current_audio_path = audio_path
        self.instrumental_audio_path = inst_path
        self.btn_toggle_vocals.setEnabled(True)
        self.set_karaoke_mode(False)
        
        abs_video = os.path.abspath(video_path) if video_path else ""
        abs_audio = os.path.abspath(audio_path) if audio_path else ""
        abs_inst = os.path.abspath(inst_path) if inst_path else ""
        
        if abs_video and os.path.exists(abs_video):
            self.player.setSource(QUrl.fromLocalFile(abs_video))
        else:
            self.player.setSource(QUrl())
            
        if abs_audio and os.path.exists(abs_audio):
            self.audio_player_original.setSource(QUrl.fromLocalFile(abs_audio))
        else:
            QMessageBox.critical(self, "Error", f"Original audio file not found:\n{abs_audio}")
            return
            
        if abs_inst and os.path.exists(abs_inst):
            self.audio_player_instrumental.setSource(QUrl.fromLocalFile(abs_inst))
        else:
            self.audio_player_instrumental.setSource(QUrl())
        
        self.set_volume(self.volume_slider.value())
        self.btn_play.setText(t("pause"))
        
        self.player.play()
        self.audio_player_original.play()
        
    def toggle_vocals_clicked(self):
        self.set_karaoke_mode(self.btn_toggle_vocals.isChecked())
        
    def set_karaoke_mode(self, enabled):
        if not self.instrumental_audio_path:
            return
            
        self.is_karaoke_mode = enabled
        self.btn_toggle_vocals.setChecked(enabled)
        
        if enabled:
            self.btn_toggle_vocals.setText(t("vocals_off"))
            self.audio_player_instrumental.setPosition(self.player.position())
            if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
                self.audio_player_instrumental.play()
        else:
            self.btn_toggle_vocals.setText(t("vocals_on"))
            self.audio_player_original.setPosition(self.player.position())
            if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
                self.audio_player_original.play()
                
        self.set_volume(self.volume_slider.value())
        self.vocal_toggled.emit(enabled)
        
    def toggle_play(self):
        if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.player.pause()
            self.audio_player_original.pause()
            self.audio_player_instrumental.pause()
            self.btn_play.setText(t("play"))
        else:
            self.player.play()
            self.audio_player_original.play()
            if self.is_karaoke_mode:
                self.audio_player_instrumental.play()
            self.btn_play.setText(t("pause"))
            
    def stop_playback(self):
        self.player.stop()
        self.audio_player_original.stop()
        self.audio_player_instrumental.stop()
        self.btn_play.setText(t("play"))
        
    def release_media(self):
        """Stop all players and release file handles so files can be deleted."""
        self.player.stop()
        self.audio_player_original.stop()
        self.audio_player_instrumental.stop()
        self.player.setSource(QUrl())
        self.audio_player_original.setSource(QUrl())
        self.audio_player_instrumental.setSource(QUrl())
        self.btn_play.setText(t("play"))
        
    def skip_playback(self):
        # Force a stop and emit song_finished to trigger next song logic
        self.stop_playback()
        self.song_finished.emit()

    def retranslate_ui(self):
        self.setWindowTitle(t("player_title"))
        if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.btn_play.setText(t("pause"))
        else:
            self.btn_play.setText(t("play"))
        self.btn_stop.setText(t("stop"))
        self.btn_next.setText(t("next"))
        if self.is_karaoke_mode:
            self.btn_toggle_vocals.setText(t("vocals_off"))
        else:
            self.btn_toggle_vocals.setText(t("vocals_on"))
        if hasattr(self, 'qr_label') and self.qr_label:
            ip = get_local_ip()
            url = f"http://{ip}:8080"
            self.qr_label.setToolTip(t("scan_qr").format(url=url))
            if hasattr(self, 'qr_url_label') and self.qr_url_label:
                self.qr_url_label.setText(url)
        
    def setup_qr_code(self):
        ip = get_local_ip()
        url = f"http://{ip}:8080"
        qr = qrcode.QRCode(version=1, box_size=4, border=1)
        qr.add_data(url)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        
        img = img.convert("RGBA")
        raw_data = bytearray(img.tobytes("raw", "RGBA"))
        for i in range(0, len(raw_data), 4):
            if raw_data[i] == 255 and raw_data[i+1] == 255 and raw_data[i+2] == 255:
                raw_data[i+3] = 160
        data_bytes = bytes(raw_data)
        
        qimg = QImage(data_bytes, img.width, img.height, QImage.Format.Format_RGBA8888)
        pixmap = QPixmap.fromImage(qimg)
        
        self.qr_label = QWidget(
            self,
            Qt.WindowType.Tool |
            Qt.WindowType.FramelessWindowHint |
            Qt.WindowType.WindowTransparentForInput
        )

        self.qr_label.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.qr_label.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        
        layout = QVBoxLayout(self.qr_label)
        layout.setContentsMargins(4, 4, 4, 4)
        layout.setSpacing(4)
        layout.setAlignment(Qt.AlignmentFlag.AlignCenter)
        
        self.qr_img_label = QLabel()
        self.qr_img_label.setPixmap(pixmap)
        self.qr_img_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.qr_img_label.setStyleSheet("background-color: transparent; border: 2px solid rgba(255,255,255,140); border-radius: 4px;")
        layout.addWidget(self.qr_img_label)
        
        self.qr_url_label = QLabel(url)
        self.qr_url_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.qr_url_label.setStyleSheet(
            "color: #FFFFFF; font-size: 11px; font-weight: bold; font-family: 'Segoe UI', Arial, sans-serif; "
            "background-color: rgba(0, 0, 0, 180); border: 1px solid rgba(255, 255, 255, 120); "
            "border-radius: 4px; padding: 3px 6px;"
        )
        layout.addWidget(self.qr_url_label)
        
        self.qr_label.setToolTip(t("scan_qr").format(url=url))
        self.qr_label.adjustSize()
        self.qr_label.show()
        
        self.position_qr_code()
        self.video_widget.installEventFilter(self)

    def eventFilter(self, source, event):
        if source == self.video_widget and event.type() in (QEvent.Type.Resize, QEvent.Type.Move):
            self.position_qr_code()
        return super().eventFilter(source, event)

    def position_qr_code(self):
        if getattr(self, 'qr_label', None) is not None:
            padding = 20
            # Get the video widget's position relative to the screen to move the floating tool window
            global_pos = self.video_widget.mapToGlobal(QPoint(0, 0))
            self.qr_label.adjustSize()
            self.qr_label.move(
                global_pos.x() + self.video_widget.width() - self.qr_label.width() - padding,
                global_pos.y() + padding
            )

    def moveEvent(self, event):
        super().moveEvent(event)
        self.position_qr_code()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.position_qr_code()

    def showEvent(self, event):
        super().showEvent(event)
        if hasattr(self, 'qr_label'):
            self.qr_label.show()
            self.position_qr_code()

    def hideEvent(self, event):
        super().hideEvent(event)
        if hasattr(self, 'qr_label'):
            self.qr_label.hide()

    def closeEvent(self, event):
        self.hide()
        event.ignore()

    def changeEvent(self, event):
        super().changeEvent(event)
        if event.type() == QEvent.Type.WindowStateChange:
            if self.isMinimized():
                if hasattr(self, 'qr_label'):
                    self.qr_label.hide()
            else:
                if hasattr(self, 'qr_label') and self.isVisible():
                    self.qr_label.show()
                    self.position_qr_code()

class KaraokeMainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Karaoke - Song Queue")
        self.setWindowIcon(QIcon("mic.svg"))
        self.setGeometry(100, 100, 500, 800)
        
        self.youtube = YouTubeKaraokeSource()
        self.vocal_remover = VocalRemover()
        self.queue_manager = QueueManager(self.youtube, self.vocal_remover)
        
        self.queue_manager.item_updated.connect(self.update_queue_item_progress)
        self.queue_manager.item_ready.connect(self.on_queue_item_ready)
        
        self.current_playing_row = -1
        self.next_task_id = 1
        
        self.player_window = PlayerWindow()
        self.player_window.vocal_toggled.connect(self.sync_vocal_button)
        self.player_window.song_finished.connect(self.play_next_song)

        # Listen to player state changes to update the queue highlight
        self.player_window.player.playbackStateChanged.connect(self.update_playing_row_highlight)
        
        self.web_server = WebServerThread()
        self.web_server.play_song_requested.connect(self.play_from_queue_by_index)
        self.web_server.toggle_play_requested.connect(self.player_window.toggle_play)
        self.web_server.stop_requested.connect(self.player_window.stop_playback)
        self.web_server.toggle_vocals_requested.connect(self.toggle_vocals_from_remote)
        self.web_server.seek_requested.connect(self.player_window.set_position)
        self.web_server.next_song_requested.connect(self.player_window.skip_playback)
        self.web_server.move_to_next_requested.connect(self.move_to_next)
        self.web_server.delete_requested.connect(self.delete_song_by_index)
        self.web_server.replay_requested.connect(self.replay_song_by_index)
        self.web_server.volume_requested.connect(self.player_window.volume_slider.setValue)
        self.web_server.add_local_requested.connect(self.add_local_song_to_queue)
        self.web_server.add_youtube_requested.connect(self.add_song_from_web_youtube)
        self.web_server.delete_local_song_requested.connect(self.delete_local_song_from_web)
        self.web_server.clear_played_requested.connect(self.clear_played_songs)
        
        self.player_window.player.playbackStateChanged.connect(self.sync_player_state_to_web)
        self.player_window.player.positionChanged.connect(self.sync_player_state_to_web)
        self.player_window.player.durationChanged.connect(self.sync_player_state_to_web)
        self.player_window.volume_slider.valueChanged.connect(self.sync_player_state_to_web)
        self.player_window.vocal_toggled.connect(self.sync_player_state_to_web)
        
        self.web_server.start()
        
        self.init_ui()
        self.retranslate_ui()
        self.player_window.show()
        
    def init_ui(self):
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        layout = QVBoxLayout(central_widget)
        
        # Queue Group
        self.queue_group = QGroupBox(t("song_queue"))
        queue_layout = QVBoxLayout(self.queue_group)
        
        queue_btn_layout1 = QHBoxLayout()
        self.btn_add_song = QPushButton(t("add_song"))
        self.btn_add_song.clicked.connect(self.open_add_song_dialog)
        queue_btn_layout1.addWidget(self.btn_add_song)
        
        self.btn_move_next = QPushButton(t("move_to_next"))
        self.btn_move_next.clicked.connect(self.move_selected_to_next)
        queue_btn_layout1.addWidget(self.btn_move_next)
        
        self.btn_delete_song = QPushButton(t("delete_song"))
        self.btn_delete_song.clicked.connect(self.delete_selected_song)
        queue_btn_layout1.addWidget(self.btn_delete_song)
        
        queue_layout.addLayout(queue_btn_layout1)

        queue_btn_layout2 = QHBoxLayout()
        self.btn_replay_song = QPushButton(t("replay_song"))
        self.btn_replay_song.clicked.connect(self.replay_selected_song)
        queue_btn_layout2.addWidget(self.btn_replay_song)

        self.btn_clear_played = QPushButton(t("clear_played"))
        self.btn_clear_played.clicked.connect(self.clear_played_songs)
        queue_btn_layout2.addWidget(self.btn_clear_played)

        self.btn_settings = QPushButton(t("btn_settings"))
        self.btn_settings.clicked.connect(self.open_settings_dialog)
        queue_btn_layout2.addWidget(self.btn_settings)

        queue_layout.addLayout(queue_btn_layout2)
        
        self.queue_table = QTableWidget(0, 3)
        self.queue_table.setHorizontalHeaderLabels([t("col_title"), t("col_status"), t("col_progress")])
        self.queue_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Interactive)
        self.queue_table.horizontalHeader().setStretchLastSection(True)

        self.queue_table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.queue_table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.queue_table.itemDoubleClicked.connect(self.play_from_queue)
        
        queue_layout.addWidget(self.queue_table)
        
        layout.addWidget(self.queue_group)
        
        # Vocal Removal Controls
        self.vocal_group = QGroupBox(t("vocal_control"))
        vocal_layout = QVBoxLayout(self.vocal_group)
        
        self.btn_toggle_vocals = QPushButton(t("toggle_vocals"))
        self.btn_toggle_vocals.setCheckable(True)
        self.btn_toggle_vocals.setEnabled(False)
        self.btn_toggle_vocals.clicked.connect(self.toggle_vocals_clicked)
        vocal_layout.addWidget(self.btn_toggle_vocals)
        
        layout.addWidget(self.vocal_group)
        
    def showEvent(self, event):
        super().showEvent(event)
        
        # Total available width inside the table
        total_width = self.queue_table.viewport().width()
        
        # Set percentage widths:
        self.queue_table.setColumnWidth(0, int(total_width * 0.50))  # 50% for Title
        self.queue_table.setColumnWidth(1, int(total_width * 0.25))  # 25% for Status
        self.queue_table.setColumnWidth(2, int(total_width * 0.25))  # 25% for Progress

    def is_song_played(self, row):
        if row < 0 or row >= self.queue_table.rowCount():
            return False
        title_item = self.queue_table.item(row, 0)
        if title_item:
            val = title_item.data(Qt.ItemDataRole.UserRole + 5)
            if val is not None:
                return bool(val)
        return False

    def set_song_played(self, row, played=True):
        if row < 0 or row >= self.queue_table.rowCount():
            return
        title_item = self.queue_table.item(row, 0)
        if title_item:
            title_item.setData(Qt.ItemDataRole.UserRole + 5, played)

    def update_playing_row_highlight(self, *args):
        """Highlights the active playing song and styles played songs in the application table."""
        playback_state = self.player_window.player.playbackState()
        is_playing = (playback_state == QMediaPlayer.PlaybackState.PlayingState)
        is_paused = (playback_state == QMediaPlayer.PlaybackState.PausedState)
        is_active = is_playing or is_paused

        for r in range(self.queue_table.rowCount()):
            title_item = self.queue_table.item(r, 0)
            status_item = self.queue_table.item(r, 1)
            
            if not title_item or not status_item:
                continue

            font = title_item.font()
            is_played = self.is_song_played(r)
            
            if r == self.current_playing_row and is_active:
                # Active song row styling (takes priority while playing/paused)
                font.setBold(True)
                title_item.setFont(font)
                
                highlight_brush = QBrush(QColor(108, 99, 255, 60))
                title_item.setBackground(highlight_brush)
                status_item.setBackground(highlight_brush)
                
                if is_playing:
                    title_item.setIcon(self.style().standardIcon(QStyle.StandardPixmap.SP_MediaPlay))
                    status_item.setText(t("status_playing"))
                    status_item.setForeground(QBrush(QColor("#00c853")))  # Green
                else:
                    title_item.setIcon(self.style().standardIcon(QStyle.StandardPixmap.SP_MediaPause))
                    status_item.setText(t("status_paused"))
                    status_item.setForeground(QBrush(QColor("#ff9800")))  # Amber
            else:
                # Inactive / normal row styling
                font.setBold(False)
                title_item.setFont(font)
                title_item.setIcon(QIcon())
                
                # Revert status text if it was playing/paused
                if status_item.text() in ("▶ Playing", "⏸ Paused", "▶ 正在播放", "⏸ 暂停中", t("status_playing"), t("status_paused")):
                    status_item.setText(t("status_ready"))
                
                if is_played:
                    # Gray background for already played songs
                    gray_bg = QBrush(QColor(70, 70, 80, 160))
                    dim_text = QBrush(QColor(150, 150, 160))
                    title_item.setBackground(gray_bg)
                    title_item.setForeground(dim_text)
                    status_item.setBackground(gray_bg)
                    status_item.setForeground(dim_text)
                else:
                    clear_brush = QBrush(QColor(0, 0, 0, 0))
                    normal_text = QBrush(QColor("#eaeaea"))
                    title_item.setBackground(clear_brush)
                    title_item.setForeground(normal_text)
                    status_item.setBackground(clear_brush)
                    if status_item.text() in ("Ready", "已就绪", t("status_ready")):
                        status_item.setForeground(QBrush(QColor("#00c853")))
                    elif status_item.text().startswith("Error") or status_item.text().startswith("错误") or status_item.text().startswith(t("status_error")):
                        status_item.setForeground(QBrush(QColor("#e94560")))
                    else:
                        status_item.setForeground(normal_text)

        self.sync_queue_to_web()
        
    def move_selected_to_next(self):
        current_row = self.queue_table.currentRow()
        if current_row >= 0:
            self.move_to_next(current_row)
            
    def move_to_next(self, source_row):
        if source_row < 0 or source_row >= self.queue_table.rowCount(): return
        
        target_row = self.current_playing_row + 1 if self.current_playing_row >= 0 else 0
        if source_row == target_row or source_row == self.current_playing_row:
            return
            
        title_item = self.queue_table.takeItem(source_row, 0)
        status_item = self.queue_table.takeItem(source_row, 1)
        progress_widget = self.queue_table.cellWidget(source_row, 2)
        progress_val = progress_widget.value() if isinstance(progress_widget, QProgressBar) else 0
        
        self.queue_table.removeRow(source_row)
        
        if source_row < target_row:
            target_row -= 1
            
        if source_row < self.current_playing_row:
            self.current_playing_row -= 1
            
        self.queue_table.insertRow(target_row)
        self.queue_table.setItem(target_row, 0, title_item)
        self.queue_table.setItem(target_row, 1, status_item)
        
        new_pb = QProgressBar()
        new_pb.setRange(0, 100)
        new_pb.setValue(progress_val)
        self.queue_table.setCellWidget(target_row, 2, new_pb)
            
        self.update_playing_row_highlight()
        
    def delete_selected_song(self):
        current_row = self.queue_table.currentRow()
        if current_row >= 0:
            self.delete_song_by_index(current_row)
            
    def delete_song_by_index(self, row):
        if row < 0 or row >= self.queue_table.rowCount(): return
        
        if row == self.current_playing_row:
            self.player_window.skip_playback()
            
        self.queue_table.removeRow(row)
        
        if hasattr(self, 'current_playing_row'):
            if self.current_playing_row > row:
                self.current_playing_row -= 1
            elif self.current_playing_row == row:
                self.current_playing_row = -1
            
        self.update_playing_row_highlight()
        
    def replay_selected_song(self):
        current_row = self.queue_table.currentRow()
        if current_row >= 0:
            self.replay_song_by_index(current_row)

    def replay_song_by_index(self, row):
        if 0 <= row < self.queue_table.rowCount():
            self.set_song_played(row, False)
            self.update_playing_row_highlight()
            self.sync_queue_to_web()
        
    def play_next_song(self):
        start_row = 0
        if hasattr(self, 'current_playing_row') and self.current_playing_row >= 0:
            finished_row = self.current_playing_row
            self.set_song_played(finished_row, True)
            start_row = finished_row + 1
            
        found_row = -1
        for next_row in range(start_row, self.queue_table.rowCount()):
            if self.is_song_played(next_row):
                continue  # Skip song if already played
            
            status_item = self.queue_table.item(next_row, 1)
            status_text = status_item.text() if status_item else ""
            
            ready_statuses = ("Ready", "▶ Playing", "⏸ Paused", "已就绪", "▶ 正在播放", "⏸ 暂停中", t("status_ready"), t("status_playing"), t("status_paused"))
            if status_text in ready_statuses:
                found_row = next_row
                break
            elif status_text not in ready_statuses and not status_text.startswith("Error") and not status_text.startswith("失败"):
                # Next unplayed song is downloading/processing; wait for it
                self.current_playing_row = -1
                self.player_window.current_title = ""
                self.update_playing_row_highlight()
                self.sync_player_state_to_web()
                return

        if found_row != -1:
            self.play_from_queue_by_index(found_row)
        else:
            self.current_playing_row = -1
            self.player_window.current_title = ""
            self.update_playing_row_highlight()
            self.sync_player_state_to_web()

    def play_from_queue_by_index(self, row):
        item = self.queue_table.item(row, 0)
        if item:
            self.play_from_queue(item)
            
    def toggle_vocals_from_remote(self):
        self.btn_toggle_vocals.setChecked(not self.btn_toggle_vocals.isChecked())
        self.toggle_vocals_clicked()
        
    def sync_queue_to_web(self):
        if not hasattr(self, 'web_server'): return
        queue_data = []
        for i in range(self.queue_table.rowCount()):
            title_item = self.queue_table.item(i, 0)
            status_item = self.queue_table.item(i, 1)
            progress_bar = self.queue_table.cellWidget(i, 2)
            title = title_item.text() if title_item else ""
            
            queue_data.append({
                "title": title,
                "title_s": to_simplified(title),
                "title_t": to_traditional(title),
                "status": status_item.text() if status_item else "",
                "progress": progress_bar.value() if progress_bar else 0,
                "played": self.is_song_played(i),
                "url": title_item.data(Qt.ItemDataRole.UserRole) or "" if title_item else "",
                "video": title_item.data(Qt.ItemDataRole.UserRole + 1) or title_item.data(257) or "" if title_item else "",
                "audio": title_item.data(Qt.ItemDataRole.UserRole + 2) or title_item.data(258) or "" if title_item else "",
                "inst": title_item.data(Qt.ItemDataRole.UserRole + 3) or title_item.data(259) or "" if title_item else "",
                "is_current": (i == self.current_playing_row)
            })
        self.web_server.update_queue(queue_data)

    def find_duplicate_in_queue(self, title="", url=None, audio_path=None, video_path=None):
        """
        Check if a song is already present in the queue table.
        Returns a dict {'row': i, 'title': ..., 'status': ..., 'played': ...}
        if duplicate detected, else None.
        """
        if not hasattr(self, 'queue_table') or self.queue_table.rowCount() == 0:
            return None

        target_norm = normalize_song_title(title) if title else ""
        target_id = extract_video_id(url) if url else ""
        norm_audio = os.path.normpath(os.path.abspath(audio_path)).lower() if audio_path else ""
        norm_video = os.path.normpath(os.path.abspath(video_path)).lower() if video_path else ""

        # Check unplayed songs first, then played songs
        for check_played in (False, True):
            for i in range(self.queue_table.rowCount()):
                is_played = self.is_song_played(i)
                if is_played != check_played:
                    continue

                item = self.queue_table.item(i, 0)
                if not item:
                    continue

                row_title = item.text()
                row_url = item.data(Qt.ItemDataRole.UserRole) or ""
                row_video = item.data(Qt.ItemDataRole.UserRole + 1) or item.data(257) or ""
                row_audio = item.data(Qt.ItemDataRole.UserRole + 2) or item.data(258) or ""

                # 1. Match by YouTube URL / Video ID
                if target_id:
                    row_id = extract_video_id(row_url)
                    if row_id and row_id == target_id:
                        status_item = self.queue_table.item(i, 1)
                        return {
                            "row": i,
                            "title": row_title,
                            "status": status_item.text() if status_item else "",
                            "played": is_played
                        }
                if url and row_url and url.strip().lower() == row_url.strip().lower():
                    status_item = self.queue_table.item(i, 1)
                    return {
                        "row": i,
                        "title": row_title,
                        "status": status_item.text() if status_item else "",
                        "played": is_played
                    }

                # 2. Match by file paths (audio or video)
                if norm_audio:
                    row_norm_audio = os.path.normpath(os.path.abspath(row_audio)).lower() if row_audio else ""
                    if row_norm_audio and norm_audio == row_norm_audio:
                        status_item = self.queue_table.item(i, 1)
                        return {
                            "row": i,
                            "title": row_title,
                            "status": status_item.text() if status_item else "",
                            "played": is_played
                        }
                    # Check folder match if inside song folder
                    if row_norm_audio:
                        dir_target = os.path.dirname(norm_audio)
                        dir_row = os.path.dirname(row_norm_audio)
                        if dir_target and dir_row and dir_target == dir_row:
                            status_item = self.queue_table.item(i, 1)
                            return {
                                "row": i,
                                "title": row_title,
                                "status": status_item.text() if status_item else "",
                                "played": is_played
                            }

                if norm_video:
                    row_norm_video = os.path.normpath(os.path.abspath(row_video)).lower() if row_video else ""
                    if row_norm_video and norm_video == row_norm_video:
                        status_item = self.queue_table.item(i, 1)
                        return {
                            "row": i,
                            "title": row_title,
                            "status": status_item.text() if status_item else "",
                            "played": is_played
                        }

                # 3. Match by normalized title (including Simplified/Traditional Chinese)
                if target_norm:
                    row_norm = normalize_song_title(row_title)
                    if row_norm and target_norm == row_norm:
                        status_item = self.queue_table.item(i, 1)
                        return {
                            "row": i,
                            "title": row_title,
                            "status": status_item.text() if status_item else "",
                            "played": is_played
                        }
                    target_hans = to_simplified(target_norm)
                    row_hans = to_simplified(row_norm)
                    if target_hans and row_hans and target_hans == row_hans:
                        status_item = self.queue_table.item(i, 1)
                        return {
                            "row": i,
                            "title": row_title,
                            "status": status_item.text() if status_item else "",
                            "played": is_played
                        }

        return None
        
    def sync_player_state_to_web(self, *args):
        if not hasattr(self, 'web_server'): return
        
        state = {
            "playing": self.player_window.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState,
            "paused": self.player_window.player.playbackState() == QMediaPlayer.PlaybackState.PausedState,
            "position": self.player_window.player.position(),
            "duration": self.player_window.player.duration(),
            "volume": self.player_window.volume_slider.value(),
            "vocals_on": not self.player_window.is_karaoke_mode,
            "current_title": self.player_window.current_title,
            "current_row": self.current_playing_row
        }
        self.web_server.update_player_state(state)
        
    def sync_vocal_button(self, is_karaoke):
        self.btn_toggle_vocals.setEnabled(True)
        self.btn_toggle_vocals.setChecked(is_karaoke)
        
    def toggle_vocals_clicked(self):
        self.player_window.set_karaoke_mode(self.btn_toggle_vocals.isChecked())
        
    def open_add_song_dialog(self):
        dialog = AddSongDialog(self.youtube, self)
        dialog.song_selected.connect(self.add_song_to_queue)
        dialog.local_song_selected.connect(self.add_local_song_to_queue)
        dialog.local_song_deleted.connect(self.on_local_song_deleted)
        dialog.exec()
        
    def remove_from_queue_by_path(self, audio_path):
        """Remove all queue rows whose audio path matches the deleted file."""
        if not audio_path:
            return
        rows_to_remove = []
        for i in range(self.queue_table.rowCount()):
            item = self.queue_table.item(i, 0)
            if item:
                row_audio = item.data(Qt.ItemDataRole.UserRole + 2) or item.data(258)
                if row_audio and os.path.normpath(row_audio) == os.path.normpath(audio_path):
                    rows_to_remove.append(i)
        # Remove in reverse so indices stay valid
        for row in reversed(rows_to_remove):
            if row == self.current_playing_row:
                self.player_window.stop_playback()
                self.current_playing_row = -1
            elif self.current_playing_row > row:
                self.current_playing_row -= 1
            self.queue_table.removeRow(row)
        if rows_to_remove:
            self.update_playing_row_highlight()
        
    def add_local_song_to_queue(self, video_path, audio_path, inst_path, title):
        row_position = self.queue_table.rowCount()
        self.queue_table.insertRow(row_position)
        
        task_id = self.next_task_id
        self.next_task_id += 1
        
        title_item = QTableWidgetItem(title)
        title_item.setData(257, video_path)
        title_item.setData(258, audio_path)
        title_item.setData(259, inst_path)
        title_item.setData(Qt.ItemDataRole.UserRole + 4, task_id)
        title_item.setData(Qt.ItemDataRole.UserRole + 5, False)
        self.queue_table.setItem(row_position, 0, title_item)
        
        status_item = QTableWidgetItem(t("status_ready"))
        self.queue_table.setItem(row_position, 1, status_item)
        
        progress_bar = QProgressBar()
        progress_bar.setRange(0, 100)
        progress_bar.setValue(100)
        self.queue_table.setCellWidget(row_position, 2, progress_bar)
        
        self.update_playing_row_highlight()
        
        # If player is stopped, check if this song should auto-play
        if self.player_window.player.playbackState() != QMediaPlayer.PlaybackState.PlayingState:
            should_play = False
            if self.current_playing_row == -1:
                all_prior_played = all(self.is_song_played(r) for r in range(0, row_position))
                if all_prior_played:
                    should_play = True
            elif row_position == self.current_playing_row + 1:
                if not self.is_song_played(row_position):
                    should_play = True
            if should_play:
                self.play_from_queue_by_index(row_position)
        
    def add_song_from_web_youtube(self, url, title, quality=""):
        self.add_song_to_queue(url, title, quality=quality if quality else None)

    def open_settings_dialog(self):
        dlg = SettingsDialog(self)
        dlg.settings_saved.connect(self.retranslate_ui)
        dlg.exec()

    def retranslate_ui(self):
        self.setWindowTitle(t("app_title"))
        if hasattr(self, 'queue_group'):
            self.queue_group.setTitle(t("song_queue"))
        if hasattr(self, 'btn_add_song'):
            self.btn_add_song.setText(t("add_song"))
        if hasattr(self, 'btn_move_next'):
            self.btn_move_next.setText(t("move_to_next"))
        if hasattr(self, 'btn_delete_song'):
            self.btn_delete_song.setText(t("delete_song"))
        if hasattr(self, 'btn_replay_song'):
            self.btn_replay_song.setText(t("replay_song"))
        if hasattr(self, 'btn_clear_played'):
            self.btn_clear_played.setText(t("clear_played"))
        if hasattr(self, 'btn_settings'):
            self.btn_settings.setText(t("btn_settings"))
        if hasattr(self, 'queue_table'):
            self.queue_table.setHorizontalHeaderLabels([t("col_title"), t("col_status"), t("col_progress")])
            status_map = {
                "Ready": t("status_ready"),
                "已就绪": t("status_ready"),
                "Waiting": t("status_waiting"),
                "等待中": t("status_waiting"),
                "Downloading...": t("status_downloading"),
                "下载中...": t("status_downloading"),
                "Removing Vocals...": t("status_removing_vocals"),
                "人声消除中...": t("status_removing_vocals"),
                "▶ Playing": t("status_playing"),
                "▶ 正在播放": t("status_playing"),
                "⏸ Paused": t("status_paused"),
                "⏸ 暂停中": t("status_paused"),
                "Played": t("status_played"),
                "已唱": t("status_played"),
            }
            for row in range(self.queue_table.rowCount()):
                st_item = self.queue_table.item(row, 1)
                if st_item and st_item.text() in status_map:
                    st_item.setText(status_map[st_item.text()])
            self.update_playing_row_highlight()
            self.sync_queue_to_web()
        if hasattr(self, 'vocal_group'):
            self.vocal_group.setTitle(t("vocal_control"))
        if hasattr(self, 'btn_toggle_vocals'):
            self.btn_toggle_vocals.setText(t("toggle_vocals"))
        if hasattr(self, 'player_window'):
            self.player_window.retranslate_ui()

    def clear_played_songs(self):
        for row in range(self.queue_table.rowCount() - 1, -1, -1):
            if row == self.current_playing_row:
                continue
            if self.is_song_played(row):
                self.queue_table.removeRow(row)
                if hasattr(self, 'current_playing_row') and self.current_playing_row > row:
                    self.current_playing_row -= 1
        self.update_playing_row_highlight()
        self.sync_queue_to_web()

    def add_song_to_queue(self, url, title, output_path=None, quality=None):
        row_position = self.queue_table.rowCount()
        self.queue_table.insertRow(row_position)
        
        task_id = self.next_task_id
        self.next_task_id += 1

        # Always create a new dedicated folder inside configured downloads for the song
        if not output_path or output_path == "downloads":
            base_dl = SettingsManager().get_download_dir()
            safe_title = re.sub(r'[\\/:*?"<>|]', '_', title).strip(" .")
            if not safe_title:
                safe_title = f"youtube_song_{task_id}"
            output_path = os.path.join(base_dl, safe_title)
        
        title_item = QTableWidgetItem(title)
        title_item.setData(Qt.ItemDataRole.UserRole, url)
        title_item.setData(Qt.ItemDataRole.UserRole + 4, task_id)
        title_item.setData(Qt.ItemDataRole.UserRole + 5, False)
        self.queue_table.setItem(row_position, 0, title_item)
        
        status_item = QTableWidgetItem(t("status_waiting"))
        self.queue_table.setItem(row_position, 1, status_item)
        
        progress_bar = QProgressBar()
        progress_bar.setRange(0, 100)
        progress_bar.setValue(0)
        self.queue_table.setCellWidget(row_position, 2, progress_bar)
        
        target_quality = quality or SettingsManager().get_video_quality()
        self.queue_manager.add_task(url, task_id, output_path, target_quality)
        self.update_playing_row_highlight()
        
    def find_row_by_task_id(self, task_id):
        for i in range(self.queue_table.rowCount()):
            item = self.queue_table.item(i, 0)
            if item and item.data(Qt.ItemDataRole.UserRole + 4) == task_id:
                return i
        return -1
        
    def update_queue_item_progress(self, task_id, status_text, progress_value):
        row_index = self.find_row_by_task_id(task_id)
        if row_index != -1:
            status_item = self.queue_table.item(row_index, 1)
            if status_item:
                status_item.setText(status_text)
            
            progress_bar = self.queue_table.cellWidget(row_index, 2)
            if progress_bar and isinstance(progress_bar, QProgressBar):
                progress_bar.setValue(progress_value)
        self.sync_queue_to_web()
                
    def on_queue_item_ready(self, task_id, video_path, audio_path, inst_path):
        row_index = self.find_row_by_task_id(task_id)
        if row_index != -1:
            title_item = self.queue_table.item(row_index, 0)
            title_item.setData(Qt.ItemDataRole.UserRole + 1, video_path)
            title_item.setData(Qt.ItemDataRole.UserRole + 2, audio_path)
            title_item.setData(Qt.ItemDataRole.UserRole + 3, inst_path)
            status_item = self.queue_table.item(row_index, 1)
            if status_item and status_item.text() not in (t("status_playing"), t("status_paused"), "▶ Playing", "⏸ Paused", "▶ 正在播放", "⏸ 暂停中"):
                status_item.setText(t("status_ready"))
            
            # If player is stopped, check if this song should auto-play
            if self.player_window.player.playbackState() != QMediaPlayer.PlaybackState.PlayingState:
                should_play = False
                if self.current_playing_row == -1:
                    all_prior_played = all(self.is_song_played(r) for r in range(0, row_index))
                    if all_prior_played and not self.is_song_played(row_index):
                        should_play = True
                elif row_index == self.current_playing_row + 1:
                    if not self.is_song_played(row_index):
                        should_play = True
                
                if should_play:
                    self.play_from_queue_by_index(row_index)
        self.update_playing_row_highlight()
        self.web_server.update_library()
            
    def play_from_queue(self, item):
        row = item.row()
        title_item = self.queue_table.item(row, 0)
        status_item = self.queue_table.item(row, 1)
        
        ready_statuses = ("Ready", "▶ Playing", "⏸ Paused", "已就绪", "▶ 正在播放", "⏸ 暂停中", t("status_ready"), t("status_playing"), t("status_paused"))
        if status_item.text() not in ready_statuses:
            QMessageBox.warning(self, "Not Ready", "This song is still processing.")
            return
            
        if hasattr(self, 'current_playing_row') and self.current_playing_row >= 0 and self.current_playing_row != row:
            self.set_song_played(self.current_playing_row, True)

        self.current_playing_row = row
        
        video_path = title_item.data(Qt.ItemDataRole.UserRole + 1) or title_item.data(257)
        audio_path = title_item.data(Qt.ItemDataRole.UserRole + 2) or title_item.data(258)
        inst_path = title_item.data(Qt.ItemDataRole.UserRole + 3) or title_item.data(259)
        
        self.btn_toggle_vocals.setEnabled(True)
        self.btn_toggle_vocals.setChecked(False)
        self.player_window.current_title = title_item.text()
        self.player_window.play_song(video_path, audio_path, inst_path)
        self.update_playing_row_highlight()
        self.sync_player_state_to_web()
        self.player_window.show()
        self.player_window.raise_()
        self.player_window.activateWindow()

    def delete_local_song_from_web(self, payload):
        try:
            video_path = payload.get("video")
            audio_path = payload.get("audio")
            inst_path = payload.get("inst")
            folder = payload.get("folder")
            
            # Release player file handles if this song is currently loaded/playing
            if audio_path and self.player_window.current_audio_path:
                try:
                    if os.path.normpath(os.path.abspath(audio_path)) == os.path.normpath(os.path.abspath(self.player_window.current_audio_path)):
                        self.player_window.release_media()
                        self.current_playing_row = -1
                        self.player_window.current_audio_path = None
                        self.player_window.current_title = ""
                        self.sync_player_state_to_web()
                except Exception:
                    pass
            
            vocals_path = inst_path.replace("_instrumental.wav", "_vocals.wav") if inst_path else None
            
            errors = []
            for path in [video_path, audio_path, inst_path, vocals_path]:
                if path and os.path.exists(path):
                    try:
                        os.remove(path)
                    except Exception as e:
                        errors.append(f"{path}: {e}")
            
            # Delete the song's subfolder (ensuring it's not root downloads)
            downloads_dir = os.path.abspath("downloads")
            if folder:
                abs_folder = os.path.abspath(folder)
                if abs_folder != downloads_dir and abs_folder.startswith(downloads_dir):
                    try:
                        shutil.rmtree(abs_folder, ignore_errors=True)
                    except Exception as e:
                        errors.append(f"{folder}: {e}")

            # Also remove it from the song queue if it was queued
            if audio_path:
                self.remove_from_queue_by_path(audio_path)
                
            self.web_server.update_library() 
            payload["result"] = {"ok": True, "errors": errors}
        except Exception as e:
            payload["result"] = {"ok": False, "error": str(e)}
        finally:
            event = payload.get("event")
            if event:
                event.set() 

    def on_local_song_deleted(self, audio_path):
        self.remove_from_queue_by_path(audio_path)
        self.web_server.update_library()  # <-- Sync new library state to web

    def closeEvent(self, event):
        DependencyManager.get_instance().cancel_all()
        self.web_server.stop_server()
        self.web_server.wait()
        self.player_window.destroy()
        QApplication.quit()

if __name__ == "__main__":
    # 强制将工作路径切换到当前运行的 exe 目录
    os.chdir(os.path.dirname(os.path.abspath(sys.argv[0])))

    # 声明独立的 Windows 应用 ID，确保任务栏正确显示 mic 图标
    import ctypes
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("mykaraoke.app.unique.id")
    except Exception:
        pass

    app = QApplication(sys.argv)
    app.setWindowIcon(QIcon("mic.svg"))  # 设置全局应用图标
    window = KaraokeMainWindow()
    window.show()
    sys.exit(app.exec())