package net.semlang.java;

import java.util.ArrayList;
import java.util.List;

public class ListBuilders {
    private ListBuilders() {
        // Not instantiable
    }

    public static <T> List<T> create() {
        return new ArrayList<T>();
    }

    public static <T> List<T> append(List<T> list, T element) {
        list.add(element);
        return list;
    }

    public static <T> List<T> appendAll(List<T> list, List<T> toAdd) {
        list.addAll(toAdd);
        return list;
    }
}
