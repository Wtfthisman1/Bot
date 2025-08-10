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

    /**
     * Обрабатывает текстовое сообщение
     */
    public void handleText(long chatId, String text, String name) {
        // Проверяем, содержит ли текст ссылки
        List<String> urls = extractUrls(text);
        
        if (!urls.isEmpty()) {
            handleUrls(chatId, urls, name);
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
                messageSender.sendMessage(chatId, "❌ Ошибка обработки голосового сообщения");
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
    private void handleUrls(long chatId, List<String> urls, String name) {
        if (urls.size() > 5) {
            messageSender.sendMessage(chatId, "⚠️ Максимум 5 ссылок за раз. Обработаю первые 5.");
            urls = urls.subList(0, 5);
        }
        
        messageSender.sendChatAction(chatId, "typing");
        messageSender.sendMessage(chatId, 
            String.format("🔗 Найдено %d ссылок. Начинаю обработку...", urls.size()));
        
        // Добавляем задачи в очередь
        for (String url : urls) {
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
}
