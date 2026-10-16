package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Several plugins moved in one pass — one snapshot, one repair of the code for every row at once, the values
 * repaired per row, one pom write.
 *
 * <p>The rule it enforces is not a layout decision: <b>a project must never sit on plugin A's new version with
 * plugin B's old source</b>. Every row is checked before anything at all is written, and the write that moves
 * the versions happens once, after every rewrite has already succeeded.
 *
 * <h2>The order, and why each step is where it is</h2>
 *
 * <ol>
 *   <li><b>Check every row first.</b> {@link PluginUpgradeService#compare} answers without writing. Nothing it
 *       finds refuses the pass since 2026-09-29: what it could not read is said back in
 *       {@link Result#leftForYou()}.</li>
 *   <li><b>One snapshot.</b> A commit per plugin would describe a state the user never asked to be in.</li>
 *   <li><b>The code, once, against every row's target at once</b> ({@link PluginUpgradeService#repairCode},
 *       2026-10-16): the bot's {@code @Migrate} rules applied, a default rule seeded for each broken element none
 *       names. One pass because a rule may name an element of either plugin, and javac checks the rewrite against
 *       the classpath the bot will compile with.</li>
 *   <li><b>The values, per row, after the code</b> ({@link PluginUpgradeService#repairValues}): a managed value a
 *       rule rewrote compiles again, so only what no rule covers is reset — the last resort.</li>
 *   <li><b>One pom write, for every row at once.</b> See {@code MavenService.setDependencyVersions}.</li>
 * </ol>
 *
 * <p><b>What it deliberately does not promise:</b> a failure <em>after</em> the snapshot leaves the project
 * mid-pass with no pom change. That is what the snapshot is for; rolling the files back here would be a second,
 * worse implementation of the revert the Versions tab already offers.
 */
public final class ProjectUpgrade {

    private ProjectUpgrade() {
    }

    /**
     * One row of the window, resolved to the operation it stands for.
     *
     * @param upgrades      the engine pointed at that plugin's coordinate
     * @param targetVersion the version the row's combo box is on — older than the installed one for a downgrade,
     *                      which is the same operation and the same control
     */
    public record Row(PluginUpgradeService upgrades, String targetVersion) {}

    /** One plugin that moved, as the summary names it. */
    public record Moved(String plugin, String from, String to) {

        @Override
        public String toString() {
            return plugin + " " + from + " → " + to;
        }
    }

    /**
     * What the pass did, for the window to say back. {@code filesRewritten} is what was written and
     * {@code usesRewritten} the uses of a broken element the rules rewrote; nothing here is a report of what
     * <em>would</em> happen.
     */
    public record Result(List<Moved> moved, int filesRewritten, int usesRewritten, List<String> leftForYou,
                         List<String> repaired, List<Path> deleted) {

        public Result {
            moved = List.copyOf(moved);
            leftForYou = List.copyOf(leftForYou);
            repaired = List.copyOf(repaired);
            deleted = List.copyOf(deleted);
        }

        public Result(List<Moved> moved, int filesRewritten, int usesRewritten) {
            this(moved, filesRewritten, usesRewritten, List.of(), List.of(), List.of());
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
            if (!repaired.isEmpty()) {
                text.append(" Repaired the plugin's values: ").append(String.join(" ", repaired));
            }
            if (filesRewritten == 0) {
                text.append(" Nothing in this bot's own code had to change.");
            } else {
                text.append(" ").append(usesRewritten).append(" use")
                        .append(usesRewritten == 1 ? "" : "s").append(" migrated in ")
                        .append(filesRewritten).append(" file").append(filesRewritten == 1 ? "" : "s")
                        .append(" — a default rule stands where none was written, marked for review in Migrations.");
            }
            if (!leftForYou.isEmpty()) {
                text.append(" ").append(leftForYou.size()).append(leftForYou.size() == 1 ? " thing was" : " things were")
                        .append(" left for you to finish: ").append(String.join(" ", leftForYou));
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

    /** Runs the whole pass off the calling thread; see the class javadoc for what a failure after the snapshot means. */
    public static CompletableFuture<Result> run(List<Row> rows, PomWriter writer) {
        List<Row> moving = rows.stream()
                .filter(r -> !r.targetVersion().isBlank()
                        && !r.targetVersion().equals(r.upgrades().currentVersion()))
                .toList();
        if (moving.isEmpty()) return CompletableFuture.completedFuture(Result.nothing());

        return CompletableFuture
                .supplyAsync(() -> {
                    // Nothing below this line may write until every row has answered. The reports are held in a
                    // list keyed by position: two equal rows would collapse into one as map keys.
                    List<Report> checked = new ArrayList<>();
                    for (Row row : moving) checked.add(row.upgrades().compare(row.targetVersion(), false));

                    moving.getFirst().upgrades().snapshot(snapshotMessage(moving));

                    Map<String, String> versions = new LinkedHashMap<>();
                    List<Moved> moved = new ArrayList<>();
                    List<String> left = new ArrayList<>();
                    for (int i = 0; i < moving.size(); i++) {
                        Row row = moving.get(i);
                        versions.put(row.upgrades().coordinate(), row.targetVersion());
                        // Read before anything moves: the version the project is on is read off the pom.
                        moved.add(new Moved(row.upgrades().displayName(), row.upgrades().currentVersion(),
                                row.targetVersion()));
                        // What the check could not read does not stop the move (2026-09-29); it is said back.
                        String name = row.upgrades().displayName();
                        checked.get(i).problems().forEach(problem -> left.add(name + ": " + problem));
                    }

                    PluginUpgradeService.Repaired code = moving.getFirst().upgrades().repairCode(versions);
                    left.addAll(code.leftAsWritten());

                    List<String> repaired = new ArrayList<>();
                    List<Path> deleted = new ArrayList<>();
                    int files = code.files();
                    for (Row row : moving) {
                        PluginUpgradeService.Values values = row.upgrades().repairValues(row.targetVersion());
                        repaired.addAll(values.repaired());
                        left.addAll(values.left());
                        deleted.addAll(values.deleted());
                    }
                    return new Pass(versions, new Result(moved, files, code.uses(), left, repaired, deleted));
                })
                .thenCompose(pass -> writer.write(pass.versions()).thenApply(v -> pass.result()));
    }

    /** The pom write and the sentence that follows it, carried together through the last step. */
    private record Pass(Map<String, String> versions, Result result) {}

    private static String snapshotMessage(List<Row> rows) {
        if (rows.size() == 1) {
            Row only = rows.getFirst();
            return "Before " + only.upgrades().displayName() + " upgrade to " + only.targetVersion();
        }
        return "Before upgrading " + rows.size() + " plugins";
    }
}
