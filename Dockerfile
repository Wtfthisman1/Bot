###############################################################################
#  Production Docker image для развертывания (JAR собирается локально)
###############################################################################

# === STAGE 1: Base ===
FROM openjdk:17-jdk-slim as base

# Устанавливаем системные зависимости
RUN apt-get update && apt-get install -y \
    python3 \
    python3-pip \
    ffmpeg \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Создаем рабочую директорию
WORKDIR /app

# === STAGE 2: Python dependencies (КЕШИРУЕМЫЕ) ===
# Устанавливаем Python зависимости (кешируем)
COPY requirements.txt .
RUN pip3 install --no-cache-dir -r requirements.txt

# Создаем директории для кеша и загрузок
RUN mkdir -p /app/upload /app/logs /app/.cache/huggingface

# Предварительная загрузка модели Whisper (кешируем)
COPY whisper_preload.py .
# Модель будет загружена при первом запуске контейнера

# === STAGE 3: Application (НЕ КЕШИРУЕМЫЕ СЛОИ) ===
# Принудительно обновляем каждый раз
ARG CACHEBUST=1
ARG BUILD_DATE=unknown

# Копируем Python скрипты (обновляются при каждой сборке)
COPY src/main/resources/pythonScript/ ./pythonScript/

# Копируем конфигурацию (обновляется при каждой сборке)
COPY docker-compose.yml .
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh

# Копируем JAR файлы (обновляются при каждой сборке)
COPY build/libs/ ./libs/

# Копируем .env файл если есть
COPY .env* ./

# Устанавливаем права на скрипты
RUN chmod +x pythonScript/*.py

# === STAGE 4: Final runtime ===
# Точка входа
ENTRYPOINT ["./docker-entrypoint.sh"]
