package edu.itc.se.lab01.task4;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    static final long LIMIT = 4_000_000;
    static final int CHUNKS = 16;
    static final int DOWNLOADS = 500;
    static final int SLOW_ID = 123;           

    static long countPrimes(long from, long to) {
        long count = 0;
        for (long n = Math.max(from, 2); n < to; n++) {
            boolean prime = true;
            for (long d = 2; d * d <= n; d++) if (n % d == 0) { prime = false; break; }
            if (prime) count++;
        }
        return count;
    }

    static class PrimeCounter implements Callable<Long> {
        private final long from, to;
        PrimeCounter(long from, long to) { this.from = from; this.to = to; }
        @Override public Long call() { return countPrimes(from, to); }
    }

    static class FakeDownload implements Callable<String> {
        private final int id;
        private final String url;
        FakeDownload(int id, String url) { this.id = id; this.url = url; }

        @Override public String call() throws Exception {
            Thread.sleep(id == SLOW_ID ? 5_000 : 200);        
            if (id % 50 == 0) throw new IOException("boom");  
            return url + " -> " + url.length();
        }
    }

    static void runDownloads(String label, ExecutorService pool) {
        long t0 = System.nanoTime();
        List<Future<String>> futures = new ArrayList<>();
        for (int id = 1; id <= DOWNLOADS; id++) {
            futures.add(pool.submit(new FakeDownload(id, "http://example.com/file" + id)));
        }

        int ok = 0, failed = 0, timedOut = 0;
        for (int i = 0; i < futures.size(); i++) {
            Future<String> f = futures.get(i);
            try {
                f.get(1, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {          
                failed++;
                if (failed == 1) System.out.println("  first failure: download " + (i + 1)
                        + " -> " + e.getCause());
            } catch (TimeoutException e) 
            {          
                timedOut++;
                System.out.println("  download " + (i + 1) + " timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%-40s: ok=%d failed=%d timedOut=%d in %d ms%n",
                label, ok, failed, timedOut, ms);
    }

    static void runWithCompletableFuture(String label, ExecutorService pool) {
        long t0 = System.nanoTime();
        List<CompletableFuture<String>> cfs = new ArrayList<>();
        for (int id = 1; id <= DOWNLOADS; id++) {
            FakeDownload task = new FakeDownload(id, "http://example.com/file" + id);
            String url = "http://example.com/file" + id;
            cfs.add(CompletableFuture
                    .supplyAsync(() -> {
                        try { return task.call(); }
                        catch (Exception e) { throw new CompletionException(e); }
                    }, pool)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .handle((res, ex) -> {            
                        if (ex == null) return res;
                        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                                ? ex.getCause() : ex;
                        return (cause instanceof TimeoutException ? "TIMEOUT " : "FAILED ") + url;
                    }));
        }
        CompletableFuture.allOf(cfs.toArray(new CompletableFuture[0])).join();

        int ok = 0, failed = 0, timedOut = 0;
        for (CompletableFuture<String> cf : cfs) {
            String s = cf.join();                   

            if (s.startsWith("TIMEOUT ")) timedOut++;
            else if (s.startsWith("FAILED ")) failed++;
            else ok++;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%-40s: ok=%d failed=%d timedOut=%d in %d ms%n",
                label, ok, failed, timedOut, ms);
    }

    public static void main(String[] args) throws Exception {
        int cpus = Runtime.getRuntime().availableProcessors();

        long t0 = System.nanoTime();
        long single = countPrimes(0, LIMIT);
        System.out.printf("single-threaded: %d primes in %d ms%n",
                single, (System.nanoTime() - t0) / 1_000_000);

        t0 = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(cpus)) {
            List<Future<Long>> futures = new ArrayList<>();
            long step = LIMIT / CHUNKS;
            for (int c = 0; c < CHUNKS; c++) {
                long from = c * step;
                long to = (c == CHUNKS - 1) ? LIMIT : from + step;
                futures.add(pool.submit(new PrimeCounter(from, to)));
            }
            long total = 0;
            for (Future<Long> f : futures) total += f.get();
            System.out.printf("pool (%d threads): %d primes in %d ms%s%n", cpus, total,
                    (System.nanoTime() - t0) / 1_000_000,
                    total == single ? "" : "  <-- MISMATCH!");
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            runDownloads("downloads on fixed(20) platform threads", pool);
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            runDownloads("downloads on virtual threads", pool);
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            runWithCompletableFuture("CompletableFuture on virtual threads", pool);
        }

        System.out.println("main finished, no System.exit used");
    }
}