import warnings
import re

# Suppress harmless pkg_resources deprecation warning inside zhconv
warnings.filterwarnings("ignore", category=UserWarning, module="zhconv")

try:
    import zhconv
    HAS_ZHCONV = True
except ImportError:
    HAS_ZHCONV = False


def to_simplified(text: str) -> str:
    if not text:
        return ""
    if not HAS_ZHCONV:
        return text
    try:
        return zhconv.convert(text, "zh-hans")
    except Exception:
        return text


def to_traditional(text: str) -> str:
    if not text:
        return ""
    if not HAS_ZHCONV:
        return text
    try:
        return zhconv.convert(text, "zh-hant")
    except Exception:
        return text


def match_chinese(query: str, target: str) -> bool:
    """
    Returns True if query matches target.
    Supports:
    - Case-insensitive substring matching
    - Simplified Chinese query matching Traditional Chinese target
    - Traditional Chinese query matching Simplified Chinese target
    - Multi-word matching (all space-separated tokens in query must match target)
    """
    if not query:
        return True
    if not target:
        return False

    tokens = query.strip().split()
    if not tokens:
        return True

    t_lower = target.lower()
    t_hans = to_simplified(t_lower) if HAS_ZHCONV else t_lower
    t_hant = to_traditional(t_lower) if HAS_ZHCONV else t_lower

    for tok in tokens:
        q_lower = tok.lower()
        if q_lower in t_lower:
            continue
        if HAS_ZHCONV:
            q_hans = to_simplified(q_lower)
            if q_hans in t_hans:
                continue
            q_hant = to_traditional(q_lower)
            if q_hant in t_hant:
                continue
        return False

    return True


def normalize_song_title(title: str) -> str:
    if not title:
        return ""
    t = str(title).strip().lower()
    # Strip file extensions
    for ext in [".mp4", ".mkv", ".mp3", ".webm", ".avi", ".flv", ".wav", ".m4a"]:
        if t.endswith(ext):
            t = t[:-len(ext)].strip()
            break
    # Strip resolution tags like [1080p], [4K], (720p), (HD), (HQ), (4K 60FPS)
    t = re.sub(r'[\(\[\{]\s*(?:4k(?:\s*\d+fps)?|1080p|720p|480p|hd|hq|uhd)\s*[\)\]\}]', '', t, flags=re.IGNORECASE)
    # Strip duration tags like [04:23] or (3:45)
    t = re.sub(r'[\(\[\{]\s*\d{1,2}:\d{2}\s*[\)\]\}]', '', t)
    # Strip common MV / KTV tags
    t = re.sub(r'[\(\[\{]\s*(?:official\s*(?:music\s*video|mv|video|audio)?|mv|ktv(?:版)?|动态歌词(?:版)?|高清|完整版|高音质|无损|lyrics?|audio)\s*[\)\]\}]', '', t, flags=re.IGNORECASE)
    # Replace separators and brackets with spaces
    t = re.sub(r'[_\-\—\·\|\\/【】（）()\[\]]+', ' ', t)
    return re.sub(r'\s+', ' ', t).strip()


def extract_video_id(url: str) -> str:
    if not url:
        return ""
    u = str(url).strip()
    match = re.search(r'(?:v=|\/v\/|youtu\.be\/|\/embed\/|\/shorts\/)([^#&?]+)', u)
    if match and len(match.group(1)) == 11 and re.match(r'^[a-zA-Z0-9_-]{11}$', match.group(1)):
        return match.group(1)
    if len(u) == 11 and re.fullmatch(r'[a-zA-Z0-9_-]{11}', u) and not any(c in u for c in "/?#&="):
        return u
    return ""

