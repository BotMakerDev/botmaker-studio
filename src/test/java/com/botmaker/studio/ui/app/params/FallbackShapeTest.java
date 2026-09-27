package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Studio draws for a value no plugin draws, decided without a screen (6f). */
class FallbackShapeTest {

    /** A value written as a call, declared but drawn by nobody: two parts, one of them itself a call. */
    public record Pair(String name, Inner inner) {
        public static Pair of(String name, Inner inner) { return new Pair(name, inner); }
    }

    public record Inner(int count) {}

    static final class PairType implements PluginType<Pair>, ComponentType<Pair> {
        @Override public Class<Pair> type() { return Pair.class; }
        @Override public Pair fresh() { return new Pair("a", new Inner(1)); }
        @Override public java.lang.reflect.Executable factory() {
            try {
                return Pair.class.getMethod("of", String.class, Inner.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class, Inner.class); }
        @Override public List<Object> components(Pair p) { return List.of(p.name(), p.inner()); }
        @Override public Pair build(List<Object> parts) { return new Pair((String) parts.get(0), (Inner) parts.get(1)); }
    }

    static final class InnerType implements PluginType<Inner>, ComponentType<Inner> {
        @Override public Class<Inner> type() { return Inner.class; }
        @Override public Inner fresh() { return new Inner(0); }
        @Override public List<Class<?>> componentTypes() { return List.of(int.class); }
        @Override public List<Object> components(Inner i) { return List.of(i.count()); }
        @Override public Inner build(List<Object> parts) { return new Inner((int) parts.getFirst()); }
    }

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(new PairType(), new InnerType()), List.of());

    private static String spelled(Object value) {
        return GRAMMAR.initializerOfAny(value).map(JavaValue::source).orElseThrow();
    }

    @Test
    void an_enum_is_a_choice_of_its_constants() {
        var shape = assertInstanceOf(FallbackShape.Choice.class,
                FallbackShape.of(ValueGrammar.empty(), ChronoUnit.class, "java.time.temporal.ChronoUnit.DAYS", 0));
        assertEquals(List.of((Object[]) ChronoUnit.values()), shape.constants());
        assertEquals(Optional.of(ChronoUnit.DAYS), shape.held());
        assertTrue(FallbackShape.write(ValueGrammar.empty(), ChronoUnit.class, ChronoUnit.HOURS)
                .map(JavaValue::source).orElseThrow().endsWith("ChronoUnit.HOURS"));
    }

    @Test
    void a_declared_call_is_its_parts_and_rebuilds_equal_to_itself() {
        Pair held = new Pair("x", new Inner(3));
        var parts = assertInstanceOf(FallbackShape.Parts.class, FallbackShape.of(GRAMMAR, Pair.class, spelled(held), 0));
        assertEquals(List.of(String.class, Inner.class), parts.types());
        assertEquals(List.of("x", new Inner(3)), parts.values());
        // Both sides are the grammar's fully qualified writer (initializerOfAny / initializer).
        assertEquals(spelled(held), FallbackShape.rebuild(GRAMMAR, Pair.class, parts, parts.values())
                .map(JavaValue::source).orElseThrow(), "unedited parts write the same call back");
    }

    @Test
    void a_part_that_is_itself_a_call_is_parts_again_one_level_down() {
        assertInstanceOf(FallbackShape.Parts.class, FallbackShape.of(GRAMMAR, Inner.class, spelled(new Inner(3)), 1));
    }

    @Test
    void depth_four_is_kept() {
        assertInstanceOf(FallbackShape.Kept.class,
                FallbackShape.of(GRAMMAR, Inner.class, spelled(new Inner(3)), FallbackShape.MAX_DEPTH));
    }

    @Test
    void unreadable_source_is_kept() {
        var kept = assertInstanceOf(FallbackShape.Kept.class,
                FallbackShape.of(ValueGrammar.empty(), ChronoUnit.class, "someUnit", 0));
        assertEquals("someUnit", kept.source());
        assertInstanceOf(FallbackShape.Kept.class, FallbackShape.of(GRAMMAR, Pair.class, "Pair.load()", 0));
    }

    @Test
    void reset_offers_the_fresh_value_when_there_is_one() {
        var kept = assertInstanceOf(FallbackShape.Kept.class, FallbackShape.of(GRAMMAR, Pair.class, "Pair.load()", 0));
        assertTrue(kept.fresh().isPresent(), "Pair declares a fresh value");
    }

    /**
     * A plugin editor that answers nothing (a picture editor with no picture) is not the end: the value still
     * gets its shape. Here the decision is the same one `ValueEditors` makes after every claimant declined.
     */
    @Test
    void a_null_plugin_editor_falls_through_to_the_shape() {
        assertInstanceOf(FallbackShape.Parts.class,
                ValueEditors.fallbackShape(GRAMMAR, Pair.class, spelled(new Pair("x", new Inner(3))), 0));
    }

    @Test
    void a_jdk_literal_is_a_field_that_reads_what_is_typed() {
        var literal = assertInstanceOf(FallbackShape.Literal.class,
                FallbackShape.of(ValueGrammar.empty(), int.class, "42", 0));
        assertEquals("42", literal.shown());
        assertEquals(Optional.of(7), FallbackShape.literal(int.class, " 7 "));
        assertEquals(Optional.empty(), FallbackShape.literal(int.class, "seven"));
        assertEquals(Optional.of("hi"), FallbackShape.literal(String.class, "hi"));
        assertEquals(Optional.of(true), FallbackShape.literal(boolean.class, "true"));
    }
}
