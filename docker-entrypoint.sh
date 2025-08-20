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
JAR_FILE=$(find /app/libs -name "*.jar" | head -1)
if [[ -z "$JAR_FILE" ]]; then
    log "ERROR: JAR файл не найден в /app/libs/"
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

