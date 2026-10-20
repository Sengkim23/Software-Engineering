package edu.itc.se.lab01.challenge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class WordCount {
    private static final Path POISON = Path.of("__WORD_COUNT_POISON_7f3d__");
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}]+");
    private final BlockingQueue<Path> queue = new ArrayBlockingQueue<>(16);
    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final AtomicInteger filesDone = new AtomicInteger();
    private final AtomicInteger filesTotal = new AtomicInteger();
    private final LongAdder wordsDone = new LongAdder();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean partial = new AtomicBoolean();
    private final AtomicLong startNanos = new AtomicLong();
    private final AtomicLong stopNanos = new AtomicLong();
    private final Path dir;
    private final int workers;
    private final boolean virtual;
    private final int top;
    private final long timeoutSec;
    private volatile Thread scanner;
    private volatile ExecutorService pool;
    private volatile Thread reporter;
    private volatile CountDownLatch workersDone;
    private volatile Thread deadlineThread;

    public WordCount(Path dir, int workers, boolean virtual, int top, long timeoutSec) {
        this.dir = dir.toAbsolutePath().normalize();
        this.workers = Math.max(1, workers);
        this.virtual = virtual;
        this.top = Math.max(1, top);
        this.timeoutSec = Math.max(0, timeoutSec);
    }

    public void run() throws InterruptedException {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory: " + dir);
        try (Stream<Path> paths = Files.walk(dir)) {
            filesTotal.set((int) Math.min(Integer.MAX_VALUE, paths.filter(Files::isRegularFile).count()));
        } catch (IOException e) { throw new IllegalStateException("Cannot scan directory: " + e.getMessage(), e); }
        startNanos.set(System.nanoTime());
        pool = virtual ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newFixedThreadPool(workers);
        workersDone = new CountDownLatch(workers);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "word-count-shutdown-hook"));
        scanner = Thread.ofPlatform().name("word-count-scanner").start(this::scan);
        for (int i = 0; i < workers; i++) pool.submit(this::work);
        reporter = Thread.ofPlatform().name("word-count-reporter").start(() -> {
            while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (!stopping.get()) report();
            }
        });
        if (timeoutSec > 0) {
            deadlineThread = Thread.ofPlatform().name("word-count-deadline").start(() -> {
                try { Thread.sleep(TimeUnit.SECONDS.toMillis(timeoutSec)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (!stopping.get()) { System.out.println("deadline reached – stopping"); shutdown(); }
            });
        }
        workersDone.await();
        if (!stopping.get()) {
            stopping.set(true);
            stopNanos.compareAndSet(0, System.nanoTime());
        }
        cleanupThreads();
        printResult(partial.get());
    }

    private void scan() {
        try (Stream<Path> paths = Files.walk(dir)) {
            var iterator = paths.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext() && !Thread.currentThread().isInterrupted() && !stopping.get()) {
                Path p = iterator.next();
                if (!queue.offer(p, 100, TimeUnit.MILLISECONDS)) {
                    if (stopping.get() || Thread.currentThread().isInterrupted()) break;
                    // Retry via loop below without losing this path.
                    while (!queue.offer(p, 100, TimeUnit.MILLISECONDS)) {
                        if (stopping.get() || Thread.currentThread().isInterrupted()) return;
                    }
                }
            }
        } catch (IOException e) {
            if (!stopping.get()) System.err.println("Scanner error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (!stopping.get()) {
                for (int i = 0; i < workers; i++) {
                    try { queue.put(POISON); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
    }

    private void work() {
        try {
            while (!Thread.currentThread().isInterrupted() && !stopping.get()) {
                Path p = queue.take();
                if (p == POISON) return;
                if (Thread.currentThread().isInterrupted() || stopping.get()) return;
                countFile(p);
                filesDone.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally { workersDone.countDown(); }
    }

    private void countFile(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while (!Thread.currentThread().isInterrupted() && !stopping.get() && (line = reader.readLine()) != null) {
                for (String word : NON_LETTERS.split(line.toLowerCase(java.util.Locale.ROOT))) {
                    if (!word.isEmpty()) {
                        counts.computeIfAbsent(word, ignored -> new LongAdder()).increment();
                        wordsDone.increment();
                    }
                }
            }
        } catch (IOException e) {
            if (!stopping.get()) System.err.println("Cannot read " + file + ": " + e.getMessage());
        }
    }

    private List<Map.Entry<String, Long>> topWords(int limit) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        counts.forEach((word, count) -> entries.add(Map.entry(word, count.sum())));
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    private void report() {
        double elapsed = elapsedSeconds();
        long words = wordsDone.sum();
        long rate = elapsed <= 0 ? 0 : (long) (words / elapsed);
        String top3 = topWords(3).stream().map(e -> e.getKey() + "(" + e.getValue() + ")").reduce((a,b) -> a + " " + b).orElse("-");
        System.out.printf("[%.1fs] files %d/%d  words %,d  %,d w/s  top: %s%n", elapsed, filesDone.get(), filesTotal.get(), words, rate, top3);
    }

    public void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        partial.set(true);
        stopNanos.compareAndSet(0, System.nanoTime());
        Thread s = scanner;
        if (s != null) s.interrupt();
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        Thread r = reporter;
        if (r != null) r.interrupt();
        Thread d = deadlineThread;
        if (d != null && d != Thread.currentThread()) d.interrupt();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        joinUntil(s, until);
        joinUntil(r, until);
        if (p != null) {
            try { p.awaitTermination(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void joinUntil(Thread t, long until) {
        if (t == null || t == Thread.currentThread()) return;
        try { long left = until - System.nanoTime(); if (left > 0) t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void cleanupThreads() {
        Thread r = reporter; if (r != null) r.interrupt();
        Thread d = deadlineThread; if (d != null && d != Thread.currentThread()) d.interrupt();
        joinUntil(reporter, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
        joinUntil(deadlineThread, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
        if (pool != null) pool.shutdown();
    }

    private double elapsedSeconds() {
        long end = stopNanos.get() == 0 ? System.nanoTime() : stopNanos.get();
        return Math.max(0.001, (end - startNanos.get()) / 1_000_000_000.0);
    }

    public void printResult(boolean isPartial) {
        long words = wordsDone.sum();
        System.out.printf("%s files=%d words=%,d elapsed=%.2fs mode=%s%n", isPartial ? "[PARTIAL]" : "DONE", filesDone.get(), words, elapsedSeconds(), virtual ? "virtual(" + workers + ")" : "platform(" + workers + ")");
        System.out.printf("%5s  %-24s %12s %10s%n", "rank", "word", "count", "share");
        int rank = 1;
        for (Map.Entry<String, Long> e : topWords(top)) {
            double share = words == 0 ? 0 : e.getValue() * 100.0 / words;
            System.out.printf("%5d  %-24s %,12d %9.2f%%%n", rank++, e.getKey(), e.getValue(), share);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(Path.of("result.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("rank,word,count,share_percent"); writer.newLine();
            rank = 1;
            for (Map.Entry<String, Long> e : topWords(top)) {
                double share = words == 0 ? 0 : e.getValue() * 100.0 / words;
                writer.write(rank++ + ",\"" + e.getKey().replace("\"", "\"\"") + "\"," + e.getValue() + "," + String.format(java.util.Locale.ROOT, "%.4f", share)); writer.newLine();
            }
            System.out.println("result.csv written");
        } catch (IOException e) { System.err.println("Could not write result.csv: " + e.getMessage()); }
    }

    static Map<String, Long> countDirectoryForTest(Path dir, int workerCount) throws Exception {
        WordCount wc = new WordCount(dir, workerCount, false, Integer.MAX_VALUE, 0);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        ConcurrentHashMap<String, LongAdder> localCounts = wc.counts;
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            CountDownLatch latch = new CountDownLatch(files.size());
            for (Path file : files) executor.submit(() -> {
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        for (String word : NON_LETTERS.split(line.toLowerCase(java.util.Locale.ROOT))) {
                            if (!word.isEmpty()) localCounts.computeIfAbsent(word, ignored -> new LongAdder()).increment();
                        }
                    }
                } catch (IOException e) { throw new RuntimeException(e); }
                finally { latch.countDown(); }
            });
            latch.await();
        } finally { executor.shutdownNow(); }
        Map<String, Long> result = new java.util.HashMap<>();
        localCounts.forEach((word, count) -> result.put(word, count.sum()));
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(2); }
        Path dir = Path.of(args[0]); int top = 10; long timeout = 0; boolean virtual = false; int workers = Runtime.getRuntime().availableProcessors();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--top" -> { if (++i >= args.length) throw new IllegalArgumentException("--top requires N"); top = Integer.parseInt(args[i]); }
                case "--timeout" -> { if (++i >= args.length) throw new IllegalArgumentException("--timeout requires seconds"); timeout = Long.parseLong(args[i]); }
                case "--virtual" -> virtual = true;
                case "--workers" -> { if (++i >= args.length) throw new IllegalArgumentException("--workers requires N"); workers = Integer.parseInt(args[i]); }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (top < 1 || workers < 1 || timeout < 0) throw new IllegalArgumentException("top/workers must be positive and timeout non-negative");
        new WordCount(dir, workers, virtual, top, timeout).run();
    }
    private static void usage() { System.out.println("Usage: WordCount <dir> [--top N] [--timeout SECONDS] [--workers N] [--virtual]"); }
}
