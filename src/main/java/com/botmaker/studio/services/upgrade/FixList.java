package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.BreakKind;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.CallSite;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Candidate;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Choice;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Decision;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Site;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an upgrade asks before it writes anything (2026-10-06): one {@link Issue} per place in the bot the
 * move breaks, in source order, each with the fixes the repair can write there.
 *
 * <p>The upgrade does not happen until every issue has a fix: the user walks the list, picks, and only then
 * does {@link ProjectUpgrade} take its snapshot, rewrite the code and move the pom. Until this date the
 * report was prose under the table, a call nobody chose for was defaulted silently, and the user learned what
 * had been written by reading the Review tab afterwards.
 *
 * <p>The fixes are exactly what the engine can write without a compile error ({@link Decision}): the engine's
 * own answer (a redirect where a pointer names one, else a default value), each candidate of a member that
 * became several, a default value marked for review, or the call deleted. A type the bot writes down that is
 * gone has no value to stand in for it; its one fix is to leave it as written and marked, which the user
 * acknowledges. A renamed type is no issue: the rename is mechanical and file-wide.
 */
public final class FixList {

    private FixList() {}

    /**
     * One fix the repair can write.
     *
     * @param decision what the repair is told at the issue's site; {@code null} for an acknowledgement, which
     *                 tells it nothing
     */
    public record Fix(String label, Decision decision) {}

    /**
     * One place the move breaks.
     *
     * @param site      the call, or {@code null} for a type the bot writes down
     * @param what      what broke, as the user reads it: {@code Mouse.click was removed}
     * @param where     {@code Main.java:12}, or the files a type is written in
     * @param code      the line as written, {@code ""} when there is none
     * @param suggested index into {@code fixes} of the one offered first
     */
    public record Issue(CallSite site, String what, String where, String code, List<Fix> fixes, int suggested) {
        public Issue {
            fixes = List.copyOf(fixes);
        }

        public Fix suggestedFix() {
            return fixes.get(suggested);
        }
    }

    /** The issues of {@code report}, in source order. */
    public static List<Issue> of(Report report) {
        List<Issue> out = new ArrayList<>();
        Set<CallSite> asked = new HashSet<>();

        for (Choice choice : concat(report.splits(), report.guesses())) {
            boolean guess = report.guesses().contains(choice);
            for (Site site : choice.sites()) {
                if (!asked.add(site.site())) continue;
                List<Fix> fixes = new ArrayList<>();
                for (int i = 0; i < site.candidates().size(); i++) {
                    Candidate candidate = site.candidates().get(i);
                    fixes.add(new Fix("Use " + candidate.display(), Decision.redirect(i)));
                }
                // The first fix is offered first: the author's preferred candidate, or with none that fits
                // here, the default value.
                int suggested = 0;
                fixes.add(new Fix("Use a default value, marked for review", Decision.DEFAULT));
                if (site.statement()) fixes.add(new Fix("Delete the call", Decision.DISCARD));
                String what = choice.display() + (guess ? " is gone; where it went is a guess"
                        : " became " + choice.candidates().size() + " members");
                if (!choice.note().isBlank()) what += ". " + choice.note();
                out.add(new Issue(site.site(), what, where(site.site()), site.site().text(), fixes, suggested));
            }
        }

        for (Break b : report.breaks()) {
            if (b.kind() == BreakKind.TYPE_RENAMED) continue;
            if (b.leavesWork()) {
                List<CallSite> sites = b.sites();
                String files = String.join(", ", sites.stream().map(FixList::where).distinct().toList());
                out.add(new Issue(null, b.type() + " is gone, and this bot writes it down", files,
                        sites.isEmpty() ? "" : sites.getFirst().text(),
                        List.of(new Fix("Leave it as written and marked; change it after the upgrade", null)), 0));
                continue;
            }
            for (CallSite site : b.sites()) {
                if (!asked.add(site)) continue;
                List<Fix> fixes = new ArrayList<>();
                String engine = b.repair().isBlank() ? "Studio's repair" : b.repair();
                fixes.add(new Fix("Suggested: " + engine, Decision.PREFERRED));
                if (!engine.toLowerCase(java.util.Locale.ROOT).contains("default")) {
                    fixes.add(new Fix("Use a default value, marked for review", Decision.DEFAULT));
                }
                // The engine turns a delete into a default where the call's value is used.
                fixes.add(new Fix("Delete the call (a value it gives becomes a default)", Decision.DISCARD));
                out.add(new Issue(site, b.display() + " " + brokeAs(b), where(site), site.text(), fixes, 0));
            }
        }

        // A gone type has no one site: it goes after every call.
        out.sort(Comparator.comparing((Issue i) -> i.site() == null ? "￿" : i.site().file())
                .thenComparingInt(i -> i.site() == null ? Integer.MAX_VALUE : i.site().line()));
        return List.copyOf(out);
    }

    /**
     * The repair's picks from the chosen fixes: one per issue with a site and a decision. An issue not yet
     * chosen is absent, so it is the caller's to refuse before calling this.
     */
    public static Map<CallSite, Decision> picks(List<Issue> issues, Map<Issue, Fix> chosen) {
        Map<CallSite, Decision> out = new LinkedHashMap<>();
        for (Issue issue : issues) {
            Fix fix = chosen.get(issue);
            if (fix != null && fix.decision() != null && issue.site() != null) out.put(issue.site(), fix.decision());
        }
        return out;
    }

    private static String brokeAs(Break b) {
        return switch (b.kind()) {
            case MEMBER_REMOVED -> "was removed";
            case FIELD_REMOVED -> "is gone";
            case SIGNATURE_CHANGED -> "takes different arguments now" + (b.detail().isBlank() ? "" : ": " + b.detail());
            case TYPE_REMOVED -> "is gone";
            case TYPE_RENAMED -> "was renamed";
        };
    }

    private static String where(CallSite site) {
        return site.file() + ":" + site.line();
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
