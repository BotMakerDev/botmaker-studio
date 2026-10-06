package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Installed tab says on each row and after a pass — sentences rather than layout, so asserted with no
 * toolkit: static methods over plain values, the same split as {@code PluginAlreadyProvidedTest} beside it.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ProjectUpgradeFeedbackTest {

    /** A row opens on its installed version, so its one button is the upgrade (2026-10-06). */
    @Test
    void aRowOnItsVersionOffersTheNewerOne() {
        assertEquals("Upgrade to 1.4.0", InstalledPluginsTab.actionLabel("1.3.0", "1.3.0", "1.4.0", false));
        assertEquals("1.4.0", InstalledPluginsTab.actionTarget("1.3.0", "1.3.0", "1.4.0", false));
    }

    @Test
    void aPickedVersionIsASwitchEvenWhenItIsOlder() {
        assertEquals("Switch to 1.2.0", InstalledPluginsTab.actionLabel("1.3.0", "1.2.0", "1.4.0", false));
        assertEquals("1.2.0", InstalledPluginsTab.actionTarget("1.3.0", "1.2.0", "1.4.0", false));
    }

    @Test
    void aRowWithNothingNewerHasNothingToDo() {
        assertEquals("", InstalledPluginsTab.actionLabel("1.4.0", "1.4.0", "", false));
        assertEquals("", InstalledPluginsTab.actionTarget("1.4.0", "1.4.0", "", false));
        assertEquals("Up to date", InstalledPluginsTab.idleLabel(false));
    }

    /** The dev build a dev-mode project is trying is never offered an upgrade. */
    @Test
    void aDevBuildInDevModeOffersNoUpgrade() {
        assertEquals("", InstalledPluginsTab.actionLabel("1.3.1-SNAPSHOT", "1.3.1-SNAPSHOT", "1.4.0", true));
        assertEquals("Dev build", InstalledPluginsTab.idleLabel(true));
    }

    /** Outside dev mode a refused dev build moves to a release, which is a switch: it may be older. */
    @Test
    void aRefusedDevBuildSwitchesToARelease() {
        assertEquals("Switch to v1.2.3", InstalledPluginsTab.actionLabel("1.3.1-SNAPSHOT", "1.3.1-SNAPSHOT",
                "v1.2.3", false));
    }

    /** A row pinned to a dev build was not loaded (2026-10-03); the line above the table says what to do. */
    @Test
    void devBuildRowsAreNamedWithTheWayOut() {
        assertEquals("", InstalledPluginsTab.devText(0));
        assertTrue(InstalledPluginsTab.devText(1).startsWith("A plugin is pinned to a dev build"));
        assertTrue(InstalledPluginsTab.devText(2).startsWith("2 plugins are"));
        assertTrue(InstalledPluginsTab.devText(2).contains("or use dev mode"));
    }

    @Test
    void aRemovalThatRewroteCodeSaysHowMuchAndWhereTheWayBackIs() {
        String summary = InstalledPluginsTab.removalSummary("Basics", 2, 5);

        assertTrue(summary.startsWith("Removed Basics from this project."), summary);
        assertTrue(summary.contains("5 calls replaced or deleted in 2 files"), summary);
        assertTrue(summary.contains("Versions tab"), summary);
    }

    @Test
    void aRemovalThatTouchedNoCodeSaysOnlyThePomChanged() {
        String summary = InstalledPluginsTab.removalSummary("Basics", 0, 0);

        assertTrue(summary.contains("only the pom changed"), summary);
    }
}
