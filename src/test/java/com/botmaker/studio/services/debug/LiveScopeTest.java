package com.botmaker.studio.services.debug;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.SimpleName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which names on the canvas may show the paused frame's value: a local whose declaration is in scope at the
 * paused line. Parsed without a classpath, as the editor often is, so nothing here leans on a binding.
 */
class LiveScopeTest {

    private static final String SOURCE = """
            class Bot {
                int count;
                void play(int rounds) {
                    int score = 0;
                    for (int i = 0; i < rounds; i++) {
                        score = score + i;
                    }
                    if (rounds > 1) {
                        String label = "a";
                        System.out.println(label);
                    } else {
                        String label = "b";
                        System.out.println(label);
                    }
                    count = score;
                    Runnable r = () -> System.out.println(score);
                }
                void other() {
                    int score = 9;
                }
            }
            """;

    private static CompilationUnit parse() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(SOURCE.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    /** Every occurrence of {@code name}, in source order. */
    private static List<SimpleName> names(CompilationUnit cu, String name) {
        List<SimpleName> out = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName n) {
                if (n.getIdentifier().equals(name)) out.add(n);
                return true;
            }
        });
        return out;
    }

    private static int line(CompilationUnit cu, SimpleName n) {
        return cu.getLineNumber(n.getStartPosition());
    }

    @Test
    void aLocalIsLiveOnlyOnceDeclaredAndWhileItsBlockIsOpen() {
        CompilationUnit cu = parse();
        SimpleName scoreInLoop = names(cu, "score").get(1);
        assertTrue(LiveScope.isLive(scoreInLoop, cu, 6));
        assertTrue(LiveScope.isLive(scoreInLoop, cu, 15));
        assertFalse(LiveScope.isLive(scoreInLoop, cu, 4), "the declaration line has not run yet");
        SimpleName i = names(cu, "i").get(1);
        assertTrue(LiveScope.isLive(i, cu, 6));
        assertFalse(LiveScope.isLive(i, cu, 8), "the loop is over");
    }

    @Test
    void aParameterIsLiveInTheWholeMethodAndAFieldNever() {
        CompilationUnit cu = parse();
        SimpleName rounds = names(cu, "rounds").get(1);
        assertTrue(LiveScope.isLive(rounds, cu, 4));
        SimpleName count = names(cu, "count").get(1);
        assertEquals(15, line(cu, count));
        assertFalse(LiveScope.isLive(count, cu, 16), "a field is not a local of the frame");
    }

    @Test
    void twoSiblingLocalsOfOneNameAreTold() {
        CompilationUnit cu = parse();
        List<SimpleName> labels = names(cu, "label");
        SimpleName thenUse = labels.get(1);
        SimpleName elseUse = labels.get(3);
        assertTrue(LiveScope.isLive(thenUse, cu, 10));
        assertFalse(LiveScope.isLive(elseUse, cu, 10), "paused in the then branch, the else label is not it");
        assertTrue(LiveScope.isLive(elseUse, cu, 13));
    }

    @Test
    void aDeclarationAndAnAssignmentSpeakForTheirVariableAndAnAssignedNameIsNotShownTwice() {
        CompilationUnit cu = parse();
        List<SimpleName> scores = names(cu, "score");
        org.eclipse.jdt.core.dom.ASTNode declaration = scores.getFirst().getParent().getParent();
        assertEquals(List.of("score"), LiveScope.shownBy(declaration, false).stream()
                .map(SimpleName::getIdentifier).toList());
        SimpleName target = scores.get(1);
        org.eclipse.jdt.core.dom.ASTNode assignment = target.getParent().getParent();
        assertEquals(List.of(target), LiveScope.shownBy(assignment, false));
        assertEquals(List.of(), LiveScope.shownBy(target, true), "the assignment's chip already says it");
        SimpleName read = scores.get(2);
        assertEquals(List.of(read), LiveScope.shownBy(read, true));
        assertEquals(List.of(), LiveScope.shownBy(read, false), "a name that is not a name block");
    }

    @Test
    void anotherMethodsLocalIsNotTheFramesAndALambdaSeesWhatItCaptures() {
        CompilationUnit cu = parse();
        List<SimpleName> scores = names(cu, "score");
        SimpleName otherScore = scores.getLast();
        assertFalse(LiveScope.isLive(otherScore, cu, 15));
        SimpleName captured = scores.get(scores.size() - 2);
        assertEquals(16, line(cu, captured));
        assertTrue(LiveScope.isLive(captured, cu, 16));
    }
}
