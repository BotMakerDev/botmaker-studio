package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.run.Readiness;
import com.botmaker.plugin.host.LoadedPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Run window's readiness line: the least ready plugin wins, and a failing one costs only itself. */
class ReadinessMergeTest {

    private static LoadedPlugin saying(String artifactId, Readiness readiness) {
        return TestPlugins.loaded(artifactId, StudioPlugin.declare().readiness(() -> {
            if (readiness == null) throw new IllegalStateException("broken");
            return readiness;
        }));
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
        LoadedPlugin silent = TestPlugins.loaded("silent", StudioPlugin.declare());
        assertEquals(Optional.empty(), PluginHost.leastReady(List.of(silent, saying("broken", null))));
        Readiness ready = Readiness.ready("");
        assertEquals(Optional.of(ready), PluginHost.leastReady(List.of(saying("broken", null), saying("r", ready))));
    }
}
