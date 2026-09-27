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
                GRAMMAR.initializer(FORM, read).map(JavaValue::source));
    }

    /**
     * A constant the type names ({@code ComponentType.constants}) is how a value equal to it is written; any
     * other value still goes through the factory, and a type that names none keeps its factory for every value.
     */
    @Test
    void aValueEqualToANamedConstantIsWrittenAsIt() throws NoSuchFieldException {
        java.lang.reflect.Field soft = Tone.class.getField("SOFT");
        ComponentType<Tone> named = new ComponentType<>() {
            @Override public Class<Tone> type() { return Tone.class; }
            @Override public List<Class<?>> componentTypes() { return TONE.componentTypes(); }
            @Override public List<Object> components(Tone t) { return TONE.components(t); }
            @Override public Tone build(List<Object> parts) { return TONE.build(parts); }
            @Override public List<java.lang.reflect.Field> constants() { return List.of(soft); }
        };
        ValueGrammar grammar = ValueGrammar.of(List.of(), List.of(named));
        String owner = JavaNames.canonical(Tone.class);

        assertEquals(Optional.of(owner + ".SOFT"), grammar.initializer(FORM, new Tone(1, 0)).map(JavaValue::source));
        assertEquals(Optional.of("new " + owner + "(2, 0)"),
                grammar.initializer(FORM, new Tone(2, 0)).map(JavaValue::source));
        assertEquals(Optional.of("new " + owner + "(1, 0)"),
                GRAMMAR.initializer(FORM, new Tone(1, 0)).map(JavaValue::source), "named by nobody: the factory");
    }

    /** A value whose declaration cannot write all of it: {@code Chime.of(pitch)} has no hold. */
    public record Chime(int pitch, int hold) {
        public static Chime of(int pitch) { return new Chime(pitch, 0); }
        public Chime held(int hold) { return new Chime(pitch, hold); }
    }

    private static final ComponentType<Chime> CHIME = new ComponentType<>() {
        @Override public Class<Chime> type() { return Chime.class; }
        @Override public Executable factory() { return TestValues.method(Chime.class, "of", int.class); }
        @Override public List<Class<?>> componentTypes() { return List.of(int.class); }
        @Override public List<Object> components(Chime c) { return List.of(c.pitch()); }
        @Override public Chime build(List<Object> parts) { return Chime.of(((Number) parts.get(0)).intValue()); }
    };

    private static final ComponentType<Chime> CHIME_HELD = new ComponentType<>() {
        @Override public Class<Chime> type() { return Chime.class; }
        @Override public Executable factory() { return TestValues.method(Chime.class, "held", int.class); }
        @Override public List<Class<?>> componentTypes() { return List.of(Chime.class, int.class); }
        @Override public List<Object> components(Chime c) { return List.of(c.held(0), c.hold()); }
        @Override public Chime build(List<Object> parts) {
            return ((Chime) parts.get(0)).held(((Number) parts.get(1)).intValue());
        }
    };

    /**
     * A value the declaration's own factory would write with something lost is written as the chain that keeps
     * it, on the value the factory does write; a value the factory writes whole keeps the factory's form. A
     * chain whose receiver is the value itself (a wither on {@code Tone}) is never a way to write it.
     */
    @Test
    void aValueTheFactoryWouldLosePartOfIsWrittenAsTheChainThatKeepsIt() {
        ValueGrammar grammar = ValueGrammar.of(List.of(), List.of(CHIME, CHIME_HELD));
        String owner = JavaNames.canonical(Chime.class);

        assertEquals(Optional.of(owner + ".of(3)"),
                grammar.initializer(Chime.class, new Chime(3, 0)).map(JavaValue::source));
        assertEquals(Optional.of(owner + ".of(3).held(200)"),
                grammar.initializer(Chime.class, new Chime(3, 200)).map(JavaValue::source));
        assertEquals(Optional.of(new Chime(3, 200)), grammar.valueOf(Chime.class, "Chime.of(3).held(200)"));
        assertEquals(Optional.of(owner + ".of(3)"),
                ValueGrammar.of(List.of(), List.of(CHIME)).initializer(Chime.class, new Chime(3, 200))
                        .map(JavaValue::source),
                "no chain declared: the factory writes it as it always did");
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
