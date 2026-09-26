package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.nav.SourceNavigation;
import com.botmaker.studio.nav.SourceNavigation.Entry;
import com.botmaker.studio.nav.TypeRename;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VcsFileStatus;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The Project Files explorer: a filter, the file tree, and a collapsible <b>Structure</b> section for the open
 * file. What it shows is {@link ExplorerModel}'s; this class draws it and handles the clicks.
 *
 * <p><b>The tree is a folder tree again (2026-09-26), with package chains folded.</b> It was a flat list from
 * 2026-08-29, on the grounds that {@code com → <bot> → …} is three rows of Java ceremony between the user and
 * the only thing they open. Folding keeps that point and gives back the folders a bot now really has — the
 * plugin files under {@code plugins/sdk}, the user's own sub-packages — so {@code com.mybot} is one row.
 * Groups: <b>My code</b> ({@link FileRole#EDITABLE} Java), <b>Resources</b> ({@code src/main/resources}, to
 * see the pictures and data files, which open nowhere here) and <b>Library</b> (read-only bundled source).
 *
 * <p>A row's icon says what the file is ({@link ExplorerModel.Kind}), its colour whether it differs from the
 * last saved version — git's answer plus the editor's unsaved buffers, read on a worker. A right-click offers
 * <i>Show in file manager</i>, <i>Copy path</i> and, for a class of the user's, <i>Rename…</i>
 * ({@link TypeRename}: the class and every use of it, after a safety version). Deleting, creating and moving
 * are still the file manager's: an explorer that half-does them is the worst of both.
 *
 * <p>Roles come from {@link FileRole#of} — this class must not re-derive them from paths.
 */
public class FileExplorerManager {

    private static final String GROUP_USER = "My code";
    private static final String GROUP_RESOURCES = "Resources";
    private static final String GROUP_LIBRARY = "Library";

    private final ProjectConfig config;
    private final CodeEditorService codeEditorService;
    private final ProjectState state;
    private final EventBus eventBus;
    private final TreeView<ExplorerNode> fileTree = new TreeView<>();
    private final TextField filter = new TextField();
    private final ListView<Entry> structure = new ListView<>();
    private final TitledPane structurePane = new TitledPane("Structure", structure);
    private final PauseTransition statusDelay = new PauseTransition(Duration.millis(900));
    /** Whether each row was open, by {@link ExplorerNode#key()}, so a refresh keeps what the user folded. */
    private final Map<String, Boolean> expansion = new HashMap<>();
    private Map<Path, VcsFileStatus> status = Map.of();
    private int statusRun;
    private Consumer<CodeBlock> onReveal = block -> { };

    /**
     * A tree row: a group heading, a folder, or a file.
     *
     * @param label shown text; a folded package chain for a folder
     * @param kind  what it is, for its icon; null for a group
     */
    public record ExplorerNode(String label, Path path, FileRole role, ExplorerModel.Kind kind, boolean group) {

        static ExplorerNode group(String label) {
            return new ExplorerNode(label, null, null, null, true);
        }

        boolean isFolder() {
            return kind == ExplorerModel.Kind.FOLDER;
        }

        /** A Java file the canvas can draw. */
        boolean opens() {
            return !group && !isFolder() && path.getFileName().toString().endsWith(".java");
        }

        /** Stable key for save/restore of expansion state. */
        String key() {
            return group ? "group:" + label : path.toAbsolutePath().toString();
        }
    }

    public FileExplorerManager(StudioContext ctx) {
        this.config = ctx.config();
        this.codeEditorService = ctx.codeEditorService();
        this.state = ctx.state();
        this.eventBus = ctx.eventBus();

        // A rebind writes a newly added plugin's @Managed holder (HostPluginValues.createMissing) and
        // announces itself with this event, so the tree is re-read.
        eventBus.subscribe(CoreApplicationEvents.LibrariesChangedEvent.class, e -> refreshTree(), true);
        // Every edit re-renders the open file: its outline is re-read, and its row may now differ from the
        // last saved version — re-read after a pause, as a burst of typing is one change, not twenty.
        eventBus.subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, e -> {
            refreshStructure();
            statusDelay.playFromStart();
        }, true);
        statusDelay.setOnFinished(e -> refreshStatus());
    }

    /** Where a Structure row lands: the canvas's own scroll-and-select. */
    public void setOnReveal(Consumer<CodeBlock> onReveal) {
        this.onReveal = onReveal == null ? block -> { } : onReveal;
    }

    public VBox createView() {
        VBox container = new VBox();
        container.getStyleClass().add("file-explorer");

        Label header = new Label("Project Files");
        header.getStyleClass().add("sidebar-header");
        header.setMaxWidth(Double.MAX_VALUE);

        filter.setPromptText("Filter files…");
        filter.getStyleClass().add("file-filter");
        filter.textProperty().addListener((o, was, now) -> refreshTree());

        configureTree();
        configureStructure();
        refreshTree();

        // The tree takes every row the structure leaves; the structure is capped so a long class cannot push
        // the files off the panel, and folds to its heading.
        fileTree.getStyleClass().add("file-tree");
        fileTree.setMaxWidth(Double.MAX_VALUE);
        fileTree.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(fileTree, Priority.ALWAYS);
        // Coming back to the panel is when a stale colour would be noticed, so it is re-read then too.
        fileTree.setOnMouseEntered(e -> statusDelay.playFromStart());

        container.getChildren().addAll(header, filter, fileTree, structurePane);
        container.setFillWidth(true);
        return container;
    }

    // ------------------------------------------------------------------
    // the file tree
    // ------------------------------------------------------------------

    private void configureTree() {
        fileTree.setShowRoot(false);
        fileTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(ExplorerNode item, boolean empty) {
                super.updateItem(item, empty);
                // Cells are recycled: reset every class this factory sets.
                getStyleClass().removeAll("tree-dir", "tree-lib", "tree-active", "tree-group",
                        "vcs-new", "vcs-added", "vcs-modified");
                setTooltip(null);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                setText(item.label());
                if (item.group()) {
                    setGraphic(null);
                    getStyleClass().add("tree-group");
                    setContextMenu(null);
                    return;
                }
                setGraphic(icon(item.kind()));
                setTooltip(new Tooltip(item.kind().displayName()));
                setContextMenu(contextMenu(item));
                if (item.isFolder()) {
                    getStyleClass().add("tree-dir");
                    return;
                }
                // One style for every file that is not the user's to type in.
                if (item.role() != null && item.role().isReadOnly()) getStyleClass().add("tree-lib");
                VcsFileStatus changed = status.get(item.path().toAbsolutePath().normalize());
                if (changed != null && changed != VcsFileStatus.DELETED) getStyleClass().add("vcs-" + changed.label());
                if (changed != null) setTooltip(new Tooltip(item.kind().displayName() + " · " + changed.label()
                        + " since the last saved version"));
                if (state.getActiveFile() != null && item.path().equals(state.getActiveFile().getPath())) {
                    getStyleClass().add("tree-active");
                }
            }
        });

        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.getValue() == null) return;
            ExplorerNode node = newVal.getValue();
            if (!node.opens() || !Files.isRegularFile(node.path())) return;
            if (state.getActiveFile() == null || !state.getActiveFile().getPath().equals(node.path())) {
                codeEditorService.switchToFile(node.path());
                fileTree.refresh();
            }
        });
    }

    private static SVGPath icon(ExplorerModel.Kind kind) {
        SVGPath path = new SVGPath();
        path.setContent(kind.svg());
        path.getStyleClass().addAll("tree-icon-svg", "tree-kind-" + kind.id());
        return path;
    }

    public void refreshTree() {
        Predicate<Path> matches = ExplorerModel.matching(filter.getText());
        boolean filtering = !filter.getText().isBlank();

        Path javaRoot = config.projectPath().resolve("src").resolve("main").resolve("java");
        if (!Files.exists(javaRoot)) javaRoot = config.mainSourceFile().getParent();
        Path entryPoint = entryPoint();

        TreeItem<ExplorerNode> root = new TreeItem<>(ExplorerNode.group("root"));
        root.setExpanded(true);
        Predicate<Path> java = p -> p.getFileName().toString().endsWith(".java");
        TreeItem<ExplorerNode> user = group(GROUP_USER, true, filtering, ExplorerModel.tree(javaRoot,
                java.and(p -> FileRole.of(p) == FileRole.EDITABLE).and(matches)), entryPoint);
        TreeItem<ExplorerNode> resources = group(GROUP_RESOURCES, false, filtering,
                ExplorerModel.tree(config.resourcesRoot(), matches), entryPoint);
        TreeItem<ExplorerNode> library = group(GROUP_LIBRARY, false, filtering, ExplorerModel.tree(javaRoot,
                java.and(p -> FileRole.of(p) == FileRole.LIBRARY).and(matches)), entryPoint);

        // Each group only when it has anything: an ordinary project reads as one list plus its resources.
        if (!user.getChildren().isEmpty() || !filtering) root.getChildren().add(user);
        if (!resources.getChildren().isEmpty()) root.getChildren().add(resources);
        if (!library.getChildren().isEmpty()) root.getChildren().add(library);
        fileTree.setRoot(root);
        refreshStatus();
    }

    private TreeItem<ExplorerNode> group(String label, boolean openByDefault, boolean filtering,
                                         ExplorerModel.Folder folder, Path entryPoint) {
        TreeItem<ExplorerNode> group = new TreeItem<>(ExplorerNode.group(label));
        fill(group, folder, openByDefault, filtering, entryPoint);
        open(group, openByDefault, filtering);
        return group;
    }

    /** {@code folder}'s children under {@code item}: folders first, then files. */
    private void fill(TreeItem<ExplorerNode> item, ExplorerModel.Folder folder, boolean openByDefault,
                      boolean filtering, Path entryPoint) {
        for (ExplorerModel.Folder sub : folder.folders()) {
            TreeItem<ExplorerNode> child = new TreeItem<>(
                    new ExplorerNode(sub.label(), sub.path(), null, ExplorerModel.Kind.FOLDER, false));
            fill(child, sub, openByDefault, filtering, entryPoint);
            open(child, openByDefault, filtering);
            item.getChildren().add(child);
        }
        for (Path file : folder.files()) {
            String source = file.getFileName().toString().endsWith(".java")
                    ? BotSources.sourceOf(config, state, file).orElse(null) : null;
            item.getChildren().add(new TreeItem<>(new ExplorerNode(file.getFileName().toString(), file,
                    FileRole.of(file), ExplorerModel.kindOf(file, source, entryPoint), false)));
        }
    }

    /**
     * A filter opens everything it kept; otherwise a row keeps what the user left it at, or its default. Only
     * an unfiltered tree records what the user does — a filtered one was opened wholesale, not by them.
     */
    private void open(TreeItem<ExplorerNode> item, boolean byDefault, boolean filtering) {
        String key = item.getValue().key();
        item.setExpanded(filtering || expansion.getOrDefault(key, byDefault));
        if (!filtering) item.expandedProperty().addListener((o, was, now) -> expansion.put(key, now));
    }

    private Path entryPoint() {
        try {
            return config.entrySourceFile();
        } catch (RuntimeException none) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // what changed since the last saved version
    // ------------------------------------------------------------------

    /** Reads git's status on a worker and repaints the tree with it; a newer read replaces an older one. */
    private void refreshStatus() {
        int run = ++statusRun;
        List<ProjectState.SourceFile> buffers = state.getAllFiles().stream()
                .map(f -> new ProjectState.SourceFile(f.getPath(), f.getContent())).toList();
        Path projectDir = config.projectPath();
        Thread worker = new Thread(() -> {
            Map<Path, VcsFileStatus> read;
            try {
                read = ExplorerModel.status(projectDir, new ProjectVcs(projectDir).status(), buffers);
            } catch (IOException | RuntimeException unreadable) {
                read = Map.of();
            }
            Map<Path, VcsFileStatus> result = read;
            Platform.runLater(() -> {
                if (run != statusRun) return;
                status = result;
                fileTree.refresh();
            });
        }, "explorer-status");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------
    // the Structure section
    // ------------------------------------------------------------------

    private void configureStructure() {
        structurePane.getStyleClass().add("file-structure");
        structurePane.setExpanded(true);
        structurePane.setAnimated(false);
        structure.getStyleClass().add("file-structure-list");
        structure.setPrefHeight(200);
        structure.setPlaceholder(new Label("Nothing declared in this file."));
        structure.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null
                        : "  ".repeat(entry.depth()) + entry.kind().glyph() + "  " + entry.label());
            }
        });
        // A click, not a selection change: the list is rebuilt after every edit and must not jump the canvas.
        structure.setOnMouseClicked(e -> {
            Entry entry = structure.getSelectionModel().getSelectedItem();
            if (entry != null) SourceNavigation.blockFor(entry.node(), state.getNodeToBlockMap()).ifPresent(onReveal);
        });
    }

    private void refreshStructure() {
        CompilationUnit cu = state.getCompilationUnit().orElse(null);
        structure.getItems().setAll(SourceNavigation.structure(cu));
        String file = state.getActiveFile() == null ? null : state.getActiveFile().getPath().getFileName().toString();
        structurePane.setText(file == null ? "Structure" : "Structure — " + file);
    }

    /** What the Structure section lists, for a test. */
    List<String> structureNames() {
        return structure.getItems().stream().map(Entry::name).toList();
    }

    /** The tree's rows, depth-first, as their labels; for a test. */
    List<String> rows() {
        List<String> out = new java.util.ArrayList<>();
        collect(fileTree.getRoot(), 0, out);
        return out;
    }

    private static void collect(TreeItem<ExplorerNode> item, int depth, List<String> out) {
        if (item == null) return;
        for (TreeItem<ExplorerNode> child : item.getChildren()) {
            out.add("  ".repeat(depth) + child.getValue().label());
            collect(child, depth + 1, out);
        }
    }

    /** The filter field, for a test. */
    TextField filter() {
        return filter;
    }

    // ------------------------------------------------------------------
    // the context menu
    // ------------------------------------------------------------------

    private ContextMenu contextMenu(ExplorerNode node) {
        MenuItem show = new MenuItem("Show in file manager");
        show.setOnAction(e -> BrowserLauncher.open((node.isFolder() ? node.path() : node.path().getParent())
                .toUri().toString()));
        MenuItem copy = new MenuItem("Copy path");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(node.path().toAbsolutePath().toString());
            Clipboard.getSystemClipboard().setContent(content);
        });
        ContextMenu menu = new ContextMenu(show, copy);
        if (node.opens() && node.role() == FileRole.EDITABLE) {
            MenuItem rename = new MenuItem("Rename…");
            if (state.isReaderMode()) {
                rename.setDisable(true);
                rename.setText("Rename… (this bot is open for reading)");
            }
            rename.setOnAction(e -> rename(node.path()));
            menu.getItems().addAll(new SeparatorMenuItem(), rename);
        }
        return menu;
    }

    /**
     * Renames {@code file} and its class, and every use of the class in the bot. The plan is made on a worker
     * (it parses the bot with bindings), with a safety version taken first — the rename changes files the user
     * is not looking at, and the editor's ↶ reaches only the open one — and applied on the FX thread to the
     * open buffers and the disk alike.
     */
    private void rename(Path file) {
        String oldName = file.getFileName().toString().replaceFirst("\\.java$", "");
        TextInputDialog dialog = new TextInputDialog(oldName);
        ThemedWindows.apply(dialog);
        if (fileTree.getScene() != null) dialog.initOwner(fileTree.getScene().getWindow());
        dialog.setTitle("Rename");
        dialog.setHeaderText("Rename " + oldName + " and every use of it in this bot");
        dialog.setContentText("New name:");
        String newName = dialog.showAndWait().map(String::strip).orElse("");
        if (newName.isEmpty() || newName.equals(oldName)) return;

        Map<Path, String> sources = new LinkedHashMap<>();
        BotSources.scan(config, state, sources::put);
        Path target = file.resolveSibling(newName.replaceFirst("\\.java$", "") + ".java");
        Set<Path> existing = Files.exists(target) ? Set.of(target) : Set.of();
        BotParser parser = BotParser.of(state);
        ProjectState.Snapshot before = state.snapshot();
        status("Renaming " + oldName + "…");
        Thread worker = new Thread(() -> {
            TypeRename.Result result = TypeRename.plan(file, newName, sources, existing, parser);
            if (result instanceof TypeRename.Result.Plan) {
                Checkpoints.take(config.projectPath(), before, VersionOrigin.SAFETY,
                        "Before renaming " + oldName + " to " + newName);
            }
            Platform.runLater(() -> {
                switch (result) {
                    case TypeRename.Result.Refused refused -> status("Not renamed: " + refused.reason());
                    case TypeRename.Result.Plan plan -> apply(plan, oldName);
                }
            });
        }, "explorer-rename");
        worker.setDaemon(true);
        worker.start();
    }

    private void apply(TypeRename.Result.Plan plan, String oldName) {
        Path active = state.getActiveFile() == null ? null : state.getActiveFile().getPath();
        try {
            for (Map.Entry<Path, String> rewrite : plan.rewrites().entrySet()) {
                Path path = rewrite.getKey();
                if (path.equals(plan.from())) {
                    Files.writeString(plan.to(), rewrite.getValue());
                    Files.delete(plan.from());
                    state.removeFile(plan.from());
                    continue;
                }
                state.getFile(path).ifPresent(buffer -> buffer.setContent(rewrite.getValue()));
                if (Files.isRegularFile(path)) Files.writeString(path, rewrite.getValue());
            }
        } catch (IOException failed) {
            status("Rename stopped part-way: " + failed.getMessage()
                    + " — Versions has the project as it was before.");
            refreshTree();
            return;
        }
        // The open file is re-drawn from its (possibly rewritten) buffer; the renamed one opens under its name.
        if (plan.from().equals(active)) codeEditorService.switchToFile(plan.to());
        else if (active != null && plan.rewrites().containsKey(active)) codeEditorService.switchToFile(active);
        refreshTree();
        int others = plan.rewrites().size() - 1;
        status("Renamed " + oldName + " to " + plan.to().getFileName().toString().replaceFirst("\\.java$", "")
                + (others == 0 ? "." : " and its uses in " + others + (others == 1 ? " other file." : " other files."))
                + " Versions has a safety copy from before.");
    }

    private void status(String message) {
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }
}
