package net.semlang.java;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class UnicodeStrings {
    /**
     * WARNING: This is an O(n) operation, unlike String.length().
     * <p>
     * This returns the number of code points in the string.
     */
    public static BigInteger length(String string) {
        return BigInteger.valueOf(string.codePointCount(0, string.length()));
    }

    public static List<Integer> toCodePoints(String string) {
        return string.codePoints().boxed().collect(Collectors.toList());
    }

    private static final BigInteger UPPER_CODE_POINT_BOUND_EXCLUSIVE = BigInteger.valueOf(1_114_112L);
    public static Optional<Integer> asCodePoint(BigInteger value) {
        if (UPPER_CODE_POINT_BOUND_EXCLUSIVE.compareTo(value) > 0) {
            return Optional.of(value.intValueExact());
        } else {
            return Optional.empty();
        }
    }
}
