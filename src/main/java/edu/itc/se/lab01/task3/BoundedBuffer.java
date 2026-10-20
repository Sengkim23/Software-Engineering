package edu.itc.se.lab01.task3;

import java.util.ArrayDeque;

public class BoundedBuffer implements OrderQueue {
    private final ArrayDeque<Order> items = new ArrayDeque<>();
    private final int capacity;

    public BoundedBuffer(int capacity) { this.capacity = capacity; }

    @Override
    public synchronized void put(Order order) throws InterruptedException {
        while (items.size() == capacity) {   
            wait();
        }
        items.addLast(order);
        notifyAll();                         
    }

    @Override
    public synchronized Order take() throws InterruptedException {
        while (items.isEmpty()) {
            wait();
        }
        Order o = items.removeFirst();
        notifyAll();                          
        return o;
    }
}