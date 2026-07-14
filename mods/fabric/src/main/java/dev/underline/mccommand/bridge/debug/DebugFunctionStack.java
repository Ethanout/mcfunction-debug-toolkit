package dev.underline.mccommand.bridge.debug;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class DebugFunctionStack {
    private static final ThreadLocal<NavigableMap<Integer, Identifier>> STACK =
            ThreadLocal.withInitial(TreeMap::new);

    private DebugFunctionStack() {
    }

    public static void enter(int depth, Identifier functionId) {
        NavigableMap<Integer, Identifier> stack = STACK.get();
        stack.tailMap(depth, true).clear();
        stack.put(depth, functionId);
    }

    public static List<Identifier> throughDepth(int depth) {
        return List.copyOf(new ArrayList<>(STACK.get().headMap(depth, true).values()));
    }

    static void clear() {
        STACK.remove();
    }
}
