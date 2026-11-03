package com.botmaker.studio.services.run;

import com.botmaker.plugin.host.PluginCoordinate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEntriesTest {

    private static RunEntries.Declared declared(String artifact, String title) {
        return new RunEntries.Declared(PluginCoordinate.parse("com.example:" + artifact), artifact, null, title,
                "com.example.Main");
    }

    @Test
    void namedIgnoresCaseAndSpaces() {
        RunEntries.Declared explore = declared("sdk", "Explore map");
        List<RunEntries.Declared> all = List.of(explore, declared("sdk", "Check"));
        assertEquals(explore, RunEntries.named(all, "  explore   MAP ").orElseThrow());
        assertTrue(RunEntries.named(all, "Explore").isEmpty());
    }

    @Test
    void oneTitleFromTwoPluginsIsRefusedNamingBoth() {
        List<RunEntries.Declared> all = List.of(declared("sdk", "Check"), declared("other", "check"));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> RunEntries.named(all, "Check"));
        assertTrue(refused.getMessage().contains("com.example:sdk, com.example:other"), refused.getMessage());
    }
}
