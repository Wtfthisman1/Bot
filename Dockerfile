###############################################################################
#  Runtime (Java 17 + Python 3.11 + Whisper) без лишних стадий сборки JAR
###############################################################################
FROM python:3.11-slim AS runtime
WORKDIR /app

# 1) Системные зависимости (JRE, ffmpeg для конвертации аудио, libsndfile для работы с WAV)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      openjdk-17-jre-headless \
      ffmpeg \
      libsndfile1 \
      curl && \
    rm -rf /var/lib/apt/lists/*

# 2) Python‑зависимости
RUN pip install --no-cache-dir \
      "numpy<2" \
      torch==2.2.1+cpu \
      -f https://download.pytorch.org/whl/torch_stable.html && \
    pip install --no-cache-dir \
      faster-whisper==1.0.1 \
      yt-dlp \
      tqdm

# 3) Предзагрузка моделей Whisper в кеш
COPY whisper_preload.py .
RUN python3 whisper_preload.py

# 4) Копируем готовый JAR из репозитория
COPY build/libs/*.jar app.jar

# 5) Копируем ваши Python-скрипты и логбэк-конфиг из папки src/main/resources
COPY src/main/resources/pythonScript/ pythonScript/
COPY src/main/resources/logback.xml .

# 6) Переменные среды и тома для хранения пользовательских файлов и логов
ENV UPLOAD_DIR=/app/upload
ENV APP_STORAGE_BASE=/app/upload/videos
VOLUME ["/app/upload", "/app/logs"]

# 7) Открываем порт, на котором слушает Spring Boot
EXPOSE 8080

# 8) Docker‑healthcheck для автоперезапуска при проблемах
HEALTHCHECK --interval=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# 9) Запуск приложения
CMD ["java","-jar","app.jar","--upload.dir=${UPLOAD_DIR}","--app.storage.base=${APP_STORAGE_BASE}"]
