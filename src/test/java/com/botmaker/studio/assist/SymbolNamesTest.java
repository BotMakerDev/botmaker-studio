package com.botmaker.studio.assist;

import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A declaration named the way an assistant names it, found as the name node a rename starts from. */
class SymbolNamesTest {

    private static final String COLLECT = """
            package com.bot;
            public class Collect {
                static int tries = 3;
                static void body(int wait) {
                    int count = wait;
                }
                static void again() {}
                static void again(int n) {}
            }
            """;
    private static final String MODE = """
            package com.bot;
            enum Mode { FAST, SLOW }
            """;

    private final Map<Path, CompilationUnit> units = new LinkedHashMap<>();

    {
        units.put(Path.of("Collect.java"), BotParser.syntax(COLLECT));
        units.put(Path.of("Mode.java"), BotParser.syntax(MODE));
    }

    private String named(String symbol) {
        SymbolNames.Located at = SymbolNames.locate(units, symbol);
        String source = at.file().toString().equals("Mode.java") ? MODE : COLLECT;
        return source.substring(at.start()).split("\\W")[0];
    }

    @Test
    void eachKindIsFoundAtItsName() {
        assertEquals("Collect", named("Collect"));
        assertEquals("body", named("Collect.body"));
        assertEquals("body", named("Collect.body()"));
        assertEquals("tries", named("Collect.tries"));
        assertEquals("wait", named("Collect.body.wait"), "a parameter");
        assertEquals("count", named("Collect.body.count"), "a local");
        assertEquals("SLOW", named("Mode.SLOW"), "an enum constant");
    }

    @Test
    void whatNamesNothingOrSeveralIsRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SymbolNames.locate(units, "Collect.nope"))
                .getMessage().contains("declares no"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SymbolNames.locate(units, "Collect.again"))
                .getMessage().contains("2 declarations"));
        assertThrows(IllegalArgumentException.class, () -> SymbolNames.locate(units, "a.b.c.d"));
    }
}
