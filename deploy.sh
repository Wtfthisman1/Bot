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

# Конфигурация
IMAGE_NAME="ghcr.io/${GITHUB_REPOSITORY:-your-username/your-repo}"
COMPOSE_FILE="docker-compose.prod.yml"

# Проверка переменных окружения
check_env() {
    if [[ -z "${BOT_TOKEN:-}" ]]; then
        error "BOT_TOKEN не установлен"
        exit 1
    fi
    
    if [[ -z "${ADMIN_CHAT_ID:-}" ]]; then
        warning "ADMIN_CHAT_ID не установлен"
    fi
    
    if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
        warning "GITHUB_REPOSITORY не установлен, используется значение по умолчанию"
    fi
}

# Функция обновления образа
update_image() {
    local tag=${1:-latest}
    log "Обновление образа ${IMAGE_NAME}:${tag}..."
    
    if docker pull "${IMAGE_NAME}:${tag}"; then
        success "Образ обновлен успешно"
    else
        error "Не удалось обновить образ"
        exit 1
    fi
}

# Функция развертывания
deploy() {
    local tag=${1:-latest}
    
    log "Развертывание версии ${tag}..."
    
    # Останавливаем текущий контейнер
    log "Остановка текущего контейнера..."
    docker-compose -f "$COMPOSE_FILE" down || true
    
    # Обновляем образ
    update_image "$tag"
    
    # Запускаем новый контейнер
    log "Запуск нового контейнера..."
    IMAGE_TAG="$tag" docker-compose -f "$COMPOSE_FILE" up -d
    
    # Ждем готовности
    log "Ожидание готовности приложения..."
    sleep 30
    
    # Проверяем health
    if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
        success "Приложение успешно развернуто!"
        log "Health check: http://localhost:8080/actuator/health"
    else
        warning "Приложение может быть еще не готово. Проверьте логи:"
        log "docker-compose -f $COMPOSE_FILE logs -f bot"
    fi
}

# Функция отката
rollback() {
    local previous_tag=${1:-latest}
    log "Откат к версии ${previous_tag}..."
    deploy "$previous_tag"
}

# Функция логи
logs() {
    docker-compose -f "$COMPOSE_FILE" logs -f bot
}

# Функция статуса
status() {
    log "Статус контейнеров:"
    docker-compose -f "$COMPOSE_FILE" ps
    
    log "Использование ресурсов:"
    docker stats --no-stream || true
    
    log "Health check:"
    curl -s http://localhost:8080/actuator/health | jq . 2>/dev/null || curl -s http://localhost:8080/actuator/health
}

# Функция очистки
cleanup() {
    log "Очистка неиспользуемых образов..."
    docker image prune -f
    
    log "Очистка неиспользуемых томов..."
    docker volume prune -f
}

# Функция предзагрузки моделей
preload_models() {
    local models=${1:-"small,medium"}
    log "Предзагрузка моделей: $models"
    
    # Останавливаем если запущен
    docker-compose -f "$COMPOSE_FILE" down 2>/dev/null || true
    
    # Запускаем с предзагрузкой
    WHISPER_PRELOAD_MODELS="$models" docker-compose -f "$COMPOSE_FILE" up -d
    
    log "Ожидание завершения предзагрузки..."
    sleep 60
    
    # Проверяем логи
    if docker-compose -f "$COMPOSE_FILE" logs bot | grep -q "Предзагрузка моделей завершена успешно"; then
        success "Модели предзагружены успешно"
    else
        warning "Предзагрузка может быть еще в процессе. Проверьте логи:"
        log "docker-compose -f $COMPOSE_FILE logs bot"
    fi
}

# Главная функция
main() {
    case "${1:-help}" in
        "deploy")
            check_env
            deploy "${2:-latest}"
            ;;
        "rollback")
            check_env
            rollback "$2"
            ;;
        "update")
            check_env
            update_image "${2:-latest}"
            ;;
        "logs")
            logs
            ;;
        "status")
            status
            ;;
        "cleanup")
            cleanup
            ;;
        "preload")
            preload_models "$2"
            ;;
        "help"|*)
            echo "Использование: $0 {deploy|rollback|update|logs|status|cleanup|preload|help}"
            echo ""
            echo "Команды:"
            echo "  deploy [tag]   - Развернуть приложение (по умолчанию latest)"
            echo "  rollback [tag] - Откатиться к предыдущей версии"
            echo "  update [tag]   - Обновить образ без перезапуска"
            echo "  logs           - Показать логи"
            echo "  status         - Показать статус и метрики"
            echo "  cleanup        - Очистить неиспользуемые ресурсы"
            echo "  preload [models] - Предзагрузить модели (например: $0 preload small,medium)"
            echo "  help           - Показать эту справку"
            echo ""
            echo "Переменные окружения:"
            echo "  BOT_TOKEN          - Токен Telegram бота (обязательно)"
            echo "  ADMIN_CHAT_ID      - ID чата администратора"
            echo "  GITHUB_REPOSITORY  - GitHub репозиторий (например: username/repo)"
            echo ""
            echo "Примеры:"
            echo "  $0 deploy                    # Развернуть latest версию"
            echo "  $0 deploy v1.2.3             # Развернуть конкретную версию"
            echo "  $0 rollback v1.2.2           # Откатиться к версии"
            echo "  $0 preload small,medium      # Предзагрузить модели"
            echo "  $0 status                    # Проверить статус"
            ;;
    esac
}

main "$@"
