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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
