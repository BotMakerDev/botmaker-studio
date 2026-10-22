package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BotMethods#appendCall}: what {@code PluginValues.appendCall} writes — a call as the method's last
 * statement, before its closing {@code return}, laid out, its class imported.
 */
class AppendCallTest {

    private static final String TAVERN = """
            package com.bot;

            public final class Tavern {

                public static Outcome body() {
                    Log.info("in");
                    return Outcome.DONE;
                }

                public static void chores() {
                }
            }
            """;

    private static JavaValue value(String source) {
        return JavaValue.parse(source).orElseThrow();
    }

    @Test
    void theCallLandsBeforeTheClosingReturnWithItsClassImported() {
        String written = BotMethods.withCall(TAVERN, "Tavern", "body", "com.sdk.Navigate", "press",
                List.of(value("Screens.TAVERN.beerBuy")));

        assertTrue(written.contains("import com.sdk.Navigate;"), written);
        int call = written.indexOf("Navigate.press(Screens.TAVERN.beerBuy);");
        assertTrue(call > written.indexOf("Log.info(\"in\");"), written);
        assertTrue(call < written.indexOf("return Outcome.DONE;"), written);
        assertTrue(written.contains("        Navigate.press(Screens.TAVERN.beerBuy);\n"), "laid out: " + written);
    }

    @Test
    void aMethodWithNoReturnTakesTheCallLast() {
        String written = BotMethods.withCall(TAVERN, "Tavern", "chores", "com.sdk.Navigate", "to",
                List.of(value("Screens.TAVERN")));

        String chores = written.substring(written.indexOf("chores()"));
        assertTrue(chores.contains("Navigate.to(Screens.TAVERN);"), written);
    }

    @Test
    void anArgumentsImportsAreAdded() {
        JavaValue imported = JavaValue.built(value("Pick.first()").node(), List.of("com.sdk.Pick"));
        String written = BotMethods.withCall(TAVERN, "Tavern", "body", "com.sdk.Navigate", "press",
                List.of(value("Screens.TAVERN.beerBuy"), imported));

        assertTrue(written.contains("import com.sdk.Pick;"), written);
        assertTrue(written.contains("Navigate.press(Screens.TAVERN.beerBuy, Pick.first());"), written);
    }

    @Test
    void aMethodTheBotLacksIsRefused() {
        BotIndex index = BotIndex.over(Map.of(Path.of("/bot/Tavern.java"), TAVERN), List.of(), Path.of("/bot"));

        Refactor.Outcome outcome = BotMethods.appendCall(index, "Tavern::missing", "com.sdk.Navigate", "press",
                List.of());

        assertEquals("The bot has no method Tavern::missing.",
                assertInstanceOf(Refactor.Refused.class, outcome).reason());
        assertInstanceOf(Refactor.Refused.class, BotMethods.appendCall(index, "body", "com.sdk.Navigate", "to",
                List.of()));
    }
}
