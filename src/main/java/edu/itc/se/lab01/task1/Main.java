package edu.itc.se.lab01.task1;

import java.util.List;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        boolean sequential = List.of(args).contains("--sequential");

        Thread apollo = new countdown("apollo");

        Thread gemini = new Thread(new countdownTask(), "gemini");

        Thread mercury = new Thread(() -> {
            String name = Thread.currentThread().getName();
            try {
                for (int i = 3; i >= 1; i--) {
                    System.out.println(name + ": " + i);
                    Thread.sleep(200);
                }
                System.out.println(name + ": liftoff!");
            } catch (InterruptedException e) {
                System.out.println(name + " interrupted");
                Thread.currentThread().interrupt();
            }
        }, "mercury");

        Thread artemis = Thread.ofVirtual()
                .name("artemis")
                .unstarted(new countdownTask());

        List<Thread> threads = List.of(apollo, gemini, mercury, artemis);

        for (Thread t : threads) {
            if (sequential) {
                t.run();
            } else {
                t.start();
            }
        }
        System.out.println("Mission control: all launched");

        for (Thread t : threads) {
            t.join();
        }
        System.out.println("Mission control: all landed");

        for (Thread t : threads) {
            System.out.printf("%-8s isVirtual=%-5b state=%s%n",
                    t.getName(), t.isVirtual(), t.getState());
        }
    }
}