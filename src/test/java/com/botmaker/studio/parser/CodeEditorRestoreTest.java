package com.botmaker.studio.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restore is an edit ({@code 39} §6): it publishes like one, and a user's own code coming back lands even when
 * it no longer compiles — {@code log} is declared nowhere here.
 */
class CodeEditorRestoreTest {

    private static final String LIVE = """
            package com.mybot;
            public class Bot {
                void mine() { int a = 1; }
            }
            """;

    private static final String OLD = """
            package com.mybot;
            public class Bot {
                void mine() { log("slow"); }
                void home() { log("home"); }
            }
            """;

    @Test
    void aFunctionComesBackEvenWhenItNoLongerCompiles() {
        EditorFixture f = new EditorFixture(LIVE);
        f.editor.replaceMethod(OLD, "Bot#mine()");
        assertNotNull(f.lastCode, "published: " + f.statusMessages);
        assertTrue(f.lastCode.contains("log(\"slow\")"), f.lastCode);
    }

    @Test
    void aLostFunctionIsAddedBack() {
        EditorFixture f = new EditorFixture(LIVE);
        f.editor.replaceMethod(OLD, "Bot#home()");
        assertNotNull(f.lastCode, "published: " + f.statusMessages);
        assertTrue(f.lastCode.contains("void home()") && f.lastCode.contains("int a = 1"), f.lastCode);
    }

    @Test
    void aWholeFileIsOneEdit() {
        EditorFixture f = new EditorFixture(LIVE);
        f.editor.replaceFile(OLD);
        assertNotNull(f.lastCode, "published: " + f.statusMessages);
        assertTrue(f.lastCode.contains("void home()"), f.lastCode);
    }
}
