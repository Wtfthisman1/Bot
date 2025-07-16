# whisper_preload.py
from faster_whisper import WhisperModel

for model_name in ("base", "medium", "large-v3"):
    print(f"Preloading Whisper model {model_name}…")
    WhisperModel(model_name, device="cpu", compute_type="int8")
print("All models preloaded.")
