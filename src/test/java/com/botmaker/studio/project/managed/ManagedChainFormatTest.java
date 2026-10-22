package com.botmaker.studio.project.managed;

import com.botmaker.studio.plugin.grammar.JavaValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A managed value's chain as the host writes it (2026-10-22): one line while it fits the 120 columns, one call per
 * line once it does not — a screen map's {@code setup()} grows a call per edit and was written on one line.
 */
class ManagedChainFormatTest {

    private static final String SCREENS = """
            package com.bot.plugins.sdk;

            public final class Screens {

                public static final class Home {
                    public Setup setup() {
                        return Setup.of();
                    }
                }

                public static final Home HOME = new Home();
            }
            """;

    private static final String LONG = "Setup.of().markers(Pictures.HOME_TITLE, Pictures.HOME_AVATAR)"
            + ".absent(Pictures.POPUP_CLOSE).from(Screens.START.enter).back(Screens.HOME.close)"
            + ".takes(Duration.ofMillis(1200L))";

    @Test
    void aLongChainIsOneCallPerLine() {
        String written = NestedClasses.setupSet(SCREENS, "Screens", "HOME", "setup", java(LONG));

        List<String> lines = written.lines().toList();
        assertTrue(lines.stream().allMatch(line -> line.length() <= 120), written);
        for (String call : List.of(".markers(", ".absent(", ".from(", ".back(", ".takes(")) {
            assertEquals(1, lines.stream().filter(line -> line.strip().startsWith(call)).count(),
                    call + " starts its own line\n" + written);
        }
        assertTrue(written.contains("public static final Home HOME = new Home();"), "the rest is untouched");
    }

    @Test
    void aShortChainStaysOnOneLine() {
        String written = NestedClasses.setupSet(SCREENS, "Screens", "HOME", "setup",
                java("Setup.of().markers(Pictures.HOME_TITLE)"));

        assertTrue(written.contains("return Setup.of().markers(Pictures.HOME_TITLE);"), written);
        assertEquals(SCREENS.lines().count(), written.lines().count(), written);
    }

    @Test
    void aMethodValueIsLaidOutTheSameWay() {
        String source = """
                package com.bot.plugins.sdk;

                public final class Sdk {
                    public static Flow value() {
                        return null;
                    }
                }
                """;

        String written = JavaManagedEdits.setValue(source, "Sdk", "value", java(LONG));

        assertTrue(written.lines().allMatch(line -> line.length() <= 120), written);
        assertTrue(written.lines().anyMatch(line -> line.strip().startsWith(".takes(")), written);
    }

    private static JavaValue java(String source) {
        return new JavaValue(JavaValue.parse(source).orElseThrow().node(), List.of(), source);
    }
}
