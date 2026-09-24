package com.botmaker.studio.parser;

import com.botmaker.studio.blocks.expr.ArrayAccessBlock;
import com.botmaker.studio.blocks.expr.ArrayCreationBlock;
import com.botmaker.studio.blocks.expr.AssignmentValueBlock;
import com.botmaker.studio.blocks.expr.CastBlock;
import com.botmaker.studio.blocks.expr.ConditionalBlock;
import com.botmaker.studio.blocks.expr.DeclarationExpressionBlock;
import com.botmaker.studio.blocks.expr.InstanceofBlock;
import com.botmaker.studio.blocks.expr.LambdaBlock;
import com.botmaker.studio.blocks.expr.SourceExpressionBlock;
import com.botmaker.studio.blocks.expr.SuperAccessBlock;
import com.botmaker.studio.blocks.expr.SwitchExpressionBlock;
import com.botmaker.studio.blocks.expr.ThisBlock;
import com.botmaker.studio.blocks.expr.TokenLiteralBlock;
import com.botmaker.studio.blocks.expr.TypeLiteralBlock;
import com.botmaker.studio.blocks.expr.UnaryBlock;
import com.botmaker.studio.blocks.expr.UnknownExpressionBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.palette.ExpressionCatalog;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PatternInstanceofExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.TypePattern;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value blocks of Phase 5 of the block plan: which block each Java expression becomes, the edits those
 * blocks make, and the forms seeded from the expression menu. The statement side is {@link StatementBlocksTest}.
 */
class ExpressionBlocksTest {

    private static String source(String body) {
        return """
                package com.mybot;
                public class Subject extends Base {
                    boolean flag;
                    Object o = "x";
                    int[] numbers = {1, 2};
                    int n;
                    public void run() throws Exception {
                %s
                    }
                }
                class Base {
                    protected int size;
                }
                """.formatted(body.indent(8));
    }

    /** The block drawn for the value the first statement of {@code run()} declares. */
    private static CodeBlock valueOf(EditorFixture f) {
        return f.state.getNodeToBlockMap().get(initializer(f));
    }

    private static Expression initializer(EditorFixture f) {
        VariableDeclarationStatement declaration =
                (VariableDeclarationStatement) f.body("run").getStatements().getFirst().getAstNode();
        return ((VariableDeclarationFragment) declaration.fragments().getFirst()).getInitializer();
    }

    private static void assertParses(String code) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setSource(code.toCharArray());
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        for (IProblem problem : cu.getProblems()) {
            assertFalse(problem.isError(), problem.getMessage() + " in:\n" + code);
        }
    }

    // ---- Which block each expression becomes ----

    @Test
    void eachExpressionKindBecomesItsOwnBlock() {
        Map<String, Class<? extends CodeBlock>> expected = new LinkedHashMap<>();
        expected.put("int v = flag ? 1 : 2;", ConditionalBlock.class);
        expected.put("int v = (int) 2.5;", CastBlock.class);
        expected.put("boolean v = o instanceof String;", InstanceofBlock.class);
        expected.put("boolean v = o instanceof String s;", InstanceofBlock.class);
        expected.put("Runnable v = () -> { };", LambdaBlock.class);
        expected.put("java.util.function.IntUnaryOperator v = x -> x + 1;", LambdaBlock.class);
        expected.put("int v = numbers[0];", ArrayAccessBlock.class);
        expected.put("int[] v = new int[5];", ArrayCreationBlock.class);
        expected.put("char v = 'a';", TokenLiteralBlock.class);
        expected.put("String v = \"\"\"\n    hi\n    \"\"\";", TokenLiteralBlock.class);
        expected.put("int v = 0xFF;", TokenLiteralBlock.class);
        expected.put("long v = 1_000L;", TokenLiteralBlock.class);
        expected.put("Class<?> v = String.class;", TypeLiteralBlock.class);
        expected.put("Object v = this;", ThisBlock.class);
        expected.put("String v = super.toString();", SuperAccessBlock.class);
        expected.put("int v = super.size;", SuperAccessBlock.class);
        expected.put("int v = -n;", UnaryBlock.class);
        expected.put("int v = n++;", UnaryBlock.class);
        expected.put("int v = ~n;", UnaryBlock.class);
        expected.put("int v = (n = 3);", AssignmentValueBlock.class);
        expected.put("int v = switch (n) { case 1 -> 10; default -> 0; };", SwitchExpressionBlock.class);
        expected.put("int v = switch (n) { case 1: yield 10; default: yield 0; };", SourceExpressionBlock.class);
        expected.put("Runnable v = new Runnable() { public void run() { } };", SourceExpressionBlock.class);
        assertAll(expected.entrySet().stream().map(e -> (org.junit.jupiter.api.function.Executable) () ->
                assertInstanceOf(e.getValue(), valueOf(new EditorFixture(source(e.getKey()))), e.getKey())));
    }

    /** Every expression kind the JDT has, in one method — and not one of them is drawn as "no block yet". */
    @Test
    void noExpressionFallsToTheUnknownBlock() {
        EditorFixture f = new EditorFixture(source("""
                int a = flag ? 1 : 2;
                double d = (double) a;
                boolean b = o instanceof String s && s.isEmpty() || o instanceof Integer;
                Runnable r = () -> System.out.println(a);
                java.util.function.IntBinaryOperator add = (int x, int y) -> { return x + y; };
                java.util.function.Supplier<String> make = String::new;
                int first = numbers[a - 1];
                int[][] grid = new int[3][];
                char c = 'z';
                String t = \"""
                    two
                    lines\""";
                long big = 0x7FFF_FFFFL + 1_000 + 2e3 > 0 ? 1L : 2L;
                Class<?> k = int[].class;
                Object self = Subject.this;
                String parent = super.toString() + super.size;
                a = -a + ~a;
                a += a++ + --a;
                a |= 1;
                int sum = (a + 1) * (a - 1) + a + a;
                int picked = switch (a) { case 1, 2 -> 10; case 3 -> { yield 30; } default -> throw new IllegalStateException(); };
                Object named = switch (o) { case String str -> str; default -> o; };
                for (int i = 0, j = 9; i < j; i++, j--) { }
                try (var in = new java.io.StringReader("")) { in.read(); }
                Runnable anonymous = new Runnable() { public void run() { } };
                while ((a = a - 1) > 0) { }"""));
        List<String> unknown = new ArrayList<>();
        f.state.getNodeToBlockMap().forEach((node, block) -> {
            if (block instanceof UnknownExpressionBlock) unknown.add(node + " (" + node.getClass().getSimpleName() + ")");
        });
        assertTrue(unknown.isEmpty(), "drawn as unknown: " + unknown);
    }

    @Test
    void everyOperandOfALongSumIsDrawn() {
        EditorFixture f = new EditorFixture(source("int v = 1 + 2 + 3 + 4;"));
        InfixExpression sum = (InfixExpression) initializer(f);
        assertEquals(2, sum.extendedOperands().size());
        for (Object extra : sum.extendedOperands()) {
            assertNotNull(f.state.getNodeToBlockMap().get((ASTNode) extra), "the operand " + extra + " has a block");
        }
    }

    @Test
    void parenthesesAreTheBlockOfWhatTheyHold() {
        EditorFixture f = new EditorFixture(source("int v = (n + 1) * 2;"));
        InfixExpression product = (InfixExpression) initializer(f);
        CodeBlock grouped = f.state.getNodeToBlockMap().get(product.getLeftOperand());
        assertNotNull(grouped);
        assertInstanceOf(InfixExpression.class, grouped.getAstNode(), "the block is the sum inside");
    }

    @Test
    void aDeclarationInsideAStatementIsItsOwnBlock() {
        EditorFixture f = new EditorFixture(source("try (var in = new java.io.StringReader(\"\")) { in.read(); }"));
        TryStatement tryStmt = (TryStatement) f.body("run").getStatements().getFirst().getAstNode();
        ASTNode resource = (ASTNode) tryStmt.resources().getFirst();
        assertInstanceOf(DeclarationExpressionBlock.class, f.state.getNodeToBlockMap().get(resource));

        SimpleName name = ((VariableDeclarationFragment)
                ((VariableDeclarationExpression) resource).fragments().getFirst()).getName();
        f.editor.renameScopedVariable(name, "reader");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("var reader = new java.io.StringReader") && f.lastCode.contains("reader.read()"),
                f.lastCode);
    }

    // ---- The edits the value blocks make ----

    @Test
    void aCastIsRetypedAndRefusesWhatIsNotAType() {
        EditorFixture f = new EditorFixture(source("long v = (int) 2.5;"));
        f.editor.setExpressionType(initializer(f), "long");
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("(long) 2.5"), f.lastCode);

        EditorFixture g = new EditorFixture(source("long v = (int) 2.5;"));
        g.editor.setExpressionType(initializer(g), "not a type!");
        assertNull(g.lastCode, "text that is not a type is refused");
        assertFalse(g.statusMessages.isEmpty(), "and the refusal says why");
    }

    @Test
    void aTypeCheckAClassLiteralAndANewArrayAreRetyped() {
        EditorFixture f = new EditorFixture(source("boolean v = o instanceof String;"));
        f.editor.setExpressionType(initializer(f), "java.util.List<String>");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("o instanceof java.util.List<String>"), f.lastCode);

        EditorFixture g = new EditorFixture(source("Class<?> v = String.class;"));
        g.editor.setExpressionType(initializer(g), "Integer");
        assertNotNull(g.lastCode);
        assertTrue(g.lastCode.contains("Integer.class"), g.lastCode);

        EditorFixture h = new EditorFixture(source("long[] v = new int[5];"));
        h.editor.setExpressionType(initializer(h), "long");
        assertNotNull(h.lastCode);
        assertTrue(h.lastCode.contains("new long[5]"), h.lastCode);
    }

    @Test
    void aPatternVariableIsRetypedAndRenamedInItsOwnCheckOnly() {
        EditorFixture f = new EditorFixture(source("""
                if (o instanceof String s) { System.out.println(s); }
                if (o instanceof Integer s) { System.out.println(s); }"""));
        PatternInstanceofExpression[] checks = new PatternInstanceofExpression[2];
        f.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            int i;
            @Override
            public boolean visit(PatternInstanceofExpression node) {
                checks[i++] = node;
                return true;
            }
        });
        SingleVariableDeclaration variable = (SingleVariableDeclaration)
                ((TypePattern) checks[0].getPattern()).getPatternVariable();
        f.editor.renamePatternVariable(variable.getName(), "text");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("o instanceof String text)") && f.lastCode.contains("println(text)"),
                f.lastCode);
        assertTrue(f.lastCode.contains("o instanceof Integer s) { System.out.println(s); }"),
                "the second check keeps its s: " + f.lastCode);

        EditorFixture g = new EditorFixture(source("if (o instanceof String s) { }"));
        g.editor.setExpressionType(((org.eclipse.jdt.core.dom.IfStatement)
                g.body("run").getStatements().getFirst().getAstNode()).getExpression(), "CharSequence");
        assertNotNull(g.lastCode);
        assertTrue(g.lastCode.contains("o instanceof CharSequence s"), g.lastCode);
    }

    @Test
    void aNumberKeepsItsSpellingAndRefusesWhatIsNotOne() {
        EditorFixture f = new EditorFixture(source("int v = 0xFF;"));
        f.editor.setNumberToken((NumberLiteral) initializer(f), "0x1F");
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("int v = 0x1F;"), f.lastCode);

        EditorFixture g = new EditorFixture(source("int v = 0xFF;"));
        g.editor.setNumberToken((NumberLiteral) initializer(g), "-1");
        assertNull(g.lastCode, "a sign is an operator, not part of a number literal");
        g.editor.setNumberToken((NumberLiteral) initializer(g), "twelve");
        assertNull(g.lastCode);
    }

    @Test
    void aCharacterHoldsOneCharacter() {
        EditorFixture f = new EditorFixture(source("char v = 'a';"));
        f.editor.setCharacter((CharacterLiteral) initializer(f), "'");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        assertTrue(f.lastCode.contains("char v = '\\'';"), f.lastCode);

        EditorFixture g = new EditorFixture(source("char v = 'a';"));
        g.editor.setCharacter((CharacterLiteral) initializer(g), "ab");
        assertNull(g.lastCode);
    }

    @Test
    void aTextBlockIsRewrittenAtItsOwnIndentation() {
        EditorFixture f = new EditorFixture(source("String v = \"\"\"\n    one\n    \"\"\";"));
        f.editor.setTextBlock((TextBlock) initializer(f), "first \"\"\" line\nsecond");
        assertNotNull(f.lastCode);
        assertParses(f.lastCode);
        EditorFixture after = new EditorFixture(f.lastCode);
        assertEquals("first \"\"\" line\nsecond", ((TextBlock) initializer(after)).getLiteralValue());
    }

    @Test
    void aValueTypedAsJavaIsParenthesisedWhereAnOperatorWouldSplitIt() {
        EditorFixture f = new EditorFixture(source("int v = n * 2;"));
        InfixExpression product = (InfixExpression) initializer(f);
        f.editor.replaceExpressionSource(product.getLeftOperand(), "n + 1");
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("int v = (n + 1) * 2;"), f.lastCode);

        EditorFixture g = new EditorFixture(source("int v = n * 2;"));
        g.editor.replaceExpressionSource(((InfixExpression) initializer(g)).getLeftOperand(), "n +");
        assertNull(g.lastCode, "a typo is not written");
        assertFalse(g.statusMessages.isEmpty());
    }

    @Test
    void aMenuPickInsideAnOperatorIsParenthesised() {
        EditorFixture f = new EditorFixture(source("int v = n * 2;"));
        f.editor.replaceExpression(((InfixExpression) initializer(f)).getLeftOperand(), ExpressionCatalog.ADD);
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("int v = (0 + 0) * 2;"), f.lastCode);
    }

    @Test
    void everyCompoundAssignmentOperatorCanBePicked() {
        EditorFixture f = new EditorFixture(source("n |= 2;"));
        Assignment assignment = (Assignment) ((ExpressionStatement)
                f.body("run").getStatements().getFirst().getAstNode()).getExpression();
        f.editor.updateAssignmentOperator(assignment, ">>>=");
        assertNotNull(f.lastCode);
        assertTrue(f.lastCode.contains("n >>>= 2;"), f.lastCode);
    }

    // ---- The expression menu ----

    @Test
    void theNewMenuFormsBuildValuesThatBecomeTheirBlocks() {
        record Case(String declaration, ExpressionType form, Class<? extends CodeBlock> block) {}
        List<Case> cases = List.of(
                new Case("int v = 0;", ExpressionCatalog.CHOOSE, ConditionalBlock.class),
                new Case("int v = 0;", ExpressionCatalog.NEGATE, UnaryBlock.class),
                new Case("int v = 0;", ExpressionCatalog.CAST, CastBlock.class),
                new Case("boolean v = false;", ExpressionCatalog.INSTANCEOF, InstanceofBlock.class),
                new Case("int[] v = null;", ExpressionCatalog.NEW_ARRAY, ArrayCreationBlock.class),
                new Case("char v = 'x';", ExpressionCatalog.CHARACTER, TokenLiteralBlock.class),
                new Case("Class<?> v = null;", ExpressionCatalog.CLASS_LITERAL, TypeLiteralBlock.class),
                new Case("Runnable v = null;", ExpressionCatalog.LAMBDA, LambdaBlock.class),
                new Case("java.util.function.IntUnaryOperator v = null;", ExpressionCatalog.LAMBDA, LambdaBlock.class));
        assertAll(cases.stream().map(c -> (org.junit.jupiter.api.function.Executable) () -> {
            EditorFixture f = new EditorFixture(source(c.declaration()));
            f.editor.replaceExpression(initializer(f), c.form());
            assertNotNull(f.lastCode, c.form().id() + " wrote nothing");
            assertParses(f.lastCode);
            assertInstanceOf(c.block(), valueOf(new EditorFixture(f.lastCode)), f.lastCode);
        }));
    }

    @Test
    void aLambdaIsOfferedOnlyWhereAFunctionalInterfaceIsExpected() {
        EditorFixture f = new EditorFixture(source("Runnable v = null;\nString w = null;"));
        VariableDeclarationStatement runnable =
                (VariableDeclarationStatement) f.body("run").getStatements().get(0).getAstNode();
        VariableDeclarationStatement text =
                (VariableDeclarationStatement) f.body("run").getStatements().get(1).getAstNode();
        ResolvedType runnableType = ResolvedType.of(runnable.getType().resolveBinding());
        ResolvedType textType = ResolvedType.of(text.getType().resolveBinding());
        assertTrue(ExpressionCatalog.isCompatibleWith(ExpressionCatalog.LAMBDA, runnableType, null));
        assertFalse(ExpressionCatalog.isCompatibleWith(ExpressionCatalog.LAMBDA, textType, null));
        assertFalse(ExpressionCatalog.isCompatibleWith(ExpressionCatalog.NEW_ARRAY, textType, null));
    }
}
