package net.semlang.java;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

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

    public static Optional<BigInteger> dividedBy(BigInteger left, BigInteger right) {
        if (BigInteger.ZERO.equals(right)) {
            return Optional.empty();
        }
        return Optional.of(left.divide(right));
    }

    public static Optional<BigInteger> modulo(BigInteger left, BigInteger right) {
        if (right.compareTo(BigInteger.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(left.mod(right));
    }

    public static BigInteger sum(List<BigInteger> list) {
        return list.stream().reduce(BigInteger.ZERO, BigInteger::add);
    }
}
