#!/bin/bash

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Функции для логирования
log() {
    echo -e "${BLUE}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1"
}

success() {
    echo -e "${GREEN}✅ $1${NC}"
}

error() {
    echo -e "${RED}❌ $1${NC}"
}

warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

# Проверяем, что мы в Git репозитории
if [ ! -d ".git" ]; then
    error "Не найден Git репозиторий. Запустите скрипт из корня проекта."
    exit 1
fi

# Проверяем, что есть изменения для коммита
if git diff-index --quiet HEAD --; then
    warning "Нет изменений для коммита. Продолжаем с текущим состоянием."
else
    log "🔍 Обнаружены изменения в коде..."
    
    # Добавляем все изменения
    log "📝 Добавляем изменения в Git..."
    git add .
    
    # Коммитим изменения
    log "💾 Создаем коммит..."
    git commit -m "Auto-deploy: $(date +'%Y-%m-%d %H:%M:%S')"
    
    success "Изменения закоммичены"
fi

# Шаг 1: Сборка bootJar
log "🔨 Собираем bootJar..."
if ./gradlew bootJar; then
    success "bootJar собран успешно"
else
    error "Ошибка при сборке bootJar"
    exit 1
fi

# Шаг 2: Проверяем, что JAR файл создан
JAR_FILE="build/libs/bot.jar"
if [ ! -f "$JAR_FILE" ]; then
    error "JAR файл не найден: $JAR_FILE"
    exit 1
fi

success "JAR файл готов: $JAR_FILE"

# Шаг 3: Пушим в Git
log "📤 Пушим изменения в Git..."
if git push; then
    success "Изменения запушены в Git"
else
    error "Ошибка при пуше в Git"
    exit 1
fi

# Шаг 4: Деплой на сервер
log "🚀 Начинаем деплой на сервер..."

# Функция для выполнения команд на сервере
run_on_server() {
    sshpass -p "REDACTED_PASSWORD" ssh -o StrictHostKeyChecking=no root@REDACTED_IP "$1"
}

# Проверяем подключение к серверу
log "🔌 Проверяем подключение к серверу..."
if ! run_on_server "echo 'Connection test'"; then
    error "Не удается подключиться к серверу"
    exit 1
fi

success "Подключение к серверу установлено"

# Шаг 5: Останавливаем и удаляем старые контейнеры
log "🛑 Останавливаем старые контейнеры..."
if run_on_server "cd /root/Bot && docker-compose down"; then
    success "Старые контейнеры остановлены"
else
    warning "Не удалось остановить контейнеры (возможно, их не было)"
fi

# Шаг 6: Удаляем старые образы (кроме базовых)
log "🗑️  Удаляем старые образы..."
if run_on_server "docker images | grep 'bot' | awk '{print \$3}' | xargs -r docker rmi -f"; then
    success "Старые образы удалены"
else
    warning "Не удалось удалить образы (возможно, их не было)"
fi

# Шаг 7: Делаем pull на сервере
log "📥 Обновляем код на сервере..."
if run_on_server "cd /root/Bot && git pull"; then
    success "Код обновлен на сервере"
else
    error "Ошибка при обновлении кода на сервере"
    exit 1
fi

# Шаг 8: Собираем Docker с оптимизированным кешированием
log "🔨 Собираем Docker образ с оптимизированным кешированием..."
if run_on_server "cd /root/Bot && docker-compose build --build-arg CACHEBUST=$(date +%s) --build-arg BUILD_DATE=$(date +%s)"; then
    success "Docker образ собран"
else
    error "Ошибка при сборке Docker образа"
    exit 1
fi

# Шаг 9: Запускаем контейнеры
log "🚀 Запускаем контейнеры..."
if run_on_server "cd /root/Bot && docker-compose up -d"; then
    success "Контейнеры запущены"
else
    error "Ошибка при запуске контейнеров"
    exit 1
fi

# Шаг 10: Проверяем статус
log "🔍 Проверяем статус контейнеров..."
sleep 5
if run_on_server "docker-compose ps"; then
    success "Статус контейнеров получен"
else
    error "Ошибка при получении статуса контейнеров"
fi

# Шаг 11: Проверяем логи
log "📋 Проверяем логи запуска..."
if run_on_server "docker logs telegram-bot --tail 10"; then
    success "Логи получены"
else
    error "Ошибка при получении логов"
fi

success "🎉 Деплой завершен успешно!"
log "Бот должен быть доступен и готов к работе"
