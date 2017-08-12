package net.semlang.java;

import java.math.BigInteger;

public class Naturals {
    private Naturals() {
        // Not instantiable
    }

    public static boolean greaterThan(BigInteger left, BigInteger right) {
        return left.compareTo(right) > 0;
    }

    public static BigInteger absoluteDifference(BigInteger left, BigInteger right) {
        return left.subtract(right).abs();
    }

}
