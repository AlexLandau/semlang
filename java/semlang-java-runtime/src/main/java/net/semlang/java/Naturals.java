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

    public static boolean lessThan(BigInteger left, BigInteger right) {
        return left.compareTo(right) < 0;
    }

    public static boolean greaterThan(BigInteger left, BigInteger right) {
        return left.compareTo(right) > 0;
    }

    public static BigInteger absoluteDifference(BigInteger left, BigInteger right) {
        return left.subtract(right).abs();
    }

    public static Optional<BigInteger> fromInteger(BigInteger integer) {
        if (integer.signum() >= 0) {
            return Optional.of(integer);
        } else {
            return Optional.empty();
        }
    }

    public static BitsBigEndian toBits(BigInteger value) {
        int length = value.bitLength();
        if (length == 0) {
            return BitsBigEndian.create(Arrays.asList(Bit.ZERO));
        }
        List<Bit> bits = new ArrayList<>(length);
        for (int i = length - 1; i >= 0; i--) {
            if (value.testBit(i)) {
                bits.add(Bit.ONE);
            } else {
                bits.add(Bit.ZERO);
            }
        }
        return BitsBigEndian.create(bits);
    }
}
