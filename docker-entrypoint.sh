#!/usr/bin/env bash
set -euo pipefail

# Функция логирования
log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# Функция проверки переменных окружения
check_env() {
    if [[ -z "${BOT_TOKEN:-}" ]]; then
        log "ERROR: BOT_TOKEN не установлен"
        exit 1
    fi
    if [[ -z "${ADMIN_CHAT_ID:-}" ]]; then
        log "WARNING: ADMIN_CHAT_ID не установлен"
    fi
}

# Проверяем переменные окружения
check_env

# Создаем необходимые директории
log "Создание директорий..."
mkdir -p "${UPLOAD_DIR:-/app/upload}" /app/logs "${XDG_CACHE_HOME:-/app/.cache}"

# Сброс прав: дальше всё идёт от transcribot, а не от root.
#
# Внутри контейнера работают yt-dlp и ffmpeg — на чужих файлах и чужих
# ссылках. Пока процесс шёл от root, выход из любого из них означал root в
# контейнере целиком. Начать root'ом всё же приходится: каталоги upload и logs
# монтируются с хоста, и владельца им выставить может только он.
APP_USER="${APP_USER:-transcribot}"
if [[ "$(id -u)" == "0" ]] && id -u "$APP_USER" >/dev/null 2>&1; then
    log "Выравнивание прав на каталогах для $APP_USER..."
    # Только смонтированное: остальное уже принадлежит нужному пользователю
    # с самой сборки, а рекурсивный chown по /app стоил бы минуты на старте
    chown -R "$APP_USER" "${UPLOAD_DIR:-/app/upload}" /app/logs "${XDG_CACHE_HOME:-/app/.cache}" || \
        log "WARNING: владелец каталогов не сменился — на хосте нужен chown -R 10001 ./upload ./logs"
    log "Продолжаем от пользователя $APP_USER"
    # setpriv из util-linux, а не su или gosu: он уже есть в образе, не
    # заводит новый пароль/сессию и не оставляет между собой и java лишний
    # процесс — сигнал остановки от Docker доходит до приложения напрямую
    exec setpriv --reuid="$APP_USER" --regid="$APP_USER" --init-groups "$0" "$@"
fi

# Опциональная предзагрузка моделей Whisper
if [[ -n "${WHISPER_PRELOAD_MODELS:-}" ]]; then
    log "Предзагрузка Whisper моделей: ${WHISPER_PRELOAD_MODELS}"
    log "Устройство: ${WHISPER_DEVICE:-cpu}, Вычисления: ${WHISPER_COMPUTE_TYPE:-int8}"
    
    # Проверяем доступность Python и скрипта
    if command -v python3 &> /dev/null && [[ -f /app/whisper_preload.py ]]; then
        if python3 /app/whisper_preload.py; then
            log "Предзагрузка моделей завершена успешно"
        else
            log "WARNING: Предзагрузка моделей завершилась с ошибкой (продолжаем работу)"
        fi
    else
        log "ERROR: Python3 или whisper_preload.py недоступны"
        exit 1
    fi
else
    log "Предзагрузка моделей не запрошена (WHISPER_PRELOAD_MODELS пуст)"
fi

# Проверяем доступность JAR файла
if [[ -f "/app/libs/bot.jar" ]]; then
    JAR_FILE="/app/libs/bot.jar"
elif [[ -f "/app/libs/demo.jar" ]]; then
    JAR_FILE="/app/libs/demo.jar"
else
    # Ищем любой JAR файл в директории libs
    JAR_FILE=$(find /app/libs -name "*.jar" | head -1)
fi

if [[ -z "$JAR_FILE" ]]; then
    log "ERROR: JAR файл не найден в /app/libs/"
    log "Содержимое директории /app/libs/:"
    ls -la /app/libs/ 2>/dev/null || log "Директория /app/libs/ не существует"
    exit 1
fi
log "Найден JAR файл: $JAR_FILE"

# Настройки JVM для контейнера
export JAVA_OPTS="${JAVA_OPTS:-} -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Запуск приложения
log "Запуск Spring Boot приложения..."
exec java $JAVA_OPTS -jar "$JAR_FILE" \
    --upload.dir="${UPLOAD_DIR:-/app/upload}" \
    --app.storage.base="${APP_STORAGE_BASE:-/app/upload/videos}"

