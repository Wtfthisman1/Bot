#!/bin/bash

# Скрипт проверки готовности к деплою
set -e

# Цвета
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${BLUE}[$(date +'%H:%M:%S')]${NC} $1"; }
success() { echo -e "${GREEN}✅ $1${NC}"; }
error() { echo -e "${RED}❌ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠️ $1${NC}"; }

# Настройки сервера
SERVER_HOST=${SERVER_HOST:-"REDACTED_IP"}
SERVER_USER=${SERVER_USER:-"root"}
SERVER_PASS=${SERVER_PASS:-"REDACTED_PASSWORD"}

# Функция для выполнения команд на сервере
run_on_server() {
    local cmd="$1"
    local timeout=${2:-30}
    
    timeout $timeout sshpass -p "$SERVER_PASS" ssh \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=20 \
        -o ServerAliveInterval=60 \
        -o ServerAliveCountMax=3 \
        "$SERVER_USER@$SERVER_HOST" "$cmd" || return 1
}

# Проверка локальной среды
check_local_environment() {
    log "Проверяем локальную среду..."
    
    # Проверяем Java
    if command -v java &> /dev/null; then
        local java_version=$(java -version 2>&1 | head -n 1)
        log "Java: $java_version"
    else
        error "Java не установлена"
        return 1
    fi
    
    # Проверяем Docker
    if command -v docker &> /dev/null; then
        local docker_version=$(docker --version)
        log "Docker: $docker_version"
    else
        warn "Docker не установлен локально (не критично для деплоя)"
    fi
    
    # Проверяем rsync
    if command -v rsync &> /dev/null; then
        log "rsync: доступен"
    else
        error "rsync не установлен (необходим для копирования файлов)"
        return 1
    fi
    
    # Проверяем sshpass
    if command -v sshpass &> /dev/null; then
        log "sshpass: доступен"
    else
        error "sshpass не установлен (необходим для автоматического подключения)"
        return 1
    fi
    
    success "Локальная среда проверена"
}

# Проверка файлов проекта
check_project_files() {
    log "Проверяем файлы проекта..."
    
    local required_files=(
        "build.gradle"
        "gradlew"
        "docker-compose.yml"
        "Dockerfile"
        "docker-entrypoint.sh"
        ".env"
    )
    
    for file in "${required_files[@]}"; do
        if [[ -f "$file" ]]; then
            log "✅ $file"
        else
            error "❌ $file - не найден"
            return 1
        fi
    done
    
    success "Файлы проекта проверены"
}

# Проверка переменных окружения
check_environment_variables() {
    log "Проверяем переменные окружения..."
    
    # Загружаем .env файл
    if [[ -f ".env" ]]; then
        source .env
    fi
    
    local required_vars=(
        "BOT_TOKEN"
        "ADMIN_CHAT_ID"
        "UPLOAD_BASE_URL"
    )
    
    for var in "${required_vars[@]}"; do
        if [[ -n "${!var}" ]]; then
            log "✅ $var: ${!var:0:10}..."
        else
            error "❌ $var - не установлена"
            return 1
        fi
    done
    
    success "Переменные окружения проверены"
}

# Проверка сервера
check_server() {
    log "Проверяем сервер..."
    
    # Проверяем ping
    if ping -c 1 -W 5 "$SERVER_HOST" > /dev/null 2>&1; then
        log "✅ Сервер доступен (ping)"
    else
        error "❌ Сервер недоступен (ping)"
        return 1
    fi
    
    # Проверяем SSH
    if run_on_server "echo 'SSH connection OK'" 30; then
        log "✅ SSH подключение работает"
    else
        error "❌ SSH подключение не работает"
        return 1
    fi
    
    # Проверяем Docker на сервере
    if run_on_server "docker --version" 30; then
        log "✅ Docker установлен на сервере"
    else
        error "❌ Docker не установлен на сервере"
        return 1
    fi
    
    # Проверяем docker-compose на сервере
    if run_on_server "docker-compose --version" 30; then
        log "✅ docker-compose установлен на сервере"
    else
        error "❌ docker-compose не установлен на сервере"
        return 1
    fi
    
    success "Сервер проверен"
}

# Проверка сборки
check_build() {
    log "Проверяем сборку проекта..."
    
    # Очищаем и собираем проект
    if ./gradlew clean bootJar; then
        log "✅ Сборка успешна"
    else
        error "❌ Ошибка сборки"
        return 1
    fi
    
    # Проверяем, что JAR создался
    if [[ -f "build/libs/bot.jar" ]]; then
        local jar_size=$(du -h build/libs/bot.jar | cut -f1)
        log "✅ JAR файл создан: build/libs/bot.jar ($jar_size)"
    else
        error "❌ JAR файл не найден"
        return 1
    fi
    
    success "Сборка проверена"
}

# Основная функция
main() {
    log "🔍 Начинаем проверку готовности к деплою..."
    
    local all_checks_passed=true
    
    # Проверяем локальную среду
    if ! check_local_environment; then
        all_checks_passed=false
    fi
    
    echo
    
    # Проверяем файлы проекта
    if ! check_project_files; then
        all_checks_passed=false
    fi
    
    echo
    
    # Проверяем переменные окружения
    if ! check_environment_variables; then
        all_checks_passed=false
    fi
    
    echo
    
    # Проверяем сервер
    if ! check_server; then
        all_checks_passed=false
    fi
    
    echo
    
    # Проверяем сборку
    if ! check_build; then
        all_checks_passed=false
    fi
    
    echo
    
    if [[ "$all_checks_passed" == "true" ]]; then
        success "🎉 Все проверки пройдены! Можно запускать деплой."
        log "Запустите: ./deploy.sh"
    else
        error "❌ Некоторые проверки не пройдены. Исправьте ошибки перед деплоем."
        exit 1
    fi
}

# Запуск основной функции
main "$@"
