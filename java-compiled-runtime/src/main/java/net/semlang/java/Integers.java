package net.semlang.java;

import java.math.BigInteger;

public class Integers {
    private Integers() {
        // Not instantiable
    }

    public static boolean lessThan(BigInteger left, BigInteger right) {
        return left.compareTo(right) < 0;
    }

    public static boolean greaterThan(BigInteger left, BigInteger right) {
        return left.compareTo(right) > 0;
    }

}
