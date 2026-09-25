package com.botmaker.studio.ui.app;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the upgrade window says when it is not doing anything — the two sentences a user reads instead of
 * guessing.
 *
 * <p>The window used to leave a disabled Apply button with nothing beside it, and to close itself on success
 * so the one message worth reading was on screen for no frames at all. Both are sentences rather than
 * layout, so both are asserted with no toolkit: these are static methods over plain values, the same split
 * as {@code PluginAlreadyProvidedTest} beside it.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class ProjectUpgradeFeedbackTest {

    @Test
    void aWindowWhereNoRowMovedSaysToPickAVersion() {
        String why = ProjectUpgradeDialog.applyBlockedReason(0, List.of(), false);

        assertTrue(why.contains("Pick a version"), why);
    }

    /** A check in flight is a wait, not a refusal, and must not read as one. */
    @Test
    void aCheckStillRunningSaysSoRatherThanNamingAProblem() {
        assertEquals("A check is still running.",
                ProjectUpgradeDialog.applyBlockedReason(1, List.of(), true));
    }

    @Test
    void aBlockedRowIsNamedAndTheWayOutIsGiven() {
        String why = ProjectUpgradeDialog.applyBlockedReason(2, List.of("BotMaker SDK"), false);

        assertTrue(why.startsWith("BotMaker SDK is blocked"), why);
        assertTrue(why.contains("installed version"), "it says how to let the others move: " + why);
    }

    @Test
    void twoBlockedRowsAreListedAndTheVerbAgrees() {
        String why = ProjectUpgradeDialog.applyBlockedReason(2, List.of("SDK", "Basics"), false);

        assertTrue(why.startsWith("SDK, Basics are blocked"), why);
    }

    /** Nothing in the way, nothing said: an enabled button needs no caption. */
    @Test
    void aPassThatCanRunHasNoSentenceAtAll() {
        assertEquals("", ProjectUpgradeDialog.applyBlockedReason(1, List.of(), false));
    }

    @Test
    void aRemovalThatRewroteCodeSaysHowMuchAndWhereTheWayBackIs() {
        String summary = ProjectUpgradeDialog.removalSummary("Basics", 2, 5);

        assertTrue(summary.startsWith("Removed Basics from this project."), summary);
        assertTrue(summary.contains("5 calls replaced or deleted in 2 files"), summary);
        assertTrue(summary.contains("Versions tab"), summary);
    }

    @Test
    void aRemovalThatTouchedNoCodeSaysOnlyThePomChanged() {
        String summary = ProjectUpgradeDialog.removalSummary("Basics", 0, 0);

        assertTrue(summary.contains("only the pom changed"), summary);
    }
}
