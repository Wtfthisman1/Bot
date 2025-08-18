# 🚀 Развертывание на сервере

## 📋 Пошаговая инструкция

### Шаг 1: Подготовка сервера

```bash
# Подключитесь к серверу
ssh user@your-server-ip

# Скачайте и запустите скрипт настройки
wget https://raw.githubusercontent.com/your-username/your-repo/main/server-setup.sh
chmod +x server-setup.sh
sudo ./server-setup.sh

# Перелогиньтесь для применения изменений группы docker
exit
ssh user@your-server-ip
```

### Шаг 2: Клонирование репозитория

```bash
# Клонируйте репозиторий
git clone https://github.com/your-username/your-repo.git
cd your-repo

# Переименуйте в Bot (если нужно)
mv your-repo Bot
cd Bot
```

### Шаг 3: Настройка переменных окружения

```bash
# Создайте .env файл
cp env.example .env
nano .env
```

**Содержимое .env файла:**
```bash
# Обязательные переменные
BOT_TOKEN=your_real_bot_token_here
ADMIN_CHAT_ID=your_real_admin_chat_id_here

# Настройки GitHub (для CI/CD)
GITHUB_REPOSITORY=your-username/your-repo

# Опционально: предзагрузка моделей
WHISPER_PRELOAD_MODELS=small,medium
WHISPER_DEVICE=cpu
WHISPER_COMPUTE_TYPE=int8

# Настройки приложения
UPLOAD_BASE_URL=http://your-server-ip:8080
```

### Шаг 4: Первый запуск

```bash
# Сделайте скрипт исполняемым
chmod +x deploy.sh

# Загрузите переменные окружения
source .env

# Первый деплой (скачает образ из registry)
./deploy.sh deploy
```

### Шаг 5: Проверка работы

```bash
# Проверьте статус
./deploy.sh status

# Проверьте логи
./deploy.sh logs

# Проверьте health check
curl http://localhost:8080/actuator/health
```

## 🔄 Ежедневное использование

### Обновление приложения

```bash
# Автоматическое обновление (через CI/CD)
# Просто делайте push в main ветку локально:
git push origin main

# Ручное обновление
./deploy.sh deploy

# Обновление конкретной версии
./deploy.sh deploy v1.2.3
```

### Управление приложением

```bash
# Просмотр логов
./deploy.sh logs

# Проверка статуса
./deploy.sh status

# Остановка
docker-compose -f docker-compose.prod.yml down

# Запуск
docker-compose -f docker-compose.prod.yml up -d

# Перезапуск
./deploy.sh deploy
```

### Предзагрузка моделей

```bash
# Предзагрузить модели (только при первом запуске)
./deploy.sh preload small,medium

# Или через переменные окружения
WHISPER_PRELOAD_MODELS="small,medium" ./deploy.sh deploy
```

## 🛠️ Устранение неполадок

### Проверка логов

```bash
# Логи приложения
./deploy.sh logs

# Логи Docker
docker-compose -f docker-compose.prod.yml logs -f bot

# Логи системы
journalctl -u docker.service -f
```

### Проверка ресурсов

```bash
# Использование диска
df -h

# Использование памяти
free -h

# Использование CPU
htop

# Docker ресурсы
docker stats
```

### Перезапуск при проблемах

```bash
# Полный перезапуск
./deploy.sh deploy

# Откат к предыдущей версии
./deploy.sh rollback

# Очистка ресурсов
./deploy.sh cleanup
```

## 📊 Мониторинг

### Health Check

```bash
# Проверка здоровья приложения
curl http://localhost:8080/actuator/health

# Детальная информация
curl http://localhost:8080/actuator/health | jq .
```

### Метрики

```bash
# Основные метрики
curl http://localhost:8080/actuator/metrics

# Информация о приложении
curl http://localhost:8080/actuator/info
```

## 🔒 Безопасность

### Firewall

```bash
# Проверка статуса firewall
sudo ufw status

# Открыть дополнительные порты (если нужно)
sudo ufw allow 3000/tcp
```

### Обновления

```bash
# Обновление системы
sudo apt update && sudo apt upgrade -y

# Обновление Docker
sudo apt update
sudo apt install docker-ce docker-ce-cli containerd.io
```

## 📁 Структура на сервере

```
/opt/telegram-bot/
├── upload/          # Загруженные файлы
├── logs/            # Логи приложения
└── .cache/          # Кеш моделей Whisper

~/Bot/
├── docker-compose.prod.yml  # Production конфигурация
├── deploy.sh                # Скрипт развертывания
├── .env                     # Переменные окружения
└── .git/                    # Git репозиторий
```

## 🚨 Важные команды

```bash
# Экстренная остановка
docker-compose -f docker-compose.prod.yml down

# Принудительная очистка
docker system prune -a -f

# Проверка свободного места
df -h /opt/telegram-bot

# Очистка старых логов
find /opt/telegram-bot/logs -name "*.log" -mtime +30 -delete
```

## 📞 Поддержка

При возникновении проблем:

1. Проверьте логи: `./deploy.sh logs`
2. Проверьте статус: `./deploy.sh status`
3. Проверьте ресурсы: `htop`, `df -h`
4. Проверьте Docker: `docker ps`, `docker stats`
5. Создайте issue в GitHub с логами
