package net.semlang.java;

import java.util.Optional;
import java.util.function.Function;

public class Tries {
    private Tries() {
        // Not instantiable
    }

    public static <T> T assume(Optional<T> argument) {
        return argument.get();
    }

    public static <T, U> Optional<U> map(Optional<T> argument, Function<T, U> function) {
        return argument.map(function);
    }
}
