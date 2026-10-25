package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.Readiness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Run window's readiness line: the least ready plugin wins, and a failing one costs only itself. */
class ReadinessMergeTest {

    private static StudioPlugin saying(String id, Readiness readiness) {
        return new StudioPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<Readiness> readiness() {
                if (readiness == null) throw new IllegalStateException("broken");
                return Optional.of(readiness);
            }
        };
    }

    @Test
    void theLeastReadyIsShown() {
        Readiness blocked = Readiness.blocked("signed out of Steam");
        assertEquals(Optional.of(blocked), PluginHost.leastReady(List.of(
                saying("a", Readiness.ready("game window found")), saying("b", blocked),
                saying("c", Readiness.waiting("updating")))));
    }

    @Test
    void nothingToSayAndAFailingCheckAreLeftOut() {
        StudioPlugin silent = () -> "silent";
        assertEquals(Optional.empty(), PluginHost.leastReady(List.of(silent, saying("broken", null))));
        Readiness ready = Readiness.ready("");
        assertEquals(Optional.of(ready), PluginHost.leastReady(List.of(saying("broken", null), saying("r", ready))));
    }
}
