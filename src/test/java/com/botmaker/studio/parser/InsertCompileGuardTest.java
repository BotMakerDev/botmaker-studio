package com.botmaker.studio.parser;

import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.EnumDraft;
import com.botmaker.studio.parser.guard.CompileGuard;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The canvas's inserts are held to {@link CompileGuard}: only a new error refuses, and the user is told why. */
class InsertCompileGuardTest {

    private static String source(String body) {
        return """
                package com.mybot;
                public class Subject {
                    public void run() {
                %s
                    }
                }
                """.formatted(body.indent(8));
    }

    private static Expression initializer(EditorFixture f) {
        VariableDeclarationStatement declaration =
                (VariableDeclarationStatement) f.body("run").getStatements().getFirst().getAstNode();
        return ((VariableDeclarationFragment) declaration.fragments().getFirst()).getInitializer();
    }

    @Test
    void anInsertThatAddsACompileErrorIsRefusedWithTheReason() {
        EditorFixture f = new EditorFixture(source("int v = 0;"));
        f.editor.replaceWithVariable(initializer(f), "nothingCalledThis");

        assertNull(f.lastCode, "nothing is written");
        assertTrue(f.statusMessages.stream().anyMatch(m -> m.startsWith("That would not compile here")),
                f.statusMessages.toString());
    }

    @Test
    void anErrorTheFileAlreadyHadNeverBlocksAnInsert() {
        EditorFixture f = new EditorFixture(source("int v = missing;"));
        f.editor.addStatement(f.body("run"), BlockCatalog.byId("PRINT").orElseThrow(), 1);

        assertNotNull(f.lastCode, f.statusMessages.toString());
    }

    @Test
    void theGuardCountsASecondCopyOfAnExistingErrorAsNew() {
        CompileGuard guard = new CompileGuard(BotParser.SYNTAX, null);
        String broken = source("int v = 0");
        assertEquals(0, guard.introduced(broken, broken).size());
        assertEquals(1, guard.introduced(source("int v = 0;"), broken).size());
    }

    @Test
    void anEnumIsInsertedUnderTheNameAndValuesTheUserGave() {
        EditorFixture f = new EditorFixture(source("int v = 0;"));
        f.editor.addEnumStatement(f.body("run"), 1, new EnumDraft("Direction", List.of("UP", "DOWN")));

        assertNotNull(f.lastCode, f.statusMessages.toString());
        assertTrue(f.lastCode.contains("enum Direction"), f.lastCode);
        assertTrue(f.lastCode.contains("UP, DOWN"), f.lastCode);
    }

    @Test
    void aSecondEnumOfOneNameIsRefusedByTheGuardToo() {
        EditorFixture f = new EditorFixture(source("enum Direction { UP }"));
        f.editor.addEnumStatement(f.body("run"), 1, new EnumDraft("Direction", List.of("DOWN")));

        assertNull(f.lastCode);
    }

    @Test
    void anEnumDraftSaysWhatIsWrongWithIt() {
        assertEquals(Optional.empty(), new EnumDraft("Direction", List.of("UP")).problem(Set.of()));
        assertTrue(new EnumDraft("Direction", List.of("UP")).problem(Set.of("Direction")).isPresent());
        assertTrue(new EnumDraft("class", List.of("UP")).problem(Set.of()).isPresent());
        assertTrue(new EnumDraft("Direction", List.of()).problem(Set.of()).isPresent());
        assertTrue(new EnumDraft("Direction", List.of("UP", "UP")).problem(Set.of()).isPresent());
        assertTrue(new EnumDraft("Direction", List.of("2UP")).problem(Set.of()).isPresent());
        assertEquals(List.of("UP", "DOWN", "LEFT"), EnumDraft.constantsOf(" UP, DOWN  LEFT,"));
    }
}
