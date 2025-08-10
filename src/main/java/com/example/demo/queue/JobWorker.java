package com.example.demo.queue;


import com.example.demo.service.DownloaderExecutor;
import com.example.demo.service.MessageSender;
import com.example.demo.service.TranscribeExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobWorker implements Runnable {

    private final JobQueue queue;
    private final DownloaderExecutor downloader;
    private final TranscribeExecutor transcriber;
    private final MessageSender messageSender;

    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    void start() {
        worker = new Thread(this, "job-worker");
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        worker.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                ProcessingJob job = queue.take();   // блокируемся
                switch (job.state()) {
                    case NEW        -> download(job);
                    case DOWNLOADED -> transcribe(job);
                }
            } catch (InterruptedException ie) {
                if (!running) break;   // нормальный shutdown
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.error("Ошибка обработки задачи", ex);
            }
        }
        log.info("Job-worker завершён");
    }

    private void download(ProcessingJob job) throws Exception {
        Path file = downloader.download(job.chatId(), job.url());
        log.info("Скачано {}", file);
        queue.enqueue(job.withFile(file));   // put гарантированно
    }

    private void transcribe(ProcessingJob job) throws Exception {
        Path txt = transcriber.run(job.chatId(), job.filePath());
        log.info("Транскрипция готова {}", txt);
        messageSender.sendTranscript(job.chatId(), txt);
    }
}
