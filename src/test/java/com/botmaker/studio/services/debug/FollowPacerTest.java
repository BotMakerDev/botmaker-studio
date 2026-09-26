package com.botmaker.studio.services.debug;

import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Follow's pacing: one frame per window, and a loop recognised when a line comes round within one. */
class FollowPacerTest {

    private static final Path A = Path.of("A.java");
    private static final Path B = Path.of("B.java");

    @Test
    void aWindowWithNoLinesIsNoFrame() {
        assertNull(new FollowPacer().take());
    }

    @Test
    void straightLineCodeShowsTheLatestLineAndIsNotALoop() {
        FollowPacer pacer = new FollowPacer();
        pacer.hit(new FollowPacer.Hit(A, 3));
        pacer.hit(new FollowPacer.Hit(A, 4));

        FollowPacer.Frame frame = pacer.take();
        assertEquals(new FollowPacer.Hit(A, 4), frame.latest());
        assertFalse(frame.looping());
        assertNull(pacer.take(), "taking a frame starts a fresh window");
    }

    @Test
    void aLineHitTwiceInOneWindowIsALoop() {
        FollowPacer pacer = new FollowPacer();
        for (int i = 0; i < 3; i++) {
            pacer.hit(new FollowPacer.Hit(A, 5));
            pacer.hit(new FollowPacer.Hit(A, 6));
        }
        FollowPacer.Frame frame = pacer.take();
        assertTrue(frame.looping());
        assertEquals(2, frame.hits().size());
        assertEquals(Optional.of(A), frame.singleFile());
    }

    @Test
    void aWindowAcrossTwoFilesHasNoSingleFile() {
        FollowPacer pacer = new FollowPacer();
        pacer.hit(new FollowPacer.Hit(A, 1));
        pacer.hit(new FollowPacer.Hit(B, 1));
        assertEquals(Optional.empty(), pacer.take().singleFile());
    }

    @Test
    void theEnclosingLoopIsTheInnermostOneHoldingEveryLine() {
        CompilationUnit cu = SourceParser.parse("""
                class C {
                    void m() {
                        while (true) {
                            a();
                            while (x()) {
                                b();
                                c();
                            }
                        }
                    }
                }
                """);
        List<ExpressionStatement> calls = new ArrayList<>();
        List<WhileStatement> loops = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(ExpressionStatement node) { calls.add(node); return true; }
            @Override public boolean visit(WhileStatement node) { loops.add(node); return true; }
        });
        ASTNode a = calls.get(0), b = calls.get(1), c = calls.get(2);

        assertSame(loops.get(1), FollowPacer.enclosingLoop(List.of(b, c)).orElseThrow(), "inner loop holds b and c");
        assertSame(loops.get(0), FollowPacer.enclosingLoop(List.of(a, b)).orElseThrow(), "only the outer holds a");
        assertSame(loops.get(1), FollowPacer.enclosingLoop(List.of(loops.get(1), b)).orElseThrow(),
                "a loop's own header line counts as inside it");
    }

    @Test
    void codeInNoSharedLoopHasNone() {
        CompilationUnit cu = SourceParser.parse("class C { void m() { a(); b(); } }");
        List<ASTNode> calls = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override public boolean visit(ExpressionStatement node) { calls.add(node); return true; }
        });
        assertTrue(FollowPacer.enclosingLoop(calls).isEmpty());
    }
}
