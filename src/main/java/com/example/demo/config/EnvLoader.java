package com.example.demo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

@Configuration
@Slf4j
public class EnvLoader {

    @PostConstruct
    public void loadEnvFile() {
        File envFile = new File(".env");
        if (envFile.exists()) {
            log.info("Загружаю переменные из .env файла");
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(envFile)) {
                props.load(fis);
                props.forEach((key, value) -> {
                    if (System.getProperty(key.toString()) == null) {
                        System.setProperty(key.toString(), value.toString());
                        log.info("Установлена переменная: {} = {}", key, 
                            key.toString().contains("TOKEN") ? "***" : value);
                    }
                });
                log.info("Загружено {} переменных из .env файла", props.size());
            } catch (IOException e) {
                log.error("Ошибка загрузки .env файла", e);
            }
        } else {
            log.warn(".env файл не найден. Используются переменные окружения или значения по умолчанию");
        }
    }
}
