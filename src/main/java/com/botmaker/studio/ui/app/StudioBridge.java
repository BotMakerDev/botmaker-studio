package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.assist.AgentContext;
import com.botmaker.plugin.api.overlay.Marks;
import com.botmaker.plugin.api.overlay.OverlayFrame;
import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.overlay.Watched;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.assist.LiveBot;
import com.botmaker.studio.assist.PluginMove;
import com.botmaker.studio.assist.RunLog;
import com.botmaker.studio.assist.StudioDriver;
import com.botmaker.studio.assist.SymbolNames;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.services.ReviewService;
import org.eclipse.jdt.core.dom.NodeFinder;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.BlockId;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.services.ClassFiles;
import com.botmaker.studio.services.overlay.OverlayTargets;
import com.botmaker.studio.services.overlay.WatchedScreen;
import com.botmaker.studio.ui.app.overlay.OverlayEditor;
import com.botmaker.studio.ui.app.trial.TrialMenu;
import com.botmaker.studio.ui.app.trial.Trials;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.Runs;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.NewProjects;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.project.params.JavaParameter;
import com.botmaker.studio.project.params.JavaParameters;
import com.botmaker.studio.project.params.ParameterRow;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.upgrade.InstalledPlugin;
import com.botmaker.studio.services.upgrade.PluginHolders;
import com.botmaker.studio.services.upgrade.PluginUpgradeService;
import com.botmaker.studio.services.upgrade.ProjectUpgrade;
import com.botmaker.studio.services.upgrade.FixList;
import com.botmaker.studio.services.LocalBuilds;
import com.botmaker.studio.plugin.ReleasedPlugins;
import com.botmaker.studio.project.managed.ManagedMigrations;
import com.botmaker.studio.project.managed.MigrationCleanup;
import com.botmaker.studio.project.managed.MigrationSteps;
import com.botmaker.studio.project.managed.MigrationSummary;
import com.botmaker.studio.sharing.PluginCatalog;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.botmaker.studio.ui.app.LiveEditorBot.onFx;

/**
 * Studio as the MCP endpoint drives it ({@link StudioDriver}): targets, the overlay editor's caret and boxes, and
 * runs through the same events and the same {@link Trials} the toolbar and the menus use. Calls arrive on Jetty's
 * threads; each hops to the FX thread for what reads or changes the editor, and parses the bot off it.
 *
 * <p>Every move is one a user could make, made where they can see it: a target or a statement in another file
 * opens that file in the editor first.
 */
final class StudioBridge implements StudioDriver {

    private final StudioContext ctx;
    private final ClassFiles classFiles;
    private final Trials trials;
    private final RunLog log;
    private final Supplier<Window> owner;
    private final JitPackSearch jitpack = new JitPackSearch();
    private final GitHubClient github = new GitHubClient();
    // The plugin index, read off the same raw CDN as Plugins & Libraries, with no account.
    private final PluginRegistry registry = new PluginRegistry(github);

    StudioBridge(StudioContext ctx, Trials trials, RunLog log, Supplier<Window> owner) {
        this.ctx = ctx;
        this.classFiles = new ClassFiles(ctx);
        this.trials = trials;
        this.log = log;
        this.owner = owner;
    }

    // ---- where blocks go -----------------------------------------------------------------------------------

    @Override
    public List<Target> targets() {
        return targets(found());
    }

    /** The plugins' targets, the sources read on FX and parsed here. */
    private List<OverlayTargets.Target> found() {
        record Asked(List<OverlayPart.TargetType> types, Supplier<BotIndex> index) {}
        Asked asked = onFx(() -> {
            List<OverlayPart.TargetType> types = new ArrayList<>();
            for (PluginHost.OwnedOverlay owned : PluginHost.overlayParts()) types.addAll(owned.part().targets());
            return new Asked(types, BotIndex.prepare(ctx.config(), ctx.state()));
        });
        return asked.types().isEmpty() ? List.of() : OverlayTargets.find(asked.index().get(), asked.types());
    }

    private OverlayTargets.Target target(String given) {
        List<OverlayTargets.Target> found = found();
        Target picked = StudioDriver.target(targets(found), given);
        return found.stream().filter(t -> t.key().equals(picked.key())).findFirst().orElseThrow();
    }

    private List<Target> targets(List<OverlayTargets.Target> found) {
        Path root = ctx.config().sourceRoot();
        return found.stream().map(t -> new Target(t.key(), t.label(), t.group(), LiveBot.relative(root, t.file()),
                t.method())).toList();
    }

    @Override
    public String setTarget(String key) {
        OverlayTargets.Target target = target(key);
        return onFx(() -> {
            boolean inOverlay = OverlayEditor.showTarget(target);
            if (!inOverlay) ctx.codeEditorService().switchToFile(target.file());
            say("The assistant opened " + target.label() + "." + target.method() + "().");
            return "Opened " + target.label() + "." + target.method() + "() — " + target.file().getFileName()
                    + " is open in the editor" + (inOverlay ? " and in the overlay editor." : ".");
        });
    }

    @Override
    public String moveCaret(String file, String blockId) {
        return onFx(() -> {
            Statement statement = statementOf(file, blockId);
            CodeBlock block = ctx.state().getNodeToBlockMap().get(statement);
            // A call's row holds its MethodInvocation, not the statement around it.
            if (!(block instanceof StatementBlock) && statement instanceof ExpressionStatement call) {
                block = ctx.state().getNodeToBlockMap().get(call.getExpression());
            }
            if (!(block instanceof StatementBlock row)) {
                throw new IllegalArgumentException("Statement " + blockId + " has no row to put the caret on.");
            }
            Optional<String> refused = OverlayEditor.caretOn(row);
            if (refused.isPresent()) throw new IllegalArgumentException(refused.get());
            return "The caret is on " + blockId + ".";
        });
    }

    @Override
    public String showArea(Area area, String label) {
        if (area.width() <= 0 || area.height() <= 0) {
            throw new IllegalArgumentException("An area needs a width and a height above 0.");
        }
        return onFx(() -> {
            Marks marks = OverlayEditor.assistantMarks().orElseThrow(() -> new IllegalArgumentException(
                    "The overlay editor is not open, so there is nothing to draw on. The user opens it with ⧉ Overlay."));
            marks.show(area, Marks.Kind.NOTE, label == null ? "" : label);
            return "Boxed " + area.x() + "," + area.y() + " " + area.width() + "×" + area.height() + " on the game.";
        });
    }

    // ---- runs ----------------------------------------------------------------------------------------------

    @Override
    public String runBot() {
        return onFx(() -> {
            ctx.eventBus().publish(new CoreApplicationEvents.ExecutionRequestedEvent());
            return "Starting the bot. run_state says when it runs; read_trace what it does.";
        });
    }

    @Override
    public String runActivity(String key) {
        OverlayTargets.Target target = target(key);
        BotIndex index = onFx(() -> BotIndex.prepare(ctx.config(), ctx.state())).get();
        record OnItsOwn(String packageName, boolean valued) {}
        OnItsOwn runnable = index.read(units -> {
            CompilationUnit unit = units.get(target.file());
            if (unit == null) return null;
            List<OnItsOwn> out = new ArrayList<>();
            unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration method) {
                    if (out.isEmpty() && method.getName().getIdentifier().equals(target.method())
                            && method.getParent() instanceof AbstractTypeDeclaration) {
                        Optional<IMethodBinding> binding = TrialMenu.runnable(method).filter(b ->
                                b.getDeclaringClass().getErasure().getQualifiedName().equals(target.className()));
                        binding.ifPresent(b -> out.add(new OnItsOwn(TrialMenu.packageOf(b),
                                !"void".equals(b.getReturnType().getName()))));
                    }
                    return true;
                }
            });
            return out.isEmpty() ? null : out.getFirst();
        });
        if (runnable == null) {
            throw new IllegalArgumentException(target.label() + "." + target.method() + "() does not run on its own: "
                    + "it must be static, take nothing and not be private.");
        }
        return onFx(() -> trials.runOnItsOwn(runnable.packageName(), target.className(), target.method(),
                runnable.valued()));
    }

    @Override
    public String tryStatement(String file, String blockId, Map<String, String> values) {
        return onFx(() -> trials.tryWith(statementOf(file, blockId), values));
    }

    @Override
    public String stop() {
        if (!log.busy()) return "Nothing runs.";
        return onFx(() -> {
            ctx.eventBus().publish(log.debugging() ? new CoreApplicationEvents.DebugStopRequestedEvent()
                    : new CoreApplicationEvents.StopRunRequestedEvent());
            return "Stopping. run_state says when it has.";
        });
    }

    // ---- structure and navigation --------------------------------------------------------------------------

    @Override
    public String addFile(String name, String kind, String packageName) {
        ClassFiles.Kind what = kind == null || kind.isBlank() ? ClassFiles.Kind.CLASS : ClassFiles.Kind.fromId(kind)
                .orElseThrow(() -> new IllegalArgumentException("kind is class, interface, record or enum."));
        notReading();
        String pkg = packageName == null ? "" : packageName.strip();
        if (!pkg.isEmpty() && !javax.lang.model.SourceVersion.isName(pkg)) {
            throw new IllegalArgumentException("“" + pkg + "” is not a package name: "
                    + ctx.config().mainPackage() + ".util.");
        }
        Path folder = pkg.isEmpty() ? ctx.config().mainPackageDir()
                : ctx.config().sourceRoot().resolve(pkg.replace('.', '/'));
        Path file = classFiles.create(folder, what, name);
        return "Added " + LiveBot.relative(ctx.config().sourceRoot(), file) + ". add_method puts methods in it.";
    }

    @Override
    public String deleteFile(String file) {
        notReading();
        Path at = onFx(() -> path(file));
        ProjectState.Snapshot before = onFx(() -> ctx.state().snapshot());
        Optional<Refactor.Refused> refused = classFiles.planDelete(index(), before, at);
        if (refused.isPresent()) {
            Path root = ctx.config().sourceRoot();
            List<String> lines = new ArrayList<>();
            lines.add(refused.get().reason());
            for (Usages.Usage use : refused.get().uses()) {
                lines.add(LiveBot.relative(root, use.file()) + ":" + use.line()
                        + (use.enclosing().isEmpty() ? "" : " in " + use.enclosing()) + "  " + use.text().strip());
            }
            throw new IllegalArgumentException(String.join("\n", lines));
        }
        return onFx(() -> {
            try {
                return classFiles.delete(at);
            } catch (java.io.IOException failed) {
                throw new IllegalArgumentException(at.getFileName() + " could not be deleted: " + failed.getMessage());
            }
        });
    }

    @Override
    public String findUsages(String symbol) {
        BotIndex index = index();
        SymbolNames.Located at = index.read(units -> SymbolNames.locate(units, symbol));
        List<Usages.Usage> uses = Refactor.uses(index, at.file(), at.start()).orElseThrow(() ->
                new IllegalArgumentException(symbol + " could not be resolved, so its uses cannot be found."));
        if (uses.isEmpty()) return "Nothing uses " + at.what() + ".";
        Path root = ctx.config().sourceRoot();
        List<String> lines = new ArrayList<>();
        for (Usages.Usage use : uses.subList(0, Math.min(uses.size(), USAGE_LIMIT))) {
            lines.add(LiveBot.relative(root, use.file()) + ":" + use.line()
                    + (use.enclosing().isEmpty() ? "" : " in " + use.enclosing()) + "  " + use.text().strip());
        }
        if (uses.size() > USAGE_LIMIT) lines.add("… and " + (uses.size() - USAGE_LIMIT) + " more.");
        return uses.size() + " uses of " + at.what() + ":\n" + String.join("\n", lines);
    }

    private static final int USAGE_LIMIT = 200;

    @Override
    public String rename(String symbol, String to) {
        BotIndex index = index();
        SymbolNames.Located at = index.read(units -> SymbolNames.locate(units, symbol));
        if (!symbol.contains(".") && at.file().getFileName().toString().equals(symbol + ".java")) {
            // A class whose file is named after it: the explorer's rename, which moves the file with it.
            notReading();
            ProjectState.Snapshot before = onFx(() -> ctx.state().snapshot());
            Refactor.Planned moved = planned(classFiles.planRename(index, before, at.file(), to));
            return onFx(() -> {
                unchangedSince(index, moved);
                try {
                    return classFiles.applyRename(moved);
                } catch (java.io.IOException failed) {
                    throw new IllegalArgumentException("The rename stopped part-way (" + failed.getMessage()
                            + "); revert goes back to the safety version from before.");
                }
            });
        }
        Refactor.Planned plan = planned(Refactor.rename(index, at.file(), at.start(), to));
        return onFx(() -> {
            unchangedSince(index, plan);
            openFile(at.file());
            CompilationUnit unit = ctx.state().getCompilationUnit().orElseThrow(() ->
                    new IllegalArgumentException("Studio has not drawn " + at.file().getFileName() + " yet."));
            ASTNode target = NodeFinder.perform(unit, at.start(), 0);
            List<String> said = new ArrayList<>();
            try (EventBus.Subscription heard = ctx.eventBus().subscribe(CoreApplicationEvents.StatusMessageEvent.class,
                    e -> said.add(e.message()))) {
                if (!ctx.codeEditorService().getCodeEditor().applyRefactor(plan, target)) {
                    throw new IllegalArgumentException("Studio did not rename it"
                            + (said.isEmpty() ? "." : ": " + said.getLast()));
                }
            }
            return plan.summary() + " — " + plan.rewrites().size() + " file(s), one undo step.";
        });
    }

    /** {@code outcome}'s plan, or its refusal as the tool's answer with the names that would work. */
    private static Refactor.Planned planned(Refactor.Outcome outcome) {
        return switch (outcome) {
            case Refactor.Refused refused -> throw new IllegalArgumentException(refused.reason()
                    + (refused.fixes().isEmpty() ? "" : " Names that would work: "
                    + refused.fixes().stream().map(Refactor.Fix::label).toList() + "."));
            case Refactor.Planned planned -> planned;
        };
    }

    /**
     * Refuses {@code plan} when a file it rewrites was typed in since {@code index} read it, which the plan would
     * overwrite. FX thread.
     */
    private void unchangedSince(BotIndex index, Refactor.Planned plan) {
        Map<Path, String> now = new LinkedHashMap<>();
        BotSources.scan(ctx.config(), ctx.state(), now::put);
        for (Path file : plan.rewrites().keySet()) {
            if (!java.util.Objects.equals(now.get(file), index.sources().get(file))) {
                throw new IllegalArgumentException(file.getFileName() + " changed meanwhile. Try again.");
            }
        }
    }

    /** Refuses a change to the bot's files while it is open for reading, as the explorer does. */
    private void notReading() {
        if (onFx(() -> ctx.state().isReaderMode())) {
            throw new IllegalArgumentException("This bot is open for reading, so its files are not changed.");
        }
    }

    @Override
    public String open(String symbol) {
        BotIndex index = index();
        SymbolNames.Located at = index.read(units -> SymbolNames.locate(units, symbol));
        return onFx(() -> {
            openFile(at.file());
            ctx.eventBus().publish(new CoreApplicationEvents.RevealRequestedEvent(at.file(), at.start()));
            return "Showing " + at.what() + " in " + at.file().getFileName() + ".";
        });
    }

    // ---- review --------------------------------------------------------------------------------------------

    @Override
    public String listReview() {
        List<ReviewService.Item> items = onFx(() -> ReviewService.scan(ctx.config(), ctx.state()));
        if (items.isEmpty()) return "Nothing to review.";
        List<String> lines = new ArrayList<>();
        for (ReviewService.Item item : items) {
            lines.add(reviewId(item) + "  " + item.where() + " line " + item.line() + ": "
                    + String.join(" / ", item.entries()));
        }
        return String.join("\n", lines);
    }

    @Override
    public String markReviewed(String id) {
        return onFx(() -> {
            ReviewService.Item item = reviewItem(id);
            if (!ReviewService.markReviewed(ctx.config(), ctx.state(), item)) {
                throw new IllegalArgumentException(item.where() + " changed since; list_review again.");
            }
            return "Marked " + item.where() + " reviewed.";
        });
    }

    @Override
    public String removeMark(String id) {
        return onFx(() -> {
            ReviewService.Item item = reviewItem(id);
            if (!ReviewService.removeMark(ctx.config(), ctx.state(), item)) {
                throw new IllegalArgumentException(item.where() + " changed since; list_review again.");
            }
            return "Took the mark off " + item.where() + ".";
        });
    }

    @Override
    public String undoChange(String id) {
        record Asked(ReviewService.Item item, List<String> parameters) {}
        Asked asked = onFx(() -> {
            ReviewService.Item item = reviewItem(id);
            return new Asked(item, ReviewService.parametersOf(ctx.config(), ctx.state(), item));
        });
        // The versions are read here, off the FX thread; the function is written back on it.
        ReviewService.Undo found = ReviewService.findEarlier(ctx.config(), asked.item(), asked.parameters());
        if (!(found instanceof ReviewService.Undo.Ready ready)) {
            throw new IllegalArgumentException(found instanceof ReviewService.Undo.Refused refused ? refused.reason()
                    : "Nothing to undo.");
        }
        ReviewService.Undo done = onFx(() -> ReviewService.restore(ctx.config(), ctx.state(), asked.item(), ready.earlier()));
        return switch (done) {
            case ReviewService.Undo.Done d -> "Put " + asked.item().where() + " back as \"" + d.version() + "\" had it.";
            case ReviewService.Undo.Refused r -> throw new IllegalArgumentException(r.reason());
            case ReviewService.Undo.Ready r -> throw new IllegalStateException("not written");
        };
    }

    /** {@code com/bot/Miner.java#mine@12}: a review item's file, function and line, stable until it is edited. */
    private String reviewId(ReviewService.Item item) {
        return LiveBot.relative(ctx.config().sourceRoot(), item.file()) + "#" + item.function() + "@" + item.line();
    }

    /** The review item {@code id} names in a fresh scan. FX thread. */
    private ReviewService.Item reviewItem(String id) {
        String wanted = id == null ? "" : id.strip();
        return ReviewService.scan(ctx.config(), ctx.state()).stream().filter(i -> reviewId(i).equals(wanted))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No review item " + wanted
                        + ". list_review gives the ids; one moves when its file is edited."));
    }

    /** The bot's index: its sources read on FX, parsed on the caller's thread. */
    private BotIndex index() {
        return onFx(() -> BotIndex.prepare(ctx.config(), ctx.state())).get();
    }

    /** Shows {@code file} in the editor, saying so when it was not the one shown. FX thread. */
    private void openFile(Path file) {
        ProjectFile active = ctx.state().getActiveFile();
        if (active == null || !active.getPath().equals(file)) {
            ctx.codeEditorService().switchToFile(file);
            say("The assistant opened " + file.getFileName() + ".");
        }
    }

    // ---- versions ------------------------------------------------------------------------------------------

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    @Override
    public String listVersions(int limit) {
        List<ProjectVcs.CommitInfo> history = history();
        if (history.isEmpty()) return "The bot has no versions yet. checkpoint saves one.";
        int shown = Math.clamp(limit, 1, 200);
        List<String> lines = new ArrayList<>();
        for (ProjectVcs.CommitInfo c : history.subList(0, Math.min(shown, history.size()))) {
            lines.add(c.shortSha() + "  " + WHEN.format(c.when()) + "  " + c.origin().displayName() + "  "
                    + c.title() + (c.milestone() ? "  ★" : "")
                    + (c.tags().isEmpty() ? "" : "  [" + String.join(", ", c.tags()) + "]"));
        }
        if (history.size() > shown) lines.add("… and " + (history.size() - shown) + " older.");
        return String.join("\n", lines);
    }

    @Override
    public String checkpoint(String label) {
        String name = label == null ? "" : label.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("A version needs a label: what it is.");
        Path dir = versionedProject();
        ProjectState.Snapshot editor = onFx(() -> ctx.state().snapshot());
        try {
            String sha = Checkpoints.save(dir, editor, VersionOrigin.AI, name);
            if (sha == null) return "Nothing changed since the last version, so none was saved.";
            // Named, so it is a milestone in the Versions tab like a version the user saved.
            new ProjectVcs(dir).name(sha, name);
            return "Saved the version " + sha + " “" + name + "”. revert " + sha + " puts the bot back to it.";
        } catch (IOException e) {
            throw new IllegalArgumentException("Studio could not save a version: " + e.getMessage());
        }
    }

    @Override
    public String revert(String version) {
        String wanted = version == null ? "" : version.strip();
        Path dir = versionedProject();
        ProjectVcs vcs = new ProjectVcs(dir);
        if (vcs.merging()) {
            throw new IllegalArgumentException("An update is in progress; the user finishes or cancels it in the "
                    + "Versions tab first.");
        }
        List<ProjectVcs.CommitInfo> matching = wanted.length() < 4 ? List.of()
                : history().stream().filter(c -> c.sha().startsWith(wanted)).toList();
        if (matching.size() != 1) {
            throw new IllegalArgumentException((matching.isEmpty() ? "No version " : "More than one version starts ")
                    + wanted + ". list_versions gives the ids.");
        }
        ProjectVcs.CommitInfo target = matching.getFirst();
        // From here to the reload the editor holds the old code: a run or a save would write it back.
        reloading = true;
        try {
            ProjectState.Snapshot editor = onFx(() -> ctx.state().snapshot());
            // The editor's edits go to disk first, so the version restoreTo saves of "now" holds them.
            Checkpoints.flush(editor);
            vcs.restoreTo(target.sha());
        } catch (IOException | RuntimeException e) {
            reloading = false;
            throw new IllegalArgumentException("Studio could not put the bot back: " + e.getMessage());
        }
        // The reload tears this window down, endpoint and all: it waits for the answer to reach the client.
        CompletableFuture.delayedExecutor(RELOAD_DELAY_MS, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() ->
                ctx.eventBus().publish(new CoreApplicationEvents.ProjectReloadRequestedEvent())));
        return "Put the bot back as “" + target.title() + "” (" + WHEN.format(target.when()) + ") had it; what it "
                + "was is saved as a version first. Studio reloads the project now.";
    }

    private static final long RELOAD_DELAY_MS = 1500;

    /** Set by {@link #revert} until the reload replaces this window, and this bridge with it. */
    private volatile boolean reloading;

    @Override
    public boolean reloading() {
        return reloading;
    }

    private List<ProjectVcs.CommitInfo> history() {
        try {
            return new ProjectVcs(ctx.config().projectPath()).history();
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /**
     * The project's folder, when its versions are the assistant's to write. Not in a submodule: its history is
     * also another repository's, where Studio writes only the versions the user starts ({@link Checkpoints}).
     */
    private Path versionedProject() {
        Path dir = ctx.config().projectPath();
        if (new ProjectVcs(dir).sharedRepository()) {
            throw new IllegalArgumentException("This bot's history is another repository's too, so the assistant "
                    + "saves and restores no version of it; the user does that in the Versions tab.");
        }
        return dir;
    }

    // ---- plugins -------------------------------------------------------------------------------------------

    @Override
    public String listPlugins() {
        List<InstalledPlugin> declared = installed(registryEntries());
        List<String> lines = new ArrayList<>();
        PluginMove last = move.get();
        if (last != null) lines.add(last.line());
        if (declared.isEmpty()) lines.add("The bot's pom declares no plugin.");
        Path dir = ctx.config().projectPath();
        for (InstalledPlugin plugin : declared) {
            // A template pins its SDK through a property: the version is the property's value, not ${…}.
            String version = MavenService.readDependencyVersion(dir, plugin.artifact().groupId(),
                    plugin.artifact().artifactId()).orElse(plugin.installed());
            lines.add(plugin.displayName() + "  " + plugin.coordinate() + "  " + version
                    + (plugin.source() == InstalledPlugin.Source.UNLISTED ? "  (not in the registry)"
                    : !plugin.available().isBlank() && !plugin.available().equals(version)
                    ? "  (the registry checked " + plugin.available() + ")" : ""));
        }
        List<StudioPlugin> bound = PluginHost.plugins();
        lines.add(bound.isEmpty() ? "No plugin is loaded." : "Loaded: "
                + String.join(", ", bound.stream().map(StudioPlugin::displayName).toList()) + ".");
        String failed = PluginsWindow.failureText(PluginHost.failures());
        if (!failed.isEmpty()) lines.add(failed);
        return String.join("\n", lines);
    }

    @Override
    public String searchPlugins(String query) {
        List<PluginRegistry.Plugin> entries = registryEntries();
        if (entries.isEmpty()) return "The plugin registry could not be read, or lists nothing.";
        List<UserLibrary> declared = ctx.libraryService().declaredLibraries();
        List<String> lines = new ArrayList<>();
        for (PluginRegistry.Plugin plugin : entries) {
            if (!plugin.matches(query) || !plugin.isInstallable()) continue;
            lines.add(plugin.id() + "  " + plugin.name()
                    + (plugin.version().isBlank() ? "" : " " + plugin.version())
                    + (plugin.isInstalledIn(declared) ? "  (in the bot)" : "")
                    + (plugin.description().isBlank() ? "" : " — " + plugin.description().strip()));
        }
        return lines.isEmpty() ? "No plugin matches " + query + "." : String.join("\n", lines);
    }

    @Override
    public String addPlugin(String id) {
        List<PluginRegistry.Plugin> entries = registryEntries();
        if (entries.isEmpty()) throw new IllegalArgumentException("The plugin registry could not be read.");
        PluginRegistry.Plugin plugin = registryPlugin(entries, id);
        String name = plugin.name().isBlank() ? plugin.id() : plugin.name();
        List<UserLibrary> declared = ctx.libraryService().declaredLibraries();
        if (plugin.isInstalledIn(declared)) throw new IllegalArgumentException(name + " is in the bot already.");
        String provided = BrowsePluginsTab.alreadyProvided(plugin, declared,
                PluginHost.plugins().stream().map(StudioPlugin::id).toList());
        if (!provided.isEmpty()) throw new IllegalArgumentException(provided);
        String version = waitFor(new PluginCatalog(registry, jitpack).version(plugin), 30, "find " + name + "'s version");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("No version of " + plugin.coordinate() + " could be found.");
        }
        if (!userAgrees("Add a plugin?", "The assistant wants to add " + name + " " + version + " ("
                + plugin.coordinate() + ") to this bot.\n\nA plugin runs with Studio's own permissions. Registry "
                + "plugins are checked for loading, not reviewed for safety.")) {
            throw new IllegalArgumentException("The user did not agree to add " + name + ".");
        }
        List<MavenService.Shadowed> dropped = waitFor(ctx.libraryService().installPlugin(
                new UserLibrary(plugin.groupId(), plugin.artifactId(), version), plugin.editorLibraries()),
                PLUGIN_WRITE_SECONDS, "add " + name);
        String removed = MavenService.Shadowed.sentence(dropped);
        say("The assistant added " + name + " " + version + ".");
        return "Added " + name + " " + version + "." + (removed.isEmpty() ? "" : " " + removed)
                + " list_palette shows what it offers.";
    }

    @Override
    public String removePlugin(String id, boolean deleteFiles, boolean confirm) {
        List<InstalledPlugin> declared = installed(registryEntries());
        InstalledPlugin plugin = declaredPlugin(declared, id);
        PluginUpgradeService upgrades = new PluginUpgradeService(ctx.config(), ctx.state(), ctx.libraryService(),
                jitpack, plugin.artifact(), InstalledPlugin.ambiguousAmong(ctx.config().projectPath(), declared));
        PluginUpgradeService.Report report = upgrades.removal();
        PluginHolders holders = upgrades.holders();
        String preview = removalPreview(plugin, report, holders, deleteFiles);
        if (!confirm) return preview + "\nremove_plugin with confirm removes it.";
        if (!userAgrees("Remove a plugin?", preview)) {
            throw new IllegalArgumentException("The user did not agree to remove " + plugin.displayName() + ".");
        }
        PluginHolders deleted = deleteFiles ? holders : PluginHolders.NONE;
        // No picks: a call with nothing that replaces it takes its default, a value and a review mark.
        PluginUpgradeService.Repaired repaired = waitFor(upgrades.remove(plugin.editorDependencies(),
                !report.breaks().isEmpty(), Map.of(), deleted), PLUGIN_WRITE_SECONDS, "remove " + plugin.displayName());
        onFx(() -> {
            deleted.forget(ctx.config(), ctx.state());
            return null;
        });
        say("The assistant removed " + plugin.displayName() + ".");
        return InstalledPluginsTab.removalSummary(plugin.displayName(), repaired.files(), report.breaks().size(),
                repaired.leftAsWritten(), repaired.deleted());
    }

    /**
     * The Installed tab's version menu and its button, as one call (2026-10-13): the same pass, every fix the
     * plugin suggests taken as the tab's fix sheet would preselect it, and the user asked on screen first, as
     * for adding a plugin — another version is other code running with Studio's permissions. A confirmed call
     * answers once the question is on screen ({@link PluginMove}): the answer and the pass outlast a client's call.
     */
    @Override
    public String setPluginVersion(String id, String version, boolean confirm, Runnable moved) {
        List<InstalledPlugin> declared = installed(registryEntries());
        InstalledPlugin plugin = declaredPlugin(declared, id);
        String name = plugin.displayName();
        // As the tab, whose version menu is off for a twin: moving one copy repairs the bot as if the other were gone.
        if (InstalledPlugin.sameArtifactGroups(declared).stream().anyMatch(g -> g.size() > 1 && g.contains(plugin))) {
            throw new IllegalArgumentException("The pom declares " + name + " twice, under both groupIds. The user "
                    + "keeps one copy in Plugins & Libraries first.");
        }
        PluginUpgradeService upgrades = new PluginUpgradeService(ctx.config(), ctx.state(), ctx.libraryService(),
                jitpack, plugin.artifact(), InstalledPlugin.ambiguousAmong(ctx.config().projectPath(), declared));
        String from = upgrades.currentVersion();
        String to = version == null ? "" : version.strip();
        String local = ctx.libraryService().devMode() ? LocalBuilds.scan().stream()
                .filter(b -> b.coordinate().equals(upgrades.coordinate())).map(LocalBuilds.Build::version)
                .findFirst().orElse("") : "";
        List<String> offered = new ArrayList<>();
        if (!local.isEmpty()) offered.add(local);
        List<String> released = new ArrayList<>(waitFor(upgrades.availableVersions(), 30, "list " + name + "'s versions"));
        released.add(plugin.available());
        // Only the local build is a dev version here: Studio refuses any other -SNAPSHOT pin at bind.
        for (String v : released) {
            if (!v.isBlank() && !ReleasedPlugins.isDevVersion(v) && !offered.contains(v)) offered.add(v);
        }
        if (!offered.contains(to)) {
            throw new IllegalArgumentException((to.isEmpty() ? "Name the version to move " + name + " to."
                    : name + " has no version " + to + " here.")
                    + (offered.isEmpty() ? " No version of it could be read." : " Its versions: "
                    + String.join(", ", offered.stream().limit(15).map(v -> v.equals(local) ? v + " (local build)"
                    : v).toList()) + ".")
                    + (ReleasedPlugins.isDevVersion(to) && local.isEmpty()
                    ? " A -SNAPSHOT build loads only in dev mode: set_setting dev_mode on." : ""));
        }
        if (to.equals(from)) return name + " is on " + to + " already.";
        PluginUpgradeService.Report report = waitFor(CompletableFuture.supplyAsync(() -> upgrades.compare(to)),
                PLUGIN_WRITE_SECONDS, "read what moving " + name + " changes");
        String preview = movePreview(plugin, from, to, to.equals(local), report);
        if (!confirm) return preview + "\nset_plugin_version with confirm moves it.";
        PluginMove asked = PluginMove.asked(name, from, to);
        PluginMove before = move.get();
        if (before != null && before.busy() || !move.compareAndSet(before, asked)) {
            throw new IllegalArgumentException("Another plugin move is under way; list_plugins says where it is.");
        }
        String question = "The assistant wants to move " + name + ".\n\n" + preview + "\n\nThe version moved to runs "
                + "with Studio's own permissions.";
        // Its own thread: the question waits up to ASK_MINUTES and the pass up to PLUGIN_WRITE_SECONDS.
        try {
            Thread.ofVirtual().name("plugin-move").start(() -> carryMove(asked, upgrades, question, moved));
        } catch (RuntimeException | Error e) {
            move.set(asked.declined("Studio could not start it."));
            throw e;
        }
        return asked.started();
    }

    /** Asks {@code question} and, once the user agrees, moves the plugin; {@link #move} follows it to its end. */
    private void carryMove(PluginMove asked, PluginUpgradeService upgrades, String question, Runnable moved) {
        // The end is published last: until the plugins' tools are served anew, every tool still waits.
        PluginMove ended = asked.failed("it stopped unexpectedly.");
        boolean wrote = false;
        try {
            if (!userAgrees("Move a plugin?", question)) {
                ended = asked.declined("the user did not agree.");
                return;
            }
            move.set(asked.moving());
            wrote = true;
            // No picks: every place with a choice takes the plugin's own suggestion, and is marked for review.
            ProjectUpgrade.Result result = waitFor(ProjectUpgrade.run(List.of(new ProjectUpgrade.Row(upgrades,
                    asked.to())), ctx.libraryService()::updateVersions), PLUGIN_WRITE_SECONDS, "move " + asked.plugin());
            onFx(() -> {
                // A holder the repair of the values deleted: its buffer goes, and the editor moves off it.
                ProjectFile active = ctx.state().getActiveFile();
                result.deleted().forEach(ctx.state()::removeFile);
                if (active != null && result.deleted().contains(active.getPath())) {
                    ctx.state().setActiveFile(ctx.config().entrySourceFile());
                }
                return null;
            });
            say("The assistant moved " + asked.plugin() + " from " + asked.from() + " to " + asked.to() + ".");
            ended = asked.moved(result.summary());
        } catch (RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // userAgrees' unanswered question lands here too, before anything was written.
            ended = wrote ? asked.failed(why) : asked.declined(why);
        } finally {
            // An Error passes the catch and ends it as stopped unexpectedly: every tool would wait for it otherwise.
            try {
                // A failed pass may have written the pom before it stopped: the tools are served anew either way.
                if (wrote) moved.run();
            } finally {
                move.set(ended);
            }
        }
    }

    /** The assistant's last plugin move, in flight or ended; null before the first. */
    private final AtomicReference<PluginMove> move = new AtomicReference<>();

    /** The Review tab's retired rows and the migration window's methods, as {@link MigrationSummary} says them. */
    @Override
    public String listMigrations() {
        Supplier<BotIndex> prepared = onFx(() -> BotIndex.prepare(ctx.config(), ctx.state()));
        BotIndex index = prepared.get();
        ValueGrammar grammar = PluginHost.grammar();
        try {
            List<ManagedMigrations.Method> methods = ManagedMigrations.methods(index, grammar);
            return MigrationSummary.listing(MigrationSteps.pending(index, grammar, PluginHost.managedValues()),
                    methods, methods.isEmpty() ? List.of() : MigrationCleanup.unneeded(index, grammar));
        } catch (RuntimeException | LinkageError e) {
            // As the Review tab: a plugin type that cannot load leaves the migrations unread, not Studio broken.
            throw new IllegalArgumentException("The bot's migrations could not be read: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    @Override
    public String pluginMoving() {
        PluginMove now = move.get();
        return now != null && now.busy() ? now.line() : "";
    }

    /** What moving {@code plugin} from {@code from} to {@code to} would change, as the question says it. */
    private static String movePreview(InstalledPlugin plugin, String from, String to, boolean local,
                                      PluginUpgradeService.Report report) {
        List<String> lines = new ArrayList<>();
        lines.add("Moving " + plugin.displayName() + " (" + plugin.coordinate() + ") from " + from + " to " + to
                + (local ? ", this computer's own build, which no release checked" : "") + ": "
                + InstalledPluginsTab.chipText(report) + ".");
        if (!report.breaks().isEmpty()) {
            lines.add(report.breaks().size() + " call(s) are repaired, and the functions they are in are marked for "
                    + "review: " + String.join(", ", report.breaks().stream()
                    .map(PluginUpgradeService.Break::display).distinct().limit(20).toList()) + ".");
        }
        int choices = FixList.of(report).size();
        if (choices > 0) {
            lines.add(choices + " place(s) have more than one fix; each takes the one the plugin suggests.");
        }
        lines.addAll(report.values());
        for (MigrationSteps.Stranded set : report.stranded()) {
            lines.add(MigrationSummary.stranded(set, plugin.displayName() + " " + to));
        }
        if (!report.leftForYou().isEmpty()) {
            lines.add("Left as written and marked, for the user to change: " + String.join(", ", report.leftForYou()
                    .stream().map(PluginUpgradeService.Break::type).distinct().toList()) + ".");
        }
        if (report.isIncomplete()) lines.add("Not everything could be read: " + report.problems().getFirst());
        lines.add("A version of the bot is saved first.");
        return String.join("\n", lines);
    }

    /** The plugin the pom declares that {@code id} names — by its coordinate, artifactId or name. */
    private static InstalledPlugin declaredPlugin(List<InstalledPlugin> declared, String id) {
        String wanted = id == null ? "" : id.strip();
        return declared.stream()
                .filter(p -> p.coordinate().equalsIgnoreCase(wanted) || p.artifact().artifactId().equalsIgnoreCase(wanted)
                        || p.displayName().equalsIgnoreCase(wanted))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("The bot declares no plugin " + wanted
                        + ". list_plugins lists them."));
    }

    private static final long PLUGIN_WRITE_SECONDS = 600;

    /** What removing {@code plugin} would change, as the Installed tab's question says it. */
    private static String removalPreview(InstalledPlugin plugin, PluginUpgradeService.Report report,
                                         PluginHolders holders, boolean deleteFiles) {
        List<String> lines = new ArrayList<>();
        lines.add("Removing " + plugin.displayName() + " (" + plugin.coordinate() + "): "
                + InstalledPluginsTab.chipText(report) + ".");
        lines.add(report.breaks().isEmpty() ? "The bot calls nothing in it, so only the pom changes."
                : report.breaks().size() + " use(s) become a default value or are deleted, and the functions they "
                + "are in are marked for review: " + String.join(", ", report.breaks().stream()
                .map(PluginUpgradeService.Break::display).distinct().limit(20).toList()) + ".");
        if (!report.leftForYou().isEmpty()) {
            lines.add("Left as written and marked, for the user to change: " + String.join(", ", report.leftForYou()
                    .stream().map(PluginUpgradeService.Break::type).distinct().toList()) + ".");
        }
        if (report.isIncomplete()) lines.add("Not everything could be read: " + report.problems().getFirst());
        if (!holders.isEmpty()) {
            lines.add((deleteFiles ? "Its files are deleted: " : "Its files stay (deleteFiles deletes them): ")
                    + holders.fileNames() + ".");
            if (!holders.references().isEmpty()) lines.add(InstalledPluginsTab.holderReferences(holders.references()));
        }
        lines.add("A version of the bot is saved first.");
        return String.join("\n", lines);
    }

    /** The installable registry plugin {@code id} names — by its id, name or artifactId. */
    private static PluginRegistry.Plugin registryPlugin(List<PluginRegistry.Plugin> entries, String id) {
        String wanted = id == null ? "" : id.strip();
        return entries.stream().filter(PluginRegistry.Plugin::isInstallable)
                .filter(p -> p.id().equalsIgnoreCase(wanted) || p.name().equalsIgnoreCase(wanted)
                        || p.artifactId().equalsIgnoreCase(wanted))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("The registry has no plugin " + wanted
                        + ". search_plugins lists them."));
    }

    // ---- new projects --------------------------------------------------------------------------------------

    @Override
    public String listTemplates() {
        List<GalleryEntry> templates = vettedTemplatesOrNone();
        if (templates.isEmpty()) {
            return "The gallery cannot be read, or lists no vetted template. create_project without a template "
                    + "makes a blank project.";
        }
        return String.join("\n", templates.stream().map(t -> t.name() + "  (" + t.slug() + ")"
                + (t.description().isBlank() ? "" : " — " + t.description().strip())).toList());
    }

    @Override
    public String createProject(String name, String template, List<String> plugins) {
        String wanted = name == null ? "" : name.strip();
        String problem = new ProjectCreator().nameProblem(wanted);
        if (problem != null) throw new IllegalArgumentException(problem);
        String from = template == null ? "" : template.strip();
        // Vetted only, as New Project offers by default: a community template is code nobody reviewed, and the
        // user would not see which one was picked. Blank only when the gallery cannot answer, as in the dialog:
        // botmaker-base is what Blank became.
        List<GalleryEntry> vetted = vettedTemplatesOrNone();
        if (from.isEmpty() && !vetted.isEmpty()) {
            throw new IllegalArgumentException("Name a template: " + String.join(", ", vetted.stream()
                    .map(GalleryEntry::name).toList()) + ". list_templates says what each is.");
        }
        GalleryEntry entry = from.isEmpty() ? null : vetted.stream()
                .filter(t -> t.name().equalsIgnoreCase(from) || t.slug().equalsIgnoreCase(from)
                        || t.repo().equalsIgnoreCase(from))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(vetted.isEmpty()
                        ? "The gallery cannot be read, so only a blank project can be made: leave template empty."
                        : "No vetted template " + from + ". list_templates lists them."));
        List<PluginRegistry.Plugin> picks = List.of();
        if (!plugins.isEmpty()) {
            List<PluginRegistry.Plugin> entries = registryEntries();
            if (entries.isEmpty()) throw new IllegalArgumentException("The plugin registry could not be read.");
            picks = plugins.stream().map(id -> registryPlugin(entries, id)).toList();
            String names = String.join(", ", picks.stream().map(p -> p.name().isBlank() ? p.id() : p.name()).toList());
            if (!userAgrees("Create a project with plugins?", "The assistant wants to create " + wanted + " with "
                    + names + ".\n\nA plugin runs with Studio's own permissions. Registry plugins are checked for "
                    + "loading, not reviewed for safety.")) {
                throw new IllegalArgumentException("The user did not agree to add " + names + ".");
            }
        }
        // From here to the switch nothing may start: a run or an edit begun while the template downloads would be
        // torn down by the switch.
        reloading = true;
        try {
            NewProjects.create(wanted, entry, picks, github, new GitHubAuth());
        } catch (IOException | RuntimeException e) {
            reloading = false;
            Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                    ? e.getCause() : e;
            throw new IllegalArgumentException("Studio could not create " + wanted + ": " + cause.getMessage());
        }
        Path dir = Constants.PROJECTS_ROOT.resolve(wanted);
        try {
            Checkpoints.flush(onFx(() -> ctx.state().snapshot()));
        } catch (IOException | RuntimeException e) {
            reloading = false;
            throw new IllegalArgumentException("Created " + wanted + ", but Studio could not save this project to "
                    + "switch to it: " + e.getMessage());
        }
        CompletableFuture.delayedExecutor(RELOAD_DELAY_MS, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() ->
                ctx.eventBus().publish(new CoreApplicationEvents.OpenProjectRequestedEvent(dir))));
        say("The assistant created " + wanted + ".");
        return "Created " + wanted + (entry == null ? " (blank)" : " from " + entry.name()) + " in " + dir
                + ". Studio opens it in place of this project now, which ends this connection: the endpoint comes "
                + "back on the same address once it is open.";
    }

    /** The gallery's vetted templates, or none when it cannot be read — the dialog's offline case. */
    private List<GalleryEntry> vettedTemplatesOrNone() {
        try {
            return GalleryEntry.templates(waitFor(new GitHubGallery(github, new GitHubAuth()).browse(), 30,
                    "read the gallery"), false);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private List<PluginRegistry.Plugin> registryEntries() {
        try {
            return registry.browse().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | TimeoutException e) {
            return List.of();
        }
    }

    private List<InstalledPlugin> installed(List<PluginRegistry.Plugin> entries) {
        return InstalledPlugin.of(ctx.libraryService().declaredLibraries(), entries,
                InstalledPlugin.jarDeclaresPlugin(ctx.config().projectPath()));
    }

    /**
     * Asks the user {@code question} on screen and waits for the answer; a question left unanswered for
     * {@link #ASK_MINUTES} is closed and taken as a no, so no dialog lingers that would act after the call ended.
     */
    private boolean userAgrees(String header, String question) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        AtomicReference<Alert> shown = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Alert ask = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, question, ButtonType.OK, ButtonType.CANCEL);
                ask.initOwner(owner.get());
                ask.setHeaderText(header);
                // The question pops up while the user may be typing to the assistant: an Enter meant for the
                // terminal must not answer yes. Only a click on OK, or Space on it once focused, does.
                ((javafx.scene.control.Button) ask.getDialogPane().lookupButton(ButtonType.OK)).setDefaultButton(false);
                ((javafx.scene.control.Button) ask.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
                shown.set(ask);
                answer.complete(ask.showAndWait().filter(b -> b == ButtonType.OK).isPresent());
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);
            }
        });
        try {
            return answer.get(ASK_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            Platform.runLater(() -> {
                if (shown.get() != null) shown.get().close();
            });
            throw new IllegalArgumentException("The user did not answer in " + ASK_MINUTES + " minutes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the user", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        }
    }

    private static final long ASK_MINUTES = 5;

    /** {@code future}'s result; a failure is refused with its own message. */
    private static <T> T waitFor(CompletableFuture<T> future, long seconds, String doing) {
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting to " + doing, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause.getCause() != null) cause = cause.getCause();
            throw new IllegalArgumentException("Studio could not " + doing + ": " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new IllegalArgumentException("Studio did not " + doing + " in " + seconds + "s.");
        }
    }

    // ---- parameters and settings ---------------------------------------------------------------------------

    @Override
    public String listParams() {
        record Read(Supplier<BotIndex> index, ValueGrammar grammar) {}
        Read read = onFx(() -> new Read(BotIndex.prepare(ctx.config(), ctx.state()), PluginHost.grammar()));
        List<JavaParameter> params = JavaParameters.over(read.index().get(), read.grammar());
        if (params.isEmpty()) return "The bot has no parameters (@Param fields).";
        List<String> lines = new ArrayList<>();
        for (JavaParameter p : params) {
            ParameterRow row = p.row();
            lines.add(p.qualified() + "  " + row.typeName() + " = " + (p.editable() ? row.value() : p.initializer())
                    + "  [" + row.categoryOrGeneral() + "]"
                    + (row.options().isEmpty() ? "" : "  one of " + row.options())
                    + (range(row).isEmpty() ? "" : "  " + range(row))
                    + (row.description().isBlank() ? "" : " — " + row.description().strip())
                    + (p.editable() ? "" : "  (not set here: " + p.note() + ")"));
        }
        return String.join("\n", lines);
    }

    /** {@code row}'s declared bounds as a phrase, {@code ""} when it declares none. */
    private static String range(ParameterRow row) {
        boolean low = !Double.isInfinite(row.min());
        boolean high = !Double.isInfinite(row.max());
        if (low && high) return "from " + bound(row.min()) + " to " + bound(row.max());
        if (low) return "at least " + bound(row.min());
        return high ? "at most " + bound(row.max()) : "";
    }

    private static String bound(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @Override
    public String setParamDefault(String param, String value) {
        String wanted = param == null ? "" : param.strip();
        String text = value == null ? "" : value.strip();
        return onFx(() -> {
            ValueGrammar grammar = PluginHost.grammar();
            List<JavaParameter> all = JavaParameters.scan(ctx.config(), ctx.state(), grammar);
            List<JavaParameter> named = all.stream().filter(p -> p.qualified().equals(wanted)).toList();
            if (named.isEmpty()) named = all.stream().filter(p -> p.name().equals(wanted)).toList();
            if (named.size() != 1) {
                throw new IllegalArgumentException((named.isEmpty() ? "The bot has no parameter " : "More than one "
                        + "class has a parameter ") + wanted + ". list_params names them"
                        + (named.isEmpty() ? "." : ": give Class.name."));
            }
            JavaParameter p = named.getFirst();
            if (!p.editable()) throw new IllegalArgumentException(p.qualified() + " cannot be set here: " + p.note());
            Optional<Object> read = grammar.valueOf(p.form(), text);
            if (read.isEmpty()) {
                throw new IllegalArgumentException("`" + text + "` is not a " + p.row().typeName() + " value.");
            }
            ParameterRow row = p.row();
            if (!row.options().isEmpty() && row.options().stream()
                    .noneMatch(option -> grammar.valueOf(p.form(), option).equals(read))) {
                throw new IllegalArgumentException(p.qualified() + " takes one of " + row.options() + ".");
            }
            if (read.get() instanceof Number n && (n.doubleValue() < row.min() || n.doubleValue() > row.max())) {
                throw new IllegalArgumentException(p.qualified() + " is " + range(row) + ".");
            }
            JavaValue written = grammar.initializer(p.form(), read.get()).orElseThrow(() ->
                    new IllegalArgumentException("That value cannot be written as " + row.typeName() + "."));
            ParameterRow stored = JavaParameters.setValue(ctx.config(), ctx.state(), p, written, grammar)
                    .orElseThrow(() -> new IllegalArgumentException(p.qualified() + " changed meanwhile. Try again."));
            return "Set " + p.qualified() + " to " + stored.value() + ".";
        });
    }

    @Override
    public String settings() {
        StudioProjectSettings now = onFx(() -> ctx.projectSettingsService().current());
        List<String> lines = new ArrayList<>();
        for (AssistedSetting setting : AssistedSetting.offered()) {
            lines.add(setting.id() + " = " + valueOf(setting, now) + "  — " + setting.displayName() + ": "
                    + setting.takes());
        }
        return String.join("\n", lines);
    }

    @Override
    public String setSetting(String key, String value) {
        AssistedSetting setting = AssistedSetting.fromId(key == null ? "" : key.strip());
        String text = value == null ? "" : value.strip();
        ProjectSettingsService service = ctx.projectSettingsService();
        if (setting == AssistedSetting.DEV_MODE) {
            boolean on = switch (text.toLowerCase(java.util.Locale.ROOT)) {
                case "on", "true" -> true;
                case "off", "false" -> false;
                default -> throw new IllegalArgumentException("dev_mode is on or off.");
            };
            // On loads jars no release vetted, with Studio's permissions: the user agrees on screen, as to an add.
            if (on && !ctx.libraryService().devMode() && !userAgrees("Turn on dev mode?", "The assistant wants to "
                    + "turn on dev mode for this project.\n\nIts plugins then load from local -SNAPSHOT builds in "
                    + "~/.m2, which no release checked, and run with Studio's own permissions.")) {
                throw new IllegalArgumentException("The user did not agree to turn on dev mode.");
            }
            // The Plugins window's own toggle: writes the setting, then reloads the plugins over it.
            waitFor(ctx.libraryService().setDevMode(on), 120, "reload the plugins");
            return "Set dev_mode to " + (on ? "on" : "off") + "; the plugins reloaded.";
        }
        StudioProjectSettings now = onFx(service::current);
        StudioProjectSettings next = switch (setting) {
            case DEBUG_OUTPUT -> {
                DebugOutput output = java.util.Arrays.stream(DebugOutput.values())
                        .filter(o -> o.name().equalsIgnoreCase(text)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("debug_output is bot, on or off."));
                yield now.withRunProperty(Runs.DEBUG.name(), output.propertyValue());
            }
            case HIDDEN_TRACE -> now.withHiddenTraceWriters(text.isEmpty() ? List.of()
                    : java.util.Arrays.stream(text.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
            case DEV_MODE -> throw new IllegalStateException("dev_mode is set above");
            case UNKNOWN -> throw new IllegalArgumentException("No setting " + key + ". get_settings lists them.");
        };
        waitFor(service.update(next), 30, "save the settings");
        return "Set " + setting.id() + " to " + valueOf(setting, next) + ".";
    }

    private static String valueOf(AssistedSetting setting, StudioProjectSettings settings) {
        return switch (setting) {
            case DEBUG_OUTPUT -> DebugOutput.fromProperty(settings.runProperties().get(Runs.DEBUG.name()))
                    .name().toLowerCase(java.util.Locale.ROOT);
            case HIDDEN_TRACE -> String.join(", ", settings.hiddenTraceWriters());
            case DEV_MODE -> settings.devMode() ? "on" : "off";
            case UNKNOWN -> "";
        };
    }

    // ---- the plugins' tools --------------------------------------------------------------------------------

    @Override
    public List<PluginTool> pluginTools() {
        List<PluginTool> out = new ArrayList<>();
        for (PluginHost.OwnedTool owned : PluginHost.assistantTools()) {
            String segment = ManagedHolders.segment(owned.pluginId());
            if (segment == null) {
                System.err.println("Warning: " + owned.pluginId() + "'s assistant tools have no name to be served under.");
                continue;
            }
            out.add(new PluginTool(segment + "_" + owned.tool().name(), owned.pluginName(), owned.tool()));
        }
        return out;
    }

    @Override
    public AgentContext agentContext() {
        record Asked(StudioServices services, WatchedScreen shown, List<Watched> said,
                     OverlayPart.FrameSource frames, Marks marks) {}
        Asked asked = onFx(() -> {
            Optional<WatchedScreen> shown = OverlayEditor.watchedScreen();
            return new Asked(HostServices.forProject(ctx.config(), owner), shown.orElse(null),
                    shown.isPresent() ? List.of() : OverlayEditor.watchedSaid(ctx.codeEditorService(), owner.get()),
                    shown.isPresent() ? null : OverlayEditor.framesSaid(ctx.codeEditorService(), owner.get()),
                    OverlayEditor.assistantMarks().orElse(Marks.NONE));
        });
        // What the overlay is beside, else the first open thing a plugin says the bot watches; resolved once,
        // here off the FX thread, since it reads the window list. The bot's own frames win over Studio's grab,
        // and stand alone for a screen with no window to dock beside (a monitor, the desktop).
        WatchedScreen screen = asked.shown() != null ? asked.shown() : asked.said().stream()
                .map(w -> WatchedScreen.resolve(w, null)).flatMap(Optional::stream).findFirst()
                .map(s -> s.withFrames(asked.frames())).orElse(null);
        Supplier<Optional<OverlayFrame>> alone = () -> {
            try {
                Optional<OverlayFrame> grabbed = asked.frames() == null ? null : asked.frames().grab();
                return grabbed == null ? Optional.empty() : grabbed;
            } catch (RuntimeException | LinkageError e) {
                return Optional.empty();
            }
        };
        return new AgentContext() {
            @Override
            public StudioServices services() {
                return asked.services();
            }

            /** The last frame {@code alone} gave, whose area {@link #watchedArea} answers without grabbing again. */
            private volatile OverlayFrame last;

            @Override
            public Optional<BufferedImage> frame() {
                if (screen != null) return screen.frame();
                last = alone.get().orElse(null);
                return Optional.ofNullable(last).map(OverlayFrame::image);
            }

            @Override
            public Optional<Area> watchedArea() {
                if (screen != null) return screen.area();
                if (last == null) last = alone.get().orElse(null);
                return Optional.ofNullable(last).map(OverlayFrame::area);
            }

            @Override
            public Marks marks() {
                return asked.marks();
            }
        };
    }

    // ---- statements ----------------------------------------------------------------------------------------

    /**
     * The statement {@code blockId} of {@code file} in the tree the editor shows, opening the file first when it
     * is not the one shown. FX thread.
     */
    private Statement statementOf(String file, String blockId) {
        Path path = path(file);
        openFile(path);
        CompilationUnit unit = ctx.state().getCompilationUnit().orElseThrow(() ->
                new IllegalArgumentException("Studio has not drawn " + path.getFileName() + " yet. Try again."));
        List<Statement> found = new ArrayList<>(1);
        unit.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (found.isEmpty() && node instanceof Statement s && BlockId.of(node).equals(blockId)) found.add(s);
            }
        });
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No statement has the id " + blockId + " in " + path.getFileName()
                    + ". Read the tree again.");
        }
        return found.getFirst();
    }

    /** The file {@code file} names, or the open one for a blank name. FX thread. */
    private Path path(String file) {
        if (file == null || file.isBlank()) {
            ProjectFile active = ctx.state().getActiveFile();
            if (active == null) throw new IllegalArgumentException("No file is open in Studio. Give `file`.");
            return active.getPath();
        }
        Map<Path, String> sources = new LinkedHashMap<>();
        BotSources.scan(ctx.config(), ctx.state(), sources::put);
        return LiveBot.resolve(sources.keySet(), ctx.config().sourceRoot(), file);
    }

    private void say(String message) {
        ctx.eventBus().publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }
}
