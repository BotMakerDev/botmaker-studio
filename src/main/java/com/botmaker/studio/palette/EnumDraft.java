package com.botmaker.studio.palette;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An enum the user described before it is inserted: its name and its constants. The rules are here, pure, so
 * the dialog that collects them and the editor that writes them agree, and a test can hold them without a
 * display — {@link FunctionDraft} is the same split for a function.
 *
 * @param name      the type's name
 * @param constants its constants, in order; at least one
 */
public record EnumDraft(String name, List<String> constants) {

    /** What the palette's Define Enum seeds when nobody is asked. */
    public static final List<String> DEFAULT_CONSTANTS = List.of("OPTION_A", "OPTION_B");

    public EnumDraft {
        name = name == null ? "" : name.trim();
        constants = constants == null ? List.of() : List.copyOf(constants);
    }

    /** {@code text} read as constants: separated by commas or spaces, blanks dropped. */
    public static List<String> constantsOf(String text) {
        if (text == null) return List.of();
        return Arrays.stream(text.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Why this cannot be written, or empty when it can. {@code takenTypes} is every type name the file
     * declares: a second type of one name is the error this exists to stop.
     */
    public Optional<String> problem(Set<String> takenTypes) {
        Optional<String> named = FunctionDraft.identifierProblem(name, "enum");
        if (named.isPresent()) return named;
        if (takenTypes != null && takenTypes.contains(name)) {
            return Optional.of("This file already has a type called \"" + name + "\".");
        }
        if (constants.isEmpty()) return Optional.of("Give the enum at least one value.");
        Set<String> seen = new HashSet<>();
        for (String constant : constants) {
            Optional<String> bad = FunctionDraft.identifierProblem(constant, "value");
            if (bad.isPresent()) return bad;
            if (!seen.add(constant)) return Optional.of("Two values cannot both be called \"" + constant + "\".");
        }
        return Optional.empty();
    }
}
