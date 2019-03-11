package net.semlang.java;

import java.util.function.Supplier;

public class Functions {
    private Functions() {
        // Not instantiable
    }

    public static Void whileTrueDo(Supplier<Boolean> condition, Supplier<Void> action) {
        while (condition.get()) {
            action.get();
        }
        return null;
    }
}
