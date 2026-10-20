package edu.itc.se.lab01.task1;

public class countdownTask implements Runnable {

    @Override
    public void run() {
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
    }

    public static void main(String[] args) throws InterruptedException {
        countdownTask task = new countdownTask();

        Thread a = new Thread(task, "A");
        Thread b = new Thread(task, "B");   
        a.start();
        b.start();

        a.join();
        b.join();
        System.out.println("main: both countdowns finished");

        task.run();   
    }
}