package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which declared plugins another declared plugin already brings, so their own entry must go (nearest-wins would
 * otherwise pin a version the bringing plugin was never built against). The collection is Aether's; the decision
 * is this pure function.
 */
class ShadowedPluginsTest {

    @Test
    void aPluginTheSdkBringsLosesItsOwnEntry() {
        Map<String, Set<String>> brings = new LinkedHashMap<>();
        brings.put("g:basics", Set.of("g:studio-api"));
        brings.put("g:sdk", Set.of("g:basics", "g:studio-api", "g:shared"));

        assertEquals(List.of(new MavenService.Shadowed("g:basics", "g:sdk")), MavenService.shadowed(brings));
        assertEquals("basics is brought by sdk, so its own entry was removed.",
                MavenService.Shadowed.sentence(MavenService.shadowed(brings)));
    }

    @Test
    void unrelatedPluginsAreKept() {
        Map<String, Set<String>> brings = new LinkedHashMap<>();
        brings.put("g:a", Set.of("x:lib"));
        brings.put("g:b", Set.of("x:lib"));

        assertEquals(List.of(), MavenService.shadowed(brings));
        assertEquals("", MavenService.Shadowed.sentence(List.of()));
    }

    @Test
    void twoPluginsClaimingEachOtherNeverBothGo() {
        Map<String, Set<String>> brings = new LinkedHashMap<>();
        brings.put("g:a", Set.of("g:b"));
        brings.put("g:b", Set.of("g:a"));

        assertEquals(1, MavenService.shadowed(brings).size());
    }
}
