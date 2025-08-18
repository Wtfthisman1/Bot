#!/usr/bin/env bash
set -euo pipefail

# Конфигурация для вашего сервера
SERVER_IP="91.184.242.68"
SERVER_USER="root"
SERVER_PASS="sksaPObCUT4b"

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
    echo -e "${BLUE}[$(date '+%H:%M:%S')]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Функция для выполнения команд на сервере
run_on_server() {
    local command="$1"
    log "Выполняю на сервере: $command"
    
    if sshpass -p "$SERVER_PASS" ssh -o StrictHostKeyChecking=no "$SERVER_USER@$SERVER_IP" "$command"; then
        success "Команда выполнена успешно"
    else
        error "Ошибка выполнения команды"
        return 1
    fi
}

# Функция для копирования файлов на сервер
copy_to_server() {
    local local_file="$1"
    local remote_path="$2"
    log "Копирую $local_file на сервер..."
    
    if sshpass -p "$SERVER_PASS" scp -o StrictHostKeyChecking=no "$local_file" "$SERVER_USER@$SERVER_IP:$remote_path"; then
        success "Файл скопирован успешно"
    else
        error "Ошибка копирования файла"
        return 1
    fi
}

# Проверка зависимостей
check_dependencies() {
    if ! command -v sshpass &> /dev/null; then
        error "sshpass не установлен. Установите: sudo apt install sshpass"
        exit 1
    fi
}

# Основная функция развертывания
deploy() {
    log "Начинаю развертывание на сервер $SERVER_IP..."
    
    # Проверяем зависимости
    check_dependencies
    
    # 1. Настройка сервера
    log "Шаг 1: Настройка сервера..."
    run_on_server "wget -O server-setup.sh https://raw.githubusercontent.com/Wtfthisman1/Bot/main/server-setup.sh"
    run_on_server "chmod +x server-setup.sh"
    run_on_server "./server-setup.sh"
    
    # 2. Клонирование репозитория
    log "Шаг 2: Клонирование репозитория..."
    run_on_server "cd /root && git clone https://github.com/Wtfthisman1/Bot.git Bot"
    run_on_server "cd /root/Bot"
    
    # 3. Создание .env файла
    log "Шаг 3: Создание .env файла..."
    run_on_server "cd /root/Bot && cp env.example .env"
    
    # 4. Копирование локального .env на сервер (если есть)
    if [[ -f ".env" ]]; then
        log "Копирую локальный .env на сервер..."
        copy_to_server ".env" "/root/Bot/.env"
    else
        warning "Локальный .env не найден. Настройте переменные вручную на сервере"
    fi
    
    # 5. Первый запуск
    log "Шаг 4: Первый запуск приложения..."
    run_on_server "cd /root/Bot && chmod +x deploy.sh"
    run_on_server "cd /root/Bot && source .env && ./deploy.sh deploy"
    
    # 6. Проверка
    log "Шаг 5: Проверка работы..."
    sleep 30
    run_on_server "cd /root/Bot && ./deploy.sh status"
    
    success "Развертывание завершено!"
    log "Проверьте работу: http://$SERVER_IP:8080/actuator/health"
    log "Логи: ssh $SERVER_USER@$SERVER_IP 'cd /root/Bot && ./deploy.sh logs'"
}

# Функция обновления
update() {
    log "Обновление приложения..."
    run_on_server "cd /root/Bot && git pull origin main"
    run_on_server "cd /root/Bot && ./deploy.sh deploy"
}

# Функция проверки статуса
status() {
    log "Проверка статуса..."
    run_on_server "cd /root/Bot && ./deploy.sh status"
}

# Функция просмотра логов
logs() {
    log "Просмотр логов..."
    run_on_server "cd /root/Bot && ./deploy.sh logs"
}

# Функция предзагрузки моделей
preload() {
    local models=${1:-"small,medium"}
    log "Предзагрузка моделей: $models"
    run_on_server "cd /root/Bot && ./deploy.sh preload $models"
}

# Главная функция
main() {
    case "${1:-help}" in
        "deploy")
            deploy
            ;;
        "update")
            update
            ;;
        "status")
            status
            ;;
        "logs")
            logs
            ;;
        "preload")
            preload "$2"
            ;;
        "help"|*)
            echo "Использование: $0 {deploy|update|status|logs|preload|help}"
            echo ""
            echo "Команды:"
            echo "  deploy   - Полное развертывание на сервер"
            echo "  update   - Обновление приложения"
            echo "  status   - Проверка статуса"
            echo "  logs     - Просмотр логов"
            echo "  preload  - Предзагрузка моделей (например: $0 preload small,medium)"
            echo "  help     - Показать эту справку"
            echo ""
            echo "Примеры:"
            echo "  $0 deploy                    # Полное развертывание"
            echo "  $0 update                    # Обновление"
            echo "  $0 preload small,medium      # Предзагрузка моделей"
            echo "  $0 status                    # Проверка статуса"
            ;;
    esac
}

main "$@"
