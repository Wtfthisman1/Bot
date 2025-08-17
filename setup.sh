#!/bin/bash

# Скрипт для настройки Telegram Bot
echo "🤖 Настройка Telegram Bot"
echo "=========================="

# Проверяем, существует ли .env файл
if [ ! -f .env ]; then
    echo "📝 Создаю файл .env из шаблона..."
    cp env.example .env
    echo "✅ Файл .env создан!"
    echo ""
    echo "⚠️  ВАЖНО: Отредактируйте файл .env и добавьте свои данные:"
    echo "   - BOT_TOKEN=ваш_токен_бота"
    echo "   - ADMIN_CHAT_ID=ваш_id_администратора"
    echo ""
else
    echo "✅ Файл .env уже существует"
fi

# Проверяем, есть ли токен в application.properties
if grep -q "REDACTED_BOT_TOKEN" src/main/resources/application.properties; then
    echo "⚠️  ВНИМАНИЕ: В application.properties найден реальный токен!"
    echo "   Рекомендуется использовать переменные окружения"
    echo "   См. SECURITY_SETUP.md для подробностей"
fi

# Проверяем .gitignore
if grep -q "\.env" .gitignore; then
    echo "✅ .env файл добавлен в .gitignore"
else
    echo "⚠️  .env файл НЕ добавлен в .gitignore!"
    echo "   Добавьте '.env' в .gitignore для безопасности"
fi

echo ""
echo "🚀 Для запуска используйте:"
echo "   ./gradlew bootRun"
echo ""
echo "📚 Документация:"
echo "   - SECURITY_SETUP.md - настройка безопасности"
echo "   - NEW_FEATURES.md - новые функции"
echo "   - README.md - общая информация"
