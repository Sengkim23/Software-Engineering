package edu.itc.se.lab01.challenge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WordCountTest {
    @TempDir Path temp;

    @Test void parallelCountsMatchSingleThreadedReferenceForTwentyFiles() throws Exception {
        Path fixture = temp.resolve("fixture"); Files.createDirectories(fixture);
        for (int i = 0; i < 20; i++) Files.writeString(fixture.resolve("f" + i + ".txt"),
                "Hello hello WORLD! Khmer-language test 123\nJava java concurrency.", StandardCharsets.UTF_8);
        Map<String, Long> expected = ReferenceWordCount.count(fixture);
        Map<String, Long> actual = runAndReadCounts(fixture, 4);
        assertEquals(expected, actual);
    }

    @Test void timeoutStopsWithinThreeSecondsForFiveThousandFiles() throws Exception {
        Path fixture = temp.resolve("many"); Files.createDirectories(fixture);
        for (int i = 0; i < 5_000; i++) Files.writeString(fixture.resolve("f" + i + ".txt"), "alpha beta gamma ".repeat(20));
        Path old = Path.of("result.csv"); byte[] previous = Files.exists(old) ? Files.readAllBytes(old) : null;
        long start = System.nanoTime();
        new WordCount(fixture, 4, false, 3, 1).run();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 3_000, "Should stop in under 3 seconds, actual=" + elapsedMs + "ms");
        if (previous != null) Files.write(old, previous); else Files.deleteIfExists(old);
    }

    private Map<String, Long> runAndReadCounts(Path fixture, int workers) throws Exception {
        // Exercise the same production tokenizer and shared-map algorithm through a package test hook.
        return WordCount.countDirectoryForTest(fixture, workers);
    }
}
