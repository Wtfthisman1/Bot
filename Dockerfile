###############################################################################
#  Production Docker image для развертывания (JAR собирается локально)
###############################################################################

# === STAGE 1: Base ===
FROM python:3.11-slim as base

# Устанавливаем Java и системные зависимости
RUN apt-get update && apt-get install -y \
    openjdk-17-jre-headless \
    ffmpeg \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Создаем рабочую директорию
WORKDIR /app

# === STAGE 2: Python dependencies (КЕШИРУЕМЫЕ) ===
# Устанавливаем Python зависимости (кешируем)
COPY requirements.txt .
RUN --mount=type=cache,target=/tmp/pip-cache \
    pip install -r requirements.txt

# Создаем директории для кеша и загрузок
RUN mkdir -p /app/upload /app/logs /app/.cache/huggingface

# Предварительная загрузка модели Whisper (кешируем)
COPY whisper_preload.py .
# Модель будет загружена при первом запуске контейнера

# === STAGE 3: Application (НЕ КЕШИРУЕМЫЕ СЛОИ) ===
# Принудительно обновляем каждый раз
ARG CACHEBUST=1
ARG BUILD_DATE=unknown

# Копируем конфигурацию (обновляется при каждой сборке)
COPY docker-compose.yml .
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh

# Копируем Python скрипты (обновляются при каждой сборке)
COPY src/main/resources/pythonScript/ ./pythonScript/

# Копируем JAR файлы (обновляются при каждой сборке)
COPY build/libs/ ./libs/

# Копируем .env файл если есть
COPY .env* ./

# Устанавливаем права на скрипты
RUN chmod +x pythonScript/*.py

# Создаем симлинки для совместимости
RUN ln -sf /usr/local/bin/python /usr/bin/python3 && \
    ln -sf /usr/local/bin/pip /usr/bin/pip3

# === STAGE 4: Final runtime ===
FROM base AS runtime

# Копируем все из base stage
COPY --from=base /usr/local/lib/python3.*/dist-packages /usr/local/lib/python3.*/dist-packages/
COPY --from=base /app/.cache /app/.cache/
COPY --from=base /app/upload /app/upload/
COPY --from=base /app/logs /app/logs/
COPY --from=base /app/pythonScript /app/pythonScript/
COPY --from=base /app/docker-compose.yml /app/docker-compose.yml
COPY --from=base /app/docker-entrypoint.sh /app/docker-entrypoint.sh
COPY --from=base /app/libs /app/libs/

# Переменные среды
ENV UPLOAD_DIR=/app/upload
ENV APP_STORAGE_BASE=/app/upload/videos
ENV XDG_CACHE_HOME=/app/.cache
ENV HF_HOME=/app/.cache/huggingface
ENV JAVA_OPTS="-Xmx2g -Xms512m -XX:+UseG1GC -XX:+UseContainerSupport"

# Тома
VOLUME ["/app/upload", "/app/logs", "/app/.cache"]

# Порт и healthcheck
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Точка входа
ENTRYPOINT ["./docker-entrypoint.sh"]
