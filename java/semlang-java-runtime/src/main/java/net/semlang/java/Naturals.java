package net.semlang.java;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class Naturals {
    private Naturals() {
        // Not instantiable
    }

    public static Optional<BigInteger> fromInteger(BigInteger integer) {
        if (integer.signum() >= 0) {
            return Optional.of(integer);
        } else {
            return Optional.empty();
        }
    }
}
