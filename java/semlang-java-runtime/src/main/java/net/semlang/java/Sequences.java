package net.semlang.java;

import java.math.BigInteger;
import java.util.function.Function;

public class Sequences {
    private Sequences() {
        // Not instantiable
    }

    public static <T> Sequence<T> create(T initialValue, Function<T, T> successorFunction) {
        return new Sequence<T>() {
            //TODO: Caching will be important here... but what kind of cache to use?

            @Override
            public T get(BigInteger index) {
                T value = initialValue;
                while (BigInteger.ZERO.compareTo(index) != 0) {
                    value = successorFunction.apply(value);
                    index = index.subtract(BigInteger.ONE);
                }
                return value;
            }

            @Override
            public T first(Function<T, Boolean> condition) {
                T value = initialValue;
                while (!condition.apply(value)) {
                    value = successorFunction.apply(value);
                }
                return value;
            }
        };
    }
}
