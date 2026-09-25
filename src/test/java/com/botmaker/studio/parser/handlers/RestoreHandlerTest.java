package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.helpers.SourceParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One function put back as an earlier version had it ({@code 39} §6). */
class RestoreHandlerTest {

    private static final String OLD = """
            import java.util.List;
            import java.time.Duration;

            class Bot {
                void a() { log("a"); }
                void mine() {
                    // slow and steady
                    wait(500);
                    List<String> seen = null;
                }
                void c() { log("c"); }
            }
            """;

    private static String restore(String live, String signature) {
        return RestoreHandler.replaceMethod(SourceParser.parse(live), live, OLD, signature);
    }

    @Test
    void replacesTheLiveFunctionWithTheOldTextCommentsIncluded() {
        String live = """
                class Bot {
                    void a() { log("a"); }
                    void mine() { wait(200); }
                    void c() { log("c"); }
                }
                """;
        String out = restore(live, "Bot#mine()");
        assertTrue(out.contains("// slow and steady"), out);
        assertTrue(out.contains("wait(500);"), out);
        assertTrue(!out.contains("wait(200)"), out);
        assertTrue(out.contains("import java.util.List;"), "the import its body names comes back:\n" + out);
        assertTrue(!out.contains("java.time.Duration"), "an import it does not name stays out:\n" + out);
    }

    @Test
    void aFunctionTheFileLostLandsWhereItUsedToBe() {
        String live = """
                import java.util.List;

                class Bot {
                    void a() { log("a"); }
                    void c() { log("c"); }
                }
                """;
        String out = restore(live, "Bot#mine()");
        int a = out.indexOf("void a()");
        int mine = out.indexOf("void mine()");
        int c = out.indexOf("void c()");
        assertTrue(a < mine && mine < c, out);
        assertEquals(1, out.split("import java.util.List;", -1).length - 1, "no second import:\n" + out);
    }

    @Test
    void aFunctionTheVersionDoesNotHaveIsNoEdit() {
        assertNull(restore("class Bot { }", "Bot#nope()"));
    }

    @Test
    void aTypeTheFileNoLongerHasIsNoEdit() {
        assertNull(restore("class Other { }", "Bot#mine()"));
    }
}
