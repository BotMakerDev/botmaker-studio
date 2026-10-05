package com.botmaker.studio.services.trial;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.services.debug.DebugSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last pause's values, as ▶ Try offers them: a bot frame's locals by class, method and name, the newest pause
 * winning; offered only when the grammar reads the text back as the local's type.
 */
class LastRunValuesTest {

    private static DebugSnapshot pause(String local, String value) {
        return new DebugSnapshot("main", List.of(
                new DebugSnapshot.Frame("run", "com.bot.Calls", Path.of("Calls.java"), 9,
                        List.of(new DebugSnapshot.Variable(local, "int", value, List.of()))),
                new DebugSnapshot.Frame("sleep", "java.lang.Thread", null, 0,
                        List.of(new DebugSnapshot.Variable("millis", "long", "5", List.of())))));
    }

    @Test
    void theNewestPauseInTheBotsMethodAnswers() {
        LastRunValues values = new LastRunValues();
        values.remember(pause("n", "3"));
        values.remember(pause("n", "4"));

        assertEquals(Optional.of("4"), values.text("com.bot.Calls", "run", "n"));
        assertTrue(values.text("java.lang.Thread", "sleep", "millis").isEmpty(), "a library frame is not the bot's");
    }

    @Test
    void aValueIsOfferedOnlyWhenItReadsBackAsItsType() {
        LastRunValues values = new LastRunValues();
        values.remember(pause("n", "4"));
        values.remember(new DebugSnapshot("main", List.of(new DebugSnapshot.Frame("run", "com.bot.Calls",
                Path.of("Calls.java"), 9, List.of(new DebugSnapshot.Variable("match", "Match", "Match #41", List.of()))))));
        TrialPlan.LastRun reader = values.reader(PluginHost.grammar());

        assertEquals(Optional.of("4"), reader.value("com.bot.Calls", "run", "n", "int").map(JavaValue::source));
        assertTrue(reader.value("com.bot.Calls", "run", "match", "com.bot.Match").isEmpty(), "an object's id is not a value");
    }
}
