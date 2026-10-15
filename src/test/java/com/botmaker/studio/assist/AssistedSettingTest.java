package com.botmaker.studio.assist;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AssistedSettingTest {

    /** {@code set_setting} takes a setting by its constant's name, so the id get_settings shows must be that name. */
    @Test
    void eachIdIsItsConstantsName() {
        for (AssistedSetting setting : AssistedSetting.values()) {
            assertEquals(setting.name().toLowerCase(Locale.ROOT), setting.id());
        }
    }
}
