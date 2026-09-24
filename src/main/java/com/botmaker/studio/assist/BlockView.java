package com.botmaker.studio.assist;

import java.util.List;

/**
 * The open file as an assistant reads it: methods, their bodies, the statements in each and the value slots on
 * each statement. Every {@code id} is a {@code parser/BlockId} — the node's structural path — which is what
 * the edit tools take back and what stays put across the re-parse after an edit elsewhere in the file.
 *
 * <p>Deliberately not the canvas's block tree: a block is a JavaFX widget with a render pipeline, and what a
 * model needs is the same few facts in a shape it can be sent as JSON.
 */
public final class BlockView {

    private BlockView() {}

    /** A method with a body, and that body. */
    public record Method(String name, String owner, Body body) {}

    /** A {@code { … }} a statement can be inserted into, at an index into {@link #statements}. */
    public record Body(String id, List<Statement> statements) {
        public Body {
            statements = List.copyOf(statements);
        }
    }

    /**
     * One statement: its kind (the block's, e.g. {@code MethodInvocation}, {@code If}), its source on one line,
     * the slots a value can be written into and the bodies nested inside it.
     */
    public record Statement(String id, String kind, String text, List<Slot> slots, List<Body> bodies) {
        public Statement {
            slots = List.copyOf(slots);
            bodies = List.copyOf(bodies);
        }
    }

    /**
     * An expression a value may replace. {@code type} is the type the position expects — a call's parameter,
     * a variable's declared type, a condition's {@code boolean} — by qualified name, and {@code writable} says
     * whether the host can write a value of it at all.
     */
    public record Slot(String id, String type, String value, boolean writable) {}
}
