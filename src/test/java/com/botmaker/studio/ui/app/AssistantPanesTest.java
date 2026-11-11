package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.app.AssistantPanes.Pane;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantPanesTest {

    @Test
    void anyPaneHidesButTheLastOneShown() {
        AssistantPanes panes = AssistantPanes.ALL.toggled(Pane.CHAT).toggled(Pane.DETAIL);
        assertEquals(Set.of(Pane.CALLS), panes.shown());
        assertFalse(panes.canHide(Pane.CALLS));
        assertTrue(panes.canHide(Pane.CHAT), "a hidden pane's toggle shows it");
        assertSame(panes, panes.toggled(Pane.CALLS), "the last one stays");
        assertEquals(Set.of(Pane.CALLS, Pane.CHAT), panes.toggled(Pane.CHAT).shown());
        assertThrows(IllegalArgumentException.class, () -> new AssistantPanes(Set.of()));
    }

    @Test
    void choosingACallShowsDetail() {
        AssistantPanes calls = new AssistantPanes(EnumSet.of(Pane.CALLS));
        assertEquals(Set.of(Pane.CALLS, Pane.DETAIL), calls.showing(Pane.DETAIL).shown());
        assertSame(AssistantPanes.ALL, AssistantPanes.ALL.showing(Pane.DETAIL));
    }

    @Test
    void theChoiceIsKeptByIdLeftToRight() {
        assertEquals("chat,calls,detail", AssistantPanes.ALL.id());
        AssistantPanes two = new AssistantPanes(EnumSet.of(Pane.DETAIL, Pane.CHAT));
        assertEquals("chat,detail", two.id());
        assertEquals(two, AssistantPanes.fromId("chat,detail"));
        assertEquals(new AssistantPanes(EnumSet.of(Pane.CALLS)), AssistantPanes.fromId(" calls , terminal"));
        for (String none : new String[] {null, "", "terminal", ","}) {
            assertEquals(AssistantPanes.ALL, AssistantPanes.fromId(none), String.valueOf(none));
        }
    }
}
