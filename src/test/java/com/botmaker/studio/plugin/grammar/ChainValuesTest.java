package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.studio.project.params.TestValues;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a person writes by hand and the grammar never does: a declared class's constant ({@code Tone.SOFT})
 * and a chain through an instance factory ({@code Tone.SOFT.louder(5).wider(2)}). Both read as the value
 * they build, and an edited value is written back through the type's own declaration.
 */
class ChainValuesTest {

    public record Tone(int level, int reach) {
        public static final Tone SOFT = new Tone(1, 0);
        static final Tone HIDDEN = new Tone(9, 9);
        public static Tone unsettled = new Tone(4, 4);

        public Tone louder(int level) { return new Tone(level, reach); }
        public Tone wider(int reach) { return new Tone(level, reach); }
    }

    private static final ComponentType<Tone> TONE = new ComponentType<>() {
        @Override public Class<Tone> type() { return Tone.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(int.class, int.class); }
        @Override public List<Object> components(Tone t) { return List.of(t.level(), t.reach()); }
        @Override public Tone build(List<Object> parts) {
            return new Tone(((Number) parts.get(0)).intValue(), ((Number) parts.get(1)).intValue());
        }
    };

    /** {@code on.method(n)}: read, never written. */
    private static ComponentType<Tone> wither(String method, Function<Tone, Integer> part,
                                              BiFunction<Tone, Integer, Tone> apply) {
        return new ComponentType<>() {
            @Override public Class<Tone> type() { return Tone.class; }
            @Override public Executable factory() { return TestValues.method(Tone.class, method, int.class); }
            @Override public List<Class<?>> componentTypes() { return List.of(Tone.class, int.class); }
            @Override public List<Object> components(Tone t) { return List.of(t, part.apply(t)); }
            @Override public Tone build(List<Object> parts) {
                return apply.apply((Tone) parts.get(0), ((Number) parts.get(1)).intValue());
            }
        };
    }

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of(TONE,
            wither("louder", Tone::level, Tone::louder), wither("wider", Tone::reach, Tone::wider)));

    private static final java.lang.reflect.Type FORM = Tone.class;

    @Test
    void aPublicConstantOfADeclaredClassReadsAsItsValue() {
        assertEquals(Optional.of(Tone.SOFT), GRAMMAR.valueOf(FORM, "Tone.SOFT"));
        assertEquals(Optional.of(Tone.SOFT), GRAMMAR.valueOf(FORM, JavaNames.canonical(Tone.class) + ".SOFT"));
        assertEquals(Optional.of(Tone.SOFT), GRAMMAR.valueOfAny("Tone.SOFT"));
        assertTrue(GRAMMAR.valueOf(FORM, "Tone.HIDDEN").isEmpty(), "not public: not a value the bot can name");
        assertTrue(GRAMMAR.valueOf(FORM, "Tone.unsettled").isEmpty(), "not final: its value is not the file's");
        assertTrue(GRAMMAR.valueOf(FORM, "Tone.NOPE").isEmpty());
    }

    @Test
    void aChainReadsAsTheValueItBuildsAndChainsCompose() {
        assertEquals(Optional.of(new Tone(5, 0)), GRAMMAR.valueOf(FORM, "Tone.SOFT.louder(5)"));
        assertEquals(Optional.of(new Tone(5, 2)), GRAMMAR.valueOf(FORM, "Tone.SOFT.louder(5).wider(2)"));
        assertEquals(Optional.of(new Tone(1, 3)),
                GRAMMAR.valueOf(FORM, "new " + JavaNames.canonical(Tone.class) + "(1, 7).wider(3)"),
                "the receiver is any readable value: here the canonical call");
        assertEquals(Optional.of(new Tone(5, 2)), GRAMMAR.valueOfAny("Tone.SOFT.louder(5).wider(2)"));
        assertEquals(Optional.of(new Tone(5, 0)), GRAMMAR.valueOf(FORM, "(Tone.SOFT).louder((5))"));
    }

    @Test
    void aChainNoDeclarationReadsStaysUnread() {
        assertTrue(GRAMMAR.valueOf(FORM, "Tone.SOFT.quieter(1)").isEmpty(), "no factory named quieter");
        assertTrue(GRAMMAR.valueOf(FORM, "Tone.SOFT.louder()").isEmpty(), "too few arguments");
        assertTrue(GRAMMAR.valueOf(FORM, "somebody.louder(5)").isEmpty(), "a receiver nothing reads");
        assertTrue(GRAMMAR.valueOf(FORM, "louder(5)").isEmpty(), "an instance factory needs its receiver");
    }

    @Test
    void anEditedChainIsWrittenThroughTheCanonicalDeclaration() {
        Object read = GRAMMAR.valueOf(FORM, "Tone.SOFT.louder(5).wider(2)").orElseThrow();

        assertEquals(Optional.of("new " + JavaNames.canonical(Tone.class) + "(5, 2)"),
                GRAMMAR.initializer(FORM, read));
    }

    @Test
    void aPartIsShownAsItWasWritten() {
        java.lang.reflect.Type list = ValueTypes.listOf(FORM);
        List<ValueGrammar.Part> parts = GRAMMAR.partsOfInitializer(list,
                "java.util.List.of(Tone.SOFT.louder( 5 ),   somebody.other())").orElseThrow();

        assertEquals("Tone.SOFT.louder( 5 )", parts.get(0).source());
        assertEquals("somebody.other()", parts.get(1).source());
        assertEquals(Optional.of(new Tone(5, 0)), GRAMMAR.valueOf(FORM, parts.get(0).written()));
        assertTrue(GRAMMAR.valueOf(list, "java.util.List.of(Tone.SOFT.louder( 5 ), somebody.other())").isEmpty(),
                "one unread part empties the whole list");
    }
}
