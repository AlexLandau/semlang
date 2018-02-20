package net.semlang.java;

import java.util.Optional;
import java.util.function.Function;

public class Tries {
    private Tries() {
        // Not instantiable
    }

    public static <T> Optional<T> failure() {
        return Optional.empty();
    }

    public static <T> Optional<T> success(T value) {
        return Optional.of(value);
    }

    public static <T> boolean isSuccess(Optional<T> argument) {
        return argument.isPresent();
    }

    public static <T> T assume(Optional<T> argument) {
        return argument.get();
    }

    public static <T, U> Optional<U> map(Optional<T> argument, Function<T, U> function) {
        return argument.map(function);
    }

    public static <T, U> Optional<U> flatMap(Optional<T> argument, Function<T, Optional<U>> function) {
        return argument.flatMap(function);
    }
}
