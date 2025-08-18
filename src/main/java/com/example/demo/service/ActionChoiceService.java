package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActionChoiceService {
    
    private final ApplicationContext applicationContext;
    private final DownloadService downloadService;
    private final MessageSender messageSender;
    
    // Хранилище ожидающих выбора действий пользователей
    private final Map<Long, PendingAction> pendingActions = new HashMap<>();
    
    /**
     * Обрабатывает ссылку и предлагает выбор действия
     */
    public void handleUrlWithChoice(long chatId, String url, String name) {
        // Сохраняем ожидающее действие
        pendingActions.put(chatId, new PendingAction(url, name));
        
        String message = """
            🔗 Получена ссылка: %s
            
            Что вы хотите сделать?
            
            🎯 <b>Выберите действие:</b>
            
            📝 <b>Транскрибировать</b> - получить текстовую расшифровку аудио/видео
            📥 <b>Скачать</b> - загрузить файл на сервер и получить ссылку для скачивания
            
            💡 <b>Рекомендации:</b>
            • Для получения текста из видео/аудио → Транскрибировать
            • Для сохранения файла → Скачать
            """.formatted(url);
        
        messageSender.sendMessage(chatId, message, "HTML");
    }
    
    /**
     * Обрабатывает выбор действия пользователя
     */
    public void handleActionChoice(long chatId, String choice) {
        PendingAction action = pendingActions.get(chatId);
        if (action == null) {
            messageSender.sendMessage(chatId, "❌ Не найдено ожидающее действие. Отправьте ссылку заново.");
            return;
        }
        
        // Удаляем ожидающее действие
        pendingActions.remove(chatId);
        
        switch (choice.toLowerCase()) {
            case "транскрибировать", "транскрипция", "текст", "расшифровка" -> {
                messageSender.sendMessage(chatId, "📝 Начинаю транскрибирование...");
                MessageHandler messageHandler = applicationContext.getBean(MessageHandler.class);
                messageHandler.handleUrls(chatId, java.util.List.of(action.url()), action.userName());
            }
            case "скачать", "загрузить", "файл", "download" -> {
                messageSender.sendMessage(chatId, "📥 Начинаю загрузку...");
                downloadService.createDownloadTask(chatId, action.url(), action.userName());
            }
            default -> {
                messageSender.sendMessage(chatId, 
                    "❌ Не понял ваш выбор. Пожалуйста, выберите:\n" +
                    "• <b>Транскрибировать</b> - для получения текста\n" +
                    "• <b>Скачать</b> - для загрузки файла", "HTML");
                
                // Возвращаем действие в ожидание
                pendingActions.put(chatId, action);
            }
        }
    }
    
    /**
     * Проверяет, есть ли ожидающее действие для пользователя
     */
    public boolean hasPendingAction(long chatId) {
        return pendingActions.containsKey(chatId);
    }
    
    /**
     * Очищает ожидающее действие пользователя
     */
    public void clearPendingAction(long chatId) {
        pendingActions.remove(chatId);
    }
    
    /**
     * Ожидающее действие пользователя
     */
    private record PendingAction(String url, String userName) {}
}
