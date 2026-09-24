package com.botmaker.studio.palette;

import java.util.Map;

/**
 * One line per palette entry saying what it does, for the insert and value menus: the tooltip on a row, and
 * the second line under its name in search results. A name like "Do While" or "Yield Value" is only a name to
 * someone who already knows Java, and the menus are read by people who do not.
 *
 * <p>Keyed by id rather than held on the records, so {@link BlockType} and {@link ExpressionType} stay the
 * data the parser dispatches on. An entry with no line here gets a sentence built from its kind.
 */
public final class PaletteDescriptions {

    private PaletteDescriptions() {}

    private static final Map<String, String> BLOCKS = Map.ofEntries(
            Map.entry("PRINT", "Write a line to the console."),
            Map.entry("IF", "Run blocks only when a condition is true."),
            Map.entry("SWITCH", "Pick which blocks run by comparing one value against cases."),
            Map.entry("TRY", "Run blocks and catch the error they might throw."),
            Map.entry("SYNCHRONIZED", "Run blocks while holding a lock, one thread at a time."),
            Map.entry("WHILE", "Repeat blocks while a condition stays true."),
            Map.entry("FOR", "Run blocks once for each item of a list."),
            Map.entry("FOR_CLASSIC", "Repeat blocks a number of times, counting as it goes."),
            Map.entry("DO_WHILE", "Run blocks once, then repeat while a condition is true."),
            Map.entry("BREAK", "Leave the loop you are in."),
            Map.entry("CONTINUE", "Skip to the next round of the loop."),
            Map.entry("YIELD", "Give this case's value to the switch."),
            Map.entry("RETURN", "Leave the function, giving back a value if it has one."),
            Map.entry("THROW", "Stop with an error."),
            Map.entry("ASSERT", "Stop with an error when a condition is false (with -ea)."),
            Map.entry("DECLARE_INT", "A whole-number variable."),
            Map.entry("DECLARE_DOUBLE", "A decimal-number variable."),
            Map.entry("DECLARE_BOOLEAN", "A true/false variable."),
            Map.entry("DECLARE_STRING", "A text variable."),
            Map.entry("DECLARE_ARRAY", "A variable holding a fixed-size list of values."),
            Map.entry("ASSIGNMENT", "Give a variable a new value."),
            Map.entry("FUNCTION_CALL", "Run one of this class's functions."),
            Map.entry("METHOD_DECLARATION", "Add a new function to the class."),
            Map.entry("DECLARE_ENUM", "Name a fixed set of choices, like UP and DOWN."),
            Map.entry("COMMENT", "A note for people reading the code; it does nothing."));

    private static final Map<String, String> EXPRESSIONS = Map.ofEntries(
            Map.entry("TEXT", "A piece of text in quotes."),
            Map.entry("NUMBER", "A number."),
            Map.entry("TRUE", "The value true."),
            Map.entry("FALSE", "The value false."),
            Map.entry("CHARACTER", "One character, like 'a'."),
            Map.entry("CLASS_LITERAL", "A class itself, like String.class."),
            Map.entry("VARIABLE", "A variable you can read here."),
            Map.entry("ACTIVITY", "A parameter from the Parameters window."),
            Map.entry("FUNCTION_CALL", "The value a function gives back."),
            Map.entry("ENUM_CONSTANT", "One of an enum's choices."),
            Map.entry("LIST", "A nested list of values, inside another list."),
            Map.entry("INSTANTIATION", "A new object made with one of its constructors."),
            Map.entry("NEW_ARRAY", "An empty list with room for a number of values."),
            Map.entry("CAST", "The same value read as another type."),
            Map.entry("LAMBDA", "A piece of code passed as a value."),
            Map.entry("ADD", "Two numbers added, or two texts joined."),
            Map.entry("SUBTRACT", "One number minus another."),
            Map.entry("MULTIPLY", "Two numbers multiplied."),
            Map.entry("DIVIDE", "One number divided by another."),
            Map.entry("MODULO", "What is left after dividing."),
            Map.entry("NEGATE", "A number with its sign flipped."),
            Map.entry("EQUALS", "Whether two values are the same."),
            Map.entry("NOT_EQUALS", "Whether two values differ."),
            Map.entry("GREATER", "Whether the first number is bigger."),
            Map.entry("LESS", "Whether the first number is smaller."),
            Map.entry("GREATER_EQUALS", "Whether the first number is bigger or the same."),
            Map.entry("LESS_EQUALS", "Whether the first number is smaller or the same."),
            Map.entry("INSTANCEOF", "Whether a value is of a given type."),
            Map.entry("AND", "True when both sides are true."),
            Map.entry("OR", "True when either side is true."),
            Map.entry("NOT", "The opposite of a true/false value."),
            Map.entry("CHOOSE", "One of two values, depending on a condition."));

    /** What {@code block} does, in one sentence. */
    public static String of(BlockType block) {
        String known = BLOCKS.get(block.id());
        if (known != null) return known;
        return switch (block) {
            case BlockType.VarDecl v -> "A variable holding a " + v.displayName() + ".";
            case BlockType.LibraryCall call -> "Call " + call.facade().getSimpleName() + "." + call.method() + ".";
            case BlockType.LambdaCall call ->
                    "Call " + call.facade().getSimpleName() + "." + call.method() + " with blocks to run.";
            default -> block.displayName() + ".";
        };
    }

    /** What {@code expression} gives, in one sentence. */
    public static String of(ExpressionType expression) {
        return EXPRESSIONS.getOrDefault(expression.id(), expression.displayName() + ".");
    }
}
