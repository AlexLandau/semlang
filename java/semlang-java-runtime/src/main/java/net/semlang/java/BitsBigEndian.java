package net.semlang.java;

import java.util.List;

// TODO: Replace with compact representation
public final class BitsBigEndian {
    public final List<Bit> bits;

    private BitsBigEndian(List<Bit> bits) {
        this.bits = bits;
    }

    public static BitsBigEndian create(List<Bit> bits) {
        return new BitsBigEndian(bits);
    }
}
