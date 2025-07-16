###############################################################################
#  ⌁⌁⌁  Stage 1: Build Spring-Boot JAR  ⌁⌁⌁
###############################################################################
FROM gradle:8.6-jdk17 AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew clean bootJar

###############################################################################
#  ⌁⌁⌁  Stage 2: Runtime (Java 17 + Python 3.11 + Whisper)  ⌁⌁⌁
###############################################################################
FROM python:3.11-slim AS runtime
WORKDIR /app

# 1) Системные зависимости
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      openjdk-17-jre-headless ffmpeg libsndfile1 curl && \
    rm -rf /var/lib/apt/lists/*

# 2) Python‑зависимости
RUN pip install --no-cache-dir \
      torch==2.2.1+cpu -f https://download.pytorch.org/whl/torch_stable.html \
      faster-whisper==1.0.1 yt-dlp tqdm

# 3) Копируем скрипт для предзагрузки моделей и запускаем его
COPY whisper_preload.py .
RUN python3 whisper_preload.py

# 4) Копируем ваше приложение
COPY --from=builder /workspace/build/libs/*.jar app.jar
COPY resources/pythonScript/       pythonScript/
COPY logback.xml                   .

# 5) Переменные среды и тома
ENV APP_STORAGE_BASE=/data
VOLUME ["/data", "/app/logs"]

EXPOSE 8080

# 6) Healthcheck
HEALTHCHECK --interval=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# 7) Точка входа
CMD ["java","-jar","app.jar","--app.storage.base=/data"]
