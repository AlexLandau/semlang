package net.semlang.java;

import java.io.PrintStream;

public class TextOut {
    private TextOut() {
        // Not instantiable
    }

    public static Void print(PrintStream textOut, String text) {
        textOut.print(text);
        textOut.flush();
        return null;
    }
}
