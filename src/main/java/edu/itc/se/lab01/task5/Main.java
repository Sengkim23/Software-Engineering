package edu.itc.se.lab01.task5;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

public class Main {
    static final int ACCOUNTS = 4;
    static final int WORKERS = 8;
    static final long TRANSFERS_PER_WORKER = 100_000;   
    static final long INITIAL = 1_000;

    static final class Account {
        final int id;
        final ReentrantLock lock = new ReentrantLock();   // used only by TryLockBank
        private long balance;
        Account(int id, long balance) { this.id = id; this.balance = balance; }
        long balance() { return balance; }
        void withdraw(long a) { balance -= a; }
        void deposit(long a)  { balance += a; }
    }

    interface Bank {
        void transfer(Account from, Account to, long amount) throws InterruptedException;
    }

    static class BrokenBank implements Bank {
        public void transfer(Account from, Account to, long amount) {
            synchronized (from) {
                synchronized (to) {
                    if (from.balance() >= amount) { from.withdraw(amount); to.deposit(amount); }
                }
            }
        }
    }

    static class OrderedBank implements Bank {
        public void transfer(Account from, Account to, long amount) {
            Account first  = from.id < to.id ? from : to;
            Account second = (first == from) ? to : from;
            synchronized (first) {
                synchronized (second) {
                    if (from.balance() >= amount) { from.withdraw(amount); to.deposit(amount); }
                }
            }
        }
    }

    static class TryLockBank implements Bank {
        final LongAdder retries = new LongAdder();

        public void transfer(Account from, Account to, long amount) throws InterruptedException {
            while (true) {
                if (from.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                    try {
                        if (to.lock.tryLock(10, TimeUnit.MILLISECONDS)) {
                            try {
                                if (from.balance() >= amount) { from.withdraw(amount); to.deposit(amount); }
                                return;
                            } finally {
                                to.lock.unlock();
                            }
                        }
                    } finally {
                        from.lock.unlock();          
                    }
                }
                retries.increment();
                Thread.sleep(ThreadLocalRandom.current().nextInt(1, 6));   
            }
        }
    }

    static void startWatchdog() {
        Thread w = new Thread(() -> {
            var mx = ManagementFactory.getThreadMXBean();
            String last = "";
            try {
                while (true) {
                    Thread.sleep(1000);
                    long[] ids = mx.findDeadlockedThreads();   // monitors AND ReentrantLocks
                    if (ids != null) {
                        TreeSet<String> names = new TreeSet<>();
                        for (ThreadInfo ti : mx.getThreadInfo(ids)) names.add(ti.getThreadName());
                        String msg = String.join(", ", names);
                        if (!msg.equals(last)) {
                            System.out.println("[watchdog] DEADLOCK detected between: " + msg);
                            last = msg;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "watchdog");
        w.setDaemon(true);       
        w.start();
    }

    static Account[] newAccounts() {
        Account[] accs = new Account[ACCOUNTS];
        for (int i = 0; i < accs.length; i++) accs[i] = new Account(i, INITIAL);
        return accs;
    }

    static long total(Account[] accs) {
        long t = 0;
        for (Account a : accs) t += a.balance();
        return t;
    }

    static List<Thread> startWorkers(Bank bank, Account[] accs, long limit, AtomicLong completed) {
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            Thread t = new Thread(() -> {
                var rnd = ThreadLocalRandom.current();
                long done = 0;
                try {
                    while (!Thread.currentThread().isInterrupted() && done < limit) {
                        int a = rnd.nextInt(accs.length);
                        int b = (a + 1 + rnd.nextInt(accs.length - 1)) % accs.length;   // b != a
                        bank.transfer(accs[a], accs[b], rnd.nextInt(1, 50));
                        done++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();   
                } finally {
                    completed.addAndGet(done);
                    if (limit == Long.MAX_VALUE) {
                        System.out.println(Thread.currentThread().getName()
                                + " stopped after " + done + " transfers");
                    }
                }
            }, "transfer-" + w);
            workers.add(t);
        }
        workers.forEach(Thread::start);
        return workers;
    }

    static void runFinite(String label, Bank bank) throws InterruptedException {
        Account[] accs = newAccounts();
        AtomicLong completed = new AtomicLong();
        long t0 = System.nanoTime();
        List<Thread> workers = startWorkers(bank, accs, TRANSFERS_PER_WORKER, completed);
        for (Thread t : workers) t.join();          // BrokenBank never gets past this line
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String extra = (bank instanceof TryLockBank tl) ? " retries=" + tl.retries.sum() : "";
        System.out.printf("%s: transfers=%d%s total=%d (expected %d) in %d ms%n",
                label, completed.get(), extra, total(accs), ACCOUNTS * INITIAL, ms);
    }

    static void runGraceful(Bank bank) throws InterruptedException {
        Account[] accs = newAccounts();
        AtomicLong completed = new AtomicLong();
        List<Thread> workers = startWorkers(bank, accs, Long.MAX_VALUE, completed);

        System.out.println("[main] interrupting " + workers.size() + " workers after 2000 ms");
        Thread.sleep(2000);
        workers.forEach(Thread::interrupt);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);   
        boolean allStopped = true;
        for (Thread t : workers) {
            long leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            t.join(leftMs);
            if (t.isAlive()) allStopped = false;
        }
        System.out.printf("all workers stopped=%b, transfers completed=%d, total=%d (expected %d)%n",
                allStopped, completed.get(), total(accs), ACCOUNTS * INITIAL);
    }

    public static void main(String[] args) throws InterruptedException {
        String mode = args.length > 0 ? args[0] : "ordered";
        startWatchdog();
        switch (mode) {
            case "broken"   -> runFinite("BrokenBank", new BrokenBank());
            case "ordered"  -> runFinite("OrderedBank", new OrderedBank());
            case "trylock"  -> runFinite("TryLockBank", new TryLockBank());
            case "graceful" -> runGraceful(new TryLockBank());
            default -> System.out.println("usage: broken | ordered | trylock | graceful");
        }
    }
}