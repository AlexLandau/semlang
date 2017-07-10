package net.semlang.java;

import java.math.BigInteger;
import java.util.function.Function;

public interface Sequence<T> {
    T get(BigInteger index);
    T first(Function<T, Boolean> condition);
}
