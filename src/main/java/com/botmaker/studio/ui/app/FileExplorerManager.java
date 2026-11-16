// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app;

import com.botmaker.core.project.ClassKind;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.core.nav.SourceNavigation;
import com.botmaker.studio.nav.BlockAt;
import com.botmaker.core.nav.SourceNavigation.Entry;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.java.index.nav.StructureKind;
import com.botmaker.core.project.FileRole;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.studio.core.EditorState;
import com.botmaker.core.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.java.index.BotIndex;
import com.botmaker.core.project.vcs.Checkpoints;
import com.botmaker.core.project.vcs.ProjectVcs;
import com.botmaker.core.project.vcs.VcsFileStatus;
import com.botmaker.core.project.vcs.VersionOrigin;
import com.botmaker.core.services.BotSources;
import com.botmaker.studio.services.ClassFiles;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.core.events.EventBus;
import com.botmaker.studio.ui.app.viewers.ResourceViewers;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The Project Files explorer: a filter and the file tree, the open file's row unfolding to its members (a click
 * lands on the block; a <b>Structure</b> pane under the tree until 2026-11-13). What it shows is
 * {@link ExplorerModel}'s; this class draws it and handles the clicks.
 *
 * <p><b>The tree is a folder tree again (2026-09-26), with package chains folded.</b> It was a flat list from
 * 2026-08-29, on the grounds that {@code com → <bot> → …} is three rows of Java ceremony between the user and
 * the only thing they open. Folding keeps that point and gives back the folders a bot now really has — the
 * plugin files under {@code plugins/sdk}, the user's own sub-packages — so {@code com.mybot} is one row.
 * Groups: <b>My code</b> ({@link FileRole#EDITABLE} Java the user writes), <b>Resources</b> ({@code src/main/resources}, to see the pictures
 * and data files, which open nowhere here) and <b>Library</b> (read-only bundled source). The header's <i>+</i>
 * makes a class in the selected row's package. Parameters.java and the plugins' files, which their windows change,
 * are not listed: a <b>Managed by Studio</b> group from 2026-10-13, when a user could not tell which files were
 * theirs, until 2026-11-13, the user's call.
 *
 * <p>A row's icon says what the file is ({@link ExplorerModel.Kind}), its colour whether it differs from the
 * last saved version — git's answer plus the editor's unsaved buffers, read on a worker. A right-click offers
 * <i>Show in file manager</i>, <i>Copy path</i>, in the user's packages <i>New ▸ Class / Interface / Record /
 * Enum…</i>, and for a class of theirs <i>Rename…</i> (the class and every use of it) and <i>Delete…</i>
 * (refused while anything uses it). Those three are {@link ClassFiles}', which the assistant's tools share; they
 * were the file manager's until 2026-10-13, when a user building a bot found no way to add a class. Moving a
 * file between packages still is.
 *
 * <p>Roles come from {@link FileRole#of} — this class must not re-derive them from paths.
 */
public class FileExplorerManager {

    private static final String GROUP_USER = "My code";
    private static final String GROUP_RESOURCES = "Resources";
    private static final String GROUP_LIBRARY = "Library";

    private final ProjectConfig config;
    private final CodeEditorService codeEditorService;
    private final EditorState state;
    private final EventBus eventBus;
    private final ClassFiles classFiles;
    private final TreeView<ExplorerNode> fileTree = new TreeView<>();
    private final TextField filter = new TextField();
    private final PauseTransition statusDelay = new PauseTransition(Duration.millis(900));
    /** Whether each row was open, by {@link ExplorerNode#key()}, so a refresh keeps what the user folded. */
    private final Map<String, Boolean> expansion = new HashMap<>();
    private Map<Path, VcsFileStatus> status = Map.of();
    /** How many review marks each file holds, from the status bar's last scan; a file with none is absent. */
    private Map<Path, Integer> toReview = Map.of();
    private int statusRun;
    /** The open file's row, which holds its members; null when no row of the tree is the open file. */
    private TreeItem<ExplorerNode> membersOf;
    /** Set while the members are rebuilt: the selection that moves off a removed row is nobody's click. */
    private boolean rebuilding;
    private Consumer<CodeBlock> onReveal = block -> { };
    private Runnable onCanvas = () -> { };
    private Consumer<Path> onView = path -> { };

    /**
     * A tree row: a group heading, a folder, or a file.
     *
     * @param label shown text; a folded package chain for a folder
     * @param kind  what it is, for its icon; null for a group
     */
    public record ExplorerNode(String label, Path path, FileRole role, ExplorerModel.Kind kind, boolean group,
                               Entry member) {

        ExplorerNode(String label, Path path, FileRole role, ExplorerModel.Kind kind, boolean group) {
            this(label, path, role, kind, group, null);
        }

        static ExplorerNode group(String label) {
            return new ExplorerNode(label, null, null, null, true);
        }

        /** A type, field, constructor, method or constant of the open file {@code file}, under its row. */
        static ExplorerNode member(Path file, Entry entry) {
            return new ExplorerNode(entry.kind().glyph() + "  " + entry.label(), file, null, null, false, entry);
        }

        boolean isMember() {
            return member != null;
        }

        boolean isFolder() {
            return kind == ExplorerModel.Kind.FOLDER;
        }

        /** A Java file the canvas can draw. */
        boolean opens() {
            return !group && !isMember() && !isFolder() && path.getFileName().toString().endsWith(".java");
        }

        /** A file a viewer shows beside the canvas — a picture, JSON, properties, text. */
        boolean views() {
            return !group && !isMember() && !isFolder() && ResourceViewers.canView(path);
        }

        /** Stable key for save/restore of expansion state; a member's by its place, as its node is re-parsed. */
        String key() {
            if (group) return "group:" + label;
            String file = path.toAbsolutePath().toString();
            return isMember() ? file + "#" + member.depth() + member.kind() + member.label() : file;
        }
    }

    public FileExplorerManager(StudioContext ctx) {
        this.config = ctx.config();
        this.codeEditorService = ctx.codeEditorService();
        this.state = ctx.state();
        this.eventBus = ctx.eventBus();
        this.classFiles = new ClassFiles(ctx);

        // A rebind writes a newly added plugin's managed holder (HostPluginValues.createMissing) and
        // announces itself with this event, so the tree is re-read.
        eventBus.subscribe(CoreApplicationEvents.LibrariesChangedEvent.class, e -> refreshTree(), true);
        // A file created, renamed or deleted, here or by the assistant.
        eventBus.subscribe(CoreApplicationEvents.ProjectFilesChangedEvent.class, e -> refreshTree(), true);
        // Every edit re-renders the open file (the core's MethodsChanged, or another file opened): its members
        // are re-read under its row, and the row may now differ from the last saved version — re-read after a
        // pause, as a burst of typing is one change, not twenty.
        eventBus.subscribe(CoreApplicationEvents.UIBlocksUpdatedEvent.class, e -> {
            refreshMembers();
            statusDelay.playFromStart();
        }, true);
        statusDelay.setOnFinished(e -> refreshStatus());
    }

    /** Where a member row lands: the canvas's own scroll-and-select. */
    public void setOnReveal(Consumer<CodeBlock> onReveal) {
        this.onReveal = onReveal == null ? block -> { } : onReveal;
    }

    /**
     * Where a file row goes: {@code canvas} once a Java file is the one drawn, {@code view} with a file a viewer
     * shows (a picture, a data file) — each the centre column's tab for it.
     */
    public void setOnOpen(Runnable canvas, Consumer<Path> view) {
        this.onCanvas = canvas == null ? () -> { } : canvas;
        this.onView = view == null ? path -> { } : view;
    }

    public VBox createView() {
        VBox container = new VBox();
        container.getStyleClass().add("file-explorer");

        Label title = new Label("Project Files");
        title.getStyleClass().add("sidebar-header");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        // In sight, not only on a right-click of the right row (2026-10-13, a user looked for it): makes the file
        // in the selected row's package, or the bot's.
        MenuButton add = new MenuButton("+");
        add.getStyleClass().add("sidebar-button");
        add.setTooltip(new Tooltip("New class, interface, record or enum"));
        add.getItems().setAll(newItems(this::newFileFolder));
        add.setOnShowing(e -> add.getItems().forEach(item -> item.setDisable(state.isReaderMode())));
        HBox header = new HBox(4, title, add);
        header.setAlignment(Pos.CENTER_LEFT);

        filter.setPromptText("Filter files…");
        filter.getStyleClass().add("file-filter");
        filter.textProperty().addListener((o, was, now) -> refreshTree());

        configureTree();
        refreshTree();

        // The tree takes the panel's height: the Structure pane under it went on 2026-11-13, its rows now the
        // open file's own, under its row.
        fileTree.getStyleClass().add("file-tree");
        fileTree.setMaxWidth(Double.MAX_VALUE);
        fileTree.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(fileTree, Priority.ALWAYS);
        // Coming back to the panel is when a stale colour would be noticed, so it is re-read then too.
        fileTree.setOnMouseEntered(e -> statusDelay.playFromStart());

        container.getChildren().addAll(header, filter, fileTree);
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
                getStyleClass().removeAll("tree-dir", "tree-lib", "tree-active", "tree-group", "tree-review",
                        "tree-member", "vcs-new", "vcs-added", "vcs-modified");
                setTooltip(null);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    return;
                }
                setText(item.label());
                if (item.isMember()) {
                    setGraphic(null);
                    setContextMenu(null);
                    getStyleClass().add("tree-member");
                    return;
                }
                if (item.group()) {
                    setGraphic(null);
                    getStyleClass().add("tree-group");
                    setContextMenu(GROUP_USER.equals(item.label())
                            ? new ContextMenu(newMenu(config.mainPackageDir())) : null);
                    return;
                }
                setGraphic(icon(item.kind()));
                String what = item.kind().displayName();
                setTooltip(new Tooltip(what));
                setContextMenu(contextMenu(item));
                if (item.isFolder()) {
                    getStyleClass().add("tree-dir");
                    return;
                }
                // One style for every file that is not the user's to type in.
                if (item.role() != null && item.role().isReadOnly()) getStyleClass().add("tree-lib");
                VcsFileStatus changed = status.get(item.path().toAbsolutePath().normalize());
                if (changed != null && changed != VcsFileStatus.DELETED) getStyleClass().add("vcs-" + changed.label());
                if (changed != null) setTooltip(new Tooltip(what + " · " + changed.label()
                        + " since the last saved version"));
                if (state.getActiveFile() != null && item.path().equals(state.getActiveFile().getPath())) {
                    getStyleClass().add("tree-active");
                }
                // Goals.java ⚑ 1: a file holding something BotMaker changed for the user to look at.
                int marks = toReview.getOrDefault(item.path().toAbsolutePath().normalize(), 0);
                if (marks > 0) {
                    setText(item.label() + " ⚑ " + marks);
                    getStyleClass().add("tree-review");
                }
            }
        });

        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            // A member row lands by its click only: the members are rebuilt after every edit, and a selection
            // moved by that must not jump the canvas.
            if (rebuilding || newVal != null && newVal.getValue() != null && newVal.getValue().isMember()) return;
            shownBySelection = newVal;
            show(newVal);
        });
        // A click on the row already selected changes no selection, and is still "show me this file" — the
        // user closed its tab, or went back to the canvas, and wants it again. The click that also selected the
        // row was answered by the selection.
        fileTree.setOnMouseClicked(e -> {
            TreeItem<ExplorerNode> item = fileTree.getSelectionModel().getSelectedItem();
            boolean answered = item == shownBySelection;
            shownBySelection = null;
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (item != null && item.getValue() != null && item.getValue().isMember()) reveal(item.getValue());
            else if (!answered) show(item);
        });
    }

    /** The row the last selection change showed, until the click that made it has been seen. */
    private TreeItem<ExplorerNode> shownBySelection;

    /** Brings {@code item}'s file forward: its viewer's tab, or the canvas drawing it. */
    private void show(TreeItem<ExplorerNode> item) {
        if (item == null || item.getValue() == null) return;
        ExplorerNode node = item.getValue();
        if (node.group() || node.isFolder() || !Files.isRegularFile(node.path())) return;
        if (node.views()) {
            onView.accept(node.path());
            return;
        }
        if (!node.opens()) return;
        if (state.getActiveFile() == null || !state.getActiveFile().getPath().equals(node.path())) {
            codeEditorService.switchToFile(node.path());
            fileTree.refresh();
        }
        onCanvas.run();
    }

    private static SVGPath icon(ExplorerModel.Kind kind) {
        SVGPath path = new SVGPath();
        path.setContent(kind.svg());
        path.getStyleClass().addAll("tree-icon-svg", "tree-kind-" + kind.id());
        return path;
    }

    /** A Markdown page the root lists, such as README.md; never a hidden one, such as a file being written. */
    static boolean markdownPage(String name) {
        return !name.startsWith(".") && name.toLowerCase(Locale.ROOT).endsWith(".md");
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
        Predicate<Path> editable = java.and(p -> FileRole.of(p) == FileRole.EDITABLE).and(matches);
        // A file a window keeps (Parameters.java, a plugin's) is that window's to change, and not listed.
        Predicate<Path> kept = p -> classFiles.managed(p).isPresent();
        TreeItem<ExplorerNode> user = group(GROUP_USER, true, filtering,
                ExplorerModel.tree(javaRoot, editable.and(kept.negate())), entryPoint);
        TreeItem<ExplorerNode> resources = group(GROUP_RESOURCES, false, filtering,
                ExplorerModel.tree(config.resourcesRoot(), matches), entryPoint);
        TreeItem<ExplorerNode> library = group(GROUP_LIBRARY, false, filtering, ExplorerModel.tree(javaRoot,
                java.and(p -> FileRole.of(p) == FileRole.LIBRARY).and(matches)), entryPoint);

        // Each group only when it has anything: an ordinary project reads as one list plus its resources.
        if (!user.getChildren().isEmpty() || !filtering) root.getChildren().add(user);
        if (!resources.getChildren().isEmpty()) root.getChildren().add(resources);
        if (!library.getChildren().isEmpty()) root.getChildren().add(library);
        // Markdown at the bot's root sits beside no source (its README): first, by name.
        List<Path> pages = new ArrayList<>();
        try (Stream<Path> top = Files.list(config.projectPath())) {
            top.filter(p -> markdownPage(p.getFileName().toString())
                            && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && matches.test(p))
                    .sorted(Comparator.reverseOrder()).forEach(pages::add);
        } catch (IOException e) {
            // A root that cannot be listed shows its sources only.
        }
        for (Path page : pages) {
            String name = page.getFileName().toString();
            root.getChildren().addFirst(new TreeItem<>(new ExplorerNode(name, page, FileRole.of(page),
                    ExplorerModel.kindOf(page, null, entryPoint), false)));
        }
        fileTree.setRoot(root);
        membersOf = null;
        refreshMembers();
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
            TreeItem<ExplorerNode> row = new TreeItem<>(new ExplorerNode(file.getFileName().toString(), file,
                    FileRole.of(file), ExplorerModel.kindOf(file, source, entryPoint), false));
            // A Java file's row unfolds to its members while it is the open file: its fold is kept from here.
            if (row.getValue().opens()) open(row, true, filtering);
            item.getChildren().add(row);
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

    /** Flags each file of {@code files} with how many times it appears: one per review mark it holds. */
    public void toReview(List<Path> files) {
        Map<Path, Integer> counts = new HashMap<>();
        for (Path file : files) counts.merge(file.toAbsolutePath().normalize(), 1, Integer::sum);
        if (counts.equals(toReview)) return;
        toReview = Map.copyOf(counts);
        fileTree.refresh();
    }

    /** The marks of {@code file}, as its row counts them. */
    int toReviewIn(Path file) {
        return toReview.getOrDefault(file.toAbsolutePath().normalize(), 0);
    }

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
    // the open file's members, under its row (the Structure pane until 2026-11-13)
    // ------------------------------------------------------------------

    /**
     * Puts the open file's members under its row, and takes them off the row that had them. The file's own
     * class is its row, so its members sit right under it; any other type of the file is a row of its own.
     */
    private void refreshMembers() {
        TreeItem<ExplorerNode> file = state.getActiveFile() == null ? null
                : find(fileTree.getRoot(), state.getActiveFile().getPath());
        rebuilding = true;
        try {
            if (membersOf != null && membersOf != file) membersOf.getChildren().clear();
            if (file == null) {
                membersOf = null;
                return;
            }
            Path path = file.getValue().path();
            String primary = path.getFileName().toString().replaceFirst("\\.java$", "");
            boolean filtering = !filter.getText().isBlank();
            List<TreeItem<ExplorerNode>> members = new ArrayList<>();
            // The row each depth's entries go under: the depth above's last type.
            Map<Integer, TreeItem<ExplorerNode>> parents = new HashMap<>();
            for (Entry entry : SourceNavigation.structure(state.getCompilationUnit().orElse(null))) {
                if (entry.depth() == 0 && entry.kind() == StructureKind.TYPE && entry.name().equals(primary)) {
                    parents.put(1, null);
                    continue;
                }
                TreeItem<ExplorerNode> row = new TreeItem<>(ExplorerNode.member(path, entry));
                TreeItem<ExplorerNode> parent = parents.get(entry.depth());
                if (parent == null) members.add(row);
                else parent.getChildren().add(row);
                if (entry.kind() == StructureKind.TYPE) {
                    parents.put(entry.depth() + 1, row);
                    open(row, true, filtering);
                }
            }
            file.getChildren().setAll(members);
            membersOf = file;
        } finally {
            rebuilding = false;
        }
    }

    /** The file row of {@code path} under {@code item}, or null. */
    private static TreeItem<ExplorerNode> find(TreeItem<ExplorerNode> item, Path path) {
        if (item == null) return null;
        for (TreeItem<ExplorerNode> child : item.getChildren()) {
            ExplorerNode node = child.getValue();
            if (node.group() || node.isFolder()) {
                TreeItem<ExplorerNode> found = find(child, path);
                if (found != null) return found;
            } else if (!node.isMember() && path.equals(node.path())) {
                return child;
            }
        }
        return null;
    }

    /** Lands on a member's block, the canvas in front. */
    private void reveal(ExplorerNode member) {
        onCanvas.run();
        BlockAt.blockFor(member.member().node(), state.getNodeToBlockMap()).ifPresent(onReveal);
    }

    /** Clicks the member row named {@code name}, as the user would; for a test. */
    void clickMember(String name) {
        List<TreeItem<ExplorerNode>> rows = new ArrayList<>(membersOf == null ? List.of() : membersOf.getChildren());
        for (int i = 0; i < rows.size(); i++) {
            ExplorerNode node = rows.get(i).getValue();
            if (node.member().name().equals(name)) {
                reveal(node);
                return;
            }
            rows.addAll(rows.get(i).getChildren());
        }
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

    /** A row's menu. */
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
        Path folder = node.isFolder() ? node.path() : node.path().getParent();
        boolean userCode = node.isFolder() || node.opens() && node.role() == FileRole.EDITABLE;
        if (userCode && classFiles.folderProblem(folder).isEmpty()) {
            menu.getItems().addAll(new SeparatorMenuItem(), newMenu(folder));
        }
        if (node.opens() && node.role() == FileRole.EDITABLE && classFiles.lockedProblem(node.path()).isEmpty()) {
            MenuItem rename = new MenuItem("Rename…");
            MenuItem delete = new MenuItem("Delete…");
            if (state.isReaderMode()) {
                rename.setDisable(true);
                rename.setText("Rename… (this bot is open for reading)");
                delete.setDisable(true);
                delete.setText("Delete… (this bot is open for reading)");
            }
            rename.setOnAction(e -> rename(node.path()));
            delete.setOnAction(e -> delete(node.path()));
            menu.getItems().addAll(new SeparatorMenuItem(), rename, delete);
        }
        return menu;
    }

    /** <i>New ▸ Class / Interface / Record / Enum…</i>, each made in {@code folder}'s package. */
    private javafx.scene.control.Menu newMenu(Path folder) {
        javafx.scene.control.Menu menu = new javafx.scene.control.Menu("New");
        menu.getItems().setAll(newItems(() -> folder));
        // Read when it opens: the group row's menu outlives a switch to or from reading.
        menu.setOnShowing(e -> menu.getItems().forEach(item -> item.setDisable(state.isReaderMode())));
        return menu;
    }

    /** One item per kind, each made in the package of the folder {@code folder} answers when it is clicked. */
    private List<MenuItem> newItems(Supplier<Path> folder) {
        List<MenuItem> items = new ArrayList<>();
        for (ClassKind kind : ClassKind.values()) {
            MenuItem item = new MenuItem(kind.displayName() + "…");
            item.setOnAction(e -> create(folder.get(), kind));
            items.add(item);
        }
        return items;
    }

    /** Where the header's <i>+</i> makes a file: the selected row's package when a class may go there. */
    private Path newFileFolder() {
        TreeItem<ExplorerNode> selected = fileTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null && !selected.getValue().group()) {
            ExplorerNode node = selected.getValue();
            Path folder = node.isFolder() ? node.path() : node.path().getParent();
            if (classFiles.folderProblem(folder).isEmpty()) return folder;
        }
        return config.mainPackageDir();
    }

    private void create(Path folder, ClassKind kind) {
        String what = kind.displayName().toLowerCase(java.util.Locale.ROOT);
        TextInputDialog dialog = new TextInputDialog();
        ThemedWindows.apply(dialog);
        if (fileTree.getScene() != null) dialog.initOwner(fileTree.getScene().getWindow());
        dialog.setTitle("New " + what);
        dialog.setHeaderText("A new " + what + " in " + classFiles.packageOf(folder));
        dialog.setContentText("Name:");
        String name = dialog.showAndWait().map(String::strip).orElse("");
        if (name.isEmpty()) return;
        try {
            Path file = classFiles.create(folder, kind, name);
            codeEditorService.switchToFile(file);
            onCanvas.run();
            status("Created " + file.getFileName() + ".");
        } catch (IllegalArgumentException refused) {
            RefusalDialog.show(window(), name + " wasn't created", refused.getMessage(), List.of(), List.of(), null);
        }
    }

    /**
     * Renames {@code file} and its class, and every use of the class in the bot ({@link ClassFiles}): planned on
     * a worker, as it parses the bot with bindings, and applied on the FX thread.
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

        Supplier<BotIndex> index = BotSources.prepareIndex(config, state);
        ProjectState.Snapshot before = state.snapshot();
        status("Renaming " + oldName + "…");
        onWorker("explorer-rename", () -> {
            Refactor.Outcome outcome = classFiles.planRename(index.get(), before, file, newName);
            Platform.runLater(() -> {
                switch (outcome) {
                    case Refactor.Refused refused -> {
                        status("");
                        RefusalDialog.show(window(), oldName + " wasn't renamed", refused,
                                Refactors.reveal(codeEditorService), fix -> {
                                    Checkpoints.take(config.projectPath(), state.snapshot(), VersionOrigin.SAFETY,
                                            "Before: " + fix.summary());
                                    apply(fix);
                                });
                    }
                    case Refactor.Planned plan -> apply(plan);
                }
            });
        });
    }

    /** Writes a rename's plan to the buffers and the disk. FX thread. */
    private void apply(Refactor.Planned plan) {
        try {
            status(classFiles.applyRename(plan));
        } catch (IOException failed) {
            status("Rename stopped part-way: " + failed.getMessage()
                    + " — Versions has the project as it was before.");
        }
    }

    /**
     * Deletes {@code file} once the user confirms and nothing uses what it declares ({@link ClassFiles}); a
     * refusal lists the uses, each a place to go.
     */
    private void delete(Path file) {
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Versions keeps a copy from before, to bring it back.",
                javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        ThemedWindows.apply(confirm);
        if (fileTree.getScene() != null) confirm.initOwner(fileTree.getScene().getWindow());
        confirm.setTitle("Delete");
        confirm.setHeaderText("Delete " + file.getFileName() + "?");
        if (confirm.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).isEmpty()) return;

        Supplier<BotIndex> index = BotSources.prepareIndex(config, state);
        ProjectState.Snapshot before = state.snapshot();
        status("Checking what uses " + file.getFileName() + "…");
        onWorker("explorer-delete", () -> {
            java.util.Optional<Refactor.Refused> refused = classFiles.planDelete(index.get(), before, file);
            Platform.runLater(() -> {
                if (refused.isPresent()) {
                    status("");
                    RefusalDialog.show(window(), file.getFileName() + " wasn't deleted", refused.get().reason(),
                            refused.get().uses(), List.of(), Refactors.reveal(codeEditorService));
                    return;
                }
                try {
                    status(classFiles.delete(file));
                } catch (IOException failed) {
                    status(file.getFileName() + " could not be deleted: " + failed.getMessage());
                }
            });
        });
    }

    private javafx.stage.Window window() {
        return fileTree.getScene() == null ? null : fileTree.getScene().getWindow();
    }

    private static void onWorker(String name, Runnable work) {
        Thread worker = new Thread(work, name);
        worker.setDaemon(true);
        worker.start();
    }

    private void status(String message) {
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }
}
