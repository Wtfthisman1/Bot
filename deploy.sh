#!/bin/bash

# Простой скрипт деплоя бота
set -e

# Цвета
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

log() { echo -e "${BLUE}[$(date +'%H:%M:%S')]${NC} $1"; }
success() { echo -e "${GREEN}✅ $1${NC}"; }
error() { echo -e "${RED}❌ $1${NC}"; exit 1; }

# Настройки сервера
SERVER_HOST=${SERVER_HOST:-"REDACTED_IP"}
SERVER_USER=${SERVER_USER:-"root"}
SERVER_PASS=${SERVER_PASS:-"REDACTED_PASSWORD"}

run_on_server() {
    sshpass -p "$SERVER_PASS" ssh -o StrictHostKeyChecking=no "$SERVER_USER@$SERVER_HOST" "$1"
}

# 1. Собираем bootJar
log "🔨 Собираем bootJar..."
./gradlew bootJar || error "Ошибка сборки"
success "JAR собран"

# 2. Коммитим и пушим
log "📤 Пушим в Git..."
git add .
git commit -m "Deploy: $(date +'%Y-%m-%d %H:%M:%S')" || true
git push || error "Ошибка пуша"
success "Код запушен"

# 3. Подключаемся к серверу
log "🔌 Подключение к серверу..."
run_on_server "echo 'OK'" || error "Нет подключения к серверу"
success "Подключение установлено"

# 4. Обновляем код на сервере
log "📥 Pull на сервере..."
run_on_server "cd /root/Bot && git pull" || error "Ошибка pull"
success "Код обновлен"

# 5. Пересобираем и запускаем контейнер
log "🐳 Пересборка контейнера..."
run_on_server "cd /root/Bot && docker-compose down && docker-compose build && docker-compose up -d" || error "Ошибка Docker"
success "Контейнер запущен"

# 6. Проверяем статус
log "🔍 Проверка статуса..."
sleep 3
run_on_server "docker-compose ps"

success "🎉 Деплой завершен!"