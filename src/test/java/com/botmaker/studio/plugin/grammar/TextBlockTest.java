package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A string of several lines is written as a text block holding exactly it; one line stays a plain literal. */
class TextBlockTest {

    private static final AST AST_ = AST.newAST(AST.getJLSLatest(), false);

    @Test
    void aTextBlockHoldsExactlyTheValueItWasWrittenFrom() {
        for (String value : List.of("a\nb", "Loot fills over 8 h.\n\nIts badge shows on Home.", "end space \nnext",
                "quotes \"\"\" and a \\ backslash\nand \"one\"", "tab\there\nlast line\n")) {
            Expression written = JdkLiterals.node(AST_, String.class, value).orElseThrow();
            TextBlock block = assertInstanceOf(TextBlock.class, written, value);
            assertEquals(value, JdkLiterals.value(block), block.getEscapedValue());
        }
    }

    @Test
    void oneLineStaysAPlainString() {
        assertInstanceOf(StringLiteral.class, JdkLiterals.node(AST_, String.class, "Tavern").orElseThrow());
    }
}
