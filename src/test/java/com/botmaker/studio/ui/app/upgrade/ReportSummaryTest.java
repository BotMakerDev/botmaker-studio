package com.botmaker.studio.ui.app.upgrade;

import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.BreakKind;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.CallSite;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The one line an upgrade report opens with, in place of the "What breaks in this bot" list (2026-10-01). */
class ReportSummaryTest {

    @Test
    void noBreakSaysSoAndNamesTheTarget() {
        assertEquals("Nothing breaks — every call in this bot still exists on 1.2.3.",
                ReportView.summary(report(List.of(), List.of())));
        assertEquals("Nothing breaks in the files that could be read.",
                ReportView.summary(report(List.of(), List.of("Main.java: unreadable"))));
    }

    @Test
    void breaksAreCountedByCallAndByTypeLeftForTheUser() {
        CallSite a = new CallSite("Main.java", 3, "Mouse.click()", 0);
        CallSite b = new CallSite("Main.java", 9, "Mouse.click()", 40);
        List<Break> breaks = List.of(
                new Break("Mouse", "click", BreakKind.MEMBER_REMOVED, "", "default value", List.of(a, b)),
                new Break("Old", "", BreakKind.TYPE_REMOVED, "", "", List.of(a)));
        assertEquals("Studio repairs 2 calls · you finish 1 removed type · nothing else changes",
                ReportView.summary(report(breaks, List.of())));
    }

    private static Report report(List<Break> breaks, List<String> problems) {
        return new Report("1.1.7", "1.2.3", PluginUpgradeService.Operation.UPGRADE, List.of(), Map.of(),
                List.of(), breaks, List.of(), List.of(), List.of(), problems);
    }
}
