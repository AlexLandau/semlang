package net.semlang.java;

// TODO: At some point this could be replaced with actual Java void, but for now this is somewhat easier, as it involves
// less code rewriting if people put void things into a variable on the Semlang side (and there may be some benefits in
// allowing Void as a generic type)
public final class Void {
    private Void() {
        // Not instantiable; all variables of this type should have the value null
    }
}
