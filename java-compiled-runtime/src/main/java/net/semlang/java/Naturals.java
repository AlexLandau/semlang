package net.semlang.java;

import java.math.BigInteger;

public class Naturals {
    private Naturals() {
        // Not instantiable
    }

    public static BigInteger absoluteDifference(BigInteger left, BigInteger right) {
        return left.subtract(right).abs();
    }

}
