package net.semlang.java;

import org.junit.Assert;
import org.junit.Test;

import java.math.BigInteger;

public class StringsTest {
    @Test
    public void testLength() {
        Assert.assertEquals(new BigInteger("5"), Strings.length("hello"));
        Assert.assertEquals(new BigInteger("0"), Strings.length(""));
        Assert.assertEquals(new BigInteger("1"), Strings.length("🙂"));
    }
}