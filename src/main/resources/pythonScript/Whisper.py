#!/usr/bin/env python3
"""
transcribe.py — простая CLI-обёртка для openai-whisper (модель “medium”).

Запуск:
    python3 transcribe.py <inputVideoOrAudio> <outputPrefix>

Пример:
    python3 transcribe.py demo.mp4 transcripts/demo
→  transcripts/demo.txt  +  logs/20250624_142500_demo.log

Переменные окружения:
    WHISPER_THREADS — сколько CPU-ядер отдавать Torch (по умолчанию 4).

Зависимости (только CPU-версии):
    pip install -U torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu
    pip install -U openai-whisper ffmpeg-python
"""

from __future__ import annotations

import os
import sys
import subprocess
from datetime import datetime
from pathlib import Path
from contextlib import redirect_stdout, redirect_stderr
from typing import List
from tqdm import tqdm
import subprocess, json
import torch
from faster_whisper import WhisperModel
import time
import resource


# ─────────────────── настройки CPU / потоки ───────────────────
NUM_THREADS = int(os.getenv("WHISPER_THREADS", "3"))
torch.set_num_threads(NUM_THREADS)

# ───────────────────────── аргументы ──────────────────────────
if len(sys.argv) != 3:
    sys.stderr.write("Usage: transcribe.py <videoPath> <transcriptPath>\n")
    sys.exit(1)

in_path    = Path(sys.argv[1]).expanduser().resolve()
out_prefix = Path(sys.argv[2]).expanduser().resolve()
wav_path   = out_prefix.with_suffix(".wav")

if not in_path.exists():
    sys.stderr.write(f"[transcribe.py] input file not found: {in_path}\n")
    sys.exit(2)

out_prefix.parent.mkdir(parents=True, exist_ok=True)

# ─────────────────────────── logging ───────────────────────────
log_dir  = out_prefix.parent / "logs"
log_dir.mkdir(parents=True, exist_ok=True)
log_file = log_dir / f"{datetime.now():%Y%m%d_%H%M%S}_{in_path.stem}.log"

class Tee:
    def __init__(self, *streams): self.streams: List = list(streams)
    def write(self, data):        [s.write(data) for s in self.streams]
    def flush(self):              [s.flush()     for s in self.streams]

# ─────────────── ffmpeg → WAV 16 kHz mono ───────────────
ffmpeg_cmd = [
    "ffmpeg", "-y", "-i", str(in_path),
    "-vn", "-ar", "16000", "-ac", "1",
    "-c:a", "pcm_s16le", str(wav_path),
]

sys.stderr.write("[ffmpeg] extracting audio …\n")
with open(log_file, "w", encoding="utf-8") as lf, subprocess.Popen(
        ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1,
) as proc:
    for line in proc.stdout:
        sys.stderr.write(f"[ffmpeg] {line}")
        lf.write(line)
    if proc.wait() != 0:
        sys.stderr.write(f"[ffmpeg] exited with {proc.returncode}\n")
        sys.exit(3)

# ─── получаем длительность wav (секунды) ───
dur = float(
    subprocess.check_output(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(wav_path),
        ],
        text=True,
    ).strip()
)

pbar = tqdm(total=dur, unit="s",
            bar_format="{l_bar}{bar}| {n_fmt}/{total_fmt}s")



# ─────────────── Whisper medium (CPU-only) ───────────────
DEVICE = "cpu"
t0 = time.perf_counter()
sys.stderr.write(f"[whisper] loading model (threads: {NUM_THREADS}) …\n")
model = WhisperModel(
   "medium",
    device=DEVICE,
    cpu_threads=NUM_THREADS,
    compute_type="int8",
)

segments_all = []          # будем накапливать сегменты для финального .txt

with open(log_file, "a", encoding="utf-8") as lf, \
        redirect_stdout(Tee(sys.stdout, lf)), \
        redirect_stderr(Tee(sys.stderr, lf)):

    sys.stderr.write("[whisper] transcribing …\n")

    # -------- transcription -------------
    segments, _ = model.transcribe(
        str(wav_path),
        beam_size=6,
        vad_filter=True,
    )

    for seg in segments:
        segments_all.append(seg)
        ts = seg.start
        print(f"[{int(ts//60):02d}:{ts%60:04.1f}] {seg.text.strip()}")
        pbar.update(seg.end - seg.start)

pbar.close()
sys.stderr.write("\n")




# ─────────────── время и RTF ───────────────
elapsed = time.perf_counter() - t0
rtf     = elapsed / dur if dur else 0
sys.stderr.write(f"[stats] wall-time: {elapsed/60:4.1f} мин "
                 f"(RTF ≈ {rtf:.2f}×)\n")

# ─────────── RAM peak ───────────
peak_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
peak_mb = peak_kb / 1024 if sys.platform != "darwin" else peak_kb / (1024 * 1024)
sys.stderr.write(f"[stats] RAM-peak: {peak_mb:.1f} MiB\n")
sys.stderr.flush()



# ─────────────── запись итогового .txt ───────────────
final_txt = out_prefix.with_suffix(".txt")
with open(final_txt, "w", encoding="utf-8") as f:
    for seg in segments_all:
        ts = seg.start
        timestamp = f"[{int(ts//60):02d}:{ts%60:04.1f}]"
        f.write(f"{timestamp} {seg.text.strip()}\n")

if final_txt.stat().st_size == 0:
    sys.stderr.write("[transcribe.py] empty transcription\n")
    sys.exit(5)

wav_path.unlink(missing_ok=True)
sys.stderr.write("[transcribe.py] done ✓\n")