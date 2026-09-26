package com.botmaker.studio.services.debug;

import com.botmaker.studio.parser.BlockId;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.ProjectState.SourceFile;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every file of the bot is a place a session can stop — the reason this exists is that only the entry class
 * used to be, so a breakpoint in an activity's file never hit.
 */
class DebugTargetsTest {

    private static final Path ROOT = Path.of("/p/src/main/java");
    private static final Path MAIN = ROOT.resolve("com/bot/Main.java");
    private static final Path COLLECT = ROOT.resolve("com/bot/Collect.java");

    private static final String MAIN_SRC = """
            package com.bot;
            public class Main {
                public static void main(String[] args) {
                    int x = 1;
                    System.out.println(x);
                }
            }
            """;
    private static final String COLLECT_SRC = """
            package com.bot;
            public class Collect {
                static void body() {
                    tap();
                    tap();
                }
                static void tap() {}
                static class Inner {}
            }
            """;

    @Test
    void everyFileAnswersForItsTopLevelClassesAndTheirNestedOnes() {
        DebugTargets targets = DebugTargets.of(ROOT,
                List.of(new SourceFile(MAIN, MAIN_SRC), new SourceFile(COLLECT, COLLECT_SRC)), Map.of());

        assertEquals(Set.of("com.bot.Main", "com.bot.Collect"), targets.classNames());
        assertEquals(COLLECT, targets.forClass("com.bot.Collect$Inner").orElseThrow().file());
        assertEquals(Optional.empty(), targets.forClass("java.lang.String"));
    }

    @Test
    void theLinesAreTheStatementsAndNotTheBraces() {
        DebugTargets targets = DebugTargets.of(ROOT, List.of(new SourceFile(MAIN, MAIN_SRC)), Map.of());
        assertEquals(Set.of(4, 5), targets.forClass("com.bot.Main").orElseThrow().lines());
        assertEquals(Optional.of(4), targets.firstLine(MAIN));
        assertFalse(targets.anyBreakpoint());
    }

    @Test
    void aBreakpointInAnotherFileIsOnItsOwnLine() {
        List<String> ids = new ArrayList<>();
        SourceParser.parse(COLLECT_SRC).accept(new ASTVisitor() {
            @Override public boolean visit(ExpressionStatement node) { ids.add(BlockId.of(node)); return true; }
        });
        DebugTargets targets = DebugTargets.of(ROOT,
                List.of(new SourceFile(MAIN, MAIN_SRC), new SourceFile(COLLECT, COLLECT_SRC)),
                Map.of(COLLECT, Set.of(ids.get(1))));

        assertTrue(targets.anyBreakpoint());
        assertEquals(Set.of(5), targets.forClass("com.bot.Collect").orElseThrow().breakpointLines());
        assertEquals(Set.of(), targets.forClass("com.bot.Main").orElseThrow().breakpointLines());
    }

    @Test
    void aFileOutsideTheSourceRootIsNotTheBots() {
        DebugTargets targets = DebugTargets.of(ROOT,
                List.of(new SourceFile(Path.of("/elsewhere/Main.java"), MAIN_SRC)), Map.of());
        assertTrue(targets.classNames().isEmpty());
    }
}
