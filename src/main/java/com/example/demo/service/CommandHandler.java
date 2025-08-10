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

    /**
     * Обрабатывает команду
     */
    public void handleCommand(long chatId, String text, String name) {
        switch (text) {
            case "/start"  -> sendWelcomeMessage(chatId, name);
            case "/help"   -> sendHelpMessage(chatId);
            case "/upload" -> uploadCommand(chatId);
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
     * Обрабатывает команду /status
     */
    private void statusCommand(long chatId) {
        // TODO: Реализовать получение статуса из JobQueue
        messageSender.sendMessage(chatId, "📊 Статус обработки:\n" +
            "⏳ Ожидает обработки: 0\n" +
            "✅ Завершено: 0\n\n" +
            "🎉 Все задачи завершены!");
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
