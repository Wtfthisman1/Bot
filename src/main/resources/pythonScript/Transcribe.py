#!/usr/bin/env python3
"""
Transcribe.py — транскрипция через whisper-ctranslate2 (faster-whisper) с VAD.

Движок и параметры повторяют WhisperRunner.java, но интерактивный ввод заменён
на переменные окружения (бот неинтерактивен). Контракт запуска прежний:

    python3 Transcribe.py <inputVideoOrAudio> <output.txt>

Рядом с <output.txt> кладутся субтитры и разметка: .srt, .vtt, .json. Они
достаются даром — whisper пишет все форматы за один проход, повторно считать
ничего не нужно. Определённый язык печатается в stderr строкой
"[transcribe] detected-language=<код>" — по ней вызывающая сторона выбирает,
какой языковой моделью потом обрабатывать текст.

Параметры (через окружение; их прокидывает TranscribeExecutor из конфигурации):
    WHISPER_MODEL          модель распознавания            (по умолч. large-v3)
    WHISPER_COMPUTE_TYPE   тип вычислений                  (по умолч. int8 — CPU;
                                                            GPU: int8_float16 / float16)
    WHISPER_DEVICE         auto|cpu|cuda                   (по умолч. cpu)
    WHISPER_THREADS        потоков CPU                      (по умолч. 6)
    WHISPER_LANGUAGE       язык или auto                    (по умолч. auto)
    WHISPER_CLEAN_AUDIO    мягкая очистка звука true/false  (по умолч. false)
    WHISPER_INITIAL_PROMPT подсказка темы                   (по умолч. пусто)
    WHISPER_CT_BINARY      путь/имя бинаря whisper-ctranslate2 (по умолч. на PATH)
    WHISPER_DIARIZE        размечать говорящих true/false   (по умолч. false)
    HF_TOKEN               токен Hugging Face для pyannote  (без него диаризации нет)
    DIARIZE_MODEL          модель диаризации  (по умолч. pyannote/speaker-diarization-3.1)
    DIARIZE_MIN_SPEAKERS   нижняя граница числа голосов     (по умолч. не задана)
    DIARIZE_MAX_SPEAKERS   верхняя граница числа голосов    (по умолч. не задана)

Диаризация (кто говорит) — необязательный второй проход по тому же wav уже
после того, как whisper закончил и освободил видеокарту. Метки говорящих
дописываются в .json к сегментам; текст и субтитры не меняются.

Зависимости: ffmpeg в системе, whisper-ctranslate2 в окружении
    pip install -U whisper-ctranslate2
    pip install pyannote.audio      # только для диаризации
"""

import os
import sys
import json
import shutil
import subprocess
from pathlib import Path


def env_str(name: str, default: str) -> str:
    v = os.getenv(name)
    return v if v is not None and v != "" else default


def env_bool(name: str, default: bool) -> bool:
    v = os.getenv(name)
    if v is None:
        return default
    return v.strip().lower() in ("1", "true", "yes", "y", "да")


def run(cmd) -> None:
    """Запуск процесса с наследованием stdout/stderr (их читает Java-сторона)."""
    sys.stderr.write("[transcribe] $ " + " ".join(cmd) + "\n")
    sys.stderr.flush()
    result = subprocess.run(cmd)  # stdout/stderr наследуются от родителя
    if result.returncode != 0:
        raise RuntimeError(f"Процесс завершился с кодом {result.returncode}: {cmd[0]}")


def extract_audio(input_path: Path, wav_path: Path, clean: bool) -> None:
    """16 kHz mono WAV. clean=True — мягкая очистка (highpass + loudnorm)."""
    base = ["ffmpeg", "-y", "-loglevel", "warning", "-i", str(input_path), "-vn"]
    if clean:
        # highpass=80 режет только гул (вентилятор/кондиционер), голос с ~85 Гц
        # loudnorm нормализует громкость по EBU R128. lowpass/afftdn НЕ применяем —
        # они вредят согласным и добавляют артефакты.
        base += ["-af", "highpass=f=80,loudnorm=I=-16:TP=-1.5:LRA=11"]
    base += ["-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav_path)]
    run(base)


def resolve_binary(name: str) -> str:
    if os.sep in name:  # путь задан явно
        return name
    found = shutil.which(name)
    return found if found else name  # иначе пусть PATH разрулит (или упадём с FileNotFound)


def diarize(wav_path: Path, json_path: Path, device: str) -> None:
    """
    Размечает, кто говорит, и дописывает метки в .json рядом с расшифровкой.

    Второй проход по тому же wav, а не whisperX вместо whisper: набор флагов
    whisper-ctranslate2 подобран против галлюцинаций и зацикливания, и менять
    движок ради одних меток — значит проверять качество распознавания заново.

    Ошибка здесь не должна ронять задачу: расшифровка уже готова, и «текст без
    имён» лучше, чем «ошибка вместо текста».
    """
    token = env_str("HF_TOKEN", "")
    if not token:
        sys.stderr.write("[transcribe] диаризация пропущена: не задан HF_TOKEN\n")
        return

    model = env_str("DIARIZE_MODEL", "pyannote/speaker-diarization-3.1")

    try:
        import torch
        from pyannote.audio import Pipeline
    except ImportError as e:
        sys.stderr.write(f"[transcribe] диаризация пропущена, нет зависимостей: {e}\n")
        sys.stderr.write("[transcribe] установите: pip install pyannote.audio\n")
        return

    try:
        sys.stderr.write(f"[transcribe] диаризация ({model}) ...\n")
        pipeline = Pipeline.from_pretrained(model, use_auth_token=token)
        if pipeline is None:
            # from_pretrained молча возвращает None, когда условия модели на
            # Hugging Face не приняты или токен не даёт к ней доступа
            sys.stderr.write("[transcribe] диаризация недоступна: примите условия модели "
                             f"на https://hf.co/{model} тем же аккаунтом, чей токен задан\n")
            return

        # Видеокарта к этому моменту свободна: whisper был отдельным процессом
        # и уже завершился
        if device.lower() == "cuda" and torch.cuda.is_available():
            pipeline.to(torch.device("cuda"))

        limits = {}
        for name, key in (("min_speakers", "DIARIZE_MIN_SPEAKERS"),
                          ("max_speakers", "DIARIZE_MAX_SPEAKERS")):
            raw = env_str(key, "")
            if raw:
                limits[name] = int(raw)

        annotation = pipeline(str(wav_path), **limits)
        turns = [(turn.start, turn.end, speaker)
                 for turn, _, speaker in annotation.itertracks(yield_label=True)]
    except Exception as e:
        sys.stderr.write(f"[transcribe] диаризация не удалась: {e}\n")
        return

    if not turns:
        sys.stderr.write("[transcribe] диаризация: голосов не найдено\n")
        return

    try:
        markup = json.loads(json_path.read_text(encoding="utf-8"))
        segments = markup.get("segments") or []
        assign_speakers(segments, turns)
        json_path.write_text(json.dumps(markup, ensure_ascii=False), encoding="utf-8")
    except Exception as e:
        sys.stderr.write(f"[transcribe] метки говорящих не записаны: {e}\n")
        return

    speakers = sorted({speaker for _, _, speaker in turns})
    sys.stderr.write(f"[transcribe] speakers={len(speakers)}\n")


def assign_speakers(segments, turns) -> None:
    """
    Каждому сегменту — тот голос, с которым он совпадает по времени сильнее.

    Границы whisper и pyannote не совпадают: whisper режет по паузам в речи,
    pyannote — по смене голоса. Поэтому не «кто говорил в начале сегмента», а
    «кто говорил дольше внутри него»: на перебивках первое даёт чужое имя.
    """
    for segment in segments:
        start = segment.get("start") or 0.0
        end = segment.get("end") or start
        best, best_overlap = None, 0.0
        for turn_start, turn_end, speaker in turns:
            overlap = min(end, turn_end) - max(start, turn_start)
            if overlap > best_overlap:
                best, best_overlap = speaker, overlap
        if best is not None:
            segment["speaker"] = best


def main() -> int:
    if len(sys.argv) != 3:
        sys.stderr.write("Usage: Transcribe.py <input> <output.txt>\n")
        return 1

    in_path = Path(sys.argv[1]).expanduser().resolve()
    out_txt = Path(sys.argv[2]).expanduser().resolve()

    if not in_path.exists():
        sys.stderr.write(f"[transcribe] input file not found: {in_path}\n")
        return 2

    out_dir = out_txt.parent
    out_dir.mkdir(parents=True, exist_ok=True)

    model         = env_str("WHISPER_MODEL", "large-v3")
    compute_type  = env_str("WHISPER_COMPUTE_TYPE", "int8")
    device        = env_str("WHISPER_DEVICE", "cpu")
    threads       = env_str("WHISPER_THREADS", "6")
    language      = env_str("WHISPER_LANGUAGE", "auto")
    clean_audio   = env_bool("WHISPER_CLEAN_AUDIO", False)
    initial_prompt = env_str("WHISPER_INITIAL_PROMPT", "")
    want_diarize   = env_bool("WHISPER_DIARIZE", False)
    binary         = resolve_binary(env_str("WHISPER_CT_BINARY", "whisper-ctranslate2"))

    # временный wav рядом с результатом, имя предсказуемое (по нему найдём txt)
    tmp_wav = out_dir / (out_txt.stem + "_whisper_tmp.wav")
    # whisper-ctranslate2 называет результаты по имени входа, а Java ждёт точное
    # имя out_txt — поэтому ниже каждый формат переносится на своё место.
    produced_txt = out_dir / (tmp_wav.stem + ".txt")
    sidecars = {ext: out_dir / (tmp_wav.stem + ext) for ext in (".srt", ".vtt", ".json", ".tsv")}

    try:
        sys.stderr.write(f"[transcribe] extracting audio (clean={clean_audio}) ...\n")
        extract_audio(in_path, tmp_wav, clean_audio)

        # Набор флагов — как в WhisperRunner (против галлюцинаций и зацикливания)
        cmd = [
            binary, str(tmp_wav),
            "--model", model,
            "--device", device,
            "--compute_type", compute_type,
            "--threads", threads,
            "--task", "transcribe",
            "--vad_filter", "True",
            "--beam_size", "5",
            "--best_of", "5",
            "--temperature", "0",
            "--condition_on_previous_text", "False",
            "--no_speech_threshold", "0.6",
            "--compression_ratio_threshold", "2.4",
            # all вместо txt: за тот же проход получаем субтитры (.srt, .vtt) и
            # .json с таймкодами и определённым языком. Отдельный запуск ради
            # субтитров стоил бы ещё одной полной транскрипции.
            "--output_format", "all",
            "--output_dir", str(out_dir),
        ]
        # Пустое значение или "auto" — не передаём флаг вовсе: тогда whisper
        # определяет язык сам (это его собственное умолчание). Раньше здесь
        # всегда стоял русский, и англоязычная запись расшифровывалась как
        # русская.
        if language and language.lower() not in ("auto", "none", ""):
            cmd += ["--language", language]
        if initial_prompt:
            cmd += ["--initial_prompt", initial_prompt]

        sys.stderr.write(f"[transcribe] running whisper ({model}, {compute_type}, {device}) ...\n")
        run(cmd)

        if not produced_txt.exists():
            sys.stderr.write(f"[transcribe] whisper did not produce: {produced_txt}\n")
            return 4

        # Переносим в точное имя, которое ждёт Java
        shutil.move(str(produced_txt), str(out_txt))

        # Субтитры и разметку кладём рядом под тем же именем: transcript.txt ->
        # transcript.srt, transcript.vtt, transcript.json
        for ext, src in sidecars.items():
            if src.exists():
                shutil.move(str(src), str(out_txt.with_suffix(ext)))

        # Диаризация — до удаления временного wav в finally: другого 16 kHz mono
        # у нас нет, а гонять ffmpeg второй раз ради того же файла незачем
        if want_diarize:
            diarize(tmp_wav, out_txt.with_suffix(".json"), device)

        # Язык из .json — по нему вызывающая сторона выберет языковую модель.
        # Ошибка разбора не должна ронять задачу: транскрипция уже готова.
        detected = ""
        json_path = out_txt.with_suffix(".json")
        if json_path.exists():
            try:
                detected = json.loads(json_path.read_text(encoding="utf-8")).get("language", "")
            except Exception as e:
                sys.stderr.write(f"[transcribe] не разобрал язык из json: {e}\n")
        sys.stderr.write(f"[transcribe] detected-language={detected}\n")

    except FileNotFoundError as e:
        sys.stderr.write(f"[transcribe] binary not found: {e}\n")
        sys.stderr.write("[transcribe] установите: pip install -U whisper-ctranslate2; "
                         "и ffmpeg в систему\n")
        return 4
    except Exception as e:
        sys.stderr.write(f"[transcribe] error: {e}\n")
        return 3
    finally:
        # чистим временный wav и возможный остаточный txt
        try:
            tmp_wav.unlink(missing_ok=True)
        except Exception:
            pass
        try:
            if produced_txt.exists() and produced_txt != out_txt:
                produced_txt.unlink(missing_ok=True)
        except Exception:
            pass
        # Остатки побочных форматов на случай ошибки на середине
        for src in sidecars.values():
            try:
                src.unlink(missing_ok=True)
            except Exception:
                pass

    # Проверка результата (как ждёт TranscribeExecutor: маркер "empty transcription")
    if not out_txt.exists() or out_txt.stat().st_size == 0:
        sys.stderr.write("[transcribe] empty transcription\n")
        return 5
    if not out_txt.read_text(encoding="utf-8", errors="ignore").strip():
        sys.stderr.write("[transcribe] empty transcription\n")
        return 5

    sys.stderr.write("[transcribe] done\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
