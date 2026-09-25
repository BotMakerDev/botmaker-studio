package com.botmaker.studio.ui.app.versions;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.vcs.BlockDiff;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.ProjectVcs.CommitInfo;
import com.botmaker.studio.project.vcs.VcsFileStatus;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.project.vcs.VersionReader;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Versions tab, Simple view ({@code docs/refactor/39-versions.md} §4): <i>Save version</i> over a timeline
 * of the project's versions — the unsaved changes pinned first, milestones bold, the versions Studio took on
 * the user's behalf folded — and, for the selected row, the files it changed, each drawn as {@link DiffCards}:
 * its changed functions as blocks, Before | After, with <i>Restore this function</i> and <i>Restore this
 * file</i> (§5–§6). Right-click a version to restore the project to it or to name it.
 *
 * <p>It replaced {@code VcsPanel} and {@code VcsDialog} (2026-09-25): a commit box nobody used over a list of
 * SHAs. <b>What the tab reads is what the editor holds</b>: a refresh first puts the editor's sources on disk
 * ({@link Checkpoints#flush}), since the editor writes only on a run and git would otherwise call a project
 * with an hour of edits clean. All git work runs off the FX thread.
 */
public final class VersionsPane {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault());

    private final Window owner;
    private final StudioContext ctx;
    private final Path projectDir;
    private final ShareActions share;

    private final BorderPane root = new BorderPane();
    private final TextField nameField = new TextField();
    private final ListView<Timeline.Row> timeline = new ListView<>();
    private final Label detailTitle = new Label();
    private final Button restore = new Button("Restore project to here…");
    private final Button rename = new Button("Name…");
    private final Button restoreFile = new Button("Restore this file");
    private final TreeView<ChangedFile> files = new TreeView<>();
    private final ScrollPane cards = new ScrollPane();
    private final DiffCards diffCards;
    /** The file whose cards are shown, as read; null while none is. */
    private DiffCards.Input shownFile;
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label status = new Label();

    private final Set<String> expanded = new HashSet<>();
    private List<CommitInfo> history = List.of();
    private int unsaved;
    /** The row whose files are shown; kept across a refresh when it still exists. */
    private Timeline.Row shown;

    // Commit author identity — the signed-in GitHub login once resolved.
    private volatile String authorName;
    private volatile String authorEmail;

    public VersionsPane(Window owner, StudioContext ctx, BotPublisher publisher, GitHubAuth auth,
                        GitHubClient client, Runnable openPublish) {
        this.owner = owner;
        this.ctx = ctx;
        this.projectDir = ctx.config().projectPath();
        this.diffCards = new DiffCards(ctx.config(), ctx.state());
        this.share = new ShareActions(owner, ctx.config().projectName(), projectDir, publisher, auth, client,
                openPublish, this::vcs, nameField::getText,
                new ShareActions.Host(this::status, this::setBusy, this::refresh));
        build();
        resolveIdentity(auth, client);
    }

    public Node node() {
        return root;
    }

    /** The timeline as drawn, for a test. */
    List<Timeline.Row> rows() {
        return List.copyOf(timeline.getItems());
    }

    /** Types {@code name} and presses Save version, for a test. */
    void saveAs(String name) {
        nameField.setText(name);
        save();
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private void build() {
        nameField.setPromptText("Name this version (optional)");
        nameField.setPrefColumnCount(22);
        nameField.setOnAction(e -> save());
        Button save = new Button("Save version");
        save.setOnAction(e -> save());

        Button refresh = new Button("⟳");
        refresh.setTooltip(new Tooltip("Read the history again"));
        refresh.setOnAction(e -> refresh());

        progress.setPrefSize(16, 16);
        progress.setVisible(false);
        status.getStyleClass().add("dialog-status");
        status.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(6, nameField, save, new Separator(Orientation.VERTICAL));
        bar.getChildren().addAll(share.buttons());
        bar.getChildren().addAll(spacer, progress, status, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        share.provenance().ifPresent(p -> nameField.setTooltip(new Tooltip(p)));

        timeline.setCellFactory(list -> new RowCell());
        timeline.setPlaceholder(new Label("No versions yet."));
        timeline.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> select(now));

        restore.setOnAction(e -> {
            if (shown instanceof Timeline.Version v) restoreTo(v.commit());
        });
        rename.setOnAction(e -> {
            if (shown instanceof Timeline.Version v) name(v.commit());
        });
        detailTitle.getStyleClass().add("dialog-subheading");
        detailTitle.setMinWidth(0);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(6, detailTitle, headerSpacer, rename, restore);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 6, 4, 6));

        files.setShowRoot(false);
        files.setCellFactory(tv -> new FileCell());
        files.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            ChangedFile f = now == null ? null : now.getValue();
            if (f != null && f.isFile()) showDiff(f.path());
        });
        cards.setFitToWidth(true);
        restoreFile.setOnAction(e -> restoreFile());
        Region fileSpacer = new Region();
        HBox.setHgrow(fileSpacer, Priority.ALWAYS);
        HBox fileBar = new HBox(6, fileSpacer, restoreFile);
        fileBar.setPadding(new Insets(4, 6, 0, 6));
        VBox fileView = new VBox(fileBar, cards);
        VBox.setVgrow(cards, Priority.ALWAYS);

        SplitPane detailSplit = new SplitPane(files, fileView);
        detailSplit.setDividerPositions(0.3);
        VBox.setVgrow(detailSplit, Priority.ALWAYS);
        VBox detail = new VBox(header, detailSplit);

        SplitPane split = new SplitPane(timeline, detail);
        split.setDividerPositions(0.32);

        root.setTop(bar);
        root.setCenter(split);
        showNothing();
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    /**
     * Puts the editor's sources on disk, then reads the history and the unsaved files again, off the FX thread.
     * Called when the tab is shown and after every action here.
     */
    public void refresh() {
        ProjectState.Snapshot editor = ctx.state().snapshot();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Checkpoints.flush(editor);
                        ProjectVcs vcs = vcs();
                        return Map.entry(vcs.status().labelled().size(), vcs.history());
                    } catch (Exception ex) {
                        return Map.entry(0, List.<CommitInfo>of());
                    }
                })
                .thenAccept(read -> Platform.runLater(() -> {
                    unsaved = read.getKey();
                    history = read.getValue();
                    rebuild();
                }));
    }

    private void rebuild() {
        Timeline.Row keep = shown;
        List<Timeline.Row> rows = Timeline.rows(history, unsaved, expanded);
        timeline.getItems().setAll(rows);
        Timeline.Row again = keep == null ? null : rows.stream().filter(r -> same(r, keep)).findFirst().orElse(null);
        if (again == null && !rows.isEmpty() && !(rows.getFirst() instanceof Timeline.Fold)) again = rows.getFirst();
        if (again != null) {
            timeline.getSelectionModel().select(again);
        } else {
            showNothing();
        }
    }

    private static boolean same(Timeline.Row a, Timeline.Row b) {
        if (a instanceof Timeline.Unsaved && b instanceof Timeline.Unsaved) return true;
        return a instanceof Timeline.Version va && b instanceof Timeline.Version vb
                && va.commit().sha().equals(vb.commit().sha());
    }

    private void select(Timeline.Row row) {
        switch (row) {
            case null -> { }
            case Timeline.Fold fold -> {
                // A fold is a toggle, not a version: open or close it and keep what was shown.
                if (!expanded.remove(fold.key())) expanded.add(fold.key());
                Platform.runLater(this::rebuild);
            }
            case Timeline.Unsaved u -> {
                shown = u;
                detailTitle.setText("Unsaved changes — " + u.files() + (u.files() == 1 ? " file" : " files"));
                restore.setDisable(true);
                rename.setDisable(true);
                loadFiles(() -> vcs().status().labelled(), true);
            }
            case Timeline.Version v -> {
                shown = v;
                CommitInfo c = v.commit();
                detailTitle.setText(c.title() + " · " + c.origin().displayName() + " · " + WHEN.format(c.when()));
                restore.setDisable(false);
                rename.setDisable(false);
                rename.setText(c.name() == null ? "Name…" : "Rename…");
                loadFiles(() -> vcs().changes(c.sha()), false);
            }
        }
    }

    private void showNothing() {
        shown = null;
        detailTitle.setText("Pick a version to see what it changed.");
        restore.setDisable(true);
        rename.setDisable(true);
        files.setRoot(null);
        clearFile();
    }

    private void clearFile() {
        shownFile = null;
        cards.setContent(null);
        restoreFile.setVisible(false);
    }

    @FunctionalInterface
    private interface Read<T> {
        T read() throws Exception;
    }

    private void loadFiles(Read<SortedMap<String, VcsFileStatus>> read, boolean discardable) {
        clearFile();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return read.read();
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .thenAccept(changed -> Platform.runLater(() -> {
                    files.setRoot(ChangedFile.tree(changed, discardable));
                    files.getRoot().getChildren().stream().findFirst()
                            .ifPresent(first -> files.getSelectionModel().select(firstFile(first)));
                }));
    }

    private static TreeItem<ChangedFile> firstFile(TreeItem<ChangedFile> item) {
        return item.getValue().isFile() || item.getChildren().isEmpty()
                ? item : firstFile(item.getChildren().getFirst());
    }

    /**
     * Reads both sides of {@code path} for the shown row and compares them off the FX thread, then draws the
     * cards on it. A file that cannot be read shows why.
     */
    private void showDiff(String path) {
        Timeline.Row row = shown;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return read(row, path);
                    } catch (Exception ex) {
                        return new DiffCards.Input(path, new VersionReader.Sides(path, path, null, null), null,
                                "Could not read " + path + ": " + ShareActions.rootMessage(ex));
                    }
                })
                .thenAccept(in -> Platform.runLater(() -> {
                    if (row != shown) return;
                    shownFile = in;
                    restoreFile.setVisible(row instanceof Timeline.Version
                            && (in.sides().after() != null || in.sides().before() != null));
                    restoreFile.setText(in.sides().after() == null ? "Bring this file back" : "Restore this file");
                    try {
                        cards.setContent(diffCards.build(in, new CardActions(row, in)));
                    } catch (RuntimeException ex) {
                        // A drawing bug costs the cards, never the diff: the text is always there to show.
                        cards.setContent(diffCards.build(new DiffCards.Input(path, in.sides(), null, in.textDiff()),
                                new CardActions(row, in)));
                    }
                }));
    }

    private DiffCards.Input read(Timeline.Row row, String path) throws Exception {
        VersionReader reader = new VersionReader(projectDir);
        VersionReader.Sides sides;
        String text;
        if (row instanceof Timeline.Version v) {
            sides = reader.version(v.commit().sha(), path);
            text = vcs().diff(v.commit().sha(), path);
        } else {
            sides = reader.unsaved(path);
            text = vcs().diff(path);
        }
        BlockDiff.FileDiff blocks = DiffCards.isJava(path) ? BlockDiff.of(sides.beforeText(), sides.afterText()) : null;
        return new DiffCards.Input(path, sides, blocks, text);
    }

    /** The cards shown, for a test; null while none are. */
    Node cardsNode() {
        return cards.getContent();
    }

    /**
     * What a function card may put back, for the row it is drawn for: a version gives back the function as it
     * left that version (or as it was before, when that version deleted it); the unsaved row gives back the
     * last saved one. Only in the file the editor has open — a function is restored as an edit, and an edit is
     * made to the open file.
     */
    private final class CardActions implements DiffCards.Actions {

        private final Timeline.Row row;
        private final DiffCards.Input in;

        CardActions(Timeline.Row row, DiffCards.Input in) {
            this.row = row;
            this.in = in;
        }

        private String source(BlockDiff.MethodChange change) {
            boolean takeBefore = row instanceof Timeline.Unsaved || change.kind() == BlockDiff.Mark.REMOVED;
            if (row instanceof Timeline.Unsaved && change.kind() == BlockDiff.Mark.ADDED) return null;
            return takeBefore ? in.sides().beforeText() : in.sides().afterText();
        }

        @Override
        public String restoreLabel(BlockDiff.MethodChange change) {
            if (source(change) == null || !isOpen(in.path()) || ctx.codeEditorService() == null) return null;
            boolean present = ctx.state().getCompilationUnit()
                    .map(cu -> BlockDiff.methods(cu).containsKey(change.signature())).orElse(false);
            return present ? "Restore this function" : "Add this function back";
        }

        @Override
        public void restoreFunction(BlockDiff.MethodChange change) {
            String source = source(change);
            if (source == null || ctx.codeEditorService() == null) return;
            ctx.codeEditorService().getCodeEditor().replaceMethod(source, change.signature());
            status("Put " + change.name() + "() back — ↶ undoes it.");
            Platform.runLater(VersionsPane.this::refresh);
        }
    }

    private boolean isOpen(String path) {
        var active = ctx.state().getActiveFile();
        return active != null && active.getPath() != null
                && active.getPath().toAbsolutePath().normalize().equals(projectDir.resolve(path).normalize());
    }

    /**
     * <i>Restore this file</i>: the open file is replaced as one edit; any other is written after a safety
     * version of everything, and the project reloads ({@code 39} §6).
     */
    private void restoreFile() {
        DiffCards.Input in = shownFile;
        if (in == null || !(shown instanceof Timeline.Version)) return;
        byte[] bytes = in.sides().after() != null ? in.sides().after() : in.sides().before();
        if (bytes == null) return;
        if (isOpen(in.path()) && DiffCards.isJava(in.path()) && ctx.codeEditorService() != null) {
            ctx.codeEditorService().getCodeEditor().replaceFile(new String(bytes, StandardCharsets.UTF_8));
            status("Restored " + in.path() + " — ↶ undoes it.");
            Platform.runLater(this::refresh);
            return;
        }
        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> {
            Checkpoints.save(projectDir, editor, VersionOrigin.SAFETY, "Before restoring " + in.path());
            Path target = projectDir.resolve(in.path());
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return null;
        }, ignored -> {
            status("Restored " + in.path() + ".");
            ctx.eventBus().publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
        });
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void save() {
        String label = nameField.getText() == null ? "" : nameField.getText().strip();
        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> Checkpoints.save(projectDir, editor, VersionOrigin.SAVE, label), sha -> {
            nameField.clear();
            if (sha != null) shown = null; // the refresh then shows the version just saved

            status(sha == null ? "Nothing to save — no change since the last version."
                    : "Saved" + (label.isEmpty() ? "." : " “" + label + "”."));
        });
    }

    private void restoreTo(CommitInfo target) {
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                "Put the whole project back as it was at “" + target.title() + "” ("
                        + WHEN.format(target.when()) + ")?\n\nWhat you have now is saved first as a version, so "
                        + "nothing is lost — you can come back to it from this list. The project reloads.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setHeaderText("Restore the project to this version?");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> {
            Checkpoints.flush(editor);
            vcs().restoreTo(target.sha());
            return null;
        }, ignored -> {
            status("Restored to “" + target.title() + "”.");
            ctx.eventBus().publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
        });
    }

    private void name(CommitInfo target) {
        TextInputDialog dialog = new TextInputDialog(target.name() == null ? "" : target.name());
        ThemedWindows.apply(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Name this version");
        dialog.setHeaderText("A name makes this version a milestone in the list.");
        dialog.setContentText("Name (empty to remove):");
        dialog.showAndWait().ifPresent(text -> run(() -> {
            vcs().name(target.sha(), text);
            return null;
        }, ignored -> status(text.isBlank() ? "Name removed." : "Named “" + text.strip() + "”.")));
    }

    private void discard(ChangedFile file) {
        boolean isNew = file.status().uncommitted();
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                (isNew ? "Delete the new file “" : "Discard your changes to “") + file.path() + "”?"
                        + (isNew ? "" : "\n\nIt goes back to how the last version had it."),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setHeaderText(isNew ? "Delete new file?" : "Discard changes?");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        run(() -> {
            if (isNew) {
                Files.deleteIfExists(projectDir.resolve(file.path()));
            } else {
                vcs().discard(file.path());
            }
            return null;
        }, ignored -> {
            status((isNew ? "Deleted " : "Discarded changes to ") + file.path() + ".");
            ctx.eventBus().publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
        });
    }

    // -------------------------------------------------------------------------
    // Plumbing
    // -------------------------------------------------------------------------

    private ProjectVcs vcs() {
        return new ProjectVcs(projectDir, authorName, authorEmail);
    }

    private void resolveIdentity(GitHubAuth auth, GitHubClient client) {
        if (auth == null || client == null || !auth.isAuthenticated()) return;
        auth.login(client).thenAccept(login -> {
            if (login != null && !login.isBlank()) {
                authorName = login;
                authorEmail = login + "@users.noreply.github.com";
            }
        });
    }

    private <T> void run(Read<T> action, Consumer<T> onSuccess) {
        setBusy(true);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return action.read();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((result, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        status("Failed: " + ShareActions.rootMessage(err));
                    } else {
                        onSuccess.accept(result);
                        refresh();
                    }
                }));
    }

    private void setBusy(boolean busy) {
        progress.setVisible(busy);
    }

    private void status(String text) {
        status.setText(text);
    }

    /** The mark beside a version: what kind it is, at a glance. */
    static String glyph(CommitInfo c) {
        if (c.name() != null) return "★";
        return switch (c.origin()) {
            case SAVE -> "★";
            case AI -> "✦";
            case UPDATE -> "↓";
            case PUBLISH -> "⇪";
            case INSTALL, CREATE -> "◆";
            case RESTORE -> "↺";
            case AUTO, SAFETY, UNKNOWN -> "•";
        };
    }

    /** "5 min ago", "3 h ago", else the date: how long ago matters more than when, for recent versions. */
    static String ago(Instant when, Instant now) {
        Duration d = Duration.between(when, now);
        if (d.toMinutes() < 1) return "just now";
        if (d.toHours() < 1) return d.toMinutes() + " min ago";
        if (d.toDays() < 1) return d.toHours() + " h ago";
        return WHEN.format(when);
    }

    // -------------------------------------------------------------------------
    // Cells
    // -------------------------------------------------------------------------

    private final class RowCell extends ListCell<Timeline.Row> {
        @Override
        protected void updateItem(Timeline.Row row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            setContextMenu(null);
            setTooltip(null);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }
            switch (row) {
                case Timeline.Unsaved u -> {
                    Label glyph = new Label("●");
                    glyph.getStyleClass().addAll("versions-glyph", "versions-unsaved");
                    Label title = new Label("Unsaved changes (" + u.files() + ")");
                    title.getStyleClass().addAll("versions-title", "versions-unsaved");
                    setGraphic(line(glyph, title, null));
                }
                case Timeline.Fold f -> {
                    Label title = new Label((f.expanded() ? "▾ " : "▸ ") + f.label());
                    title.getStyleClass().add("versions-fold");
                    setGraphic(title);
                }
                case Timeline.Version v -> {
                    CommitInfo c = v.commit();
                    Label glyph = new Label(glyph(c));
                    glyph.getStyleClass().add("versions-glyph");
                    Label title = new Label(c.title());
                    title.getStyleClass().add("versions-title");
                    if (c.milestone()) title.getStyleClass().add("versions-title--milestone");
                    String tags = c.tags().isEmpty() ? "" : " · " + String.join(", ", c.tags());
                    Label meta = new Label(c.origin().displayName() + " · " + ago(c.when(), Instant.now()) + tags);
                    meta.getStyleClass().add("versions-meta");
                    HBox line = line(glyph, title, meta);
                    if (v.quiet()) line.getStyleClass().add("versions-quiet");
                    setGraphic(line);
                    setTooltip(new Tooltip(c.message() + "\n" + c.shortSha() + " · " + c.author()));
                    setContextMenu(versionMenu(c));
                }
            }
        }

        private HBox line(Label glyph, Label title, Label meta) {
            VBox text = meta == null ? new VBox(title) : new VBox(1, title, meta);
            HBox box = new HBox(6, glyph, text);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }

        private ContextMenu versionMenu(CommitInfo c) {
            MenuItem restoreItem = new MenuItem("Restore project to here…");
            restoreItem.setOnAction(e -> restoreTo(c));
            MenuItem nameItem = new MenuItem(c.name() == null ? "Name this version…" : "Rename this version…");
            nameItem.setOnAction(e -> name(c));
            return new ContextMenu(restoreItem, nameItem);
        }
    }

    /** A node of the files tree: a directory (a path segment) or a changed file with how it changed. */
    record ChangedFile(String label, String path, VcsFileStatus status, boolean discardable) {

        boolean isFile() {
            return path != null;
        }

        /** The tree of {@code changed}, grouped by directory; a one-line note when it is empty or unreadable. */
        static TreeItem<ChangedFile> tree(SortedMap<String, VcsFileStatus> changed, boolean discardable) {
            TreeItem<ChangedFile> root = new TreeItem<>(new ChangedFile("", null, null, false));
            root.setExpanded(true);
            if (changed == null || changed.isEmpty()) {
                String note = changed == null ? "Could not read this version." : "No file changed.";
                root.getChildren().add(new TreeItem<>(new ChangedFile(note, null, null, false)));
                return root;
            }
            changed.forEach((path, status) -> insert(root, path, status, discardable));
            return root;
        }

        private static void insert(TreeItem<ChangedFile> root, String path, VcsFileStatus status, boolean discardable) {
            String[] segments = path.split("/");
            TreeItem<ChangedFile> node = root;
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                if (i == segments.length - 1) {
                    node.getChildren().add(new TreeItem<>(new ChangedFile(segment, path, status, discardable)));
                    return;
                }
                TreeItem<ChangedFile> dir = node.getChildren().stream()
                        .filter(c -> !c.getValue().isFile() && c.getValue().label().equals(segment))
                        .findFirst().orElse(null);
                if (dir == null) {
                    dir = new TreeItem<>(new ChangedFile(segment, null, null, false));
                    dir.setExpanded(true);
                    node.getChildren().add(dir);
                }
                node = dir;
            }
        }
    }

    private final class FileCell extends TreeCell<ChangedFile> {
        @Override
        protected void updateItem(ChangedFile item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(null);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (!item.isFile()) {
                setText(item.label());
                setGraphic(null);
                return;
            }
            Label name = new Label(item.label());
            Label tag = new Label(item.status().label());
            tag.setStyle("-fx-text-fill: " + item.status().color() + "; -fx-font-size: 10px;");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(6, name, spacer, tag);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
            if (item.discardable()) {
                MenuItem discardItem = new MenuItem(item.status().uncommitted()
                        ? "Delete this new file…" : "Discard changes…");
                discardItem.setOnAction(e -> discard(item));
                setContextMenu(new ContextMenu(discardItem));
            }
        }
    }
}
