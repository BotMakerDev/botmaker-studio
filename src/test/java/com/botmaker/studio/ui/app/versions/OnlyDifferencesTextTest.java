package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.project.vcs.BlockDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Java side of a function card under <i>Only differences</i>: the changed statements, and nothing else. */
class OnlyDifferencesTextTest {

    private static final String SOURCE = """
            void run() {
                a();
                b();
                if (x) {
                    c();
                }
                d();
            }
            """;

    private static BlockDiff.Span span(String statement) {
        return new BlockDiff.Span(SOURCE.indexOf(statement), statement.length(), BlockDiff.Mark.CHANGED);
    }

    @Test
    void theChangedStatementsInSourceOrder() {
        String text = DiffCards.onlyText(SOURCE, List.of(span("d();"), span("b();")));

        assertEquals("b();\n⋯\nd();", text);
    }

    @Test
    void aStatementInsideAListedContainerIsNotRepeated() {
        String container = "if (x) {\n        c();\n    }";
        String text = DiffCards.onlyText(SOURCE, List.of(span(container), span("c();")));

        assertEquals(container, text);
    }
}
