package net.semlang.java;

public final class Var<T> {
    private T value;
    private Var(T initialValue) {
        this.value = initialValue;
    }

    public static <T> Var<T> create(T initialValue) {
        return new Var<T>(initialValue);
    }

    public T get() {
        return value;
    }

    public Void set(T value) {
        this.value = value;
        return null;
    }
}
