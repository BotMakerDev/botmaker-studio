package com.botmaker.studio.ui.render.components.types;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.params.TestValues;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The type chooser's list: Java, then the project, then one group per plugin — each alphabetical. */
class TypeCatalogTest {

    private static final List<PluginHost.OwnedType> PLUGINS = List.of(
            new PluginHost.OwnedType("zeta", "Zeta Tools", Duration.class),
            new PluginHost.OwnedType("basics", "Basics", int.class),        // already in Java: not repeated
            new PluginHost.OwnedType("basics", "Basics", StringBuilder.class),
            new PluginHost.OwnedType("basics", "Basics", java.awt.Color.class));

    private static final List<BotRecords.Shape> PROJECT = List.of(
            new BotRecords.Shape("com.bot.Pair", "Pair", true, List.of()),
            new BotRecords.Shape("com.bot.Box", "Box", false, List.of()));

    private static List<String> titles(TypeCatalog catalog) {
        return catalog.groups().stream().map(TypeCatalog.Group::title).toList();
    }

    private static List<String> labels(TypeCatalog.Group group) {
        return group.entries().stream().map(TypeCatalog.Entry::label).toList();
    }

    @Test
    void javaThenProjectThenPluginsByName() {
        TypeCatalog catalog = TypeCatalog.of(TypeCatalog.Purpose.DECLARATION, TestValues.GRAMMAR, PLUGINS, PROJECT);

        assertEquals(List.of("Java", "This project", "Basics", "Zeta Tools"), titles(catalog));
        assertEquals(List.of("boolean", "char", "double", "int", "long", "String"),
                labels(catalog.groups().get(0)));
        assertEquals(List.of("Box", "Pair"), labels(catalog.groups().get(1)));
        assertEquals(List.of("Color", "StringBuilder"), labels(catalog.groups().get(2)));
    }

    @Test
    void voidIsOnlyAResult() {
        TypeCatalog declaration = TypeCatalog.of(TypeCatalog.Purpose.DECLARATION, TestValues.GRAMMAR, List.of(), List.of());
        TypeCatalog result = TypeCatalog.of(TypeCatalog.Purpose.RETURN, TestValues.GRAMMAR, List.of(), List.of());

        assertFalse(labels(declaration.groups().getFirst()).contains("void"));
        assertTrue(labels(result.groups().getFirst()).contains("void"));
    }

    @Test
    void aValueOffersOnlyWhatAPickerWritesAndTheBotsRecords() {
        TypeCatalog catalog = TypeCatalog.of(TypeCatalog.Purpose.VALUE, TestValues.GRAMMAR, PLUGINS, PROJECT);

        // The test grammar declares String and int; the rest of Java has no picker behind it.
        assertEquals(List.of("int", "String"), labels(catalog.groups().get(0)));
        assertEquals(List.of("Pair"), labels(catalog.groups().get(1)));
        assertEquals(new ValueTypes.BotClass("com.bot.Pair", List.of()),
                catalog.groups().get(1).entries().getFirst().type());
    }

    /**
     * A type whose fresh value is a call the bot evaluates — a vision result — has no value to write down
     * before the bot runs, so a parameter of it could never be filled in (feedback 3, 2026-09-27). A local
     * can still hold one.
     */
    @Test
    void aValueLeavesOutATypeOnlyARunningBotFills() {
        List<PluginHost.OwnedType> plugins = List.of(
                new PluginHost.OwnedType("sdk", "SDK", TestValues.Body.class),
                new PluginHost.OwnedType("sdk", "SDK", Duration.class));

        TypeCatalog value = TypeCatalog.of(TypeCatalog.Purpose.VALUE, TestValues.GRAMMAR, plugins, List.of());
        TypeCatalog local = TypeCatalog.of(TypeCatalog.Purpose.DECLARATION, TestValues.GRAMMAR, plugins, List.of());

        assertEquals(List.of("Duration"), labels(value.groups().getLast()));
        assertEquals(List.of("Duration", "TestValues.Body"), labels(local.groups().getLast()));
    }

    @Test
    void searchMatchesNameOrHintAndDropsEmptyGroups() {
        TypeCatalog catalog = TypeCatalog.of(TypeCatalog.Purpose.DECLARATION, TestValues.GRAMMAR, PLUGINS, PROJECT);

        assertEquals(List.of("Java"), catalog.filter("whole").stream().map(TypeCatalog.Group::title).toList());
        assertEquals(List.of("int", "long"), labels(catalog.filter("WHOLE").getFirst()));
        assertEquals(List.of("Zeta Tools"), catalog.filter("durat").stream().map(TypeCatalog.Group::title).toList());
        assertEquals(catalog.groups(), catalog.filter("  "));
    }
}
