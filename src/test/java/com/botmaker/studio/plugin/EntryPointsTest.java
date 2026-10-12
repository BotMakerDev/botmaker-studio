package com.botmaker.studio.plugin;

import com.botmaker.studio.parser.helpers.SourceParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether a bot's {@code main} starts it: the menu's ▶ entry and the Problems hint both ask this. */
class EntryPointsTest {

    private static final BiPredicate<String, String> RUN_FLOW =
            (facade, method) -> facade.equals("Bot") && method.equals("runFlow");

    private static CompilationUnit unit(String main) {
        return SourceParser.parse("""
                package com.mybot;
                public class Base {
                    static void helper() { Bot.runFlow(null, false, null); }
                %s
                }
                """.formatted(main.indent(4)));
    }

    @Test
    void aMainThatOnlyPrintsStartsNothing() {
        MethodDeclaration main = EntryPoints.main(
                unit("public static void main(String[] args) { System.out.println(\"Hello\"); }")).orElseThrow();

        assertEquals("main", main.getName().getIdentifier());
        assertFalse(EntryPoints.calledIn(main, RUN_FLOW), "a call in another method does not count");
    }

    @Test
    void aMainCallingTheEntryPointStartsTheBot() {
        MethodDeclaration main = EntryPoints.main(
                unit("public static void main(String[] args) { if (true) { Bot.runFlow(null, false, null); } }"))
                .orElseThrow();
        MethodDeclaration qualified = EntryPoints.main(
                unit("public static void main(String[] args) { com.x.Bot.runFlow(null, false, null); }"))
                .orElseThrow();

        assertTrue(EntryPoints.calledIn(main, RUN_FLOW));
        assertTrue(EntryPoints.calledIn(qualified, RUN_FLOW));
    }

    @Test
    void aClassWithoutMainHasNone() {
        assertTrue(EntryPoints.main(unit("void run() {}")).isEmpty());
        assertTrue(EntryPoints.main(null).isEmpty());
    }
}
