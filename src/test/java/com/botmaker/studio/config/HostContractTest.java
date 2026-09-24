package com.botmaker.studio.config;

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
    void anUnfilteredOrBlankTagIsTheDevVersion() {
        assertEquals(HostContract.DEV_VERSION, HostContract.orDev(null));
        assertEquals(HostContract.DEV_VERSION, HostContract.orDev(" "));
        assertEquals(HostContract.DEV_VERSION, HostContract.orDev("${botmaker.contract.tag}"));
        assertEquals("v0.3.0", HostContract.orDev(" v0.3.0 "));
    }
}
