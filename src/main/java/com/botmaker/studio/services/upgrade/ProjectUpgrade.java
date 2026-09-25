package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.CallSite;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Decision;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Several plugins moved in one pass — one snapshot, one repair per row in sequence, one pom write.
 *
 * <p>This is the only thing the project upgrade window adds to the engine, and it is here rather than in the
 * window because the rule it enforces is not a layout decision: <b>a project must never sit on plugin A's new
 * version with plugin B's old source</b>. Every row is checked before anything at all is written, and the
 * write that moves the versions happens once, after every rewrite has already succeeded.
 *
 * <h2>The order, and why each step is where it is</h2>
 *
 * <ol>
 *   <li><b>Check every row first.</b> {@link PluginUpgradeService#compare} answers without writing, so a row
 *       that cannot be repaired — an unreadable file, a removed type the bot declares, two plugins claiming
 *       one simple name — refuses the whole pass while the project is still untouched and there is nothing to
 *       undo. This is the all-or-nothing rule {@code rewriteOthers} already enforces per file, read one level
 *       up.</li>
 *   <li><b>One snapshot.</b> A commit per plugin would describe a state the user never asked to be in, and
 *       reverting it would undo one plugin's repair while leaving another's.</li>
 *   <li><b>One repair per row, committed to disk before the next row starts.</b> Each pass re-reads the
 *       project's current content, so plugin B is migrated against the files plugin A has already rewritten.
 *       Deferring every write to the end would have the second row plan its edits against text that no longer
 *       exists at those offsets.</li>
 *   <li><b>One pom write, for every row at once.</b> See
 *       {@code MavenService.setDependencyVersions}.</li>
 * </ol>
 *
 * <p><b>What it deliberately does not promise:</b> a failure <em>after</em> the snapshot — a file that cannot
 * be written, a jar that stops resolving between the check and the repair — leaves the project mid-pass with
 * no pom change. That is what the snapshot is for, and it is why the snapshot is taken before the first
 * rewrite rather than after the last one. Rolling the files back here would be a second, worse implementation
 * of the revert the VCS panel already offers.
 */
public final class ProjectUpgrade {

    private ProjectUpgrade() {
    }

    /**
     * One row of the window, resolved to the operation it stands for.
     *
     * @param upgrades       the engine pointed at that plugin's coordinate
     * @param targetVersion  the version the row's combo box is on — older than the installed one for a
     *                       downgrade, which is the same operation and the same control
     * @param alsoModernise  whether to read through the target's own deprecations, the dialog's checkbox
     * @param picks          the per-site {@link Decision}s the window collected; empty takes the engine's own
     *                       answer at every site
     */
    public record Row(PluginUpgradeService upgrades, String targetVersion, boolean alsoModernise,
                      Map<CallSite, Decision> picks) {

        public Row {
            picks = picks == null ? Map.of() : Map.copyOf(picks);
        }

        public Row(PluginUpgradeService upgrades, String targetVersion) {
            this(upgrades, targetVersion, false, Map.of());
        }
    }

    /** One plugin that moved, as the summary names it. */
    public record Moved(String plugin, String from, String to) {

        @Override
        public String toString() {
            return plugin + " " + from + " → " + to;
        }
    }

    /**
     * What the pass did, for the window to say back.
     *
     * <p>It exists because the window used to close on success: the one moment a user wants to read a
     * sentence is the moment the operation they were warned about has finished. The counts are the two the
     * pass actually knows — {@code filesRewritten} is what was written, {@code callsRepaired} is what the
     * reports found — and nothing here is a report of what <em>would</em> happen.
     */
    public record Result(List<Moved> moved, int filesRewritten, int callsRepaired) {

        public Result {
            moved = List.copyOf(moved);
        }

        public static Result nothing() {
            return new Result(List.of(), 0, 0);
        }

        /** True when the user's own code was changed, so there are review marks to walk. */
        public boolean touchedSources() {
            return filesRewritten > 0;
        }

        /**
         * The success sentence, in three parts: what moved, what it cost this bot, and where the way back
         * is. Pure, so the wording is asserted without a window.
         */
        public String summary() {
            if (moved.isEmpty()) return "Nothing moved.";
            StringBuilder text = new StringBuilder(moved.size() == 1
                    ? "Moved " + moved.getFirst() + "."
                    : "Moved " + moved.size() + " plugins: "
                            + String.join(", ", moved.stream().map(Moved::toString).toList()) + ".");
            if (filesRewritten == 0) {
                text.append(" Nothing in this bot's own code had to change.");
            } else {
                text.append(" ").append(callsRepaired).append(" call")
                        .append(callsRepaired == 1 ? "" : "s").append(" repaired in ")
                        .append(filesRewritten).append(" file").append(filesRewritten == 1 ? "" : "s")
                        .append(" — the functions they are in are marked for review.");
            }
            return text + " The previous state is one restore away in the Versions tab.";
        }
    }

    /**
     * Where the new versions land. {@code LibraryService::updateVersions} live; a test hands in a recorder,
     * because the real one re-resolves the classpath and re-binds every plugin.
     */
    @FunctionalInterface
    public interface PomWriter {
        CompletableFuture<Void> write(Map<String, String> versionsByCoordinate);
    }

    /**
     * Runs the whole pass off the calling thread. The future fails — with nothing written — when any row
     * refuses; see the class javadoc for what a failure after the snapshot means.
     */
    public static CompletableFuture<Result> run(List<Row> rows, PomWriter writer) {
        List<Row> moving = rows.stream()
                .filter(r -> !r.targetVersion().isBlank()
                        && !r.targetVersion().equals(r.upgrades().currentVersion()))
                .toList();
        if (moving.isEmpty()) return CompletableFuture.completedFuture(Result.nothing());

        return CompletableFuture
                .supplyAsync(() -> {
                    // Nothing below this line may write until every row has answered. The reports are held
                    // in a list keyed by position rather than by the row: two rows moving the same
                    // coordinate to the same version are equal as records and would collapse into one.
                    List<Report> checked = new ArrayList<>();
                    for (Row row : moving) checked.add(refuseOrReport(row));

                    moving.getFirst().upgrades().snapshot(snapshotMessage(moving));

                    Map<String, String> versions = new LinkedHashMap<>();
                    List<Moved> moved = new ArrayList<>();
                    int files = 0;
                    int calls = 0;
                    for (int i = 0; i < moving.size(); i++) {
                        Row row = moving.get(i);
                        Report report = checked.get(i);
                        // Read before the repair: the version the project is on is read off the pom, and the
                        // pom write below moves it.
                        String from = row.upgrades().currentVersion();
                        if (report.canMigrate() || (row.alsoModernise() && report.canModernise())) {
                            files += row.upgrades()
                                    .repair(row.targetVersion(), row.alsoModernise(), true, row.picks());
                            calls += report.breaks().size();
                        }
                        moved.add(new Moved(row.upgrades().displayName(), from, row.targetVersion()));
                        versions.put(row.upgrades().coordinate(), row.targetVersion());
                    }
                    return new Pass(versions, new Result(moved, files, calls));
                })
                .thenCompose(pass -> writer.write(pass.versions()).thenApply(v -> pass.result()));
    }

    /** The pom write and the sentence that follows it, carried together through the last step. */
    private record Pass(Map<String, String> versions, Result result) {}

    /**
     * That row's report, or an exception saying why the pass will not start.
     *
     * <p>The two refusals are the report's own verdicts, stated as the failure of the <em>pass</em> rather
     * than of the row: one plugin that cannot be repaired stops every other plugin's move too, because the
     * versions are written together and a half-written set is the state this class exists to prevent.
     */
    private static Report refuseOrReport(Row row) {
        String what = row.upgrades().displayName() + " " + row.targetVersion();
        Report report = row.upgrades().compare(row.targetVersion(), row.alsoModernise());
        if (report.isIncomplete()) {
            throw new IllegalStateException("The check for " + what + " could not be completed, so nothing "
                    + "has been changed: " + report.problems().getFirst());
        }
        List<Break> refused = report.unrepairable();
        if (!refused.isEmpty()) {
            throw new IllegalStateException("\"" + refused.getFirst().type() + "\" is gone from " + what
                    + " and nothing in that release takes its place, so this bot writes a type name that "
                    + "would no longer exist. Change those uses by hand first. Nothing has been changed.");
        }
        return report;
    }

    private static String snapshotMessage(List<Row> rows) {
        if (rows.size() == 1) {
            Row only = rows.getFirst();
            return "Before " + only.upgrades().displayName() + " upgrade to " + only.targetVersion();
        }
        return "Before upgrading " + rows.size() + " plugins";
    }
}
