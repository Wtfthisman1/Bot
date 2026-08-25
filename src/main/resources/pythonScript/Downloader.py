#!/usr/bin/env python3
"""
Downloader.py — скачивает видео или только аудиодорожку через yt-dlp.

Запуск (из Java):
    python3 Downloader.py <url> <outDir> <fileName> <mode>

где
    url       – ссылка на ролик (YouTube, Vimeo, TikTok и т.д.)
    outDir    – заранее созданная Java-стороной папка
    fileName  – конечное имя файла, заданное Java (с расширением)
    mode      – video | audio

Режим audio нужен для транскрипции: Whisper использует только звук, а полное
видео того же ролика весит на порядок больше и дольше качается.

Прогресс печатается не чаще раза в PROGRESS_EVERY_SEC секунд: у HLS-роликов
бывает под тысячу фрагментов, и построчный прогресс забивал лог почти
целиком (7287 строк из 7453 в одном прогоне).
"""

import sys
import time
import yt_dlp
from pathlib import Path

PROGRESS_EVERY_SEC = 5.0

# ────────── 1. аргументы ──────────
if len(sys.argv) != 5:
    sys.stderr.write("Usage: Downloader.py <url> <outDir> <fileName> <video|audio>\n")
    sys.exit(1)

url, out_dir_arg, file_name, mode = sys.argv[1:5]

if mode not in ("video", "audio"):
    sys.stderr.write(f"[Downloader.py] unknown mode: {mode}\n")
    sys.exit(1)

out_dir = Path(out_dir_arg).resolve()
out_dir.mkdir(parents=True, exist_ok=True)
full_path = out_dir / file_name


# ────────── 2. троттлинг прогресса ──────────
_last_report = 0.0


def progress_hook(d):
    global _last_report
    status = d.get("status")

    if status == "downloading":
        now = time.monotonic()
        if now - _last_report < PROGRESS_EVERY_SEC:
            return
        _last_report = now

        total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
        done = d.get("downloaded_bytes") or 0
        percent = (done * 100.0 / total) if total else 0.0
        speed = (d.get("speed") or 0) / (1024 * 1024)
        eta = d.get("eta") or 0
        print(
            f"[progress] {percent:5.1f}%  {done / 1048576:.0f}/{total / 1048576:.0f} MiB  "
            f"{speed:.2f} MiB/s  ETA {eta}s",
            flush=True,
        )
    elif status == "finished":
        # Отдельная строка: после неё идёт склейка/перекодирование, во время
        # которых прогресса нет вообще, и лог иначе выглядит зависшим
        print(f"[progress] загрузка завершена, обрабатываю: {d.get('filename')}", flush=True)


# ────────── 3. опции yt-dlp ──────────
ydl_opts = {
    "outtmpl": str(full_path),
    "noplaylist": True,
    "quiet": False,
    "noprogress": True,          # свой троттлящий хук вместо построчного прогресса
    "progress_hooks": [progress_hook],
    # Обрывы к googlevideo.com в прошлом прогоне давали «Skipping fragment N» —
    # пропущенный фрагмент это дыра в звуке и, как следствие, в расшифровке
    "retries": 10,
    "fragment_retries": 10,
    "socket_timeout": 30,
}

if mode == "audio":
    # Шаблон БЕЗ расширения: FFmpegExtractAudio дописывает своё, и полный путь
    # с ".m4a" на входе давал на выходе "имя.m4a.m4a" — Java такой файл не находила
    ydl_opts.update({
        "outtmpl": str(full_path.with_suffix("")),
        "format": "bestaudio/best",
        "postprocessors": [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "m4a",
        }],
    })
else:
    ydl_opts.update({
        "format": "bestvideo+bestaudio/best",
        "merge_output_format": "mp4",
    })


# ────────── 4. скачивание ──────────
print(f"[Downloader.py] режим={mode}, назначение={full_path}", flush=True)
try:
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([url])
except Exception as e:
    sys.stderr.write(f"[Downloader.py] yt-dlp failed: {e}\n")
    sys.exit(2)


# ────────── 5. приводим имя к ожидаемому Java ──────────
def locate_result():
    """
    Java ждёт файл ровно по full_path. Постпроцессор извлечения аудио и склейка
    контейнеров могут поменять расширение, поэтому подбираем файл по основе
    имени и переименовываем — иначе рабочая загрузка падала бы с «file not created».
    """
    if full_path.exists():
        return full_path

    # Совпадение по началу имени, а не по stem: постпроцессоры и склейка дают
    # и "имя.m4a.m4a", и "имя.f616.mp4" — точное сравнение их пропускало
    stem = full_path.stem
    candidates = [
        p for p in out_dir.iterdir()
        if p.is_file() and p.name.startswith(stem) and not p.name.endswith((".part", ".ytdl"))
    ]
    if not candidates:
        return None

    best = max(candidates, key=lambda p: p.stat().st_size)
    best.rename(full_path)
    print(f"[Downloader.py] переименован {best.name} -> {full_path.name}", flush=True)
    return full_path


result = locate_result()
if result is None or not result.exists() or result.stat().st_size == 0:
    sys.stderr.write("[Downloader.py] file not created or empty\n")
    sys.exit(3)

print(f"[Downloader.py] готово: {result} ({result.stat().st_size / 1048576:.1f} MiB)", flush=True)
sys.exit(0)
