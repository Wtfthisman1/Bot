# Telegram Bot with Whisper Transcription

Telegram бот для загрузки видео и их транскрипции с помощью Whisper AI.

## 🚀 Быстрый старт

### Локальная разработка

1. **Клонируйте репозиторий:**
```bash
git clone <your-repo-url>
cd Bot
```

2. **Создайте .env файл:**
```bash
cp env.example .env
# Отредактируйте .env с вашими настройками
```

3. **Соберите JAR локально:**
```bash
./gradlew build
```

4. **Запустите с помощью скрипта:**
```bash
./build-and-run.sh full
```

### Развертывание на сервере

#### Подготовка сервера

1. **Установите Docker и Docker Compose:**
```bash
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
```

2. **Клонируйте репозиторий на сервер:**
```bash
git clone <your-repo-url>
cd Bot
```

3. **Создайте .env файл на сервере:**
```bash
cp env.example .env
# Добавьте BOT_TOKEN, ADMIN_CHAT_ID и другие переменные
```

#### Настройка CI/CD

1. **Добавьте GitHub Secrets:**
   - `SERVER_HOST` - IP адрес сервера
   - `SERVER_USER` - пользователь для SSH
   - `SERVER_SSH_KEY` - приватный SSH ключ

2. **Настройте переменные окружения на сервере:**
```bash
export BOT_TOKEN="your-bot-token"
export ADMIN_CHAT_ID="your-admin-chat-id"
export GITHUB_REPOSITORY="your-username/your-repo"
```

3. **Первый деплой:**
```bash
./deploy.sh deploy
```

## 📋 Использование

### Локальная разработка

```bash
# Полная сборка и запуск
./build-and-run.sh full

# Только сборка
./build-and-run.sh build

# Только запуск
./build-and-run.sh run

# Предзагрузка моделей
./build-and-run.sh preload small,medium

# Просмотр логов
./build-and-run.sh logs

# Остановка
./build-and-run.sh stop
```

### Серверное развертывание

```bash
# Развернуть latest версию
./deploy.sh deploy

# Развернуть конкретную версию
./deploy.sh deploy v1.2.3

# Откатиться к предыдущей версии
./deploy.sh rollback v1.2.2

# Обновить образ без перезапуска
./deploy.sh update

# Предзагрузить модели
./deploy.sh preload small,medium

# Проверить статус
./deploy.sh status

# Просмотр логов
./deploy.sh logs
```

## 🔧 Конфигурация

### Переменные окружения

| Переменная | Описание | Обязательно |
|------------|----------|-------------|
| `BOT_TOKEN` | Токен Telegram бота | Да |
| `ADMIN_CHAT_ID` | ID чата администратора | Нет |
| `WHISPER_PRELOAD_MODELS` | Модели для предзагрузки | Нет |
| `WHISPER_DEVICE` | Устройство для Whisper (cpu/cuda) | Нет |
| `WHISPER_COMPUTE_TYPE` | Тип вычислений (int8/float16) | Нет |

### Модели Whisper

Доступные модели: `tiny`, `base`, `small`, `medium`, `large-v1`, `large-v2`, `large-v3`

Пример предзагрузки:
```bash
WHISPER_PRELOAD_MODELS="small,medium" ./deploy.sh deploy
```

## 📊 Мониторинг

### Health Check
```bash
curl http://localhost:8080/actuator/health
```

### Метрики
```bash
curl http://localhost:8080/actuator/metrics
```

### Логи
```bash
# Локально
./build-and-run.sh logs

# На сервере
./deploy.sh logs
```

## 🐳 Docker

### Локальная сборка
```bash
docker-compose build
docker-compose up -d
```

### Production
```bash
docker-compose -f docker-compose.prod.yml up -d
```

## 🔄 CI/CD Workflow

1. **Push в main/master** → автоматический деплой
2. **GitHub Actions** собирает JAR и Docker образ
3. **Образ пушится** в GitHub Container Registry
4. **Сервер** автоматически обновляется

### Ручной деплой
```bash
# В GitHub: Actions → Deploy to Server → Run workflow
```

## 📁 Структура проекта

```
Bot/
├── src/main/java/          # Java код
├── src/main/resources/     # Конфигурация и Python скрипты
├── build.gradle           # Gradle конфигурация
├── Dockerfile             # Docker образ
├── docker-compose.yml     # Локальная разработка
├── docker-compose.prod.yml # Production
├── build-and-run.sh       # Локальные скрипты
├── deploy.sh              # Серверные скрипты
└── .github/workflows/     # CI/CD
```

## 🛠️ Разработка

### Добавление новых функций

1. Разработайте локально с `./build-and-run.sh`
2. Протестируйте изменения
3. Commit и push в main
4. Автоматический деплой на сервер

### Отладка

```bash
# Локально
./build-and-run.sh logs

# На сервере
./deploy.sh logs
docker-compose -f docker-compose.prod.yml logs -f bot
```

## 🔒 Безопасность

- Все секреты хранятся в GitHub Secrets
- Переменные окружения не коммитятся в репозиторий
- Health checks для мониторинга
- Автоматические откаты при ошибках

## 📈 Производительность

- Многоступенчатая сборка Docker
- Кеширование моделей Whisper
- Оптимизированные JVM настройки
- Мониторинг ресурсов

## 🤝 Поддержка

При возникновении проблем:

1. Проверьте логи: `./deploy.sh logs`
2. Проверьте статус: `./deploy.sh status`
3. Проверьте health check: `curl http://localhost:8080/actuator/health`
4. Создайте issue в GitHub
