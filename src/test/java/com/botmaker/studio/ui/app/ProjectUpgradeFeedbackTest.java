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
        String why = ProjectUpgradeDialog.applyBlockedReason(0, false, 0);

        assertTrue(why.contains("Pick a version"), why);
    }

    /** A check in flight is a wait, not a refusal, and must not read as one. */
    @Test
    void aCheckStillRunningSaysSoRatherThanNamingAProblem() {
        assertEquals("A check is still running.",
                ProjectUpgradeDialog.applyBlockedReason(1, true, 0));
    }

    /**
     * A call nothing replaces is a guess either way: the user is asked, and one left unanswered gets a default
     * and a review mark rather than holding Apply (2026-09-29).
     */
    @Test
    void callsWaitingForAPickAreSaidAndWhatHappensToThem() {
        String why = ProjectUpgradeDialog.applyBlockedReason(1, false, 2);

        assertTrue(why.startsWith("2 calls have nothing that replaces it"), why);
        assertTrue(why.contains("default value or is deleted"), why);
        assertTrue(why.contains("left unchosen gets a default value"), why);
    }

    /** Nothing in the way, nothing said: an enabled button needs no caption. */
    @Test
    void aPassThatCanRunHasNoSentenceAtAll() {
        assertEquals("", ProjectUpgradeDialog.applyBlockedReason(1, false, 0));
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
