package com.example.demo.service;

import com.example.demo.queue.JobQueue;
import com.example.demo.queue.ProcessingJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Audio;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.Voice;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageHandler {

    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[\\w\\d\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private final TaskExecutor taskExecutor;
    private final TranscribeExecutor transcriber;
    private final TelegramFileDownloader fileDownloader;
    private final JobQueue jobQueue;
    private final MessageSender messageSender;
    private final ActionChoiceService actionChoiceService;

    /**
     * Обрабатывает текстовое сообщение
     */
    public void handleText(long chatId, String text, String name) {
        // Проверяем, есть ли ожидающее действие
        if (actionChoiceService.hasPendingAction(chatId)) {
            actionChoiceService.handleActionChoice(chatId, text);
            return;
        }
        
        // Проверяем, содержит ли текст ссылки
        List<String> urls = extractUrls(text);
        
        if (!urls.isEmpty()) {
            // Если только одна ссылка, предлагаем выбор действия
            if (urls.size() == 1) {
                actionChoiceService.handleUrlWithChoice(chatId, urls.get(0), name);
            } else {
                // Если несколько ссылок, обрабатываем как раньше (только транскрибирование)
                handleUrls(chatId, urls, name);
            }
        } else {
            messageSender.sendMessage(chatId, 
                "💡 Отправьте мне:\n" +
                "• Голосовое сообщение для транскрипции\n" +
                "• Аудио или видео файл\n" +
                "• Ссылку на YouTube/Vimeo/TikTok\n" +
                "• Или используйте /upload для загрузки нескольких файлов");
        }
    }

    /**
     * Обрабатывает голосовое сообщение
     */
    public void handleVoice(long chatId, Voice voice, String name) {
        messageSender.sendChatAction(chatId, "typing");
        messageSender.sendMessage(chatId, "🎤 Получено голосовое сообщение. Обрабатываю...");
        
        taskExecutor.execute(() -> {
            try {
                Path voiceFile = fileDownloader.downloadVoice(voice.getFileId(), chatId);
                Path transcript = transcriber.run(chatId, voiceFile);
                messageSender.sendTranscript(chatId, transcript);
            } catch (Exception e) {
                log.error("Ошибка обработки голосового сообщения", e);
                
                // Формируем понятное сообщение об ошибке
                String errorMessage = getVoiceErrorMessage(e);
                messageSender.sendMessage(chatId, errorMessage);
            }
        });
    }

    /**
     * Обрабатывает аудио файл
     */
    public void handleAudio(long chatId, Audio audio, String name) {
        messageSender.sendChatAction(chatId, "typing");
        messageSender.sendMessage(chatId, "🎵 Получен аудио файл. Обрабатываю...");
        
        taskExecutor.execute(() -> {
            try {
                Path audioFile = fileDownloader.downloadAudio(audio.getFileId(), chatId, audio.getFileName() != null ? audio.getFileName() : "audio.mp3");
                Path transcript = transcriber.run(chatId, audioFile);
                messageSender.sendTranscript(chatId, transcript);
            } catch (Exception e) {
                log.error("Ошибка обработки аудио файла", e);
                messageSender.sendMessage(chatId, "❌ Ошибка обработки аудио файла");
            }
        });
    }

    /**
     * Обрабатывает видео файл
     */
    public void handleVideo(long chatId, Video video, String name) {
        messageSender.sendChatAction(chatId, "typing");
        messageSender.sendMessage(chatId, "🎬 Получен видео файл. Обрабатываю...");
        
        taskExecutor.execute(() -> {
            try {
                Path videoFile = fileDownloader.downloadVideo(video.getFileId(), chatId, video.getFileName() != null ? video.getFileName() : "video.mp4");
                Path transcript = transcriber.run(chatId, videoFile);
                messageSender.sendTranscript(chatId, transcript);
            } catch (Exception e) {
                log.error("Ошибка обработки видео файла", e);
                messageSender.sendMessage(chatId, "❌ Ошибка обработки видео файла");
            }
        });
    }

    /**
     * Обрабатывает документ
     */
    public void handleDocument(long chatId, Document document, String name) {
        String fileName = document.getFileName();
        if (fileName != null && isVideoFile(fileName)) {
            messageSender.sendChatAction(chatId, "typing");
            messageSender.sendMessage(chatId, "📄 Получен видео документ. Обрабатываю...");
            
            taskExecutor.execute(() -> {
                try {
                    Path docFile = fileDownloader.downloadDocument(document.getFileId(), chatId, fileName);
                    Path transcript = transcriber.run(chatId, docFile);
                    messageSender.sendTranscript(chatId, transcript);
                } catch (Exception e) {
                    log.error("Ошибка обработки документа", e);
                    messageSender.sendMessage(chatId, "❌ Ошибка обработки документа");
                }
            });
        } else {
            messageSender.sendMessage(chatId, "❌ Поддерживаются только видео файлы. Отправьте .mp4, .avi, .mkv");
        }
    }

    /**
     * Обрабатывает ссылки
     */
    public void handleUrls(long chatId, List<String> urls, String name) {
        if (urls.size() > 5) {
            messageSender.sendMessage(chatId, "⚠️ Максимум 5 ссылок за раз. Обработаю первые 5.");
            urls = urls.subList(0, 5);
        }
        
        // Фильтруем поддерживаемые ссылки
        List<String> supportedUrls = new ArrayList<>();
        List<String> unsupportedUrls = new ArrayList<>();
        
        for (String url : urls) {
            if (isSupportedVideoUrl(url)) {
                supportedUrls.add(url);
            } else {
                unsupportedUrls.add(url);
            }
        }
        
        // Отправляем предупреждение о неподдерживаемых ссылках
        if (!unsupportedUrls.isEmpty()) {
            StringBuilder warning = new StringBuilder("⚠️ Неподдерживаемые ссылки:\n");
            for (String url : unsupportedUrls) {
                warning.append("• ").append(url).append("\n");
            }
            warning.append("\n🔗 Поддерживаются только видео с YouTube, Vimeo, TikTok, Instagram, Twitter/X, Facebook");
            messageSender.sendMessage(chatId, warning.toString());
        }
        
        if (supportedUrls.isEmpty()) {
            messageSender.sendMessage(chatId, "❌ Нет поддерживаемых ссылок для обработки");
            return;
        }
        
        messageSender.sendChatAction(chatId, "typing");
        messageSender.sendMessage(chatId, 
            String.format("🔗 Найдено %d поддерживаемых ссылок. Начинаю обработку...", supportedUrls.size()));
        
        // Добавляем задачи в очередь только для поддерживаемых ссылок
        for (String url : supportedUrls) {
            jobQueue.enqueue(ProcessingJob.newLink(chatId, url));
        }
    }

    /**
     * Извлекает URL из текста
     */
    private List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    /**
     * Проверяет, является ли файл видео
     */
    private boolean isVideoFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".avi") || 
               lower.endsWith(".mkv") || lower.endsWith(".mov") || 
               lower.endsWith(".webm");
    }

    /**
     * Проверяет, является ли URL поддерживаемым видео-хостингом
     */
    private boolean isSupportedVideoUrl(String url) {
        if (url == null) return false;
        
        String lowerUrl = url.toLowerCase();
        
        // Поддерживаемые платформы
        return lowerUrl.contains("youtube.com") || 
               lowerUrl.contains("youtu.be") ||
               lowerUrl.contains("vimeo.com") ||
               lowerUrl.contains("tiktok.com") ||
               lowerUrl.contains("instagram.com") ||
               lowerUrl.contains("twitter.com") ||
               lowerUrl.contains("x.com") ||
               lowerUrl.contains("facebook.com") ||
               lowerUrl.contains("fb.com");
    }

    /**
     * Формирует понятное сообщение об ошибке для голосовых сообщений
     */
    private String getVoiceErrorMessage(Exception e) {
        String errorText = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        if (errorText.contains("не смог распознать речь") || errorText.contains("empty transcription")) {
            return """
                🎤 Не удалось распознать речь в голосовом сообщении
                
                Возможные причины:
                • Слишком короткое сообщение (менее 3 секунд)
                • Отсутствует речь в сообщении
                • Плохое качество записи
                • Неподдерживаемый язык
                
                💡 Попробуйте:
                • Записать более длинное сообщение
                • Говорить четче и громче
                • Использовать русский или английский язык
                """;
        }
        
        if (errorText.contains("timeout")) {
            return """
                ⏰ Превышено время обработки голосового сообщения
                
                Возможные причины:
                • Слишком длинное сообщение
                • Высокая нагрузка на сервер
                
                💡 Попробуйте:
                • Отправить более короткое сообщение
                • Подождать и попробовать снова
                """;
        }
        
        if (errorText.contains("model") || errorText.contains("whisper")) {
            return """
                🤖 Ошибка системы распознавания речи
                
                Техническая проблема с Whisper.
                Попробуйте позже или обратитесь к администратору.
                """;
        }
        
        // Общая ошибка
        return """
            ❌ Ошибка обработки голосового сообщения
            
            Попробуйте:
            • Записать сообщение заново
            • Проверить качество записи
            • Использовать другой язык
            """;
    }
}
