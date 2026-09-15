package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.SdkDocsService;
import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.validation.DiagnosticsManager;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A real {@link CodeEditor} over a source string, wired the way {@code BotProject.open} wires one: parsed to
 * blocks by {@link BlockConverter}, with an {@link EventBus} that captures the {@code CodeUpdatedEvent} so a
 * test can assert on the rewritten source.
 *
 * <p>Shared by the write-path tests so each one is just its source and its assertion. Public because the
 * block-construction tests in {@code blocks/} drive the same converter — the harness is the module's one
 * source-to-block-tree entry point for tests, and a second copy of it would drift.
 */
public final class EditorFixture {

    private static final Path PROJECTS = Paths.get("/tmp/projects");
    private static final ProjectConfig CONFIG = ProjectConfig.forProject("MyBot", PROJECTS);
    private static final List<String> RUNTIME_CLASSPATH =
            List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator));

    public final CodeEditor editor;
    public final ProjectState state;
    public final AbstractCodeBlock root;
    /** The source this fixture first parsed — what a {@link BlockReuse} over its tree indexes into. */
    public final String source;
    public String lastCode;
    /** Every user-facing status line the editor published — how a refused edit announces itself. */
    public final List<String> statusMessages = new ArrayList<>();

    private final EventBus bus;
    private final BlockConverter converter;
    private final BlockDragAndDropManager dragAndDrop;
    private final ProjectAnalyzer analyzer;
    private CodeEditorService context;

    public EditorFixture(String source) {
        this(source, Paths.get("Subject.java").toAbsolutePath());
    }

    /** As {@link #EditorFixture(String)} but with an explicit file path — e.g. one under the activities dir. */
    public EditorFixture(String source, Path file) {
        this(source, file, RefusalJournal.in(Paths.get(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals")));
    }

    /**
     * As above with an explicit {@link RefusalJournal} — for a test that asserts on what a refused edit
     * records. The other constructors point the journal at a temp directory rather than the real cache dir, so
     * running the suite never writes into the developer's diagnostics.
     */
    public EditorFixture(String source, Path file, RefusalJournal journal) {
        this.source = source;
        state = new ProjectState();
        state.addFile(new ProjectFile(file, source));
        state.setActiveFile(file);
        // FileRole only compares paths, it never reads them, but the parser needs a real source root and
        // classpath to resolve against.
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(RUNTIME_CLASSPATH);
        state.setTemplate(ProjectTemplate.GAME_BOT);
        state.setCurrentCode(source);

        bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());
        bus.subscribe(CoreApplicationEvents.StatusMessageEvent.class, e -> statusMessages.add(e.message()));

        converter = new BlockConverter(CONFIG, state);
        dragAndDrop = new BlockDragAndDropManager(bus);
        analyzer = new ProjectAnalyzer(null, state);
        BlockConverter.ConvertResult result = TestSupport.convertAndPublish(
                converter, state, source, dragAndDrop, false, false);
        state.setCompilationUnit(result.cu());
        root = result.root();
        assertNotNull(root, "converter should produce a root block");

        editor = new CodeEditor(CONFIG, state, bus, analyzer, journal);
    }

    /**
     * The {@link CodeEditorService} the UI layer is handed — every block's {@code getUINode} and every
     * argument editor takes one. Built lazily because it opens an {@code SdkDocsService} loader thread that
     * the write-path tests have no use for.
     */
    public CodeEditorService context() {
        if (context == null) {
            context = new CodeEditorService(CONFIG, state, bus, converter, dragAndDrop,
                    new DiagnosticsManager(), analyzer, new SdkDocsService(CONFIG, bus), null);
        }
        return context;
    }

    /**
     * Parses {@code newSource} over this fixture's existing tree, the way {@code CodeEditorService.render}
     * re-parses after an edit: a fresh registry, published in one assignment, with the previous parse offered
     * back through {@code reuse}.
     *
     * <p>Pass {@link BlockReuse#NONE} to assert that reuse-off is unchanged.
     */
    public BlockConverter.ConvertResult reparse(String newSource, BlockReuse reuse) {
        return reparse(newSource, reuse, false);
    }

    /**
     * As above, under an explicit lock verdict — for the one refusal that is about the parse rather than
     * about a block: a re-parse whose verdict differs from the previous one keeps nothing.
     */
    public BlockConverter.ConvertResult reparse(String newSource, BlockReuse reuse, boolean readOnly) {
        java.util.Map<org.eclipse.jdt.core.dom.ASTNode, CodeBlock> registry = new java.util.HashMap<>();
        BlockConverter.ConvertResult result = converter.convert(
                null, newSource, registry, dragAndDrop, readOnly, false, reuse);
        state.setNodeToBlockMap(registry);
        state.setCompilationUnit(result.cu());
        state.setCurrentCode(newSource);
        return result;
    }

    /** This fixture's bus — for a test that publishes the events a real edit publishes. */
    public EventBus bus() {
        return bus;
    }

    /**
     * Calls {@code handler} with the root of every tree {@code CodeEditorService} renders.
     *
     * <p>How an FX test gets the service's blocks into a scene: {@code render} publishes the root and nothing
     * else hands it out, and a block that is in no scene has no focus owner to walk up from.
     */
    public void subscribeBlocksUpdated(java.util.function.Consumer<AbstractCodeBlock> handler) {
        bus.subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class,
                e -> handler.accept(e.rootBlock()), false);
    }

    /**
     * Asks the service to render the source the state already holds, the way installing a plugin does.
     *
     * <p>Reuse is off on this path by construction, which is what makes it the right way to establish a
     * first render: the tree it produces is the one a later edit is then offered.
     */
    public void rerender() {
        bus.publish(new CoreApplicationEvents.UIRefreshRequestedEvent(state.getCurrentCode()));
    }

    /** A path under this project's activities package — a file there is treated as an activity stub. */
    public static Path activitiesFile(String fileName) {
        return CONFIG.activitiesPackageDir().resolve(fileName).toAbsolutePath();
    }

    /** The {@link BodyBlock} for {@code methodName}'s body, found the way CodeEditorService finds it: by AST node. */
    public BodyBlock body(String methodName) {
        // Off the state's current compilation unit and registry rather than off `root`, so this keeps
        // answering after a reparse() -- root is the first parse's. That is also how CodeEditorService finds
        // a body: by AST node, through the registry it just published.
        TypeDeclaration type = (TypeDeclaration) state.getCompilationUnit()
                .orElseThrow(() -> new AssertionError("fixture has no compilation unit"))
                .types().getFirst();
        MethodDeclaration found = null;
        for (MethodDeclaration m : type.getMethods()) {
            if (m.getName().getIdentifier().equals(methodName)) found = m;
        }
        assertNotNull(found, "fixture should have " + methodName + "()");
        CodeBlock block = state.getNodeToBlockMap().get(found.getBody());
        if (block instanceof BodyBlock body) return body;
        throw new AssertionError("no body block for " + methodName);
    }

    private static List<CodeBlock> all(CodeBlock from) {
        List<CodeBlock> out = new ArrayList<>();
        out.add(from);
        if (from instanceof BlockWithChildren parent) {
            for (CodeBlock child : parent.getChildren()) out.addAll(all(child));
        }
        return out;
    }
}
