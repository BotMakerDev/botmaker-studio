package com.botmaker.studio.plugin;

import com.botmaker.studio.plugin.grammar.ValueGrammar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An editor that hands the host a value it has no Java for is told so, and the field keeps what it had
 * (2026-10-05). Until then {@code set} dropped such a value without a word: the editor showed it, and the file
 * never held it.
 */
class HostValueContextWriteTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of());

    /** A value of a class nothing declares — no literal, no enum, no container, no plugin's component. */
    private static final class Undeclared {
    }

    @Test
    void aValueTheHostCannotWriteIsRefusedWithTheReason() {
        List<String> written = new ArrayList<>();
        HostValueContext context = HostValueContext.of(int.class, GRAMMAR, "1", null, v -> written.add(v.source()));

        Optional<String> refused = context.write(new Undeclared());

        assertTrue(refused.orElse("").contains("Undeclared"), refused::toString);
        assertTrue(written.isEmpty(), "nothing reached the file");
        assertEquals(1, context.value(Integer.class).orElseThrow(), "the field still reads what it held");
    }

    /** A JDK value of the wrong kind is refused as not fitting, never as a class nobody writes. */
    @Test
    void aValueOfTheWrongKindIsRefusedNamingTheField() {
        HostValueContext context = HostValueContext.of(int.class, GRAMMAR, "1", null, null);

        String refused = context.write("seven").orElseThrow();

        assertTrue(refused.contains("(String) as int"), refused);
        assertTrue(refused.contains("does not fit the field"), refused);
    }

    @Test
    void aValueTheHostWritesAnswersEmpty() {
        List<String> written = new ArrayList<>();
        HostValueContext context = HostValueContext.of(int.class, GRAMMAR, "1", null, v -> written.add(v.source()));

        assertTrue(context.write(7).isEmpty());
        assertEquals(List.of("7"), written);
    }

    @Test
    void nothingToWriteIsRefusedToo() {
        HostValueContext context = HostValueContext.of(int.class, GRAMMAR, "1", null, null);

        assertTrue(context.write(null).isPresent());
    }
}
