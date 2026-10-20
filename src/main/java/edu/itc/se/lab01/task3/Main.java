package edu.itc.se.lab01.task3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    static final int PRODUCERS = 2;
    static final int ORDERS_EACH = 50;
    static final int CONSUMERS = 3;
    static final int CAPACITY = 5;

    static void runWith(OrderQueue queue) throws InterruptedException {
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger activeProducers = new AtomicInteger(PRODUCERS);
        List<Thread> threads = new ArrayList<>();
        long start = System.nanoTime();

        for (int p = 0; p < PRODUCERS; p++) {
            threads.add(new Thread(
                new Producer(queue, p * ORDERS_EACH + 1, ORDERS_EACH, CONSUMERS, activeProducers),
                "producer-" + (p + 1)));
        }
        for (int c = 0; c < CONSUMERS; c++) {
            threads.add(new Thread(new Consumer(queue, processed), "consumer-" + (c + 1)));
        }

        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        long ms = (System.nanoTime() - start) / 1_000_000;
        int expected = PRODUCERS * ORDERS_EACH;
        System.out.println("Total processed: " + processed.get()
                + " (expected " + expected + ") in " + ms + " ms");
        if (processed.get() != expected) {
            throw new AssertionError("processed=" + processed.get() + ", expected " + expected);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        runWith(new BoundedBuffer(CAPACITY));   
    }
}