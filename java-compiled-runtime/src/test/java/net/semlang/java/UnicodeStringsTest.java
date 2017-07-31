package net.semlang.java;

import org.junit.Assert;
import org.junit.Test;

import java.math.BigInteger;

public class UnicodeStringsTest {
    @Test
    public void testLength() {
        Assert.assertEquals(new BigInteger("5"), UnicodeStrings.length("hello"));
        Assert.assertEquals(new BigInteger("0"), UnicodeStrings.length(""));
        Assert.assertEquals(new BigInteger("1"), UnicodeStrings.length("🙂"));
    }
}