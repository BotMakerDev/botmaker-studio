package com.botmaker.studio;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JavaFX's own words follow the default locale, which Studio makes English; numbers keep the computer's format. */
class SpeakEnglishTest {

    @Test
    void theDefaultIsEnglishAndTheFormatStays() {
        Locale before = Locale.getDefault();
        Locale beforeFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.FRANCE);
            BotMakerStudio.speakEnglish();
            assertEquals(Locale.ENGLISH, Locale.getDefault(), "Cancel, not Annuler");
            assertEquals(Locale.FRANCE, Locale.getDefault(Locale.Category.FORMAT));
        } finally {
            Locale.setDefault(before);
            Locale.setDefault(Locale.Category.FORMAT, beforeFormat);
        }
    }
}
