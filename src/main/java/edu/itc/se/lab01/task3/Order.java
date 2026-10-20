package edu.itc.se.lab01.task3;

public record Order(int id, String item, int qty) {
    public static final int POISON_ID = -1;
    public boolean isPoison() { return id == POISON_ID; }
}