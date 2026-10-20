package edu.itc.se.lab01.task3;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public class Producer implements Runnable {
    private final OrderQueue queue;
    private final int firstId;
    private final int count;
    private final int consumers;
    private final AtomicInteger activeProducers;

    public Producer(OrderQueue queue, int firstId, int count,
                    int consumers, AtomicInteger activeProducers) {
        this.queue = queue;
        this.firstId = firstId;
        this.count = count;
        this.consumers = consumers;
        this.activeProducers = activeProducers;
    }

    @Override
    public void run() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        try {
            for (int i = firstId; i < firstId + count; i++) {
                Thread.sleep(rnd.nextInt(0, 21));                    
                queue.put(new Order(i, "item-" + i, rnd.nextInt(1, 6))); 
            }
            if (activeProducers.decrementAndGet() == 0) {
                for (int c = 0; c < consumers; c++) {
                    queue.put(new Order(Order.POISON_ID, "poison", 0));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}