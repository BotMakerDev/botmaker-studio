package com.botmaker.studio.parser;

import com.botmaker.studio.blocks.flow.AssertBlock;
import com.botmaker.studio.blocks.flow.LabeledBlock;
import com.botmaker.studio.blocks.flow.SynchronizedBlock;
import com.botmaker.studio.blocks.flow.ThrowBlock;
import com.botmaker.studio.blocks.flow.TryBlock;
import com.botmaker.studio.blocks.func.ConstructorCallBlock;
import com.botmaker.studio.blocks.loop.ClassicForBlock;
import com.botmaker.studio.blocks.misc.ExpressionStatementBlock;
import com.botmaker.studio.blocks.misc.SourceStatementBlock;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.palette.BlockCatalog;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.parser.handlers.SwitchNormalizer;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statement blocks of Phase 4 of the block plan: which block each statement becomes, the edits the new
 * blocks make, and the statements seeded from the insert menu.
 *
 * <p>{@link StatementRoundTripTest} asserts the weaker thing — that every statement has <em>some</em> block.
 * This one asserts it is the right one, and that what the block writes back is Java that parses.
 */
class StatementBlocksTest {

    private static String source(String body) {
        return """
                package com.mybot;
                public class Subject {
                    private final Object lock = new Object();
                    public void run() throws Exception {
                %s
                    }
                }
                """.formatted(body.indent(8));
    }

    private static StatementBlock only(EditorFixture f) {
        BodyBlock body = f.body("run");
        assertEquals(1, body.getStatements().size(), "one statement in run()");
        return body.getStatements().getFirst();
    }

    private static void assertParses(String code) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        // The parser's default source level is 1.3, which has neither `assert` nor a multi-catch.
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setSource(code.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        for (IProblem problem : cu.getProblems()) {
            assertFalse(problem.isError(), problem.getMessage() + " in:\n" + code);
        }
    }

    // ---- Which block each statement becomes ----

    @Test
    void eachStatementKindBecomesItsOwnBlock() {
        Map<String, Class<? extends CodeBlock>> expected = Map.of(
                "for (int i = 0; i < 3; i++) { }", ClassicForBlock.class,
                "try { } catch (RuntimeException e) { } finally { }", TryBlock.class,
                "throw new IllegalStateException(\"x\");", ThrowBlock.class,
                "synchronized (lock) { }", SynchronizedBlock.class,
                "assert lock != null : \"no lock\";", AssertBlock.class,
                "outer: while (true) { break outer; }", LabeledBlock.class,
                "new StringBuilder(\"x\");", ExpressionStatementBlock.class,
                "class Local { }", SourceStatementBlock.class,
                "int low = 1, high = 9;", SourceStatementBlock.class);
        assertAll(expected.entrySet().stream().map(e -> (org.junit.jupiter.api.function.Executable) () ->
                assertInstanceOf(e.getValue(), only(new EditorFixture(source(e.getKey()))), e.getKey())));
    }

    @Test
    void aTryDrawsEveryClauseAsABody() {
        TryBlock block = (TryBlock) only(new EditorFixture(source(
                "try { int a = 1; } catch (IllegalStateException e) { } catch (RuntimeException e) { } finally { }")));
        long bodies = block.getChildren().stream().filter(BodyBlock.class::isInstance).count();
        assertEquals(4, bodies, "try, two catches and finally are each a body a block can be dropped into");
    }

    @Test
    void aLabelledLoopKeepsItsOwnBlockAndItsBreakShowsTheLabel() {
        LabeledBlock block = (LabeledBlock) only(new EditorFixture(source("outer: while (true) { break outer; }")));
        assertEquals("outer", block.label());
        CodeBlock inner = block.getChildren().getFirst();
        assertEquals("WhileBlock", inner.getClass().getSimpleName(), "the labelled loop is an ordinary loop");
    }

    @Test
    void aConstructorHandingOffIsAConstructorCall() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    private final int size;
                    public Subject() { this(3); }
                    public Subject(int size) { super(); this.size = size; }
                }
                """);
        long calls = f.state.getNodeToBlockMap().values().stream()
                .filter(ConstructorCallBlock.class::isInstance).count();
        assertEquals(2, calls, "this(3) and super() each draw as a constructor call");
    }

    @Test
    void aBareBodyIsBracedOnOpenAndDrawnAsSourceUntilThen() {
        String code = source("if (lock == null) return;\nwhile (lock == null) Thread.onSpinWait();\n"
                + "for (int i = 0; i < 2; i++) ;");
        EditorFixture f = new EditorFixture(code);
        assertTrue(f.body("run").getStatements().stream().allMatch(SourceStatementBlock.class::isInstance),
                "unbraced, each is drawn as written rather than losing its body");

        String braced = SwitchNormalizer.normalize(f.state.getCompilationUnit().orElseThrow(), code);
        assertNotNull(braced, "the normaliser braces them");
        assertParses(braced);
        assertTrue(braced.contains("if (lock == null) {"), braced);
        assertTrue(braced.contains("while (lock == null) {"), braced);
        assertFalse(braced.contains("{;}") || braced.contains("{ ; }"), "an empty body is an empty block: " + braced);

        EditorFixture after = new EditorFixture(braced);
        assertTrue(after.body("run").getStatements().stream().noneMatch(SourceStatementBlock.class::isInstance),
                "once braced, each is its own block");
    }

    @Test
    void anElseIfChainIsNotBraced() {
        String code = source("if (lock == null) { } else if (lock.hashCode() > 0) { } else { }");
        EditorFixture f = new EditorFixture(code);
        assertNull(SwitchNormalizer.normalize(f.state.getCompilationUnit().orElseThrow(), code),
                "a braced if with an else-if chain needs nothing");
    }

    // ---- The edits the new blocks make ----

    private static TryStatement tryOf(EditorFixture f) {
        return (TryStatement) only(f).getAstNode();
    }

    @Test
    void aCatchIsAddedWithANameThatShadowsNothing() {
        EditorFixture f = new EditorFixture(source("int e = 0;\ntry { } finally { }"));
        TryStatement tryStmt = (TryStatement) f.body("run").getStatements().get(1).getAstNode();
        f.editor.addCatchClause(tryStmt);
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("catch (Exception e2)"), f.lastCode);
    }

    @Test
    void theLastClauseThatKeepsATryLegalCannotBeRemoved() {
        EditorFixture f = new EditorFixture(source("try { } catch (RuntimeException e) { }"));
        f.editor.deleteCatchClause(tryOf(f), 0);
        assertNull(f.lastCode, "a try with neither catch nor finally does not compile");
        assertFalse(f.statusMessages.isEmpty(), "the refusal says why");

        f.editor.addFinallyClause(tryOf(f));
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("finally"), f.lastCode);
    }

    @Test
    void aFinallyGoesWhenACatchStays() {
        EditorFixture f = new EditorFixture(source("try { } catch (RuntimeException e) { } finally { int x = 1; }"));
        f.editor.deleteFinallyClause(tryOf(f));
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertFalse(f.lastCode.contains("finally"), f.lastCode);
    }

    @Test
    void aCatchTypeIsTypedAndMayBeAMultiCatch() {
        EditorFixture f = new EditorFixture(source("try { } catch (RuntimeException e) { }"));
        CatchClause clause = (CatchClause) tryOf(f).catchClauses().getFirst();
        f.editor.setCatchType(clause, "IllegalStateException | IllegalArgumentException");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("catch (IllegalStateException | IllegalArgumentException e)"), f.lastCode);

        EditorFixture g = new EditorFixture(source("try { } catch (RuntimeException e) { }"));
        g.editor.setCatchType((CatchClause) tryOf(g).catchClauses().getFirst(), "not a type!");
        assertNull(g.lastCode, "text that is not a type name is refused");
    }

    @Test
    void aCatchNameIsRenamedInItsOwnClauseOnly() {
        EditorFixture f = new EditorFixture(source("""
                try { } catch (IllegalStateException e) { System.out.println(e); }
                try { } catch (RuntimeException e) { System.out.println(e); }"""));
        TryStatement first = (TryStatement) f.body("run").getStatements().getFirst().getAstNode();
        CatchClause clause = (CatchClause) first.catchClauses().getFirst();
        f.editor.renameScopedVariable(clause.getException().getName(), "problem");
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("catch (IllegalStateException problem) { System.out.println(problem); }")
                || f.lastCode.contains("System.out.println(problem)"), f.lastCode);
        assertTrue(f.lastCode.contains("catch (RuntimeException e)"), "the other clause keeps its e: " + f.lastCode);
        assertTrue(f.lastCode.contains("System.out.println(e)"), f.lastCode);
    }

    @Test
    void aCountingLoopIndexIsRenamedInItsOwnLoopOnly() {
        EditorFixture f = new EditorFixture(source("""
                for (int i = 0; i < 3; i++) { System.out.println(i); }
                for (int i = 0; i < 3; i++) { System.out.println(i); }"""));
        ForStatement loop = (ForStatement) f.body("run").getStatements().getFirst().getAstNode();
        VariableDeclarationFragment counter = ClassicForBlock.counter(loop);
        assertNotNull(counter);
        f.editor.renameScopedVariable(counter.getName(), "round");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("for (int round = 0; round < 3; round++)"), f.lastCode);
        assertTrue(f.lastCode.contains("System.out.println(round)"), f.lastCode);
        assertTrue(f.lastCode.contains("for (int i = 0; i < 3; i++)"), "the second loop keeps i: " + f.lastCode);
    }

    @Test
    void aSourceStatementIsReplacedByJavaAndRefusesAnythingElse() {
        EditorFixture f = new EditorFixture(source("int low = 1, high = 9;"));
        Statement statement = (Statement) only(f).getAstNode();
        f.editor.replaceStatementSource(statement, "int low = 2;\nint high = 8;");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("int low = 2;") && f.lastCode.contains("int high = 8;"), f.lastCode);

        EditorFixture g = new EditorFixture(source("int low = 1, high = 9;"));
        g.editor.replaceStatementSource((Statement) only(g).getAstNode(), "int low = ;");
        assertNull(g.lastCode, "a typo is not written");
        assertFalse(g.statusMessages.isEmpty(), "and the refusal says why");
    }

    // ---- The insert menu ----

    @Test
    void theNewMenuEntriesBuildStatementsThatBecomeTheirBlocks() {
        Map<BlockType, Class<? extends CodeBlock>> expected = Map.of(
                BlockCatalog.FOR_CLASSIC, ClassicForBlock.class,
                BlockCatalog.TRY, TryBlock.class,
                BlockCatalog.THROW, ThrowBlock.class,
                BlockCatalog.SYNCHRONIZED, SynchronizedBlock.class,
                BlockCatalog.ASSERT, AssertBlock.class);
        assertAll(expected.entrySet().stream().map(e -> (org.junit.jupiter.api.function.Executable) () -> {
            EditorFixture f = new EditorFixture(source(""));
            f.editor.addStatement(f.body("run"), e.getKey(), 0);
            assertNotNull(f.lastCode, e.getKey().id() + " inserted nothing");
            assertParses(f.lastCode);
            assertInstanceOf(e.getValue(), only(new EditorFixture(f.lastCode)), f.lastCode);
        }));
    }

    @Test
    void theCountingLoopIndexIsUniqueAtTheDropSite() {
        EditorFixture f = new EditorFixture(source("int i = 5;"));
        f.editor.addStatement(f.body("run"), BlockCatalog.FOR_CLASSIC, 1);
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("for (int i2 = 0; i2 < 10; i2++)"), f.lastCode);
    }

    @Test
    void theLockIsTheEnclosingClassSoAStaticMethodCompiles() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public static void run() {
                    }
                }
                """);
        f.editor.addStatement(f.body("run"), BlockCatalog.SYNCHRONIZED, 0);
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("synchronized (Subject.class)"), f.lastCode);
    }
}
