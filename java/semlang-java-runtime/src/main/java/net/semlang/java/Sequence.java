package net.semlang.java;

import java.math.BigInteger;
import java.util.function.Function;

public final class Sequence<T> {
    public final T base;
    public final Function<T, T> successor;
    //TODO: Caching will be important here... but what kind of cache to use?

    private Sequence(T base, Function<T, T> successor) {
        this.base = base;
        this.successor = successor;
    }

    public static <T> Sequence<T> create(T base, Function<T, T> successor) {
        return new Sequence<T>(base, successor);
    }

    public T get(BigInteger index) {
        T value = base;
        while (BigInteger.ZERO.compareTo(index) != 0) {
            value = successor.apply(value);
            index = index.subtract(BigInteger.ONE);
        }
        return value;
    }
    public T first(Function<T, Boolean> condition) {
        T value = base;
        while (!condition.apply(value)) {
            value = successor.apply(value);
        }
        return value;
    }
}
