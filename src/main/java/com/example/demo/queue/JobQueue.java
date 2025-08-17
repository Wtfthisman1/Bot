package com.example.demo.queue;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

@Component
public class JobQueue {

    private final BlockingQueue<ProcessingJob> q = new LinkedBlockingQueue<>();

    public void enqueue(ProcessingJob job)               { q.offer(job); }
    public ProcessingJob take() throws InterruptedException { return q.take(); }
    public int size()                                     { return q.size(); }
    
    /**
     * Получает все задачи пользователя
     */
    public List<ProcessingJob> getUserJobs(long chatId) {
        return q.stream()
                .filter(job -> job.chatId() == chatId)
                .collect(Collectors.toList());
    }
}
