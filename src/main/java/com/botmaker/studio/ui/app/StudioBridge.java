package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.assist.AgentContext;
import com.botmaker.plugin.api.overlay.Marks;
import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.plugin.api.overlay.Watched;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.assist.LiveBot;
import com.botmaker.studio.assist.RunLog;
import com.botmaker.studio.assist.StudioDriver;
import com.botmaker.studio.assist.SymbolNames;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.project.ProjectWrites;
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
import com.botmaker.studio.services.overlay.OverlayTargets;
import com.botmaker.studio.services.overlay.WatchedScreen;
import com.botmaker.studio.ui.app.overlay.OverlayEditor;
import com.botmaker.studio.ui.app.trial.TrialMenu;
import com.botmaker.studio.ui.app.trial.Trials;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.Runs;
import com.botmaker.shared.github.GitHubClient;
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
    private final Trials trials;
    private final RunLog log;
    private final Supplier<Window> owner;
    private final JitPackSearch jitpack = new JitPackSearch();
    // The plugin index, read off the same raw CDN as Plugins & Libraries, with no account.
    private final PluginRegistry registry = new PluginRegistry(new GitHubClient());

    StudioBridge(StudioContext ctx, Trials trials, RunLog log, Supplier<Window> owner) {
        this.ctx = ctx;
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
    public String addFile(String name) {
        String className = name == null ? "" : name.strip();
        FunctionDraft.identifierProblem(className, "class").ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        Path file = ctx.config().mainPackageDir().resolve(className + ".java");
        if (java.nio.file.Files.exists(file)) {
            throw new IllegalArgumentException(className + ".java already exists. read_tree reads it.");
        }
        String source = """
                package %s;

                public final class %s {

                    private %s() {}
                }
                """.formatted(ctx.config().mainPackage(), className, className);
        if (!ProjectWrites.create(ctx.config(), file, source, "Create " + className)) {
            throw new IllegalArgumentException("Studio could not write " + className + ".java.");
        }
        return "Added " + LiveBot.relative(ctx.config().sourceRoot(), file) + ". add_method puts methods in it.";
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
        Refactor.Planned plan = switch (Refactor.rename(index, at.file(), at.start(), to)) {
            case Refactor.Refused refused -> throw new IllegalArgumentException(refused.reason()
                    + (refused.fixes().isEmpty() ? "" : " Names that would work: "
                    + refused.fixes().stream().map(Refactor.Fix::label).toList() + "."));
            case Refactor.Planned planned -> planned;
        };
        if (!plan.moves().isEmpty()) {
            throw new IllegalArgumentException("Renaming " + at.what() + " renames its file too; the user does that "
                    + "in Studio's file explorer.");
        }
        return onFx(() -> {
            // The plan was made from the sources as they were read; a file typed in since would be overwritten.
            Map<Path, String> now = new LinkedHashMap<>();
            BotSources.scan(ctx.config(), ctx.state(), now::put);
            for (Path file : plan.rewrites().keySet()) {
                if (!java.util.Objects.equals(now.get(file), index.sources().get(file))) {
                    throw new IllegalArgumentException(file.getFileName() + " changed meanwhile. Try again.");
                }
            }
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
        if (declared.isEmpty()) lines.add("The bot's pom declares no plugin.");
        for (InstalledPlugin plugin : declared) {
            lines.add(plugin.displayName() + "  " + plugin.coordinate() + "  " + plugin.installed()
                    + (plugin.source() == InstalledPlugin.Source.UNLISTED ? "  (not in the registry)"
                    : plugin.canChange() ? "  (the registry checked " + plugin.available() + ")" : ""));
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
                    + (plugin.verifiedVersion().isBlank() ? "" : " " + plugin.verifiedVersion())
                    + (plugin.isInstalledIn(declared) ? "  (in the bot)" : "")
                    + (plugin.description().isBlank() ? "" : " — " + plugin.description().strip()));
        }
        return lines.isEmpty() ? "No plugin matches " + query + "." : String.join("\n", lines);
    }

    @Override
    public String addPlugin(String id) {
        List<PluginRegistry.Plugin> entries = registryEntries();
        if (entries.isEmpty()) throw new IllegalArgumentException("The plugin registry could not be read.");
        String wanted = id == null ? "" : id.strip();
        PluginRegistry.Plugin plugin = entries.stream().filter(PluginRegistry.Plugin::isInstallable)
                .filter(p -> p.id().equalsIgnoreCase(wanted) || p.name().equalsIgnoreCase(wanted)
                        || p.artifactId().equalsIgnoreCase(wanted))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("The registry has no plugin " + wanted
                        + ". search_plugins lists them."));
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
        String wanted = id == null ? "" : id.strip();
        InstalledPlugin plugin = declared.stream()
                .filter(p -> p.coordinate().equalsIgnoreCase(wanted) || p.artifact().artifactId().equalsIgnoreCase(wanted)
                        || p.displayName().equalsIgnoreCase(wanted))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("The bot declares no plugin " + wanted
                        + ". list_plugins lists them."));
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
        StudioProjectSettings now = onFx(service::current);
        StudioProjectSettings next = switch (setting) {
            case DEBUG_OUTPUT -> {
                DebugOutput output = java.util.Arrays.stream(DebugOutput.values())
                        .filter(o -> o.name().equalsIgnoreCase(text)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("debug_output is bot, on or off."));
                yield now.withRunProperty(Runs.DEBUG_PROPERTY, output.propertyValue());
            }
            case HIDDEN_TRACE -> now.withHiddenTraceWriters(text.isEmpty() ? List.of()
                    : java.util.Arrays.stream(text.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
            case UNKNOWN -> throw new IllegalArgumentException("No setting " + key + ". get_settings lists them.");
        };
        waitFor(service.update(next), 30, "save the settings");
        return "Set " + setting.id() + " to " + valueOf(setting, next) + ".";
    }

    private static String valueOf(AssistedSetting setting, StudioProjectSettings settings) {
        return switch (setting) {
            case DEBUG_OUTPUT -> DebugOutput.fromProperty(settings.runProperties().get(Runs.DEBUG_PROPERTY))
                    .name().toLowerCase(java.util.Locale.ROOT);
            case HIDDEN_TRACE -> String.join(", ", settings.hiddenTraceWriters());
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
        record Asked(StudioServices services, WatchedScreen shown, List<Watched> said, Marks marks) {}
        Asked asked = onFx(() -> {
            Optional<WatchedScreen> shown = OverlayEditor.watchedScreen();
            return new Asked(HostServices.forProject(ctx.config(), owner), shown.orElse(null),
                    shown.isPresent() ? List.of() : OverlayEditor.watchedSaid(ctx.codeEditorService(), owner.get()),
                    OverlayEditor.assistantMarks().orElse(Marks.NONE));
        });
        // What the overlay is beside, else the first open thing a plugin says the bot watches; resolved once,
        // here off the FX thread, since it reads the window list.
        WatchedScreen screen = asked.shown() != null ? asked.shown() : asked.said().stream()
                .map(w -> WatchedScreen.resolve(w, null)).flatMap(Optional::stream).findFirst().orElse(null);
        return new AgentContext() {
            @Override
            public StudioServices services() {
                return asked.services();
            }

            @Override
            public Optional<BufferedImage> frame() {
                return screen == null ? Optional.empty() : screen.frame();
            }

            @Override
            public Optional<Area> watchedArea() {
                return screen == null ? Optional.empty() : screen.area();
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
