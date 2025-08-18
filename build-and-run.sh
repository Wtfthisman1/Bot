#!/usr/bin/env bash
set -euo pipefail

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

# Проверка переменных окружения
check_env() {
    if [[ -z "${BOT_TOKEN:-}" ]]; then
        error "BOT_TOKEN не установлен. Создайте .env файл или установите переменную"
        exit 1
    fi
    
    if [[ -z "${ADMIN_CHAT_ID:-}" ]]; then
        warning "ADMIN_CHAT_ID не установлен"
    fi
}

# Функция сборки
build() {
    log "Сборка Docker образа..."
    docker-compose build --no-cache
    success "Образ собран успешно"
}

# Функция запуска
run() {
    log "Запуск контейнера..."
    docker-compose up -d
    success "Контейнер запущен"
    
    log "Ожидание готовности приложения..."
    sleep 10
    
    # Проверка health check
    if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
        success "Приложение готово к работе!"
        log "Health check: http://localhost:8080/actuator/health"
        log "Логи: docker-compose logs -f bot"
    else
        warning "Приложение еще не готово. Проверьте логи: docker-compose logs bot"
    fi
}

# Функция остановки
stop() {
    log "Остановка контейнера..."
    docker-compose down
    success "Контейнер остановлен"
}

# Функция очистки
clean() {
    log "Очистка Docker ресурсов..."
    docker-compose down -v --remove-orphans
    docker system prune -f
    success "Очистка завершена"
}

# Функция логи
logs() {
    docker-compose logs -f bot
}

# Функция перезапуска
restart() {
    stop
    run
}

# Функция предзагрузки моделей
preload_models() {
    local models=${1:-"small,medium"}
    log "Предзагрузка моделей: $models"
    
    # Останавливаем если запущен
    docker-compose down 2>/dev/null || true
    
    # Запускаем с предзагрузкой
    WHISPER_PRELOAD_MODELS="$models" docker-compose up -d
    
    log "Ожидание завершения предзагрузки..."
    sleep 30
    
    # Проверяем логи
    if docker-compose logs bot | grep -q "Предзагрузка моделей завершена успешно"; then
        success "Модели предзагружены успешно"
    else
        warning "Предзагрузка может быть еще в процессе. Проверьте логи: docker-compose logs bot"
    fi
}

# Главная функция
main() {
    case "${1:-help}" in
        "build")
            check_env
            build
            ;;
        "run")
            check_env
            run
            ;;
        "stop")
            stop
            ;;
        "restart")
            check_env
            restart
            ;;
        "clean")
            clean
            ;;
        "logs")
            logs
            ;;
        "preload")
            preload_models "$2"
            ;;
        "full")
            check_env
            build
            run
            ;;
        "help"|*)
            echo "Использование: $0 {build|run|stop|restart|clean|logs|preload|full}"
            echo ""
            echo "Команды:"
            echo "  build     - Собрать Docker образ"
            echo "  run       - Запустить контейнер"
            echo "  stop      - Остановить контейнер"
            echo "  restart   - Перезапустить контейнер"
            echo "  clean     - Очистить Docker ресурсы"
            echo "  logs      - Показать логи"
            echo "  preload   - Предзагрузить модели (например: $0 preload small,medium)"
            echo "  full      - Полная сборка и запуск"
            echo "  help      - Показать эту справку"
            echo ""
            echo "Переменные окружения:"
            echo "  BOT_TOKEN     - Токен Telegram бота (обязательно)"
            echo "  ADMIN_CHAT_ID - ID чата администратора"
            echo ""
            echo "Примеры:"
            echo "  $0 full                    # Полная сборка и запуск"
            echo "  $0 preload small,medium    # Предзагрузить модели"
            echo "  $0 logs                    # Показать логи"
            ;;
    esac
}

main "$@"
