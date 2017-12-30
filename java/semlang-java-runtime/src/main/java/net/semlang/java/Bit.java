package net.semlang.java;

import java.math.BigInteger;
import java.util.Optional;

public final class Bit {
    public static final Bit ZERO = new Bit(BigInteger.ZERO);
    private static final Optional<Bit> ZERO_OPT = Optional.of(ZERO);
    public static final Bit ONE = new Bit(BigInteger.ONE);
    private static final Optional<Bit> ONE_OPT = Optional.of(ONE);
    public final BigInteger natural;

    private Bit(BigInteger natural) {
        this.natural = natural;
    }

    public static Optional<Bit> create(BigInteger value) {
        if (value.equals(BigInteger.ONE)) {
            return ONE_OPT;
        } else if (value.equals(BigInteger.ZERO)) {
            return ZERO_OPT;
        } else {
            return Optional.empty();
        }
    }
}
