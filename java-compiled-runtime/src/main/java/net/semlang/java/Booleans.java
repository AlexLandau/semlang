package net.semlang.java;

public class Booleans {
    private Booleans() {
        // Not instantiable
    }

    public static boolean and(boolean a, boolean b) {
        return a && b;
    }

    public static boolean or(boolean a, boolean b) {
        return a || b;
    }

    public static boolean not(boolean a) {
        return !a;
    }
}
