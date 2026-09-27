package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.slot.Bounds;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A field's {@code @Param(min, max)} reaches the editor as {@code bounds()}, and the host holds what is written
 * inside it whatever the editor did (2026-09-27): a typed 5 into a 0–1 {@code double} is written as 1.
 */
class HostValueContextBoundsTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of());

    private static List<String> writes(java.lang.reflect.Type form, Bounds bounds, Object value) {
        List<String> written = new ArrayList<>();
        HostValueContext context = HostValueContext.of(form, GRAMMAR, "", null, v -> written.add(v.source()))
                .withBounds(bounds);
        context.set(value);
        return written;
    }

    @Test
    void anUnboundedValueSaysSo() {
        assertEquals(Bounds.NONE, HostValueContext.of(double.class, GRAMMAR, "0", null, null).bounds());
    }

    @Test
    void theEditorIsToldTheRange() {
        Bounds range = new Bounds(0, 1);

        assertEquals(range, HostValueContext.of(double.class, GRAMMAR, "0", null, null).withBounds(range).bounds());
    }

    @Test
    void aDecimalOutsideIsWrittenAtTheNearestEnd() {
        assertEquals(List.of("1.0"), writes(double.class, new Bounds(0, 1), 5.0));
        assertEquals(List.of("0.0"), writes(double.class, new Bounds(0, 1), -2.0));
        assertEquals(List.of("0.5"), writes(double.class, new Bounds(0, 1), 0.5));
    }

    @Test
    void aWholeNumberStaysWholeAtAFractionalEnd() {
        assertEquals(List.of("1"), writes(int.class, new Bounds(0.5, 9.5), 0));
        assertEquals(List.of("9"), writes(int.class, new Bounds(0.5, 9.5), 12));
    }

    @Test
    void anythingButANumberIsWrittenAsItIs() {
        assertEquals(List.of("\"x\""), writes(String.class, new Bounds(0, 1), "x"));
    }
}
