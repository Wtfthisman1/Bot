# whisper_preload.py
import os
from faster_whisper import WhisperModel
from huggingface_hub import snapshot_download

_FW_REPO_MAP = {
    "tiny": "guillaumekln/faster-whisper-tiny",
    "base": "guillaumekln/faster-whisper-base",
    "small": "guillaumekln/faster-whisper-small",
    "medium": "guillaumekln/faster-whisper-medium",
    "large-v1": "guillaumekln/faster-whisper-large-v1",
    "large-v2": "guillaumekln/faster-whisper-large-v2",
    "large-v3": "guillaumekln/faster-whisper-large-v3",
}

models_env = os.getenv("WHISPER_PRELOAD_MODELS", "").strip()
if not models_env:
    print("No WHISPER_PRELOAD_MODELS set. Skipping preload.")
else:
    models = [m.strip() for m in models_env.split(",") if m.strip()]
    compute_type = os.getenv("WHISPER_COMPUTE_TYPE", "int8").strip()
    device = os.getenv("WHISPER_DEVICE", "cpu").strip()

    for model_name in models:
        repo_id = _FW_REPO_MAP.get(model_name, None)
        cached = False
        if repo_id is not None:
            try:
                snapshot_download(repo_id=repo_id, local_files_only=True)
                cached = True
                print(f"Whisper model '{model_name}' already cached. Skipping download.")
            except Exception:
                cached = False

        if not cached:
            print(f"Preloading Whisper model {model_name} on {device} ({compute_type})…")
            # This will download if not present, or quickly init if cached elsewhere
            WhisperModel(model_name, device=device, compute_type=compute_type)

    print("Whisper preload step completed.")
