package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistedSettingTest {

    @Test
    void devModeIsOffered() {
        assertEquals(AssistedSetting.DEV_MODE, AssistedSetting.fromId("dev_mode"));
        assertTrue(AssistedSetting.offered().contains(AssistedSetting.DEV_MODE));
    }

    @Test
    void anUnknownIdIsUnknown() {
        assertEquals(AssistedSetting.UNKNOWN, AssistedSetting.fromId("dev mode"));
        assertEquals(AssistedSetting.UNKNOWN, AssistedSetting.fromId(""));
    }
}
