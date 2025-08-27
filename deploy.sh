#!/bin/bash

# Улучшенный скрипт деплоя бота
set -e

# Цвета
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${BLUE}[$(date +'%H:%M:%S')]${NC} $1"; }
success() { echo -e "${GREEN}✅ $1${NC}"; }
error() { echo -e "${RED}❌ $1${NC}"; exit 1; }
warn() { echo -e "${YELLOW}⚠️ $1${NC}"; }

# Настройки сервера
SERVER_HOST=${SERVER_HOST:-"REDACTED_IP"}
SERVER_USER=${SERVER_USER:-"root"}
SERVER_PASS=${SERVER_PASS:-"REDACTED_PASSWORD"}

# Функция для выполнения команд на сервере с таймаутом
run_on_server() {
    local cmd="$1"
    local timeout=${2:-30}
    
    log "Выполняем команду на сервере (таймаут: ${timeout}s)..."
    
    # Используем timeout для предотвращения зависания
    timeout $timeout sshpass -p "$SERVER_PASS" ssh \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=10 \
        -o ServerAliveInterval=30 \
        -o ServerAliveCountMax=3 \
        "$SERVER_USER@$SERVER_HOST" "$cmd"
    
    local exit_code=$?
    if [ $exit_code -eq 124 ]; then
        error "Таймаут выполнения команды на сервере"
    elif [ $exit_code -ne 0 ]; then
        error "Ошибка выполнения команды на сервере (код: $exit_code)"
    fi
}

# Функция для проверки подключения к серверу
check_server_connection() {
    log "Проверяем подключение к серверу..."
    
    # Сначала проверяем ping
    if ! ping -c 1 -W 5 "$SERVER_HOST" > /dev/null 2>&1; then
        error "Сервер недоступен (ping не проходит)"
    fi
    
    # Затем проверяем SSH
    if ! timeout 10 sshpass -p "$SERVER_PASS" ssh \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=5 \
        "$SERVER_USER@$SERVER_HOST" "echo 'SSH connection OK'" > /dev/null 2>&1; then
        error "SSH подключение не работает"
    fi
    
    success "Подключение к серверу работает"
}

# 1. Собираем bootJar
log "🔨 Собираем bootJar..."
if ! ./gradlew bootJar; then
    error "Ошибка сборки JAR файла"
fi
success "JAR собран"

# 2. Коммитим и пушим
log "📤 Пушим в Git..."
if ! git add .; then
    error "Ошибка добавления файлов в git"
fi

if ! git commit -m "Deploy: $(date +'%Y-%m-%d %H:%M:%S')"; then
    warn "Нет изменений для коммита"
fi

if ! git push; then
    error "Ошибка пуша в Git"
fi
success "Код запушен"

# 3. Проверяем подключение к серверу
check_server_connection

# 4. Обновляем код на сервере
log "📥 Обновляем код на сервере..."
run_on_server "cd /root/Bot && git pull" 60
success "Код обновлен"

# 5. Останавливаем контейнер
log "🛑 Останавливаем контейнер..."
run_on_server "cd /root/Bot && docker-compose down" 30
success "Контейнер остановлен"

# 6. Пересобираем контейнер
log "🔨 Пересобираем контейнер..."
run_on_server "cd /root/Bot && docker-compose build --no-cache" 300
success "Контейнер пересобран"

# 7. Запускаем контейнер
log "🚀 Запускаем контейнер..."
run_on_server "cd /root/Bot && docker-compose up -d" 60
success "Контейнер запущен"

# 8. Ждем запуска и проверяем статус
log "⏳ Ждем запуска приложения..."
sleep 10

log "🔍 Проверяем статус контейнеров..."
run_on_server "cd /root/Bot && docker-compose ps" 30

log "🔍 Проверяем логи приложения..."
run_on_server "cd /root/Bot && docker-compose logs --tail=10 bot" 30

success "🎉 Деплой завершен успешно!"