"""
Application Logging Module for KApp.
Creates timestamped event log files in the logs/ folder using format:
YYYYMMDDHHMM.log (with HHMM in 24-hour time).
Captures all logging, print statements (stdout/stderr), and uncaught exceptions.
"""
import os
import sys
import datetime
import logging
import traceback

LOG_DIR_NAME = "logs"
_active_log_file = None


class _TeeStream:
    """Stream that writes simultaneously to console and the active log file."""
    def __init__(self, original_stream, log_file_path, prefix=""):
        self.original_stream = original_stream
        self.log_file_path = log_file_path
        self.prefix = prefix

    def write(self, data):
        try:
            if self.original_stream:
                self.original_stream.write(data)
                self.original_stream.flush()
        except Exception:
            pass

        if data and data.strip():
            try:
                now_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                with open(self.log_file_path, "a", encoding="utf-8") as f:
                    for line in data.splitlines():
                        if line:
                            tag = f" [{self.prefix}]" if self.prefix else ""
                            f.write(f"[{now_str}]{tag} {line}\n")
            except Exception:
                pass

    def flush(self):
        try:
            if self.original_stream:
                self.original_stream.flush()
        except Exception:
            pass


def init_logging(base_dir: str = None) -> str:
    """
    Initializes application logging.
    Creates a new log file named YYYYMMDDHHMM.log (24-hour time) inside the logs/ folder.
    Returns the absolute path to the active log file.
    """
    global _active_log_file
    if _active_log_file:
        return _active_log_file

    app_dir = base_dir or os.path.dirname(os.path.abspath(__file__))
    logs_dir = os.path.join(app_dir, LOG_DIR_NAME)
    os.makedirs(logs_dir, exist_ok=True)

    # Format: YYYYMMDDHHMM.log (24-hour format: %Y%m%d%H%M)
    now = datetime.datetime.now()
    filename = now.strftime("%Y%m%d%H%M") + ".log"
    log_path = os.path.join(logs_dir, filename)
    _active_log_file = log_path

    # Root logger configuration
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.INFO)

    # File handler
    file_handler = logging.FileHandler(log_path, encoding="utf-8", delay=False)
    formatter = logging.Formatter("[%(asctime)s] [%(levelname)s] [%(name)s]: %(message)s", datefmt="%Y-%m-%d %H:%M:%S")
    file_handler.setFormatter(formatter)
    root_logger.addHandler(file_handler)

    # Console handler
    console_handler = logging.StreamHandler(sys.__stdout__)
    console_handler.setFormatter(formatter)
    root_logger.addHandler(console_handler)

    # Tee sys.stdout and sys.stderr to write to both console and the log file
    sys.stdout = _TeeStream(sys.__stdout__, log_path, prefix="STDOUT")
    sys.stderr = _TeeStream(sys.__stderr__, log_path, prefix="STDERR")

    # Global uncaught exception handler
    def handle_exception(exc_type, exc_value, exc_traceback):
        if issubclass(exc_type, KeyboardInterrupt):
            sys.__excepthook__(exc_type, exc_value, exc_traceback)
            return
        err_msg = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
        logging.critical(f"Uncaught Exception:\n{err_msg}")

    sys.excepthook = handle_exception

    logging.info(f"=== KApp Application Started ===")
    logging.info(f"Log File: {log_path}")

    return log_path


def get_log_dir(base_dir: str = None) -> str:
    """Returns absolute path to the logs directory, creating it if needed."""
    app_dir = base_dir or os.path.dirname(os.path.abspath(__file__))
    logs_dir = os.path.join(app_dir, LOG_DIR_NAME)
    os.makedirs(logs_dir, exist_ok=True)
    return logs_dir


def get_active_log_file() -> str:
    """Returns path to the currently active log file."""
    return _active_log_file or ""
