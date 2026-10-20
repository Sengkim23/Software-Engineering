package edu.itc.se.lab01.challenge;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public final class TestDataGenerator {
    private TestDataGenerator() {}
    private static String alphaName(int value) {
        StringBuilder result = new StringBuilder();
        int n = value;
        do { result.append((char) ('a' + n % 26)); n = n / 26 - 1; } while (n >= 0);
        return result.reverse().toString();
    }
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "data");
        int files = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int wordsPerFile = args.length > 2 ? Integer.parseInt(args[2]) : 50_000;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 20261020L;
        Files.createDirectories(dir);
        String[] vocabulary = new String[2_000];
        for (int i = 0; i < vocabulary.length; i++) vocabulary[i] = "word" + alphaName(i);
        Random random = new Random(seed);
        for (int f = 0; f < files; f++) {
            Path file = dir.resolve(String.format("file-%04d.txt", f));
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (int n = 0; n < wordsPerFile; n++) {
                    if (n > 0) w.write(n % 20 == 0 ? "\n" : " ");
                    w.write(vocabulary[random.nextInt(vocabulary.length)]);
                }
            }
            if ((f + 1) % 25 == 0 || f + 1 == files) System.out.printf("Generated %d/%d files%n", f + 1, files);
        }
        System.out.printf("Done: %d files, about %,d words%n", files, (long) files * wordsPerFile);
    }
}
