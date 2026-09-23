import yt_dlp
import json
import os
import re

from settings_manager import SettingsManager

class YouTubeKaraokeSource:
    def __init__(self):
        self.settings = SettingsManager()
        app_dir = os.path.dirname(os.path.abspath(__file__))
        deno_path = os.path.join(app_dir, 'deno.exe')
        
        # Ensure application folder is in PATH so yt-dlp and child processes can locate deno and ffmpeg
        if app_dir not in os.environ.get('PATH', ''):
            os.environ['PATH'] = app_dir + os.pathsep + os.environ.get('PATH', '')
        
        self.ydl_opts = {
            'format': 'bestvideo+bestaudio/best',
            'merge_output_format': 'mp4',
            'postprocessors': [{
                'key': 'FFmpegExtractAudio',
                'preferredcodec': 'wav',
                'preferredquality': '192',
            }],
            'keepvideo': True,
            'ffmpeg_location': app_dir,
        }
    
    def get_channel_videos(self, channel_input, max_results=None):
        """Fetch videos from a specific channel"""
    
        channel_url = self._build_channel_url(channel_input)
        print(f"Fetching from: {channel_url}")
    
        ydl_opts = {
            'extract_flat': True,
            'quiet': True,
        }
    
        # Only add playlistend if max_results is specified
        if max_results:
            ydl_opts['playlistend'] = max_results
    
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                result = ydl.extract_info(channel_url, download=False)
                entries = result.get('entries', [])
                for e in entries:
                    e['thumbnail'] = self._extract_thumbnail(e)
                print(f"Found {len(entries)} videos")
                return entries
        except Exception as e:
            print(f"Error fetching channel: {e}")
            return []
    
    def _build_channel_url(self, channel_input):
        """
        Build proper channel URL from various input formats:
        - @username
        - https://www.youtube.com/@username
        - https://www.youtube.com/channel/UC...
        - UC... (channel ID)
        """
        channel_input = channel_input.strip()
        
        # If it's already a full URL
        if channel_input.startswith('http'):
            url = channel_input.rstrip('/')
            tabs = ('/videos', '/shorts', '/streams', '/playlists', '/community')
            if any(url.endswith(tab) for tab in tabs):
                return url
            return url + '/videos'
        
        # If it starts with @, it's a handle
        if channel_input.startswith('@'):
            return f"https://www.youtube.com/{channel_input}/videos"
        
        # If it starts with UC, it's a channel ID
        if channel_input.startswith('UC'):
            return f"https://www.youtube.com/channel/{channel_input}/videos"
        
        # Otherwise, treat as handle (add @ if not present)
        if not channel_input.startswith('@'):
            channel_input = '@' + channel_input
            
        return f"https://www.youtube.com/{channel_input}/videos"
    
    def _extract_thumbnail(self, entry):
        """Extract a usable thumbnail URL from an entry dict"""
        thumb = entry.get('thumbnail')
        if not thumb:
            thumbs = entry.get('thumbnails') or []
            if thumbs and isinstance(thumbs, list):
                thumb = thumbs[-1].get('url', '')
        if not thumb and entry.get('id'):
            thumb = f"https://i.ytimg.com/vi/{entry['id']}/mqdefault.jpg"
        return thumb or ''

    def search_karaoke_videos(self, query, max_results=50):
        """Search for videos on YouTube using exact user query"""
        search_query = query.strip()
        
        ydl_opts = {
            'extract_flat': True,
            'playlistend': max_results,
            'quiet': True,
        }
        
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                result = ydl.extract_info(f"ytsearch{max_results}:{search_query}", download=False)
                entries = result.get('entries', [])
                for e in entries:
                    e['thumbnail'] = self._extract_thumbnail(e)
                return entries
        except Exception as e:
            print(f"Error searching: {e}")
            return []
    
    def download_video(self, video_url, output_path=None, quality=None, progress_callback=None):
        """Download video with audio into its own subfolder, respecting resolution and saving metadata"""
        target_dir = output_path or self.settings.get_download_dir()
        os.makedirs(target_dir, exist_ok=True)
        
        # If output_path is already a specific song folder, save files directly inside it (avoid 2-layer folders)
        base_dl = os.path.normpath(os.path.abspath(self.settings.get_download_dir()))
        curr_target = os.path.normpath(os.path.abspath(target_dir))
        if output_path and curr_target != base_dl:
            outtmpl = os.path.join(target_dir, '%(title)s.%(ext)s').replace('\\', '/')
        else:
            outtmpl = os.path.join(target_dir, '%(title)s', '%(title)s.%(ext)s').replace('\\', '/')
        
        target_quality = quality or self.settings.get_video_quality()
        max_height = SettingsManager.get_max_height(target_quality)
        
        # YouTube format selector: pick up to requested height with fallback to best
        format_selector = (
            f'bestvideo[height<={max_height}]+bestaudio/'
            f'best[height<={max_height}]/'
            f'bestvideo+bestaudio/'
            f'best'
        )
        
        opts = {
            **self.ydl_opts,
            'format': format_selector,
            'outtmpl': outtmpl,
            'quiet': True,
        }
        
        if progress_callback:
            def hook(d):
                if d['status'] == 'downloading':
                    total = d.get('total_bytes') or d.get('total_bytes_estimate')
                    if total:
                        percent = d.get('downloaded_bytes', 0) / total
                        progress_callback(percent)
            opts['progress_hooks'] = [hook]
        
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(video_url, download=True)
            # Get the expected output filename
            filename = ydl.prepare_filename(info)
            # Return path with .wav extension (due to postprocessor)
            base = os.path.splitext(filename)[0]
            
            # Find the video extension (usually mp4)
            video_ext = info.get('ext', 'mp4')
            video_file = f"{base}.{video_ext}"
            wav_file = f"{base}.wav"
            
            # Save metadata.json in song folder
            try:
                song_folder = os.path.dirname(video_file)
                meta_file = os.path.join(song_folder, "metadata.json")
                meta_data = {
                    "title": info.get("title", os.path.basename(base)),
                    "url": video_url,
                    "duration": int(info.get("duration") or 0),
                    "quality": target_quality,
                    "height": info.get("height"),
                    "resolution": f"{info.get('width', '')}x{info.get('height', '')}"
                }
                with open(meta_file, "w", encoding="utf-8") as f:
                    json.dump(meta_data, f, indent=4, ensure_ascii=False)
            except Exception as e:
                print(f"Error writing metadata.json: {e}")
            
            return video_file, wav_file