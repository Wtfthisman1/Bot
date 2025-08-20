#!/bin/bash

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Конфигурация сервера
SERVER_IP="91.184.242.68"
SERVER_USER="root"
SERVER_PASS="sksaPObCUT4b"

# Функция логирования
log() {
    echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Функция выполнения команд на сервере
run_on_server() {
    sshpass -p "$SERVER_PASS" ssh -o StrictHostKeyChecking=no "$SERVER_USER@$SERVER_IP" "$1"
}

# Функция копирования файлов на сервер
copy_to_server() {
    sshpass -p "$SERVER_PASS" scp -o StrictHostKeyChecking=no "$1" "$SERVER_USER@$SERVER_IP:$2"
}

# Основная функция деплоя
deploy() {
    log "🚀 Начинаем быстрый деплой..."
    
    # Шаг 1: Сборка JAR
    log "📦 Собираем JAR файл..."
    if ./gradlew bootJar; then
        success "JAR файл собран успешно"
    else
        error "Ошибка при сборке JAR файла"
        return 1
    fi
    
    # Шаг 2: Создание директории на сервере
    log "📁 Создаем директории на сервере..."
    run_on_server "mkdir -p /root/Bot/build/libs"
    
    # Шаг 3: Копирование JAR на сервер
    log "📤 Копируем JAR на сервер..."
    if copy_to_server "build/libs/bot.jar" "/root/Bot/build/libs/"; then
        success "JAR файл скопирован на сервер"
    else
        error "Ошибка при копировании JAR файла"
        return 1
    fi
    
    # Шаг 4: Остановка текущего контейнера
    log "⏹️ Останавливаем текущий контейнер..."
    run_on_server "cd /root/Bot && docker-compose down"
    
    # Шаг 5: Пересборка образа
    log "🔨 Пересобираем Docker образ..."
    if run_on_server "cd /root/Bot && docker-compose build --build-arg CACHEBUST=$(date +%s)"; then
        success "Docker образ пересобран"
    else
        error "Ошибка при сборке Docker образа"
        return 1
    fi
    
    # Шаг 6: Запуск нового контейнера
    log "▶️ Запускаем новый контейнер..."
    if run_on_server "cd /root/Bot && export BOT_TOKEN=7393663223:AAHDr3PXp-Ty2_zx-PLnHEGidpv6aK0Ai10 && export ADMIN_CHAT_ID=6063832614 && export GITHUB_REPOSITORY=Wtfthisman1/Bot && docker-compose up -d"; then
        success "Контейнер запущен"
    else
        error "Ошибка при запуске контейнера"
        return 1
    fi
    
    # Шаг 7: Проверка статуса
    log "🔍 Проверяем статус..."
    sleep 5
    if run_on_server "docker ps | grep telegram-bot"; then
        success "Контейнер работает!"
    else
        warning "Контейнер не найден в списке запущенных"
    fi
    
    # Шаг 8: Проверка health check
    log "🏥 Проверяем health check..."
    sleep 10
    if curl -s http://$SERVER_IP:8080/actuator/health | grep -q "UP"; then
        success "Health check пройден! Приложение работает"
    else
        warning "Health check не прошел. Проверьте логи"
    fi
    
    log "✅ Деплой завершен!"
}

# Функция просмотра логов
logs() {
    log "📋 Показываем логи..."
    run_on_server "docker logs telegram-bot --tail 50 -f"
}

# Функция проверки статуса
status() {
    log "📊 Статус приложения..."
    run_on_server "docker ps | grep telegram-bot"
    echo ""
    log "🏥 Health check:"
    curl -s http://$SERVER_IP:8080/actuator/health | jq . 2>/dev/null || curl -s http://$SERVER_IP:8080/actuator/health
}

# Функция остановки
stop() {
    log "⏹️ Останавливаем приложение..."
    run_on_server "cd /root/Bot && docker-compose down"
    success "Приложение остановлено"
}

# Функция запуска
start() {
    log "▶️ Запускаем приложение..."
    run_on_server "cd /root/Bot && export BOT_TOKEN=7393663223:AAHDr3PXp-Ty2_zx-PLnHEGidpv6aK0Ai10 && export ADMIN_CHAT_ID=6063832614 && export GITHUB_REPOSITORY=Wtfthisman1/Bot && docker-compose up -d"
    success "Приложение запущено"
}

# Функция перезапуска
restart() {
    log "🔄 Перезапускаем приложение..."
    stop
    sleep 2
    start
}

# Главная функция
main() {
    case "${1:-deploy}" in
        "deploy")
            deploy
            ;;
        "logs")
            logs
            ;;
        "status")
            status
            ;;
        "stop")
            stop
            ;;
        "start")
            start
            ;;
        "restart")
            restart
            ;;
        *)
            echo "Использование: $0 [команда]"
            echo ""
            echo "Команды:"
            echo "  deploy   - Полный деплой (сборка + развертывание)"
            echo "  logs     - Показать логи"
            echo "  status   - Проверить статус"
            echo "  stop     - Остановить приложение"
            echo "  start    - Запустить приложение"
            echo "  restart  - Перезапустить приложение"
            echo ""
            echo "По умолчанию выполняется deploy"
            ;;
    esac
}

main "$@"

