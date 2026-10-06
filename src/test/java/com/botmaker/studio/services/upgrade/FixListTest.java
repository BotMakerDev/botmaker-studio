package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.services.upgrade.FixList.Fix;
import com.botmaker.studio.services.upgrade.FixList.Issue;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.BreakKind;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.CallSite;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Choice;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Decision;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Site;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an upgrade asks before it writes anything: one issue per broken place, each with its fixes. */
class FixListTest {

    private static final CallSite CLICK_12 = new CallSite("Main.java", 12, "Mouse.click();", 0);
    private static final CallSite CLICK_3 = new CallSite("Main.java", 3, "Mouse.click();", 0);
    private static final CallSite OLD_7 = new CallSite("Bot.java", 7, "Old o = null;", 0);

    private static Report report(List<Break> breaks, List<Choice> splits) {
        return new Report("1.2.3", "1.4.0", PluginUpgradeService.Operation.UPGRADE, List.of(), Map.of(),
                List.of(), breaks, splits, List.of(), List.of(), List.of());
    }

    @Test
    void eachCallOfARemovedMemberIsAnIssueInSourceOrder() {
        List<Issue> issues = FixList.of(report(List.of(new Break("Mouse", "click", BreakKind.MEMBER_REMOVED, "",
                "default value", List.of(CLICK_12, CLICK_3))), List.of()));

        assertEquals(List.of("Main.java:3", "Main.java:12"), issues.stream().map(Issue::where).toList());
        assertEquals("Mouse.click was removed", issues.getFirst().what());
        assertEquals(Decision.PREFERRED, issues.getFirst().suggestedFix().decision());
        assertTrue(issues.getFirst().fixes().stream().anyMatch(f -> Decision.DISCARD.equals(f.decision())));
    }

    @Test
    void aRenamedTypeIsNoIssueAndAGoneTypeIsAnAcknowledgement() {
        List<Issue> issues = FixList.of(report(List.of(
                new Break("Point", "", BreakKind.TYPE_RENAMED, "Point → Spot", "rename", List.of(CLICK_3)),
                new Break("Old", "", BreakKind.TYPE_REMOVED, "", "", List.of(OLD_7))), List.of()));

        assertEquals(1, issues.size());
        assertNull(issues.getFirst().site());
        assertEquals(1, issues.getFirst().fixes().size());
        assertNull(issues.getFirst().fixes().getFirst().decision(), "it tells the repair nothing");
    }

    @Test
    void aSplitSiteWithNothingThatFitsOffersADefaultAndADeleteForAStatement() {
        Choice split = new Choice("Mouse", "scroll", 1, List.of(), "", List.of(new Site(CLICK_3, List.of(), true)));
        List<Issue> issues = FixList.of(report(List.of(new Break("Mouse", "scroll", BreakKind.MEMBER_REMOVED, "",
                "default value", List.of(CLICK_3))), List.of(split)));

        assertEquals(1, issues.size(), "a site the split asks about is not asked again by its break");
        assertEquals(List.of(Decision.DEFAULT, Decision.DISCARD),
                issues.getFirst().fixes().stream().map(Fix::decision).toList());
    }

    /** A second plugin's picks survive the first plugin's rewrite moving its calls (2026-10-06). */
    @Test
    void picksFollowACallAnEarlierRewriteMoved() {
        CallSite first = new CallSite("Main.java", 40, "Mouse.click();", 900);
        CallSite second = new CallSite("Main.java", 44, "Mouse.click();", 980);
        CallSite firstMoved = new CallSite("Main.java", 38, "Mouse.click();", 850);
        CallSite secondMoved = new CallSite("Main.java", 42, "Mouse.click();", 930);

        assertEquals(Map.of(secondMoved, Decision.DISCARD), ProjectUpgrade.carried(Map.of(second, Decision.DISCARD),
                List.of(first, second), List.of(secondMoved, firstMoved)));
    }

    @Test
    void aGoneTypeIsListedAfterEveryCall() {
        List<Issue> issues = FixList.of(report(List.of(
                new Break("Old", "", BreakKind.TYPE_REMOVED, "", "", List.of(OLD_7)),
                new Break("Mouse", "click", BreakKind.MEMBER_REMOVED, "", "default value", List.of(CLICK_3))),
                List.of()));

        assertEquals("Main.java:3", issues.getFirst().where());
        assertNull(issues.getLast().site());
    }

    @Test
    void picksAreTheChosenDecisionsAndAnAcknowledgementIsNone() {
        List<Issue> issues = FixList.of(report(List.of(
                new Break("Mouse", "click", BreakKind.MEMBER_REMOVED, "", "default value", List.of(CLICK_3)),
                new Break("Old", "", BreakKind.TYPE_REMOVED, "", "", List.of(OLD_7))), List.of()));
        Map<Issue, Fix> chosen = new IdentityHashMap<>();
        for (Issue issue : issues) chosen.put(issue, issue.fixes().getLast());

        assertEquals(Map.of(CLICK_3, Decision.DISCARD), FixList.picks(issues, chosen));
    }
}
