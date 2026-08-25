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

# Проверка зависимостей
check_dependencies() {
    log "Проверяем зависимости..."
    
    # Проверяем наличие gradlew
    if [[ ! -f "./gradlew" ]]; then
        error "Файл gradlew не найден. Убедитесь, что вы находитесь в корневой директории проекта."
    fi
    
    # Проверяем права на выполнение gradlew
    if [[ ! -x "./gradlew" ]]; then
        log "Устанавливаем права на выполнение gradlew..."
        chmod +x ./gradlew
    fi
    
    # Проверяем наличие .env файла
    if [[ ! -f ".env" ]]; then
        if [[ -f "env.example" ]]; then
            warn "Файл .env не найден, но найден env.example"
            log "Создаем .env из env.example..."
            cp env.example .env
            warn "Пожалуйста, отредактируйте .env файл с вашими настройками перед деплоем"
            read -p "Продолжить деплой? (y/N): " -n 1 -r
            echo
            if [[ ! $REPLY =~ ^[Yy]$ ]]; then
                error "Деплой отменен пользователем"
            fi
        else
            error "Файлы .env и env.example не найдены"
        fi
    fi
    
    # Проверяем наличие docker-compose.yml
    if [[ ! -f "docker-compose.yml" ]]; then
        error "Файл docker-compose.yml не найден"
    fi
    
    # Проверяем наличие Dockerfile
    if [[ ! -f "Dockerfile" ]]; then
        error "Файл Dockerfile не найден"
    fi
    
    success "Все зависимости проверены"
}

# Функция для выполнения команд на сервере с таймаутом
run_on_server() {
    local cmd="$1"
    local timeout=${2:-30}
    
    log "Выполняем команду на сервере (таймаут: ${timeout}s)..."
    
    # Сначала пробуем с SSH ключами, если не получится - с паролем
    if timeout $timeout ssh \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=20 \
        -o ServerAliveInterval=60 \
        -o ServerAliveCountMax=3 \
        "$SERVER_USER@$SERVER_HOST" "$cmd" 2>/dev/null; then
        return 0
    fi
    
    # Если SSH ключи не работают, используем пароль
    timeout $timeout sshpass -p "$SERVER_PASS" ssh \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=20 \
        -o ServerAliveInterval=60 \
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
    
    # Затем проверяем SSH с увеличенными таймаутами
    if ! timeout 30 sshpass -p "$SERVER_PASS" ssh \
        -o StrictHostKeyChecking=no \
        -o ConnectTimeout=20 \
        -o ServerAliveInterval=60 \
        -o ServerAliveCountMax=3 \
        "$SERVER_USER@$SERVER_HOST" "echo 'SSH connection OK'" > /dev/null 2>&1; then
        error "SSH подключение не работает"
    fi
    
    success "Подключение к серверу работает"
}

# Функция для подготовки сервера
prepare_server() {
    log "Подготавливаем сервер..."
    
    # Проверяем наличие Docker на сервере
    run_on_server "docker --version" 30
    
    # Проверяем наличие docker-compose на сервере
    run_on_server "docker-compose --version" 30
    
    # Создаем директорию проекта если её нет
    run_on_server "mkdir -p /root/Bot" 30
    
    success "Сервер подготовлен"
}

# Функция для копирования файлов на сервер
copy_files_to_server() {
    log "Копируем файлы на сервер..."
    
    # Создаем временную директорию для копирования
    local temp_dir="/tmp/bot_deploy_$(date +%s)"
    
    # Копируем все необходимые файлы
    if ! rsync -avz --delete \
        -e "sshpass -p '$SERVER_PASS' ssh -o StrictHostKeyChecking=no" \
        --exclude='.git' \
        --exclude='build' \
        --exclude='.gradle' \
        --exclude='logs' \
        --exclude='upload' \
        ./ "$SERVER_USER@$SERVER_HOST:$temp_dir/"; then
        error "Ошибка копирования файлов на сервер"
    fi
    
    # Перемещаем файлы в рабочую директорию
    run_on_server "rm -rf /root/Bot && mv $temp_dir /root/Bot" 60
    
    success "Файлы скопированы на сервер"
}

# Основной процесс деплоя
main() {
    log "🚀 Начинаем деплой бота..."
    
    # 1. Проверяем зависимости
    check_dependencies
    
    # 2. Собираем bootJar
    log "🔨 Собираем bootJar..."
    if ! ./gradlew clean bootJar; then
        error "Ошибка сборки JAR файла"
    fi
    
    # Проверяем, что JAR создался
    if [[ ! -f "build/libs/bot.jar" ]]; then
        error "JAR файл не найден в build/libs/bot.jar"
    fi
    
    success "JAR собран: build/libs/bot.jar"
    
    # 3. Коммитим и пушим
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

    # 4. Проверяем подключение к серверу
    check_server_connection
    
    # 5. Подготавливаем сервер
    prepare_server
    
    # 6. Копируем файлы на сервер
    copy_files_to_server

    # 7. Обновляем код на сервере (если используется git)
    log "📥 Обновляем код на сервере..."
    run_on_server "cd /root/Bot && git pull" 60
    success "Код обновлен"

    # 8. Останавливаем контейнер
    log "🛑 Останавливаем контейнер..."
    run_on_server "cd /root/Bot && docker-compose down" 30
    success "Контейнер остановлен"

    # 9. Пересобираем контейнер
    log "🔨 Пересобираем контейнер..."
    run_on_server "cd /root/Bot && docker-compose build --no-cache" 300
    success "Контейнер пересобран"

    # 10. Запускаем контейнер
    log "🚀 Запускаем контейнер..."
    run_on_server "cd /root/Bot && docker-compose up -d" 60
    success "Контейнер запущен"

    # 11. Ждем запуска и проверяем статус
    log "⏳ Ждем запуска приложения..."
    sleep 15

    log "🔍 Проверяем статус контейнеров..."
    run_on_server "cd /root/Bot && docker-compose ps" 30

    log "🔍 Проверяем логи приложения..."
    run_on_server "cd /root/Bot && docker-compose logs --tail=20 bot" 30

    success "🎉 Деплой завершен успешно!"
    log "🌐 Приложение доступно по адресу: http://$SERVER_HOST:8080"
}

# Запуск основного процесса
main "$@"