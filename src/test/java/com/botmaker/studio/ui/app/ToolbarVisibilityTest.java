package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.toolbar.ToolbarGroup;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a persisted list of hidden group names means, read back.
 *
 * <p>Every property here is about what happens when the file and the code disagree, because that is the
 * ordinary state rather than the exceptional one: a project is written by one Studio and opened by another. A
 * <b>hidden</b> set rather than a visible one is what makes a group added later — {@code OVERLAY} is one —
 * appear by default instead of being absent from every project written before it existed.
 *
 * <p>Headless: the parse and the spelling are separated from {@code menu}, which builds JavaFX. The same
 * split {@code BlockTree} was given deliberately.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ToolbarVisibilityTest {

    @Test
    void nothingHiddenIsTheDefault() {
        assertTrue(ToolbarVisibility.hidden(null).isEmpty());
        assertTrue(ToolbarVisibility.hidden(List.of()).isEmpty());
    }

    @Test
    void aGroupNobodyKnowsIsIgnoredRatherThanRefused() {
        // Written by a newer Studio that has a group this one does not. The rest of the list still applies:
        // one unrecognised name must not cost the user the groups they did switch off.
        Set<ToolbarGroup> hidden = ToolbarVisibility.hidden(List.of("RUN", "TELEMETRY", "", "tools"));

        assertEquals(Set.of(ToolbarGroup.RUN, ToolbarGroup.TOOLS), hidden);
    }

    @Test
    void anUnknownNameIsDroppedOnWriteBecauseItIsDroppedOnRead() {
        // Stated as a test because carrying unknown names through is a real design and NOT the one chosen: a
        // name no checkbox in this build can uncheck is a group the user cannot get back.
        assertEquals(List.of("RUN"), ToolbarVisibility.wire(Set.of(ToolbarGroup.RUN)));
    }

    @Test
    void theSpellingIsTheEnumName() {
        for (ToolbarGroup group : ToolbarGroup.values()) {
            assertEquals(Set.of(group), ToolbarVisibility.hidden(ToolbarVisibility.wire(Set.of(group))),
                    group + " must survive a round trip");
        }
    }

    @Test
    void theWireOrderIsDeclarationOrderRatherThanTheSetsOwn() {
        // So a settings.json diff reads the same whichever order the user ticked the boxes in.
        assertEquals(List.of("PROJECT", "RUN", "STUDIO"),
                ToolbarVisibility.wire(Set.of(ToolbarGroup.STUDIO, ToolbarGroup.RUN, ToolbarGroup.PROJECT)));
    }

    @Test
    void everyGroupIsHideableIncludingTheHostsOwn() {
        // STUDIO is refused to *plugins* (PluginHost.mergeToolbarItems) and that is unrelated: this is the
        // user hiding a section of their own bar, and STUDIO currently holds one read-out.
        assertEquals(Set.of(ToolbarGroup.STUDIO), ToolbarVisibility.hidden(List.of("STUDIO")));
    }

    @Test
    void aGroupHasAReadableNameRatherThanItsEnumSpelling() {
        for (ToolbarGroup group : ToolbarGroup.values()) {
            String label = ToolbarVisibility.label(group);
            assertTrue(label != null && !label.isBlank() && !label.equals(group.name()),
                    group + " needs a label a person would read, not " + label);
        }
    }
}
