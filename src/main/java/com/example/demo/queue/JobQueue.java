package com.example.demo.queue;

import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class JobQueue {

    private final BlockingQueue<ProcessingJob> q = new LinkedBlockingQueue<>();

    public void enqueue(ProcessingJob job)               { q.offer(job); }
    public ProcessingJob take() throws InterruptedException { return q.take(); }
    public int size()                                     { return q.size(); }
}
