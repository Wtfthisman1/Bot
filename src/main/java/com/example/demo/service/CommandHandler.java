package com.example.demo.service;

import com.example.demo.upload.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandHandler {

    private final UploadService uploadService;
    private final MessageSender messageSender;
    private final DownloadService downloadService;
    private final StatusService statusService;

    /**
     * Обрабатывает команду
     */
    public void handleCommand(long chatId, String text, String name) {
        switch (text) {
            case "/start"  -> sendWelcomeMessage(chatId, name);
            case "/help"   -> sendHelpMessage(chatId);
            case "/upload" -> uploadCommand(chatId);
            case "/download" -> downloadCommand(chatId);
            case "/status" -> statusCommand(chatId);
            case "/transcripts" -> transcriptsCommand(chatId);
            default        -> messageSender.sendMessage(chatId, "Не понимаю 🤔 Используйте /help для справки");
        }
    }

    /**
     * Отправляет приветственное сообщение
     */
    private void sendWelcomeMessage(long chatId, String name) {
        String welcome = """
            👋 Привет, %s!
            
            🎯 Я помогу вам транскрибировать аудио и видео файлы.
            
            📤 Что вы можете отправить:
            • Голосовые сообщения
            • Аудио файлы (.mp3, .wav, .m4a)
            • Видео файлы (.mp4, .avi, .mkv)
            • Ссылки на YouTube, Vimeo, TikTok
            
            Команды:
            /help - Справка
            /upload - Загрузить несколько файлов
            /download - Скачать видео/аудио по ссылке
            /status - Статус обработки
            /transcripts - Мои транскрипции
            
            💡 Просто отправьте мне файл или ссылку!
            """.formatted(name);
        
        messageSender.sendMessage(chatId, welcome);
    }

    /**
     * Отправляет справку
     */
    private void sendHelpMessage(long chatId) {
        String help = """
            📚 <b>Справка по использованию бота</b>
            
            🎤 <b>Голосовые сообщения:</b>
            Просто запишите голосовое сообщение и отправьте
            
            🎵 <b>Аудио файлы:</b>
            Отправьте .mp3, .wav, .m4a файлы
            
            🎬 <b>Видео файлы:</b>
            Отправьте .mp4, .avi, .mkv файлы (до 50 МБ)
            
            🔗 <b>Ссылки:</b>
            Отправьте ссылку на YouTube, Vimeo, TikTok
            
            📤 <b>Множественная загрузка:</b>
            /upload - для загрузки до 5 файлов одновременно
            
            ⚙️ <b>Дополнительные команды:</b>
            /download - скачать видео/аудио по ссылке
            /status - проверить статус обработки
            /transcripts - посмотреть все транскрипции
            
            ⏱️ <b>Время обработки:</b>
            • Голосовые: 1-3 минуты
            • Аудио: 2-5 минут  
            • Видео: 5-15 минут
            • Ссылки: 10-30 минут
            
            💡 <b>Совет:</b> Для длинных файлов используйте /upload
            """;
        
        messageSender.sendMessage(chatId, help, "HTML");
    }

    /**
     * Обрабатывает команду /upload
     */
    private void uploadCommand(long chatId) {
        messageSender.sendChatAction(chatId, "typing");

        String link = uploadService.generate(chatId);
        String html = """
                Вы можете загрузить до <b>5</b> файлов или ссылок.\n
                Ссылка действительна 1 час и только один раз.\n\n
                <a href=\"%s\">Перейти к форме загрузки</a>
                """.formatted(link);

        messageSender.sendMessage(chatId, html.strip(), "HTML");
    }

    /**
     * Обрабатывает команду /download
     */
    private void downloadCommand(long chatId) {
        String message = """
            📥 <b>Загрузка видео/аудио</b>
            
            Отправьте мне ссылку на видео или аудио, и я загружу его на сервер.
            
            🔗 <b>Поддерживаемые сервисы:</b>
            • YouTube, Vimeo, TikTok
            • Instagram, Facebook
            • Twitter, Reddit
            • И многие другие
            
            💡 <b>Как использовать:</b>
            1. Отправьте ссылку на видео/аудио
            2. Выберите действие: транскрибировать или скачать
            3. Получите результат
            
            ⏱️ <b>Время загрузки:</b> 5-30 минут в зависимости от размера
            """;
        
        messageSender.sendMessage(chatId, message, "HTML");
    }

    /**
     * Обрабатывает команду /status
     */
    private void statusCommand(long chatId) {
        try {
            StatusService.UserStatus status = statusService.getUserStatus(chatId);
            
            StringBuilder message = new StringBuilder();
            message.append("📊 <b>Статус обработки:</b>\n\n");
            
            if (status.totalTasks() == 0 && status.activeDownloads() == 0) {
                message.append("🎉 Нет активных задач!\n\n");
                message.append("💡 Отправьте файл или ссылку для начала работы.");
            } else {
                message.append("📋 <b>Задачи в очереди:</b>\n");
                message.append("⏳ Ожидает загрузки: ").append(status.pendingTasks()).append("\n");
                message.append("🔄 Ожидает транскрипции: ").append(status.processingTasks()).append("\n");
                message.append("📥 Активные загрузки: ").append(status.activeDownloads()).append("\n");
                message.append("📊 Всего задач: ").append(status.totalTasks()).append("\n\n");
                
                if (status.activeDownloads() > 0) {
                    message.append("🔗 <b>Активные загрузки:</b>\n");
                    for (StatusService.DownloadInfo download : status.downloads()) {
                        long duration = (System.currentTimeMillis() - download.startTime()) / 1000 / 60; // минуты
                        message.append("• ").append(download.url()).append(" (").append(duration).append(" мин)\n");
                    }
                    message.append("\n");
                }
                
                message.append("⏱️ Задачи обрабатываются в фоновом режиме.\n");
                message.append("Вы получите уведомление по завершении.");
            }
            
            messageSender.sendMessage(chatId, message.toString(), "HTML");
            
        } catch (Exception e) {
            log.error("Ошибка получения статуса для пользователя: {}", chatId, e);
            messageSender.sendMessage(chatId, "❌ Ошибка получения статуса. Попробуйте позже.");
        }
    }

    /**
     * Обрабатывает команду /transcripts
     */
    private void transcriptsCommand(long chatId) {
        // TODO: Реализовать получение списка транскрипций
        messageSender.sendMessage(chatId, "📝 Функция просмотра транскрипций в разработке.\n" +
            "Пока что транскрипции отправляются сразу после обработки.");
    }
}
