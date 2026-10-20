package edu.itc.se.lab01.task2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

public class Main {
static final int THREADS = 8;
    static final int ITERATIONS = 1_000_000;

    interface Counter {
        void increment();
        long get();
    }

    static class UnsafeCounter implements Counter {
        private long count;
        public void increment() { count++; }
        public long get() { return count; }
    }

    static class SynchronizedCounter implements Counter {
        private long count;
        public synchronized void increment() { count++; }
        public synchronized long get() { return count; }
    }

    static class LockCounter implements Counter {
        private final ReentrantLock lock = new ReentrantLock();
        private long count;
        public void increment() {
            lock.lock();
            try { count++; } finally { lock.unlock(); }
        }
        public long get() {
            lock.lock();
            try { return count; } finally { lock.unlock(); }
        }
    }

    static class AtomicCounter implements Counter {
        private final AtomicLong count = new AtomicLong();
        public void increment() { count.incrementAndGet(); }
        public long get() { return count.get(); }
    }


    static class LongAdderCounter implements Counter {
        private final LongAdder count = new LongAdder();
        public void increment() { count.increment(); }
        public long get() { return count.sum(); }
    }

    static void run(String label, Counter counter) throws InterruptedException {
        long start = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < ITERATIONS; i++) counter.increment();
            }, "inc-" + t);
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join();
        long ms = (System.nanoTime() - start) / 1_000_000;
        long expected = (long) THREADS * ITERATIONS;
        long value = counter.get();
        System.out.printf("%-22s value=%,d  correct=%-5b  %d ms%n",
                label, value, value == expected, ms);
    }

    public static void main(String[] args) throws InterruptedException {
        for (int i = 0; i < 3; i++) run("UnsafeCounter", new UnsafeCounter());
        System.out.println();
        run("SynchronizedCounter", new SynchronizedCounter());
        run("LockCounter", new LockCounter());
        run("AtomicCounter", new AtomicCounter());
        run("LongAdderCounter", new LongAdderCounter());
    }
}
