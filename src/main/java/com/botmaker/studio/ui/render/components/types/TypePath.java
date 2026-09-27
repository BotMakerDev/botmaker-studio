package com.botmaker.studio.ui.render.components.types;

import com.botmaker.studio.palette.TypeNames;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueTypes;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * One part of a type, as the argument indexes that lead to it from the top: {@code [0]} is a map's key,
 * {@code [1, 0]} the element of the list a map holds, {@code []} the whole type (feedback 3, 2026-09-27).
 *
 * <p>The chooser changed only the innermost last argument, so a map's key could not be picked at all. It now
 * changes the part the user clicked in its header, and {@link #innermost} is the part it starts on — which is
 * the part it always changed, so nobody who never clicks sees a difference. Pure: every method answers a new
 * type and leaves the one it was given alone.
 */
record TypePath(List<Integer> steps) {

    /** The whole type. */
    static final TypePath ROOT = new TypePath(List.of());

    TypePath {
        steps = List.copyOf(steps);
    }

    /** One piece of a type as the header spells it; {@code path} is null for {@code <}, {@code , } and {@code >}. */
    record Segment(String text, TypePath path) {}

    /** The innermost last argument of {@code type}: {@code Point} in {@code Map<String, List<Point>>}. */
    static TypePath innermost(Type type) {
        List<Integer> steps = new ArrayList<>();
        for (Type at = type; at instanceof ValueTypes.Parameterized p && !p.arguments().isEmpty(); at = p.last()) {
            steps.add(p.arguments().size() - 1);
        }
        return new TypePath(steps);
    }

    /** Argument {@code index} of the part this path names. */
    TypePath child(int index) {
        List<Integer> next = new ArrayList<>(steps);
        next.add(index);
        return new TypePath(next);
    }

    /** {@code rest} followed from the part this path names. */
    TypePath then(TypePath rest) {
        List<Integer> next = new ArrayList<>(steps);
        next.addAll(rest.steps());
        return new TypePath(next);
    }

    /**
     * The container at this part of {@code root} — the part itself when it is one, else the one around it —
     * or null for a type with no container at or above this part.
     */
    TypePath container(Type root) {
        if (at(root) instanceof ValueTypes.Parameterized) return this;
        return steps.isEmpty() ? null : new TypePath(steps.subList(0, steps.size() - 1));
    }

    /** The part of {@code root} this path names, or null when {@code root} has no such part. */
    Type at(Type root) {
        Type at = root;
        for (int index : steps) {
            if (!(at instanceof ValueTypes.Parameterized p) || index >= p.arguments().size()) return null;
            at = p.arguments().get(index);
        }
        return at;
    }

    /** The longest start of this path {@code root} still has — what a selection becomes after an unwrap. */
    TypePath within(Type root) {
        List<Integer> kept = new ArrayList<>();
        Type at = root;
        for (int index : steps) {
            if (!(at instanceof ValueTypes.Parameterized p) || index >= p.arguments().size()) break;
            kept.add(index);
            at = p.arguments().get(index);
        }
        return new TypePath(kept);
    }

    /**
     * {@code root} with this part replaced by {@code with}. {@code void} holds nothing and sits in nothing, so
     * picking it anywhere answers {@code void} for the whole type.
     */
    Type replace(Type root, Type with) {
        if (with == void.class) return void.class;
        return replace(root, 0, with);
    }

    private Type replace(Type at, int depth, Type with) {
        if (depth == steps.size()) return with;
        if (!(at instanceof ValueTypes.Parameterized p) || steps.get(depth) >= p.arguments().size()) return at;
        List<Type> arguments = new ArrayList<>(p.arguments());
        int index = steps.get(depth);
        arguments.set(index, replace(arguments.get(index), depth + 1, with));
        return new ValueTypes.Parameterized(p.raw(), arguments);
    }

    /** {@code root} with this part put inside {@code container}, as its last argument. */
    Type wrap(Type root, ValueContainer<?> container) {
        Type part = at(root);
        return part == null ? root : replace(root, wrapped(container, part));
    }

    /**
     * {@code inner} inside {@code container}: the last argument, with any earlier ones text. The last because
     * that is what a container holds — a list's element, a map's value — so wrapping a {@code Duration} in a
     * map gives {@code Map<String, Duration>}, the map people write.
     */
    static Type wrapped(ValueContainer<?> container, Type inner) {
        List<Type> arguments = new ArrayList<>();
        for (int i = 0; i < container.arity() - 1; i++) arguments.add(String.class);
        arguments.add(inner);
        return ValueTypes.of(container, arguments);
    }

    /** {@code root} with the container at this part replaced by what it holds; a leaf is left as it is. */
    Type unwrap(Type root) {
        return at(root) instanceof ValueTypes.Parameterized p ? replace(root, p.last()) : root;
    }

    /**
     * {@code type} spelled as the chooser's button spells it ({@link TypeNames#label}), one segment per
     * name so each can be clicked: a container's name stands for the whole container.
     */
    static List<Segment> segments(Type type) {
        List<Segment> out = new ArrayList<>();
        spell(type, ROOT, out);
        return out;
    }

    private static void spell(Type type, TypePath path, List<Segment> out) {
        if (!(type instanceof ValueTypes.Parameterized p)) {
            // Inside a container a primitive is written boxed, as the button writes it.
            Type shown = !path.steps().isEmpty() && type instanceof Class<?> cls && cls.isPrimitive()
                    ? java.lang.invoke.MethodType.methodType(cls).wrap().returnType() : type;
            out.add(new Segment(TypeNames.label(shown), path));
            return;
        }
        out.add(new Segment(TypeNames.label(p.raw()), path));
        if (p.arguments().isEmpty()) return;
        out.add(new Segment("<", null));
        for (int i = 0; i < p.arguments().size(); i++) {
            if (i > 0) out.add(new Segment(", ", null));
            spell(p.arguments().get(i), path.child(i), out);
        }
        out.add(new Segment(">", null));
    }
}
