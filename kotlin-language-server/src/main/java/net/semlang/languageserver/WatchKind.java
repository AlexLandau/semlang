package net.semlang.languageserver;

public enum WatchKind {

    Create(1),

    Change(2),

    Delete(4);

    private final int value;

    WatchKind(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static WatchKind forValue(int value) {
        WatchKind[] allValues = WatchKind.values();
        if (value < 1 || value > allValues.length)
            throw new IllegalArgumentException("Illegal enum value: " + value);
        return allValues[value - 1];
    }

}
