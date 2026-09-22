package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.value.PluginType;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type tree: how it spells itself, how deep it goes, and which single type its values are of.
 *
 * <p>What is checked here is what the deleted {@code ValueChoice} could not say — two type arguments, two
 * levels, a class the bot declares — and the one question that survived it, which is the leaf a declared set
 * and a declared range are asked of. {@code docs/refactor/32-generic-values.md} is the specification.
 *
 * <p>A leaf is a Java type's name since 2026-09-22, where it was a {@code ValueType}; whether it is
 * <em>known</em> is the grammar's question now, since only the grammar knows what the plugins declared.
 */
class ValueFormTest {

    private static final ValueForm TEXT = ValueForm.of(String.class);
    private static final ValueForm COUNT = ValueForm.of(int.class);
    private static final ValueForm POINT = ValueForm.of(Point.class);

    /** A grammar that declares {@link Point}, and so knows it. */
    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(new PluginType<Point>() {
        @Override public Class<Point> type() { return Point.class; }
        @Override public Point fresh() { return new Point(); }
        @Override public Node editor(ValueContext ctx) { return null; }
    }), List.of());

    @Test
    void aLeafSpellsItsType() {
        assertEquals("String", TEXT.sourceName());
        assertEquals(0, TEXT.depth());
    }

    @Test
    void aTypeArgumentIsBoxedAndABareDeclarationIsNot() {
        // The one thing a type argument needs from its leaf that a field of that type does not.
        assertEquals("int", COUNT.sourceName());
        assertEquals("java.util.List<Integer>", ValueForm.listOf(COUNT).sourceName());
    }

    @Test
    void aMapIsSomethingTheDeletedChoicePairCouldNotSay() {
        ValueForm form = ValueForm.mapOf(TEXT, POINT);
        // Simple names inside the brackets: the import is the grammar's separate answer, not part of the
        // form's spelling.
        assertEquals("java.util.Map<String, Point>", form.sourceName());
        assertEquals(List.of("java.awt.Point"), GRAMMAR.imports(form));
        assertEquals(1, form.depth());
    }

    @Test
    void nestingIsUnbounded() {
        ValueForm form = ValueForm.mapOf(TEXT, ValueForm.listOf(ValueForm.mapOf(TEXT, COUNT)));
        assertEquals("java.util.Map<String, java.util.List<java.util.Map<String, Integer>>>",
                form.sourceName());
        assertEquals(3, form.depth());
    }

    @Test
    void aWrongNumberOfArgumentsIsCorrectedRatherThanThrown() {
        // Total like the rest of the grammar: a caller's mistake produces a form that displays, not an
        // exception that fails an open.
        ValueForm.Of map = new ValueForm.Of(ValueContainer.MAP, List.of(TEXT));
        assertEquals(2, map.arguments().size());
        assertFalse(GRAMMAR.known(map));

        ValueForm.Of list = new ValueForm.Of(ValueContainer.LIST, List.of(TEXT, COUNT));
        assertEquals(1, list.arguments().size());
    }

    @Test
    void oneUnknownArgumentMakesTheWholeFormUnknown() {
        ValueForm form = ValueForm.mapOf(TEXT, ValueForm.of("discord.Channel"));
        assertFalse(GRAMMAR.known(form));
        assertEquals("discord.Channel", GRAMMAR.firstUnknown(form));
        assertTrue(GRAMMAR.known(ValueForm.mapOf(TEXT, POINT)));
        assertFalse(ValueGrammar.empty().known(POINT), "a type nobody declares is unknown");
        assertTrue(ValueGrammar.empty().known(COUNT), "a JDK literal needs no plugin to be read");
    }

    @Test
    void aDeclaredClassNeedsNoArguments() {
        ValueForm.Declared box = new ValueForm.Declared("com.mybot.Box", List.of());
        assertEquals("com.mybot.Box", box.sourceName());
        assertTrue(GRAMMAR.known(box));
        assertEquals("com.mybot.Box<String>", new ValueForm.Declared("com.mybot.Box", List.of(TEXT)).sourceName());
    }

    @Test
    void theLeafIsTheTypeTheUserTypesValuesOf() {
        assertEquals(TEXT, TEXT.leaf());
        assertEquals(TEXT, ValueForm.listOf(TEXT).leaf());
        // A map's values are what its cell types, so the value argument is the leaf and the key is not.
        assertEquals(COUNT, ValueForm.mapOf(TEXT, COUNT).leaf());
    }

    @Test
    void aContainerOfContainersHasNoLeafOfItsOwn() {
        // The two questions a leaf answers — what may this be, between which numbers — are meaningless for a
        // tree with several, so there is no answer rather than an arbitrary one.
        assertNull(ValueForm.listOf(ValueForm.listOf(TEXT)).leaf());
        assertNull(new ValueForm.Declared("com.mybot.Box", List.of(TEXT)).leaf());
    }

    /** A leaf nothing could name is shown as written: taking its package off would name a different type. */
    @Test
    void anUnreadableLeafIsSpelledAsWritten() {
        assertEquals("java.util.Set<String>", ValueForm.of("java.util.Set<String>").sourceName());
        assertEquals("int[]", ValueForm.of("int[]").sourceName());
    }
}
