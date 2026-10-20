package edu.itc.se.lab01.task3;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class Consumer implements Runnable {
    private final OrderQueue queue;
    private final AtomicInteger processed;

    public Consumer(OrderQueue queue, AtomicInteger processed) {
        this.queue = queue;
        this.processed = processed;
    }

    @Override
    public void run() {
        String name = Thread.currentThread().getName();
        try {
            while (true) {
                Order o = queue.take();
                if (o.isPoison()) {
                    System.out.println(name + " received poison pill, stopping");
                    return;
                }
                Thread.sleep(ThreadLocalRandom.current().nextInt(5, 31)); 
                System.out.println(name + " processed " + o);
                processed.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}