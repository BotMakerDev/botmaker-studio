package com.botmaker.studio.ui.app.params;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How an enum constant reads on a toggle: words, not the constant's shouting. */
class EnumLabelsTest {

    @Test
    void aConstantReadsAsWords() {
        assertEquals("Monday", EnumLabels.label("MONDAY"));
        assertEquals("Left click", EnumLabels.label("LEFT_CLICK"));
        assertEquals("Up", EnumLabels.label("UP"));
    }

    @Test
    void aNameAlreadyInWordsIsLeftAlone() {
        assertEquals("fastMode", EnumLabels.label("fastMode"));
        assertEquals("", EnumLabels.label(""));
    }
}
