###############################################################################
#  ⌁⌁⌁  Stage 1: Build Spring-Boot JAR  ⌁⌁⌁
###############################################################################
FROM gradle:8.6-jdk17 AS builder
WORKDIR /workspace

# Копируем весь исходник (исключая лишнее через .dockerignore)
COPY . .
RUN ./gradlew clean bootJar           \
 && ls -lh build/libs                 # только чтоб убедиться, что JAR готов

###############################################################################
#  ⌁⌁⌁  Stage 2: Runtime (Java 17 + Python 3.11 + Whisper)  ⌁⌁⌁
###############################################################################
FROM python:3.11-slim AS runtime
WORKDIR /app

# --- системные зависимости ---
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless ffmpeg libsndfile1 curl && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# --- python-зависимости ---
RUN pip install --no-cache-dir \
        "torch==2.2.1+cpu" -f https://download.pytorch.org/whl/torch_stable.html \
        faster-whisper==1.0.1 yt-dlp tqdm \


# --- копируем приложение ---
COPY --from=builder /workspace/build/libs/*.jar app.jar
COPY logback.xml .
COPY resources/pythonScript/ ./pythonScript

# --- переменные среды / каталоги ---
ENV APP_STORAGE_BASE=/data
VOLUME ["/data", "/app/logs"]

EXPOSE 8080

# --- предзагрузка трех моделей Whisper в кэш ---
RUN python3 - << 'EOF'
from faster_whisper import WhisperModel

for model_name in ["base", "medium", "large-v3"]:
    # при первом создании модель скачивается в ~/.cache/faster_whisper
    WhisperModel(model_name, device="cpu", compute_type="int8")
EOF



# --- health-check для Docker/Compose/K8s ---
HEALTHCHECK --interval=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# --- точка входа ---
CMD ["java","-jar","app.jar","--app.storage.base=/data"]
