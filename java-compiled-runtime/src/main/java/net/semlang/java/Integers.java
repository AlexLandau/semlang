package net.semlang.java;

import java.math.BigInteger;

public class Integers {
    private Integers() {
        // Not instantiable
    }

    public static BigInteger plus(BigInteger left, BigInteger right) {
        return left.add(right);
    }

    public static BigInteger minus(BigInteger left, BigInteger right) {
        return left.subtract(right);
    }

    public static BigInteger times(BigInteger left, BigInteger right) {
        return left.multiply(right);
    }

    public static boolean equals(BigInteger left, BigInteger right) {
        return left.equals(right);
    }
}
