package com.botmaker.studio.parser;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.palette.EnumDraft;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Declare Variable and the class's Add Enum, as written into a file (2026-09-26). */
class DeclareLocalTest {

    private static final String SOURCE = """
            package test;

            public class Subject {
                public void run() {
                }
            }
            """;

    @Test
    void aChosenTypeIsDeclaredWithItsImportsAndAFreshValue() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        fixture.editor.declareLocal(fixture.body("run"), 0, "table",
                ValueTypes.mapOf(String.class, ValueTypes.listOf(int.class)));

        assertNotNull(fixture.lastCode, () -> "the edit was refused: " + fixture.statusMessages);
        assertTrue(fixture.lastCode.contains("Map<String, List<Integer>> table ="), fixture.lastCode);
        assertTrue(fixture.lastCode.contains("import java.util.Map;"), fixture.lastCode);
        assertTrue(fixture.lastCode.contains("import java.util.List;"), fixture.lastCode);
    }

    @Test
    void aPrimitiveIsDeclaredWithItsDefault() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        fixture.editor.declareLocal(fixture.body("run"), 0, "count", int.class);

        assertNotNull(fixture.lastCode, () -> "the edit was refused: " + fixture.statusMessages);
        assertTrue(fixture.lastCode.contains("int count = 0;"), fixture.lastCode);
    }

    @Test
    void anEnumIsAddedToTheClassWithTheValuesGiven() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        TypeDeclaration subject = (TypeDeclaration) fixture.state.getCompilationUnit().orElseThrow().types().getFirst();
        fixture.editor.addEnumToClass(subject, new EnumDraft("Mode", List.of("FAST", "SLOW")), 1);

        assertNotNull(fixture.lastCode, () -> "the edit was refused: " + fixture.statusMessages);
        assertTrue(fixture.lastCode.replaceAll("\\s+", " ").contains("public enum Mode { FAST, SLOW }"),
                fixture.lastCode);
    }

    /** The menu's Variables are Declare Variable and Set Variable; fixed-type entries and Define Enum left. */
    @Test
    void theMenuOffersOneDeclarationAndNoEnum() {
        List<BlockType> offered = BlockCatalog.all();
        assertTrue(offered.contains(BlockCatalog.DECLARE_VARIABLE));
        assertTrue(offered.contains(BlockCatalog.ASSIGNMENT));
        assertFalse(offered.contains(BlockCatalog.DECLARE_INT));
        assertFalse(offered.contains(BlockCatalog.DECLARE_ARRAY));
        assertFalse(offered.contains(BlockCatalog.DECLARE_ENUM));
    }
}
