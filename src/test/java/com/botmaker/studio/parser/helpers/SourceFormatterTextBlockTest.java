package com.botmaker.studio.parser.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** A file holding a text block is still laid out: a screen's notes are one (2026-10-15). */
class SourceFormatterTextBlockTest {

    @Test
    void aFileWithATextBlockIsLaidOut() {
        String source = "class A {\n    static final String S = String.valueOf(1).concat(\"a\").concat(\"b\").concat(\"c\")"
                + ".concat(\"d\").concat(\"e\").concat(\"f\").concat(\"g\").concat(\"h\").concat(\"i\").concat(\"j\")"
                + ".concat(\"\"\"\n            one\n            two\\\n            \"\"\");\n}\n";
        String laid = SourceFormatter.format(source);
        assertNotEquals(source, laid, "not laid out:\n" + laid);
        String changed = SourceFormatter.formatChanged("class A {\n    static final String S = \"x\";\n}\n", source);
        assertNotEquals(source, changed, "the changed lines not laid out:\n" + changed);
    }
}
