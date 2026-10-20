package edu.itc.se.lab01.task1;

public class countdown extends Thread {

    public countdown(String name) {
        super(name);               
    }

    @Override
    public void run() {
        try {
            for (int i = 3; i >= 1; i--) {
                System.out.println(getName() + ": " + i);
                Thread.sleep(200);
            }
            System.out.println(getName() + ": liftoff!");
        } catch (InterruptedException e) {
            System.out.println(getName() + " interrupted");
            Thread.currentThread().interrupt();   
        }
    }

    public static void main(String[] args) throws InterruptedException {
        countdown a = new countdown("A");
        countdown b = new countdown("B");
        a.start();
        b.start();

        a.join();
        b.join();
        System.out.println("main: both countdowns finished");

        countdown c = new countdown("C");
        c.run();  
        System.out.println("main: C finished (ran on thread " +
                Thread.currentThread().getName() + ")");
    }
}