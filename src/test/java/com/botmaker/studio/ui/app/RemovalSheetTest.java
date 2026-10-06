package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.BreakKind;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.CallSite;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The removal question's one sentence: counts, never the list of every call (2026-10-06). */
class RemovalSheetTest {

    private static Report report(List<Break> breaks) {
        return new Report("1.2.3", "", PluginUpgradeService.Operation.UPGRADE, List.of(), Map.of(),
                List.of(), breaks, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void aBotCallingNothingChangesOnlyThePom() {
        assertEquals("This bot calls nothing in SDK, so only the pom changes. A version of the project is saved "
                + "first.", RemovalSheet.summary("SDK", report(List.of())));
    }

    @Test
    void callsAndTypesAreCountedNotListed() {
        CallSite site = new CallSite("Main.java", 3, "Mouse.click()", 0);
        CallSite again = new CallSite("Main.java", 9, "Mouse.click()", 40);
        List<Break> breaks = List.of(
                new Break("Mouse", "click", BreakKind.MEMBER_REMOVED, "", "default value", List.of(site, again)),
                new Break("Old", "", BreakKind.TYPE_REMOVED, "", "", List.of(site)));

        assertEquals("2 calls will become a default value or be deleted, marked for review. 1 type it writes "
                        + "stays as written, for you to change. A version of the project is saved first.",
                RemovalSheet.summary("SDK", report(breaks)));
    }

    /** A type the bot writes down is counted as a type, never as a call too. */
    @Test
    void aTypeLeftAsWrittenIsNoCall() {
        CallSite site = new CallSite("Main.java", 3, "Old o = null;", 0);

        assertEquals("No call needs repairing. 1 type it writes stays as written, for you to change. A version "
                        + "of the project is saved first.",
                RemovalSheet.summary("SDK", report(List.of(new Break("Old", "", BreakKind.TYPE_REMOVED, "", "",
                        List.of(site))))));
    }
}
