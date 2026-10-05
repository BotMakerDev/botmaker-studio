package com.botmaker.studio.ui.app.run;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A part's close actions run once each, a throwing one costing only itself. */
class PartContextTest {

    @Test
    void closeRunsEachActionOnceAndALateOneAtOnce() {
        List<String> ran = new ArrayList<>();
        PartContext context = new PartContext(null);
        context.onClosed(() -> ran.add("first"));
        context.onClosed(() -> {
            throw new IllegalStateException("broken part");
        });
        context.onClosed(() -> ran.add("third"));

        context.close();
        context.close();
        assertEquals(List.of("first", "third"), ran);

        context.onClosed(() -> ran.add("late"));
        assertEquals(List.of("first", "third", "late"), ran, "registered after the close: run now");
    }
}
