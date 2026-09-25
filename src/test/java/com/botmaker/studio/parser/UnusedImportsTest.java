package com.botmaker.studio.parser;

import com.botmaker.studio.parser.guard.UnusedImports;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An import the file no longer uses goes with the edit that stopped using it; a nested type is imported by its
 * canonical name, never the binary {@code a.B$C} javac refuses.
 */
class UnusedImportsTest {

    private static final BotParser BOUND = new BotParser(
            List.of(System.getProperty("java.class.path").split(File.pathSeparator)), null);

    private static final String SOURCE = """
            package com.mybot;

            import java.util.ArrayList;
            import java.util.List;
            import java.time.LocalDate;
            import java.util.*;
            import static java.lang.Math.max;

            public class Subject {
                public void run() {
                    List<String> names = new ArrayList<>();
                    System.out.println(names);
                }
            }
            """;

    @Test
    void anImportNothingUsesIsRemovedAndTheUsedOnesStay() {
        String cleaned = new UnusedImports(BOUND, null).removeFrom(SOURCE);

        assertFalse(cleaned.contains("import java.time.LocalDate;"), cleaned);
        assertTrue(cleaned.contains("import java.util.ArrayList;"), cleaned);
        assertTrue(cleaned.contains("import java.util.List;"), cleaned);
    }

    @Test
    void onDemandAndStaticImportsAreTheUsersShorthandAndStay() {
        String cleaned = new UnusedImports(BOUND, null).removeFrom(SOURCE);

        assertTrue(cleaned.contains("import java.util.*;"), cleaned);
        assertTrue(cleaned.contains("import static java.lang.Math.max;"), cleaned);
    }

    @Test
    void withoutBindingsNothingIsRemoved() {
        assertEquals(SOURCE, new UnusedImports(BotParser.SYNTAX, null).removeFrom(SOURCE));
    }

    @Test
    void deletingTheLastBlockThatNamedATypeTakesItsImport() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;

                import java.util.ArrayList;

                public class Subject {
                    public void run() {
                        ArrayList<String> names = new ArrayList<>();
                        System.out.println("hi");
                    }
                }
                """);
        Statement declaration = (Statement) f.body("run").getStatements().getFirst().getAstNode();
        f.editor.deleteStatement(declaration);

        assertNotNull(f.lastCode, f.statusMessages.toString());
        assertFalse(f.lastCode.contains("import java.util.ArrayList;"), f.lastCode);
    }

    @Test
    void aNestedTypeIsImportedByItsCanonicalName() {
        assertEquals("java.util.Map.Entry", ImportManager.importable("java.util.Map$Entry"));
        assertEquals("a.B.C.D", ImportManager.importable("a.B$C$D"));
        assertEquals("java.util.List", ImportManager.importable("java.util.List"));
        assertNull(ImportManager.importable("a.B$1"), "an anonymous class has no name to import");
    }

    @Test
    void aBinaryNameReachingAddImportIsWrittenCanonical() {
        assertEquals("import java.util.Map.Entry;", importWrittenBy(
                (cu, rewrite) -> ImportManager.addImport(cu, rewrite, "java.util.Map$Entry")));
        assertEquals("import java.util.Map.Entry;", importWrittenBy(
                (cu, rewrite) -> ImportManager.addImport(cu, rewrite, java.util.Map.Entry.class)));
    }

    private static String importWrittenBy(BiConsumer<CompilationUnit, ASTRewrite> add) {
        String code = """
                package com.mybot;

                public class Subject {}
                """;
        CompilationUnit cu = BotParser.SYNTAX.parse(null, code);
        ASTRewrite rewrite = ASTRewrite.create(cu.getAST());
        add.accept(cu, rewrite);
        String written = AstRewriteHelper.applyRewrite(rewrite, code);
        return written.lines().filter(line -> line.startsWith("import ")).findFirst().orElse(written);
    }
}
