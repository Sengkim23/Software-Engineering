package edu.itc.se.lab01.challenge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Deliberately single-threaded reference implementation used for verification. */
public final class ReferenceWordCount {
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}]+");
    private ReferenceWordCount() {}
    public static Map<String, Long> count(Path dir) throws IOException {
        Map<String, Long> result = new HashMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    for (String word : NON_LETTERS.split(line.toLowerCase(Locale.ROOT))) {
                        if (!word.isEmpty()) result.merge(word, 1L, Long::sum);
                    }
                }
            }
        }
        return result;
    }
}
