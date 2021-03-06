package net.semlang.java;

import net.semlang.java.function.Function2;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    public static <T> List<T> appendFront(List<T> existingList, T element) {
        List<T> newList = new ArrayList<T>(existingList.size() + 1);
        newList.add(element);
        newList.addAll(existingList);
        return newList;
    }

    public static <T> List<T> concatenate(List<List<T>> lists) {
        List<T> newList = new ArrayList<T>(lists.stream().mapToInt(List::size).sum());
        for (List<T> list : lists) {
            newList.addAll(list);
        }
        return newList;
    }

    public static <T> Optional<List<T>> subList(List<T> list, BigInteger start, BigInteger end) {
        int startInt = start.intValueExact();
        int endInt = end.intValueExact();
        if (startInt > endInt || endInt > list.size()) {
            return Optional.empty();
        } else {
            return Optional.of(list.subList(start.intValueExact(), end.intValueExact()));
        }
    }

    public static <T> List<T> drop(List<T> list, BigInteger numberToDrop) {
        return list.subList(numberToDrop.intValueExact(), list.size());
    }

    public static <T> List<T> lastN(List<T> list, BigInteger n) {
        int startingIndex = list.size() - n.intValueExact();
        if (startingIndex <= 0) {
            return list;
        }
        return list.subList(startingIndex, list.size());
    }

    public static <T> Optional<T> get(List<T> list, BigInteger index) {
        final int intIndex = index.intValueExact();
        try {
            return Optional.of(list.get(intIndex));
        } catch (IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    public static <T> BigInteger size(List<T> list) {
        return BigInteger.valueOf(list.size());
    }

    public static <T, U> List<U> map(List<T> list, Function<T, U> function) {
        return list.stream().map(function).collect(Collectors.toList());
    }

    public static <T, U> List<U> flatMap(List<T> list, Function<T, List<U>> function) {
        List<U> collector = new ArrayList<>();
        for (T element : list) {
            collector.addAll(function.apply(element));
        }
        return collector;
    }

    public static <T, U> U reduce(List<T> list, U initialValue, Function2<U, T, U> reducer) {
        U curValue = initialValue;
        for (T element : list) {
            curValue = reducer.apply(curValue, element);
        }
        return curValue;
    }

    public static <T> Void forEach(List<T> list, Function<T, Void> action) {
        for (T element : list) {
            action.apply(element);
        }
        return null;
    }
}
