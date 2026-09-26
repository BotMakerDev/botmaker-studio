package com.botmaker.studio.nav;

import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What the Navigate menu reads off a tree: a line's node, a file's structure, a name's declaration and doc. */
class SourceNavigationTest {

    private static final String BOT = """
            package com.mybot;

            public class Bot {
                /** How many rounds to play. */
                static int rounds = 3;

                public static void main(String[] args) {
                    int left = rounds;
                    if (left > 0) {
                        play(left);
                    }
                }

                /**
                 * Plays {@code count} rounds.
                 * <p>Stops early on a loss.
                 */
                static void play(int count) {
                }

                enum Mode { FAST, SLOW }
            }
            """;

    private static CompilationUnit parse(String source) {
        return ProjectAnalyzer.createCompilationUnit(List.of(), source, null, "/nav/com/mybot/Bot.java");
    }

    @Test
    void aLineLandsOnTheStatementThatStartsThere() {
        CompilationUnit cu = parse(BOT);
        assertInstanceOf(VariableDeclarationStatement.class, SourceNavigation.nodeAtLine(cu, 8).orElseThrow());
        assertInstanceOf(IfStatement.class, SourceNavigation.nodeAtLine(cu, 9).orElseThrow(),
                "the if, not its condition or its body");
        assertInstanceOf(ExpressionStatement.class, SourceNavigation.nodeAtLine(cu, 10).orElseThrow());
        // The closing brace of the if: nothing starts there, so the innermost statement spanning it.
        assertInstanceOf(org.eclipse.jdt.core.dom.Block.class, SourceNavigation.nodeAtLine(cu, 11).orElseThrow());
        assertTrue(SourceNavigation.nodeAtLine(cu, 999).isEmpty());
    }

    @Test
    void theStructureListsEveryDeclarationNestedInSourceOrder() {
        List<String> rows = SourceNavigation.structure(parse(BOT)).stream()
                .map(e -> e.depth() + e.kind().glyph() + e.label()).toList();
        assertEquals(List.of("0◆Bot", "1▪rounds: int", "1ƒmain(String[]): void", "1ƒplay(int): void",
                "1◆Mode", "2•FAST", "2•SLOW"), rows);
    }

    @Test
    void aCallIsDeclaredInThisFileAndDocumentedFromItsJavadoc() {
        CompilationUnit cu = parse(BOT);
        MethodInvocation call = (MethodInvocation) ((ExpressionStatement) SourceNavigation.nodeAtLine(cu, 10)
                .orElseThrow()).getExpression();
        IBinding binding = SourceNavigation.bindingOf(call.getParent()).orElseThrow();
        SourceNavigation.Declaration where = SourceNavigation.declarationOf(binding, cu, null).orElseThrow();
        ASTNode declared = assertInstanceOf(SourceNavigation.Declaration.Here.class, where).node();
        assertEquals("play", ((MethodDeclaration) declared).getName().getIdentifier());

        SourceNavigation.Doc doc = SourceNavigation.docOf(binding, cu, null);
        assertEquals("void play(int)  —  in Bot", doc.heading());
        assertTrue(doc.body().startsWith("Plays count rounds.\nStops early on a loss."), doc.body());
        assertTrue(doc.body().endsWith("Declared on line 18."), doc.body());
    }

    @Test
    void aVariableSaysWhatItIs() {
        CompilationUnit cu = parse(BOT);
        IBinding left = SourceNavigation.bindingOf(SourceNavigation.nodeAtLine(cu, 8).orElseThrow()).orElseThrow();
        assertEquals("int left  —  local variable", SourceNavigation.docOf(left, cu, null).heading());
        IBinding rounds = SourceNavigation.bindingOf(
                SourceNavigation.structure(cu).get(1).node()).orElseThrow();
        SourceNavigation.Doc doc = SourceNavigation.docOf(rounds, cu, null);
        assertEquals("int rounds  —  field of Bot", doc.heading());
        assertTrue(doc.body().startsWith("How many rounds to play."), doc.body());
    }

    @Test
    void aLibraryMemberHasNoDeclarationToGoTo() {
        CompilationUnit cu = parse("""
                package com.mybot;
                class Bot { void run() { String s = "a"; s.length(); } }
                """);
        MethodInvocation length = (MethodInvocation) ((ExpressionStatement) ((MethodDeclaration)
                ((org.eclipse.jdt.core.dom.TypeDeclaration) cu.types().getFirst()).getMethods()[0])
                .getBody().statements().get(1)).getExpression();
        IBinding binding = SourceNavigation.bindingOf(length).orElseThrow();
        assertTrue(SourceNavigation.declarationOf(binding, cu, null).isEmpty());
        assertEquals("The length.", SourceNavigation.docOf(binding, cu, "The length.").body());
    }

    @Test
    void aMemberOfAnotherBotClassIsDeclaredInItsFile(@TempDir Path root) throws Exception {
        Path helper = root.resolve("com/mybot/Helper.java");
        Files.createDirectories(helper.getParent());
        Files.writeString(helper, "package com.mybot;\npublic class Helper { public static void go() {} }\n");
        Path bot = root.resolve("com/mybot/Bot.java");
        String source = "package com.mybot;\nclass Bot { void run() { Helper.go(); } }\n";
        Files.writeString(bot, source);
        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(List.of(), source, root, bot.toString());
        MethodInvocation go = (MethodInvocation) ((ExpressionStatement) ((MethodDeclaration)
                ((org.eclipse.jdt.core.dom.TypeDeclaration) cu.types().getFirst()).getMethods()[0])
                .getBody().statements().getFirst()).getExpression();
        IBinding binding = SourceNavigation.bindingOf(go).orElseThrow();
        SourceNavigation.Declaration.Elsewhere elsewhere = assertInstanceOf(SourceNavigation.Declaration.Elsewhere.class,
                SourceNavigation.declarationOf(binding, cu, root).orElseThrow());
        assertEquals(helper, elsewhere.file());

        CompilationUnit other = ProjectAnalyzer.createCompilationUnit(List.of(), Files.readString(helper), root,
                helper.toString());
        assertInstanceOf(MethodDeclaration.class, other.findDeclaringNode(elsewhere.key()),
                "the key finds the declaration once that file is open");
    }

    @Test
    void matchingPrefersAPrefixThenASubstringThenLettersInOrder() {
        assertTrue(SourceNavigation.match("game", "GameBot.java") > SourceNavigation.match("bot", "GameBot.java"));
        assertTrue(SourceNavigation.match("bot", "GameBot.java") > SourceNavigation.match("gb", "GameBot.java"));
        assertTrue(SourceNavigation.match("gb", "GameBot.java") >= 0);
        assertTrue(SourceNavigation.match("zz", "GameBot.java") < 0);
        assertTrue(SourceNavigation.match("sdk", "Sdk.java") > SourceNavigation.match("sdk", "SdkHelpers.java"));
    }
}
