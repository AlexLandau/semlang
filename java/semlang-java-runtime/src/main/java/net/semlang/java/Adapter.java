package net.semlang.java;

public interface Adapter<I, D> {
    I from(D data);
}
