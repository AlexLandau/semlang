package net.semlang.java;

import java.util.Optional;

public class Tries {
    private Tries() {
        // Not instantiable
    }

    public static <T> T assume(Optional<T> argument) {
        return argument.get();
    }
}
