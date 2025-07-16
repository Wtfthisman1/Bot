#!/usr/bin/env python3
"""
download.py  –  скачивает видео/аудио с помощью yt-dlp.

Запуск (из Java):
    python3 download.py <url> <outDir> <fileName>

где
    url       – ссылка на ролик (YouTube, Vimeo, TikTok и т.д.)
    outDir    – заранее созданная Java-стороной папка
    fileName  – конечное имя файла, заданное Java
                (пример: video_user_chat123_20250617_142530.mp4)
"""

import sys
import yt_dlp
from pathlib import Path

# ────────── 1. аргументы ──────────
if len(sys.argv) != 4:
    sys.stderr.write("Usage: download.py <url> <outDir> <fileName>\n")
    sys.exit(1)

url, out_dir_arg, file_name = sys.argv[1:4]
out_dir = Path(out_dir_arg).resolve()
out_dir.mkdir(parents=True, exist_ok=True)

full_path = out_dir / file_name

# ────────── 2. опции yt-dlp ──────────
ydl_opts = {
    "format": "bestvideo+bestaudio/best",
    "merge_output_format": "mp4",       # если подходят только mp4
    "outtmpl": str(full_path),
    "noplaylist": True,
    "quiet": False
}


# ────────── 3. скачивание ──────────
try:
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([url])
except Exception as e:
    sys.stderr.write(f"[download.py] yt-dlp failed: {e}\n")
    sys.exit(2)

# ────────── 4. финальная проверка ──────────
if not full_path.exists() or full_path.stat().st_size == 0:
    sys.stderr.write("[download.py] file not created or empty\n")
    sys.exit(3)
# успех → exitCode 0. Путь файла Java уже знает.
sys.exit(0)
