#!/usr/bin/env bash
set -euo pipefail

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

log "Настройка сервера для Telegram Bot..."

# Проверяем, что мы root или используем sudo
if [[ $EUID -eq 0 ]]; then
    log "Запуск от root пользователя"
else
    error "Запустите скрипт с sudo: sudo $0"
    exit 1
fi

# Обновляем систему
log "Обновление системы..."
apt-get update
apt-get upgrade -y

# Устанавливаем необходимые пакеты
log "Установка необходимых пакетов..."
apt-get install -y \
    curl \
    wget \
    git \
    unzip \
    jq \
    htop \
    nano \
    ufw

# Устанавливаем Docker
log "Установка Docker..."
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com -o get-docker.sh
    sh get-docker.sh
    rm get-docker.sh
    success "Docker установлен"
else
    log "Docker уже установлен"
fi

# Устанавливаем Docker Compose
log "Установка Docker Compose..."
if ! command -v docker-compose &> /dev/null; then
    curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    chmod +x /usr/local/bin/docker-compose
    success "Docker Compose установлен"
else
    log "Docker Compose уже установлен"
fi

# Добавляем пользователя в группу docker
log "Настройка прав пользователя..."
if [[ -n "${SUDO_USER:-}" ]]; then
    usermod -aG docker "$SUDO_USER"
    log "Пользователь $SUDO_USER добавлен в группу docker"
fi

# Настраиваем firewall
log "Настройка firewall..."
ufw --force enable
ufw allow ssh
ufw allow 8080/tcp
ufw allow 80/tcp
ufw allow 443/tcp
success "Firewall настроен"

# Создаем директории для приложения
log "Создание директорий..."
mkdir -p /opt/telegram-bot/{upload,logs,.cache}
chown -R "$SUDO_USER:$SUDO_USER" /opt/telegram-bot

# Настраиваем systemd для автозапуска Docker
log "Настройка автозапуска Docker..."
systemctl enable docker
systemctl start docker

success "Настройка сервера завершена!"
log "Перезагрузите систему или перелогиньтесь для применения изменений группы docker"
log "Затем перейдите к настройке приложения"
