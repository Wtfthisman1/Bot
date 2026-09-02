###############################################################################
#  Production Docker image для развертывания (JAR собирается локально)
###############################################################################

# === STAGE 1: Base ===
FROM python:3.11-slim as base

# Устанавливаем Java и системные зависимости
RUN apt-get update && apt-get install -y \
    openjdk-21-jre-headless \
    ffmpeg \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Пользователь, от которого работает приложение. Внутри контейнера крутятся
# yt-dlp и ffmpeg на чужих данных: выход из любого из них раньше сразу давал
# root. Uid задан числом, чтобы права на смонтированных с хоста каталогах
# сходились независимо от того, какой пользователь заведётся в образе.
RUN groupadd --gid 10001 transcribot \
    && useradd --uid 10001 --gid 10001 --create-home --home-dir /home/transcribot \
       --shell /usr/sbin/nologin transcribot

# Создаем рабочую директорию
WORKDIR /app

# === STAGE 2: Python dependencies (КЕШИРУЕМЫЕ) ===
# Устанавливаем Python зависимости (кешируем)
COPY requirements.txt .
RUN --mount=type=cache,target=/tmp/pip-cache \
    pip install -r requirements.txt

# Создаем директории для кеша и загрузок
RUN mkdir -p /app/upload /app/logs /app/.cache/huggingface \
    && chown -R transcribot:transcribot /app

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

# .env В ОБРАЗ НЕ КОПИРУЕТСЯ.
#
# Здесь было `COPY .env* ./`, и это отдавало все секреты сразу: слой образа
# читает любой, у кого есть сам образ — `docker save` и `tar -x` хватает, root
# внутри контейнера не нужен. В .env лежат токен бота (полная власть над ним),
# пароль базы, общий ключ HOME_API_KEY, секрет Google и токен Hugging Face.
# Переменные передаются при запуске (см. docker-compose.yml), а на домашней
# машине приложение читает .env из рабочего каталога само.

# Устанавливаем права на скрипты
RUN chmod +x pythonScript/*.py

# Создаем симлинки для совместимости
RUN ln -sf /usr/local/bin/python /usr/bin/python3 && \
    ln -sf /usr/local/bin/pip /usr/bin/pip3

# === STAGE 4: Final runtime ===
#
# Процесс идёт не от root: docker-entrypoint.sh выравнивает права на
# смонтированных каталогах и сбрасывает права через setpriv. Выход из yt-dlp или
# ffmpeg упирается в непривилегированного transcribot, а не в root контейнера.
# USER здесь не ставится намеренно: точка входа обязана начать root'ом, иначе
# ей нечем чинить владельца тома, смонтированного с хоста.
FROM base AS runtime

# Копируем все из base stage. --chown обязателен: без него владельцем всего
# скопированного снова становится root, и непривилегированный процесс не может
# писать ни в upload, ни в logs, ни в кеш моделей
COPY --chown=transcribot:transcribot --from=base /usr/local/lib/python3.*/dist-packages /usr/local/lib/python3.*/dist-packages/
COPY --chown=transcribot:transcribot --from=base /app/.cache /app/.cache/
COPY --chown=transcribot:transcribot --from=base /app/upload /app/upload/
COPY --chown=transcribot:transcribot --from=base /app/logs /app/logs/
COPY --chown=transcribot:transcribot --from=base /app/pythonScript /app/pythonScript/
COPY --chown=transcribot:transcribot --from=base /app/docker-compose.yml /app/docker-compose.yml
COPY --chown=transcribot:transcribot --from=base /app/docker-entrypoint.sh /app/docker-entrypoint.sh
COPY --chown=transcribot:transcribot --from=base /app/libs /app/libs/

# Переменные среды
ENV UPLOAD_DIR=/app/upload
ENV APP_STORAGE_BASE=/app/upload/videos
ENV XDG_CACHE_HOME=/app/.cache
ENV HF_HOME=/app/.cache/huggingface
ENV JAVA_OPTS="-XX:+UseContainerSupport"
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# Тома
VOLUME ["/app/upload", "/app/logs", "/app/.cache"]

# Порт и healthcheck
EXPOSE 8080
# Actuator слушает свой порт на 127.0.0.1 (MANAGEMENT_PORT=8081), а не 8080:
# на 8080 у него 404, и проверка считала бы здоровый контейнер больным.
# Тот же адрес, что в healthcheck docker-compose.yml
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://127.0.0.1:8081/actuator/health || exit 1

# Точка входа
ENTRYPOINT ["./docker-entrypoint.sh"]
