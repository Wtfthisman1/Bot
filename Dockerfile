###############################################################################
#  Production Docker image для развертывания (JAR собирается локально)
###############################################################################

# ========== STAGE 1: Python dependencies ==========
FROM python:3.11-slim AS python-deps
WORKDIR /app

# Системные зависимости
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      ffmpeg \
      libsndfile1 \
      curl \
      bash && \
    rm -rf /var/lib/apt/lists/*

# Python зависимости (кешируем отдельно для быстрой пересборки)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# ========== STAGE 2: Runtime ==========
FROM python:3.11-slim AS runtime
WORKDIR /app

# Системные зависимости (только runtime)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      openjdk-21-jre-headless \
      ffmpeg \
      libsndfile1 \
      curl \
      bash && \
    rm -rf /var/lib/apt/lists/*

# Копируем Python зависимости из предыдущей стадии
COPY --from=python-deps /usr/local/lib/python3.11/site-packages /usr/local/lib/python3.11/site-packages
COPY --from=python-deps /usr/local/bin /usr/local/bin

# Копируем готовый JAR (собранный локально)
COPY build/libs/*.jar app.jar

# Копируем Python скрипты и конфиги
COPY src/main/resources/pythonScript/ pythonScript/
COPY src/main/resources/logback.xml .
COPY whisper_preload.py .

# Переменные среды и тома
ENV UPLOAD_DIR=/app/upload
ENV APP_STORAGE_BASE=/app/upload/videos
ENV XDG_CACHE_HOME=/app/.cache
ENV HF_HOME=/app/.cache/huggingface
ENV JAVA_OPTS="-Xmx2g -Xms512m -XX:+UseG1GC -XX:+UseContainerSupport"

VOLUME ["/app/upload", "/app/logs", "/app/.cache"]

# Порт и healthcheck
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Entrypoint
COPY docker-entrypoint.sh ./docker-entrypoint.sh
RUN chmod +x ./docker-entrypoint.sh
ENTRYPOINT ["./docker-entrypoint.sh"]
