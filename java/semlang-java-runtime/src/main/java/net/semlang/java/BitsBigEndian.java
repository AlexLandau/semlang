package net.semlang.java;

import java.util.List;

// TODO: Replace with compact representation
public final class BitsBigEndian {
    public final List<Bit> value;

    private BitsBigEndian(List<Bit> value) {
        this.value = value;
    }

    public static BitsBigEndian create(List<Bit> value) {
        return new BitsBigEndian(value);
    }
}
