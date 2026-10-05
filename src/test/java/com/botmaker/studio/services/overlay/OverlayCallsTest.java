package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tool's insert: a static call with values the grammar spells, a constant where the bot has one. */
class OverlayCallsTest {

    public record Thing(String path) {}

    public static final class Pad {
        public static void click(Thing thing) {}

        public static void at(int x, int y) {}

        public void own() {}
    }

    private static final ComponentType<Thing> THING_TYPE = new ComponentType<>() {
        @Override public Class<Thing> type() { return Thing.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
        @Override public List<Object> components(Thing t) { return List.of(t.path()); }
        @Override public Thing build(List<Object> parts) { return new Thing((String) parts.getFirst()); }
    };

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of(THING_TYPE));

    private static ManagedConstants.Lookup constants(ManagedConstants.Constant... known) {
        return new ManagedConstants.Lookup(List.of(known), GRAMMAR);
    }

    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        return Pad.class.getMethod(name, types);
    }

    @Test
    void valuesAreSpelledAndAConstantWinsOverItsValue() throws Exception {
        OverlayCalls.Statement at = OverlayCalls.call(method("at", int.class, int.class), List.of(3, 4), GRAMMAR,
                constants());
        assertEquals("OverlayCallsTest.Pad.at(3, 4);", at.source(), "a nested owner is named through its outer class");

        OverlayCalls.Statement click = OverlayCalls.call(method("click", Thing.class), List.of(new Thing("a.png")),
                GRAMMAR, constants(new ManagedConstants.Constant("com.example.bot.Pictures", "ORE",
                        "new Thing(\"a.png\")")));
        assertEquals("OverlayCallsTest.Pad.click(Pictures.ORE);", click.source());
        assertTrue(click.imports().contains("com.example.bot.Pictures"));
    }

    @Test
    void whatCannotBeWrittenIsRefusedWithASentence() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> OverlayCalls.call(method("own"), List.of(), GRAMMAR, constants()));
        assertThrows(IllegalArgumentException.class,
                () -> OverlayCalls.call(method("at", int.class, int.class), List.of(3), GRAMMAR, constants()));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> OverlayCalls.call(method("click", Thing.class), List.of(new Object()), GRAMMAR, constants()));
        assertTrue(unknown.getMessage().contains("Pad.click"), unknown.getMessage());
    }
}
