package com.botmaker.studio.services.upgrade;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.migrate.Broken;
import com.botmaker.studio.migrate.Migration;
import com.botmaker.studio.migrate.Rewrite;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.PluginFiles;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.project.managed.ManagedRepairs;
import com.botmaker.studio.project.managed.MigrationSteps;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * What changing one installed plugin's version — or taking it out — does to <em>this bot</em>, and the pass that
 * carries the bot over.
 *
 * <p>The report is asked before anything is written. The target version's jar is resolved (the project pom's
 * repositories, so a version never on this machine downloads on demand), the bot is parsed against the classpath
 * it would compile with, and the <b>author's migration engine</b> ({@code migrate.Migration}, 2026-10-16) lists
 * every element of the old API the bot uses that the new one breaks ({@link Report#broken()}), each with its uses
 * and whether a {@code @Migrate} rule in the bot covers it. Nothing is guessed: javac, over the bot against both
 * classpaths, is what says a use broke. Beside it the report carries the target release's own changelog
 * ({@link WhatsNew}), what is new ({@link ApiModel}), and what the move does to the bot's managed values
 * ({@link ManagedRepairs}).
 *
 * <p><b>The pass is never blocked (2026-09-29).</b> The bot's rules are applied; every broken element no rule names
 * gets a default rule seeded into its {@code Migrations} class and applied too — a value standing for nothing, or a
 * call deleted — marked {@code @Refactor} for the author to write over (2026-10-16, the user's last resort). A use
 * no rule can stand at is left as written, its function marked, and said back. Until 2026-10-16 the plugin said
 * where its members went ({@code @ReplacedBy}) and this class diffed the two jars by simple name and arity; the
 * plugin now says nothing about its own changes, and the author decides what replaces what.
 *
 * <p><b>It was {@code SdkUpgradeService} until 2026-09-15</b>; the coordinate is a constructor argument,
 * {@link #artifact}, and plugin #1 goes through the path every other plugin goes through.
 */
public final class PluginUpgradeService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final LibraryService libraryService;
    private final JitPackSearch jitpack;

    /**
     * Which plugin this service is about — the coordinate its jars are resolved at and whose pin the pom
     * carries. Its {@link UserLibrary#version()} is ignored: what the project currently pins is read from
     * the pom on every call, because the window may have written it since.
     */
    private final UserLibrary artifact;

    /** The SDK coordinate, which is what every caller passed implicitly until 2026-09-15. */
    public static final UserLibrary SDK =
            new UserLibrary(MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "");

    public PluginUpgradeService(ProjectConfig config, ProjectState state,
                                LibraryService libraryService, JitPackSearch jitpack,
                                UserLibrary artifact) {
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.jitpack = jitpack;
        this.artifact = artifact;
    }

    // =========================================================================
    // THE REPORT
    // =========================================================================

    /**
     * One section of the target's changelog, newest first — the only list a report carries that states a
     * reason to move rather than a cost.
     */
    public record Highlight(String version, String date, List<String> lines) {}

    /** Which question a report answers; the sentence above its lists differs, the engine does not. */
    public enum Operation {

        /** The target is newer than what the pom pins. */
        UPGRADE,

        /** The target is older. */
        DOWNGRADE,

        /** The plugin goes. */
        REMOVAL
    }

    /**
     * The whole answer to "what happens if I move to this version".
     *
     * @param highlights   the target release's own changelog for the span crossed, newest first ({@link WhatsNew})
     * @param addedBySince the new API grouped by the release it arrived in, newest first ({@link ApiModel#additions})
     * @param broken       each old element the bot uses that the move breaks, with its uses and its rule's state
     * @param problems     what could not be read — separate on purpose: an empty {@code broken} means something
     *                     else when this is not empty too
     * @param values       what the move does to the bot's managed values, one sentence each ({@link ManagedRepairs})
     * @param stranded     the open sets the bot holds values of that the target no longer declares
     *                     ({@link MigrationSteps#stranded}): the window offers to migrate them first
     */
    public record Report(String from, String to,
                         Operation operation,
                         List<Highlight> highlights,
                         Map<String, List<String>> addedBySince,
                         List<Broken> broken,
                         List<String> problems,
                         List<String> values,
                         List<MigrationSteps.Stranded> stranded) {

        public Report {
            broken = List.copyOf(broken);
            problems = List.copyOf(problems);
            values = List.copyOf(values);
            stranded = List.copyOf(stranded);
        }

        /** This report, with what {@link ValuesPlan} says about the bot's managed values. */
        Report with(ValuesPlan plan) {
            List<String> problems = new ArrayList<>(this.problems);
            problems.addAll(plan.problems());
            return new Report(from, to, operation, highlights, addedBySince, broken, problems, plan.sentences(),
                    plan.stranded());
        }

        /** Everything new, as one list. */
        public List<String> added() {
            return addedBySince.values().stream().flatMap(List::stream).toList();
        }

        /** The broken elements a rule the author wrote covers. */
        public List<Broken> covered() {
            return broken.stream().filter(b -> b.state() == Broken.State.WRITTEN).toList();
        }

        /** The broken elements no written rule covers: the pass seeds a default rule for each it can. */
        public List<Broken> open() {
            return broken.stream().filter(b -> b.state() != Broken.State.WRITTEN).toList();
        }

        /** How many uses of a broken element the bot has. */
        public int uses() {
            return broken.stream().mapToInt(b -> b.sites().size()).sum();
        }

        /** True when the scan ran cleanly and found nothing that would stop this bot compiling. */
        public boolean nothingBreaks() {
            return broken.isEmpty() && problems.isEmpty();
        }

        /** True when the scan could not answer the question, whatever the other lists say. */
        public boolean isIncomplete() {
            return !problems.isEmpty();
        }

        static Report unavailable(String from, String to, Operation operation, String problem) {
            return new Report(from, to, operation, List.of(), Map.of(), List.of(), List.of(problem), List.of(),
                    List.of());
        }

        /** Which way this pair of versions goes; {@link Operation#REMOVAL} is never derived. */
        static Operation operationFor(String from, String to) {
            if (from.isBlank() || to.isBlank()) return Operation.UPGRADE;
            return ApiModel.compareVersions(ApiModel.strip(to), ApiModel.strip(from)) < 0
                    ? Operation.DOWNGRADE
                    : Operation.UPGRADE;
        }
    }

    // =========================================================================
    // ENTRY POINTS
    // =========================================================================

    /**
     * The version of {@link #artifact} the project pom pins right now, {@code ""} when it pins none — read from
     * the pom on every call, since a stale answer would offer an upgrade the project has already had.
     */
    public String currentVersion() {
        return MavenService.readDependencyVersion(
                config.projectPath(), artifact.groupId(), artifact.artifactId()).orElse("");
    }

    /** {@code groupId:artifactId} — what this service is pointed at, and the key its pom write is made by. */
    public String coordinate() {
        return artifact.groupId() + ":" + artifact.artifactId();
    }

    /** What to call this plugin in a sentence — "SDK" for plugin #1, the artifact id for everybody else. */
    public String displayName() {
        return name();
    }

    /**
     * The version a row starts on and <i>Update all</i> moves to: the newest build only when it is newer than
     * what the pom pins, the installed one otherwise. A newest behind the installed one (a pin JitPack has not
     * built yet) is still offered, never preselected — that would make opening the window propose a downgrade
     * (2026-10-01: gamebot on 1.2.3 opened on the registry's 1.1.7).
     */
    public static String recommended(String installed, String newest) {
        if (newest.isBlank()) return installed;
        if (installed.isBlank()) return newest;
        return ApiModel.compareVersions(ApiModel.strip(newest), ApiModel.strip(installed)) > 0 ? newest : installed;
    }

    /** Every version JitPack can build of it, newest first. Best-effort: an empty list on any failure. */
    public CompletableFuture<List<String>> availableVersions() {
        return jitpack.fetchVersions(artifact.groupId(), artifact.artifactId());
    }

    /**
     * The report for moving this project to {@code targetVersion}.
     *
     * <p><b>Blocking</b> — resolves (and possibly downloads) the target's classpath and parses the bot against
     * both. Call it off the FX thread.
     */
    public Report compare(String targetVersion) {
        return compare(targetVersion, true);
    }

    /** The same report; {@code strand} false leaves {@link Report#stranded} empty, for the pass that writes. */
    Report compare(String targetVersion, boolean strand) {
        String from = currentVersion();
        String to = targetVersion == null ? "" : targetVersion.trim();
        Operation operation = Report.operationFor(from, to);
        if (to.isEmpty()) return Report.unavailable(from, to, operation, "No target version was chosen.");

        Optional<Path> oldJar = resolve(from);
        Optional<Path> newJar = resolve(to);
        if (newJar.isEmpty()) {
            return Report.unavailable(from, to, operation, name() + " " + to + " could not be resolved. It may not"
                    + " be published yet, or you are offline.");
        }
        if (oldJar.isEmpty()) {
            return Report.unavailable(from, to, operation, "The " + name() + " this project currently pins (" + from
                    + ") could not be resolved, so there is nothing to compare the target against.");
        }
        List<String> problems = new ArrayList<>();
        List<Broken> broken = broken(movedTo(Map.of(coordinate(), to)), problems);
        Report report = new Report(from, to, operation,
                // Read from the target jar, not diffed out of the two: a release's reason for existing is not a
                // property of its API surface.
                WhatsNew.between(newJar.get(), from, to),
                ApiModel.additions(ApiModel.snapshot(oldJar.get()), ApiModel.snapshot(newJar.get())),
                broken, problems, List.of(), List.of());
        return report.with(values(newJar.get(), to, strand));
    }

    /**
     * What taking this plugin <b>out</b> of the project would do — the same question, the bot parsed against its
     * classpath with the plugin and {@code editorDependencies} gone. A plugin another one brings in still
     * resolves, and then nothing breaks.
     *
     * <p><b>Blocking</b>, for the same reasons {@link #compare(String)} is.
     */
    public Report removal(List<UserLibrary> editorDependencies) {
        String version = currentVersion();
        List<String> problems = new ArrayList<>();
        List<Broken> broken = broken(without(editorDependencies), problems);
        return new Report(version, "", Operation.REMOVAL, List.of(), Map.of(), broken, problems, List.of(),
                List.of());
    }

    /**
     * This plugin's files in the bot and the lines naming them — what a removal offers to delete. Only the
     * plugins loaded from this coordinate's own jar count, so a second plugin's {@code plugins/} folder is never
     * offered. Empty when the jar cannot be resolved or no plugin was loaded from it.
     */
    public PluginHolders holders() {
        Optional<Path> jar = resolve(currentVersion());
        if (jar.isEmpty()) return PluginHolders.NONE;
        List<StudioPlugin> fromJar = PluginHost.plugins().stream().filter(p -> loadedFrom(p, jar.get())).toList();
        return PluginHolders.of(config, state, PluginFiles.holdersOf(fromJar));
    }

    static boolean loadedFrom(StudioPlugin plugin, Path jar) {
        try {
            var source = plugin.getClass().getProtectionDomain().getCodeSource();
            if (source == null) return false;
            return Path.of(source.getLocation().toURI()).toRealPath().equals(jar.toRealPath());
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // WHAT BREAKS, AND THE REPAIR OF THE CODE
    // =========================================================================

    /** The bot's classpath with each coordinate of {@code versions} moved to its version; empty when it fails. */
    private Optional<List<String>> movedTo(Map<String, String> versions) {
        return StagedPlugin.classpath(config.projectPath(), String.join(", ", versions.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).toList()),
                staging -> MavenService.setDependencyVersions(staging, versions));
    }

    /** The bot's classpath with this plugin and {@code editorDependencies} gone; empty when it fails. */
    private Optional<List<String>> without(List<UserLibrary> editorDependencies) {
        return StagedPlugin.classpath(config.projectPath(), coordinate() + " removed",
                staging -> MavenService.removePlugin(staging, artifact.groupId(), artifact.artifactId(),
                        editorDependencies));
    }

    /** The bot between its classpath now and {@code target}; empty, with why in {@code problems}, when it fails. */
    private Optional<Migration> migration(Optional<List<String>> target, List<String> problems) {
        if (target.isEmpty()) {
            problems.add("The classpath " + name() + " would leave this bot with could not be resolved, so what it"
                    + " breaks was not checked.");
            return Optional.empty();
        }
        try {
            BotIndex current = BotIndex.of(config, state);
            return Optional.of(Migration.of(current.against(target.get()), current.classpath()));
        } catch (RuntimeException e) {
            problems.add("This bot could not be checked against " + name() + "'s new classpath: " + e + ".");
            return Optional.empty();
        }
    }

    private List<Broken> broken(Optional<List<String>> target, List<String> problems) {
        return migration(target, problems).map(Migration::broken).orElse(List.of());
    }

    /**
     * What a repair of the code wrote, and what it left for the user: one sentence per use or element left.
     *
     * @param files         how many of the project's files were written
     * @param uses          how many uses of a broken element were rewritten
     * @param leftAsWritten what the repair could not do, each with its reason
     * @param deleted       the plugin's files a removal deleted, {@code "Sdk.java, Pictures.java"}, or blank
     */
    public record Repaired(int files, int uses, List<String> leftAsWritten, String deleted) {

        public Repaired {
            leftAsWritten = List.copyOf(leftAsWritten);
            deleted = deleted == null ? "" : deleted;
        }

        static final Repaired NOTHING = new Repaired(0, 0, List.of(), "");
    }

    /**
     * Carries the bot's code over to the classpath with each coordinate of {@code versions} at its version: its
     * rules applied, a default rule seeded and applied for each broken element none names. Writes buffers and
     * disk; no snapshot (the caller took it) and no pom write (the caller makes it, last). Never refuses: what it
     * cannot do is left as written and said back (2026-09-29).
     */
    public Repaired repairCode(Map<String, String> versions) {
        return repair(movedTo(versions), "the move to " + String.join(", ", versions.values()));
    }

    /** The same for this plugin's departure, its {@code editorDependencies} going with it. */
    public Repaired repairRemoval(List<UserLibrary> editorDependencies) {
        return repair(without(editorDependencies), "the removal of " + name());
    }

    private Repaired repair(Optional<List<String>> target, String what) {
        List<String> left = new ArrayList<>();
        Optional<Migration> migration = migration(target, left);
        if (migration.isEmpty() || migration.get().broken().isEmpty()) return new Repaired(0, 0, left, "");
        // Spelled as the bot's sources are keyed, so an existing Migrations is found and never written over.
        Path file = config.mainPackageDir().resolve(Migration.CLASS + ".java").toAbsolutePath().normalize();
        Migration.Finished finished = migration.get().finished(file, config.mainPackage(),
                ReviewMarker.available(state));
        Rewrite.Result result = finished.result();
        for (Rewrite.Left use : result.left()) {
            left.add(use.site().file().getFileName() + ":" + use.site().line() + " " + use.site().element().display()
                    + " was left as written for " + what + ": " + use.why() + ".");
        }
        if (result.outcome() instanceof Refactor.Refused refused) {
            // Nothing to apply is no failure: every element was left, and each says why above.
            if (!result.applied().isEmpty() || !result.left().isEmpty()) left.add(refused.reason());
            return new Repaired(0, 0, left, "");
        }
        Refactor.Planned planned = (Refactor.Planned) result.outcome();
        write(planned.rewrites(), Set.of());
        return new Repaired(planned.rewrites().size(), result.applied().size(), left, "");
    }

    /**
     * Takes this plugin out of the project: snapshot → repair the code → delete {@code holders}, the plugin's
     * files the user agreed to lose → drop it from the pom, its {@code editorDependencies} with it. The code is
     * repaired first because a pom that no longer declares the plugin no longer resolves the jar the old parse
     * reads. The caller drops the holders' buffers on the FX thread once this completes
     * ({@link PluginHolders#forget}).
     */
    public CompletableFuture<Repaired> remove(List<UserLibrary> editorDependencies, PluginHolders holders) {
        return CompletableFuture
                .supplyAsync(() -> {
                    snapshot("Before removing " + name());
                    Repaired repaired = repairRemoval(editorDependencies);
                    if (holders.isEmpty()) return repaired;
                    try {
                        PluginHolders.Deleted deleted = holders.delete(config, state, name());
                        List<String> left = new ArrayList<>(repaired.leftAsWritten());
                        left.addAll(deleted.left());
                        return new Repaired(repaired.files() + deleted.rewritten(), repaired.uses(), left,
                                holders.fileNames());
                    } catch (IOException e) {
                        throw new RuntimeException("The plugin's files could not be deleted: " + e.getMessage(), e);
                    }
                })
                .thenCompose(repaired -> libraryService
                        .removePlugin(artifact.groupId(), artifact.artifactId(), editorDependencies)
                        .thenApply(v -> repaired));
    }

    /**
     * The one revert away everything here promises. Public because the project upgrade window takes <b>one</b>
     * snapshot across every row it is about to move.
     */
    public void snapshot(String message) {
        try {
            new ProjectVcs(config.projectPath()).checkpoint(VersionOrigin.SAFETY, message);
        } catch (IOException e) {
            throw new RuntimeException("Could not snapshot the project first: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // REPAIR OF THE BOT'S MANAGED VALUES (2026-10-11)
    // =========================================================================

    /**
     * What the move does to this bot's managed values: the repair, or why it could not be worked out. Never a
     * refusal: what is not checked is left as written and said back, like the rest of the upgrade.
     *
     * @param planned  the repair, null when there was nothing to plan
     * @param problems what could not be checked
     * @param stranded the open sets whose values the target leaves nowhere to live ({@link Report#stranded})
     */
    record ValuesPlan(ManagedRepairs.Planned planned, List<String> problems, List<MigrationSteps.Stranded> stranded) {

        static final ValuesPlan NONE = new ValuesPlan(null, List.of(), List.of());

        /** The sentences a report shows: what the repair removes or resets, then what it keeps. */
        List<String> sentences() {
            return planned == null ? List.of() : planned.sentences();
        }
    }

    /**
     * What a repair of the values wrote.
     *
     * @param repaired what it removed or reset, one sentence each
     * @param left     what it kept as written, one sentence each
     * @param deleted  the files it deleted — the caller drops their buffers on the FX thread
     */
    public record Values(int files, List<String> repaired, List<String> left, List<Path> deleted) {

        public Values {
            repaired = List.copyOf(repaired);
            left = List.copyOf(left);
            deleted = List.copyOf(deleted);
        }

        static final Values NOTHING = new Values(0, List.of(), List.of(), List.of());
    }

    /**
     * The repair of this bot's values for the move to {@code to}: the target jar loaded beside the bound one, the
     * bot parsed against the target classpath. <b>Blocking</b> — resolves the target classpath and parses the bot.
     *
     * @param strand also what the move strands ({@link Report#stranded}): asked before the move, not during it
     */
    ValuesPlan values(Path newJar, String to, boolean strand) {
        Optional<StagedPlugin> opened = StagedPlugin.open(config.projectPath(), coordinate(), to, newJar);
        if (opened.isEmpty()) {
            if (!holdsOwnValues()) return ValuesPlan.NONE;
            return new ValuesPlan(null, List.of("The " + name() + " " + to + " plugin could not be loaded, so this"
                    + " bot's values in its files were not checked; they are left as written."), List.of());
        }
        try (StagedPlugin staged = opened.get()) {
            if (staged.declared().isEmpty() && holdsOwnValues()) {
                return new ValuesPlan(null, List.of(name() + " " + to + " declares no values Studio could read, so"
                        + " this bot's values in its files are left as written."), List.of());
            }
            BotIndex index = BotIndex.of(config, state).against(staged.classpath());
            String note = ReviewMarker.available(state)
                    ? "Reset by the " + name() + " " + to + " upgrade: it no longer compiled." : null;
            return new ValuesPlan(ManagedRepairs.plan(index, staged.declared(), staged.grammar(), note), List.of(),
                    strand ? stranded(staged.declared()) : List.of());
        } catch (RuntimeException | LinkageError e) {
            // The plugin's declarations are in this pass; whatever they throw leaves the values as written.
            return new ValuesPlan(null, List.of(name() + " " + to + "'s values could not be read, so this bot's"
                    + " values in its files are left as written: " + e + "."), List.of());
        }
    }

    /** Whether the bot marks any value the plugins bound from this coordinate declare. */
    private boolean holdsOwnValues() {
        Set<String> own = new LinkedHashSet<>();
        ownDeclared().forEach(v -> own.add(v.id()));
        if (own.isEmpty()) return false;
        return ManagedRepairs.held(BotIndex.of(config, state)).stream().anyMatch(own::contains);
    }

    /** Every value the plugins bound from this coordinate declare now; a plugin that throws declares nothing. */
    private List<ManagedValue<?>> ownDeclared() {
        List<ManagedValue<?>> own = new ArrayList<>();
        Set<String> ids = PluginHost.loaded().stream()
                .filter(l -> l.isOf(artifact.groupId(), artifact.artifactId()))
                .map(PluginHost.LoadedPlugin::id).collect(Collectors.toSet());
        for (StudioPlugin plugin : PluginHost.plugins()) {
            if (!ids.contains(plugin.id())) continue;
            try {
                List<ManagedValue<?>> values = plugin.managedValues();
                if (values != null) values.stream().filter(v -> v != null).forEach(own::add);
            } catch (RuntimeException | LinkageError e) {
                // Declares nothing it can say.
            }
        }
        return own;
    }

    /**
     * This coordinate's open sets the bot holds values of, as bound now, that {@code target}'s declarations drop —
     * read with the bound grammar on the bot as it compiles now, so the migration window opens on them as they
     * are. Never throws: what cannot be read strands nothing.
     */
    private List<MigrationSteps.Stranded> stranded(List<ManagedValue<?>> target) {
        try {
            return MigrationSteps.stranded(BotIndex.of(config, state), PluginHost.grammar(),
                    PluginHost.managedValues(), ownDeclared(), target);
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /**
     * Repairs this bot's managed values for the move to {@code targetVersion} and writes the repair — buffers and
     * disk, no snapshot (the caller took it) and no pom write (the caller makes it, last). Never refuses: what
     * cannot be checked is left as written (2026-09-29). It runs after the code's repair, so a value a rule
     * rewrote is not reset.
     */
    public Values repairValues(String targetVersion) {
        String from = currentVersion();
        if (from.equals(targetVersion)) return Values.NOTHING;
        Optional<Path> newJar = resolve(targetVersion);
        if (newJar.isEmpty()) {
            return holdsOwnValues() ? new Values(0, List.of(), List.of(name() + " " + targetVersion + " could not be"
                    + " resolved again, so this bot's values in its files are left as written."), List.of())
                    : Values.NOTHING;
        }
        ValuesPlan plan = values(newJar.get(), targetVersion, false);
        if (plan.planned() == null) return new Values(0, List.of(), plan.problems(), List.of());
        ManagedRepairs.Planned planned = plan.planned();
        if (planned.isEmpty()) return new Values(0, planned.repaired(), planned.left(), List.of());
        write(planned.rewrites(), planned.deleted());
        return new Values(planned.rewrites().size() + planned.deleted().size(), planned.repaired(), planned.left(),
                List.copyOf(planned.deleted()));
    }

    /** {@code rewrites} written — the editor's buffers and disk, a file the bot lacks created — and {@code deleted} gone. */
    private void write(Map<Path, String> rewrites, Set<Path> deleted) {
        Set<Path> written = new HashSet<>();
        BotSources.forEach(config, state, (file, source) -> {
            String after = rewrites.get(file);
            if (after == null || deleted.contains(file)) return null;
            written.add(file);
            return after;
        });
        try {
            for (Map.Entry<Path, String> created : rewrites.entrySet()) {
                if (written.contains(created.getKey())) continue;
                Files.createDirectories(created.getKey().getParent());
                Files.writeString(created.getKey(), created.getValue());
            }
            for (Path gone : deleted) Files.deleteIfExists(gone);
        } catch (IOException e) {
            throw new RuntimeException("Some files could not be written: " + e.getMessage(), e);
        }
    }

    /**
     * That jar, at one version — {@link #artifact}'s coordinate with the version filled in. The project's own pom
     * is consulted for its {@code <repositories>}, so a version never on this machine downloads on demand.
     */
    private Optional<Path> resolve(String version) {
        return MavenService.resolveArtifact(
                config.projectPath(), artifact.groupId(), artifact.artifactId(), "", version);
    }

    /** What this plugin is called in a sentence — its artifact id, except for the SDK, always "SDK". */
    private String name() {
        return MavenService.SDK_ARTIFACT_ID.equals(artifact.artifactId()) ? "SDK" : artifact.artifactId();
    }
}
