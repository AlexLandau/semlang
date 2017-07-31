package net.semlang.java;

import java.math.BigInteger;

public class UnicodeStrings {
    /**
     * WARNING: This is an O(n) operation, unlike String.length().
     * <p>
     * This returns the number of code points in the string.
     */
    public static BigInteger length(String string) {
        return BigInteger.valueOf(string.codePointCount(0, string.length()));
    }
}
