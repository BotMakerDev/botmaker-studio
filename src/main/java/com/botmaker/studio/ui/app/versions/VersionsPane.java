package com.botmaker.studio.ui.app.versions;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.ProjectVcs.CommitInfo;
import com.botmaker.studio.project.vcs.VcsFileStatus;
import com.botmaker.studio.project.vcs.VersionOrigin;
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
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
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
 * the user's behalf folded — and, for the selected row, the files it changed and their Java diff (4c draws
 * them as blocks). Right-click a version to restore the project to it or to name it.
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
    private final TreeView<ChangedFile> files = new TreeView<>();
    private final TextArea diff = new TextArea();
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
        diff.setEditable(false);
        diff.getStyleClass().add("console-area");
        diff.setStyle("-fx-font-family: monospace;");

        SplitPane detailSplit = new SplitPane(files, diff);
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
        diff.clear();
    }

    @FunctionalInterface
    private interface Read<T> {
        T read() throws Exception;
    }

    private void loadFiles(Read<SortedMap<String, VcsFileStatus>> read, boolean discardable) {
        diff.clear();
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

    private void showDiff(String path) {
        Timeline.Row row = shown;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return row instanceof Timeline.Version v ? vcs().diff(v.commit().sha(), path) : vcs().diff(path);
                    } catch (Exception ex) {
                        return "Could not diff " + path + ": " + ShareActions.rootMessage(ex);
                    }
                })
                .thenAccept(text -> Platform.runLater(() -> {
                    if (row != shown) return;
                    diff.setText(text == null || text.isBlank()
                            ? "(no text to compare — a new, binary or unchanged file)" : text);
                }));
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void save() {
        String label = nameField.getText() == null ? "" : nameField.getText().strip();
        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> Checkpoints.save(projectDir, editor, VersionOrigin.SAVE, label), sha -> {
            nameField.clear();
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
