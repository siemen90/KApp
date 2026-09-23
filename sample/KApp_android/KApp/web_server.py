import os
import json
import threading
import time
from flask import Flask, render_template, request, jsonify, Response
from PyQt6.QtCore import QThread, pyqtSignal


class WebServerThread(QThread):
    """Runs a Flask web server in a background thread."""
    # Signals to request actions on the main Qt thread
    play_song_requested = pyqtSignal(int)          # row index
    toggle_play_requested = pyqtSignal()
    stop_requested = pyqtSignal()
    next_song_requested = pyqtSignal()
    move_to_next_requested = pyqtSignal(int)       # row index
    delete_requested = pyqtSignal(int)             # row index
    replay_requested = pyqtSignal(int)             # row index
    toggle_vocals_requested = pyqtSignal()
    seek_requested = pyqtSignal(int)               # position in ms
    volume_requested = pyqtSignal(int)             # 0-100
    add_local_requested = pyqtSignal(str, str, str, str)  # video, audio, inst, title
    add_youtube_requested = pyqtSignal(str, str)   # url, title
    delete_local_song_requested = pyqtSignal(dict)

    def __init__(self, host="0.0.0.0", port=8080, parent=None):
        super().__init__(parent)
        self.host = host
        self.port = port
        self.daemon = True
        self._server = None
        self._app = None
        self._sse_clients = []
        self._sse_lock = threading.Lock()

        # Shared state (updated from the main thread)
        self.queue_data = []       # list of dicts: {title, status, progress, video, audio, inst}
        self.player_state = {
            "playing": False,
            "position": 0,
            "duration": 0,
            "volume": 50,
            "vocals_on": True,
            "current_title": ""
        }

        self._build_app()

    def _build_app(self):
        self._app = Flask(__name__,
                          template_folder=os.path.join(os.path.dirname(__file__), "templates"))

        app = self._app
        server_thread = self  # reference for closures

        @app.route("/")
        def index():
            return render_template("index.html")

        @app.route("/api/queue")
        def get_queue():
            return jsonify(server_thread.queue_data)

        @app.route("/api/queue/add_local", methods=["POST"])
        def add_local():
            data = request.json
            server_thread.add_local_requested.emit(
                data.get("video", ""),
                data.get("audio", ""),
                data.get("inst", ""),
                data.get("title", "")
            )
            return jsonify({"ok": True})

        @app.route("/api/queue/add_youtube", methods=["POST"])
        def add_youtube():
            data = request.json
            server_thread.add_youtube_requested.emit(
                data.get("url", ""),
                data.get("title", "")
            )
            return jsonify({"ok": True})

        @app.route("/api/youtube/search", methods=["POST"])
        def youtube_search():
            data = request.json
            query = data.get("query", "")
            if not query:
                return jsonify([])

            results = []

            # --- Local library matches first ---
            query_lower = query.lower()
            for song in server_thread.get_library_data():
                if query_lower in song.get("title", "").lower():
                    results.append({
                        "title": song["title"],
                        "url": "",
                        "duration": 0,
                        "source": "local",
                        "video": song.get("video", ""),
                        "audio": song.get("audio", ""),
                        "inst": song.get("inst", ""),
                    })

            # --- YouTube results ---
            try:
                from youtube_fetcher import YouTubeKaraokeSource
                yt = YouTubeKaraokeSource()
                yt_results = yt.search_karaoke_videos(query, max_results=50)
                for v in yt_results:
                    title = v.get("title", "Unknown")
                    url = v.get("url", "")
                    if url and not url.startswith("http"):
                        url = f"https://www.youtube.com/watch?v={url}"
                    duration = v.get("duration", 0)
                    results.append({"title": title, "url": url, "duration": duration or 0, "source": "youtube"})
            except Exception as e:
                print(f"[WebServer] YouTube search error: {e}")

            return jsonify(results)


        @app.route("/api/youtube/info", methods=["POST"])
        def youtube_info():
            data = request.json
            url = data.get("url", "")
            if not url:
                return jsonify({"error": "No URL provided"}), 400
            try:
                import yt_dlp
                ydl_opts = {
                    'quiet': True,
                    'no_download': True,
                    'extract_flat': False,
                    'extractor_args': {
                        'youtube': {
                            'player_client': ['android', 'web']
                        }
                    },
                }
                with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(url, download=False)
                    return jsonify({
                        "title": info.get("title", "Unknown"),
                        "url": url
                    })
            except Exception as e:
                print(f"[WebServer] YouTube info error: {e}")
                return jsonify({"error": str(e)}), 400
            
        @app.route("/api/queue/move_next/<int:row>", methods=["POST"])
        def move_next(row):
            server_thread.move_to_next_requested.emit(row)
            return jsonify({"ok": True})

        @app.route("/api/queue/delete/<int:row>", methods=["POST"])
        def delete_song(row):
            server_thread.delete_requested.emit(row)
            return jsonify({"ok": True})

        @app.route("/api/queue/replay/<int:row>", methods=["POST"])
        def replay_song(row):
            server_thread.replay_requested.emit(row)
            return jsonify({"ok": True})

        @app.route("/api/player/play/<int:row>", methods=["POST"])
        def play_song(row):
            server_thread.play_song_requested.emit(row)
            return jsonify({"ok": True})

        @app.route("/api/player/toggle_play", methods=["POST"])
        def toggle_play():
            server_thread.toggle_play_requested.emit()
            return jsonify({"ok": True})

        @app.route("/api/player/stop", methods=["POST"])
        def stop():
            server_thread.stop_requested.emit()
            return jsonify({"ok": True})
            
        @app.route("/api/player/next", methods=["POST"])
        def next_song():
            server_thread.next_song_requested.emit()
            return jsonify({"ok": True})

        @app.route("/api/player/toggle_vocals", methods=["POST"])
        def toggle_vocals():
            server_thread.toggle_vocals_requested.emit()
            return jsonify({"ok": True})

        @app.route("/api/player/seek", methods=["POST"])
        def seek():
            data = request.json
            server_thread.seek_requested.emit(int(data.get("position", 0)))
            return jsonify({"ok": True})

        @app.route("/api/player/volume", methods=["POST"])
        def volume():
            data = request.json
            server_thread.volume_requested.emit(int(data.get("volume", 50)))
            return jsonify({"ok": True})

        @app.route("/api/player/status")
        def player_status():
            return jsonify(server_thread.player_state)

        @app.route("/api/library")
        def get_library():
            return jsonify(server_thread.get_library_data())

        @app.route("/api/library/delete", methods=["POST"])
        def delete_local():
            data = request.json or {}
            done_event = threading.Event()
            payload = {
                "video": data.get("video", ""),
                "audio": data.get("audio", ""),
                "inst": data.get("inst", ""),
                "folder": data.get("folder", ""),
                "event": done_event,
                "result": {}
            }
            server_thread.delete_local_song_requested.emit(payload)
            done_event.wait(timeout=5.0)
            return jsonify(payload.get("result", {"ok": True}))

        @app.route("/api/events")
        def sse_stream():
            def generate():
                q = []
                with server_thread._sse_lock:
                    server_thread._sse_clients.append(q)
                try:
                    # Send initial state
                    yield f"data: {json.dumps({'type': 'queue', 'data': server_thread.queue_data})}\n\n"
                    yield f"data: {json.dumps({'type': 'player', 'data': server_thread.player_state})}\n\n"
                    yield f"data: {json.dumps({'type': 'library', 'data': server_thread.get_library_data()})}\n\n"
                    while True:
                        # Check for new events (poll with timeout)
                        start = time.time()
                        while not q and (time.time() - start) < 1.0:
                            time.sleep(0.05)
                        if q:
                            while q:
                                event = q.pop(0)
                                yield f"data: {json.dumps(event)}\n\n"
                        else:
                            # Heartbeat
                            yield ": heartbeat\n\n"
                except GeneratorExit:
                    pass
                finally:
                    with server_thread._sse_lock:
                        if q in server_thread._sse_clients:
                            server_thread._sse_clients.remove(q)

            return Response(generate(), mimetype="text/event-stream",
                            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    def push_event(self, event_type, data):
        """Push an event to all SSE clients."""
        event = {"type": event_type, "data": data}
        with self._sse_lock:
            for client_queue in self._sse_clients:
                client_queue.append(event)

    def get_library_data(self):
        """Scans the downloads folder and returns the list of local songs."""
        downloads_dir = "downloads"
        songs = []
        if os.path.exists(downloads_dir):
            for root, dirs, files in os.walk(downloads_dir):
                for filename in files:
                    if filename.endswith("_instrumental.wav"):
                        base = filename[:-17]
                        audio_path = os.path.join(root, f"{base}.wav")
                        inst_path = os.path.join(root, filename)
                        video_path = ""
                        for ext in [".mp4", ".mkv", ".webm", ".m4a"]:
                            possible = os.path.join(root, f"{base}{ext}")
                            if os.path.exists(possible):
                                video_path = possible
                                break
                        if os.path.exists(audio_path):
                            if root != downloads_dir:
                                display_title = os.path.basename(root)
                            else:
                                display_title = base
                            songs.append({
                                "title": display_title,
                                "video": video_path,
                                "audio": audio_path,
                                "inst": inst_path,
                                "folder": root
                            })
        return songs

    def update_library(self):
        """Pushes the updated local library data to all connected web pages."""
        songs = self.get_library_data()
        self.push_event("library", songs)

    def update_queue(self, queue_data):
        """Update the shared queue state and notify SSE clients."""
        self.queue_data = queue_data
        self.push_event("queue", queue_data)

    def update_player_state(self, state):
        """Update the shared player state and notify SSE clients."""
        self.player_state.update(state)
        self.push_event("player", self.player_state)

    def run(self):
        from werkzeug.serving import make_server
        self._server = make_server(self.host, self.port, self._app, threaded=True)
        print(f"[WebServer] Running on http://{self.host}:{self.port}")
        self._server.serve_forever()

    def stop_server(self):
        """Gracefully stop the Flask server."""
        if self._server:
            print("[WebServer] Shutting down...")
            self._server.shutdown()
