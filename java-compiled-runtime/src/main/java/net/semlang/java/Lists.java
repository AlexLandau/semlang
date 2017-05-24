package net.semlang.java;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Lists {
    private Lists() {
        // Not instantiable
    }

    public static <T> List<T> empty() {
        return Collections.emptyList();
    }

    public static <T> List<T> append(List<T> existingList, T element) {
        List<T> newList = new ArrayList<T>(existingList.size() + 1);
        newList.addAll(existingList);
        newList.add(element);
        return newList;
    }

    public static <T> T get(List<T> list, BigInteger index) {
        return list.get(index.intValueExact());
    }

    public static <T> BigInteger size(List<T> list) {
        return BigInteger.valueOf(list.size());
    }
}
