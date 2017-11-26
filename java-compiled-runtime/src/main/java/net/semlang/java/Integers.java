package net.semlang.java;

import java.math.BigInteger;
import java.util.List;

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

    public static BigInteger sum(List<BigInteger> list) {
        return list.stream().reduce(BigInteger.ZERO, BigInteger::add);
    }
}
