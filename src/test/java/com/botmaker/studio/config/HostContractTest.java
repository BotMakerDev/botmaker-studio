package com.botmaker.studio.config;

import com.botmaker.studio.services.MavenService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HostContractTest {

    @Test
    void theBuiltResourceIsFiltered() {
        // A literal ${…} here would reach a user's pom as a version nothing resolves.
        assertFalse(HostContract.version().contains("${"));
    }

    @Test
    void aProjectIsNeverGivenASnapshot() {
        assertFalse(HostContract.version().toUpperCase().contains("SNAPSHOT"));
    }

    @Test
    void anUnfilteredBlankOrSnapshotTagIsTheReleasedFallback() {
        String fallback = MavenService.CONTRACT_FALLBACK_VERSION;
        assertEquals(fallback, HostContract.orReleased(null));
        assertEquals(fallback, HostContract.orReleased(" "));
        assertEquals(fallback, HostContract.orReleased("${botmaker.contract.tag}"));
        assertEquals(fallback, HostContract.orReleased("0.0.0-SNAPSHOT"));
        assertEquals("v0.3.0", HostContract.orReleased(" v0.3.0 "));
    }

    @Test
    void devModeKeepsADevBuildsSnapshotAndOnlyThat() {
        assertEquals("0.5.1-SNAPSHOT", HostContract.devVersion(" 0.5.1-SNAPSHOT "));
        assertEquals("v0.5.0", HostContract.devVersion("v0.5.0"));
        assertEquals(MavenService.CONTRACT_FALLBACK_VERSION, HostContract.devVersion("${botmaker.contract.tag}"));
        assertEquals(MavenService.CONTRACT_FALLBACK_VERSION, HostContract.devVersion(null));
    }
}
