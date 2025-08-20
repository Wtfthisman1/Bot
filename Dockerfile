###############################################################################
#  Production Docker image для развертывания (JAR собирается локально)
###############################################################################

# ========== STAGE 1: Base с системными зависимостями ==========
FROM python:3.11-slim AS base
WORKDIR /app

# Системные зависимости (кешируем - редко меняются)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      openjdk-21-jre-headless \
      ffmpeg \
      libsndfile1 \
      curl \
      bash && \
    rm -rf /var/lib/apt/lists/*

# ========== STAGE 2: Python зависимости ==========
FROM base AS python-deps

# Python зависимости (кешируем - меняются редко)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# ========== STAGE 3: Финальный runtime ==========
FROM python-deps AS runtime

# Переменные среды (кешируем)
ENV UPLOAD_DIR=/app/upload
ENV APP_STORAGE_BASE=/app/upload/videos
ENV XDG_CACHE_HOME=/app/.cache
ENV HF_HOME=/app/.cache/huggingface
ENV JAVA_OPTS="-Xmx2g -Xms512m -XX:+UseG1GC -XX:+UseContainerSupport"

# Создаем рабочие директории (кешируем)
RUN mkdir -p /app/upload /app/logs /app/.cache/huggingface

# Скрипт предзагрузки модели Whisper
COPY whisper_preload.py .

# === НЕ КЕШИРУЕМЫЕ СЛОИ (обновляются при каждой сборке) ===
# Принудительно обновляем каждый раз
ARG CACHEBUST=1

# Копируем конфигурационные файлы
COPY src/main/resources/logback.xml .
COPY .env* ./

# Копируем готовый JAR файл
COPY build/libs/ ./libs/

# Копируем Python скрипты (после CACHEBUST)
COPY src/main/resources/pythonScript/ pythonScript/

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
