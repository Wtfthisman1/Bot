import os
from pathlib import Path

def resolve_log_dir(app_name: str = "whisper") -> Path:
    env = os.getenv("WHISPER_LOG_DIR")
    if env:
        path = Path(env).expanduser()
    else:
        # 2) XDG
        xdg = os.getenv("XDG_STATE_HOME")
        if xdg:
            path = Path(xdg) / app_name
        else:
            # 3) ~/.local/state/whisper
            path = Path.home() / ".local" / "state" / app_name

    path.mkdir(parents=True, exist_ok=True)
    return path
