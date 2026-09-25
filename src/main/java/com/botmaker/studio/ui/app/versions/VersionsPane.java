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
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.MyCopy;
import com.botmaker.studio.sharing.PublishRequest;
import com.botmaker.studio.sharing.Suggestion;
import com.botmaker.studio.util.BrowserLauncher;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.geometry.Orientation;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
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

import java.io.IOException;
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
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Versions tab, Simple view ({@code docs/refactor/39-versions.md} §4): <i>Save version</i> over a timeline
 * of the project's versions — the unsaved changes pinned first, milestones bold, the versions Studio took on
 * the user's behalf folded — and, for the selected row, the files it changed, each drawn as {@link DiffCards}:
 * its changed functions as blocks, Before | After, with <i>Restore this function</i> and <i>Restore this
 * file</i> (§5–§6). Right-click a version to restore the project to it or to name it. <i>Publish…</i> opens
 * {@link PublishSheet} on the tab's right (§8).
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
    private final GitHubAuth auth;
    private final GitHubClient client;
    /** The publish sheet, made the first time it is opened; shown on the tab's right while open. */
    private PublishSheet publishSheet;

    private final BorderPane root = new BorderPane();
    private final TextField nameField = new TextField();
    private final Label here = new Label();
    private final Label myCopy = new Label();
    private final Label original = new Label();
    private final HBox myCopySegment = segment("☁", "My copy", myCopy);
    private final HBox originalSegment = segment("★", "Original", original);
    private final Button mainAction = new Button();
    private final HBox alsoActions = new HBox(6);
    /** The strip as last read; LOCAL_ONLY with nothing known until the first refresh. */
    private SyncModel model = SyncModel.of(SyncModel.Facts.none());
    /** A newer release of the original, asked of the network once per pane and again on ⟳. */
    private volatile String available;
    private volatile boolean askOriginal = true;
    /** A zip install is linked to its original once per pane at most; a failure is retried next time. */
    private volatile boolean attachTried;
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

    // Simple | Dev (39 §9)
    private VersionsView view = VersionsView.remembered();
    private final ToggleButton simpleToggle = new ToggleButton(VersionsView.SIMPLE.displayName());
    private final ToggleButton devToggle = new ToggleButton(VersionsView.DEV.displayName());
    private final Button saveButton = new Button("Save version");
    /** Dev's commit box: a free message, first line the title. */
    private final TextArea message = new TextArea();
    /** Simple says which branch only when it is not main — Simple never switches. */
    private final Label onBranch = new Label();
    private final HBox devBar = new HBox(6);
    private final ComboBox<String> branchBox = new ComboBox<>();
    private final ComboBox<ProjectVcs.RemoteInfo> remoteBox = new ComboBox<>();
    private final Button discardFile = new Button("Discard…");
    /** The Terminal tab, which is the window's: set by the shell. */
    private Runnable openTerminal = () -> { };
    private String branch;
    /** The changed file whose diff is shown, for Dev's Discard. */
    private ChangedFile shownChanged;
    /** Set while the branch list is redrawn, so the redraw is not taken for a switch. */
    private boolean drawingBranches;

    private final Set<String> expanded = new HashSet<>();
    private List<CommitInfo> history = List.of();
    private int unsaved;
    /** The row whose files are shown; kept across a refresh when it still exists. */
    private Timeline.Row shown;

    // Commit author identity — the signed-in GitHub login once resolved.
    private volatile String authorName;
    private volatile String authorEmail;

    public VersionsPane(Window owner, StudioContext ctx, GitHubAuth auth, GitHubClient client) {
        this.owner = owner;
        this.ctx = ctx;
        this.projectDir = ctx.config().projectPath();
        this.diffCards = new DiffCards(ctx.config(), ctx.state());
        this.auth = auth;
        this.client = client;
        this.share = new ShareActions(owner, projectDir, auth, client, this::status);
        build();
        resolveIdentity();
    }

    /** The strip's model as last read, for a test. */
    SyncModel model() {
        return model;
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
        Button save = saveButton;
        save.setOnAction(e -> save());
        message.setPromptText("Commit message — the first line is the title");
        message.setPrefRowCount(2);
        message.setPrefColumnCount(40);
        message.setWrapText(true);
        onBranch.getStyleClass().add("versions-meta");

        ToggleGroup views = new ToggleGroup();
        simpleToggle.setToggleGroup(views);
        devToggle.setToggleGroup(views);
        (view == VersionsView.DEV ? devToggle : simpleToggle).setSelected(true);
        views.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                // A toggle group lets its selection be clicked off; one view is always shown.
                was.setSelected(true);
                return;
            }
            showView(now == devToggle ? VersionsView.DEV : VersionsView.SIMPLE);
        });
        HBox viewSwitch = new HBox(0, simpleToggle, devToggle);
        viewSwitch.getStyleClass().add("versions-view-switch");
        simpleToggle.getStyleClass().add("versions-view-first");
        devToggle.getStyleClass().add("versions-view-last");
        simpleToggle.setTooltip(new Tooltip("Versions as saves, with Studio's own folded away"));
        devToggle.setTooltip(new Tooltip("Every commit, text diffs, a commit box, branches and remotes"));

        Button refresh = new Button("⟳");
        refresh.setTooltip(new Tooltip("Read the history again, and ask the original for a newer release"));
        refresh.setOnAction(e -> {
            askOriginal = true;
            refresh();
        });

        progress.setPrefSize(16, 16);
        progress.setVisible(false);
        status.getStyleClass().add("dialog-status");
        status.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // The strip (39 §2): where the bot lives, left to right, and the one button that names where it goes.
        mainAction.getStyleClass().add("primary-button");
        Region stripSpacer = new Region();
        HBox.setHgrow(stripSpacer, Priority.ALWAYS);
        HBox strip = new HBox(14, segment("💻", "This computer", here), myCopySegment, originalSegment,
                stripSpacer, mainAction, alsoActions, viewSwitch);
        strip.getStyleClass().add("versions-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.setPadding(new Insets(6, 6, 0, 6));
        alsoActions.setAlignment(Pos.CENTER_LEFT);

        HBox bar = new HBox(6, nameField, message, save, onBranch, spacer, progress, status, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        buildDevBar();
        share.provenance().ifPresent(p -> original.setTooltip(new Tooltip(p)));
        showModel();

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
            if (f != null && f.isFile()) {
                shownChanged = f;
                show(discardFile, view == VersionsView.DEV && f.discardable());
                showDiff(f.path());
            }
        });
        cards.setFitToWidth(true);
        restoreFile.setOnAction(e -> restoreFile());
        Region fileSpacer = new Region();
        HBox.setHgrow(fileSpacer, Priority.ALWAYS);
        discardFile.setOnAction(e -> {
            if (shownChanged != null) discard(shownChanged);
        });
        HBox fileBar = new HBox(6, fileSpacer, discardFile, restoreFile);
        fileBar.setPadding(new Insets(4, 6, 0, 6));
        VBox fileView = new VBox(fileBar, cards);
        VBox.setVgrow(cards, Priority.ALWAYS);

        SplitPane detailSplit = new SplitPane(files, fileView);
        detailSplit.setDividerPositions(0.3);
        VBox.setVgrow(detailSplit, Priority.ALWAYS);
        VBox detail = new VBox(header, detailSplit);

        SplitPane split = new SplitPane(timeline, detail);
        split.setDividerPositions(0.32);

        root.setTop(new VBox(strip, bar, devBar));
        root.setCenter(split);
        showNothing();
        applyView();
    }

    /**
     * Dev's second row: the branch and its verbs, a remote with how the branch stands there and its verbs, and
     * a shell in the project ({@code 39} §9).
     */
    private void buildDevBar() {
        branchBox.setTooltip(new Tooltip("The branch you are on — pick another to switch to it"));
        branchBox.setOnAction(e -> {
            String picked = branchBox.getValue();
            if (!drawingBranches && picked != null && !picked.equals(branch)) switchBranch(picked);
        });
        Button newBranch = new Button("New branch…");
        newBranch.setOnAction(e -> newBranch());
        Button merge = new Button("Merge…");
        merge.setTooltip(new Tooltip("Merge another branch into this one"));
        merge.setOnAction(e -> mergeBranch());
        Button delete = new Button("Delete…");
        delete.setOnAction(e -> deleteBranch());

        remoteBox.setPromptText("No remote");
        remoteBox.setCellFactory(list -> new RemoteCell());
        remoteBox.setButtonCell(new RemoteCell());
        Button fetch = new Button("Fetch");
        fetch.setOnAction(e -> withRemote(this::fetch));
        Button pull = new Button("Pull");
        pull.setTooltip(new Tooltip("Fetch, then merge the remote's branch of the same name into this one"));
        pull.setOnAction(e -> withRemote(this::pull));
        Button push = new Button("Push");
        push.setOnAction(e -> withRemote(this::push));
        Button addRemote = new Button("Add remote…");
        addRemote.setOnAction(e -> addRemote());
        Button terminal = new Button("Open in terminal");
        terminal.setTooltip(new Tooltip("A shell in " + projectDir));
        terminal.setOnAction(e -> openTerminal.run());

        Label branchLabel = new Label("Branch");
        branchLabel.getStyleClass().add("versions-meta");
        Label remoteLabel = new Label("Remote");
        remoteLabel.getStyleClass().add("versions-meta");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        devBar.getChildren().setAll(branchLabel, branchBox, newBranch, merge, delete, new Separator(Orientation.VERTICAL),
                remoteLabel, remoteBox, fetch, pull, push, addRemote, gap, terminal);
        devBar.setAlignment(Pos.CENTER_LEFT);
        devBar.setPadding(new Insets(0, 6, 6, 6));
    }

    /** Where <i>Open in terminal</i> goes: the Terminal tab, which the shell owns. */
    public void setOnOpenTerminal(Runnable openTerminal) {
        this.openTerminal = openTerminal == null ? () -> { } : openTerminal;
    }

    /** The view shown, for a test. */
    VersionsView view() {
        return view;
    }

    /** Switches to {@code chosen}, remembers it for this user, and redraws. */
    private void showView(VersionsView chosen) {
        if (chosen == view) return;
        VersionsView.remember(chosen);
        display(chosen);
    }

    /** Draws {@code chosen} without remembering it — {@link #showView}'s half a test may call. */
    void display(VersionsView chosen) {
        view = chosen;
        (chosen == VersionsView.DEV ? devToggle : simpleToggle).setSelected(true);
        applyView();
        shown = null;
        rebuild();
    }

    private void applyView() {
        boolean dev = view == VersionsView.DEV;
        show(nameField, !dev);
        show(message, dev);
        show(devBar, dev);
        saveButton.setText(dev ? "Commit" : "Save version");
        boolean offMain = branch != null && !"main".equals(branch);
        onBranch.setText(branch == null ? "" : "On " + branch);
        show(onBranch, !dev && offMain);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static HBox segment(String glyph, String place, Label value) {
        Label mark = new Label(glyph);
        mark.getStyleClass().add("versions-glyph");
        Label name = new Label(place);
        name.getStyleClass().add("versions-meta");
        value.getStyleClass().add("versions-title");
        HBox box = new HBox(6, mark, new VBox(0, name, value));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Draws {@link #model}: the three places and the buttons ownership decides. */
    private void showModel() {
        here.setText(model.thisComputer());
        String copy = model.myCopy();
        myCopySegment.setVisible(copy != null);
        myCopySegment.setManaged(copy != null);
        myCopy.setText(copy == null ? "" : copy);
        model.mineSlug().ifPresent(s -> myCopy.setTooltip(new Tooltip("github.com/" + s)));
        String orig = model.original();
        originalSegment.setVisible(orig != null);
        originalSegment.setManaged(orig != null);
        original.setText(orig == null ? "" : orig);

        SyncModel.Action main = model.main();
        mainAction.setText(model.label(main));
        mainAction.setOnAction(e -> act(main));
        // Save version has its own button beside the name field; the strip does not repeat it.
        mainAction.setVisible(main != SyncModel.Action.SAVE_VERSION);
        mainAction.setManaged(main != SyncModel.Action.SAVE_VERSION);
        alsoActions.getChildren().clear();
        for (SyncModel.Action a : model.also()) {
            Button b = new Button(model.label(a));
            b.setOnAction(e -> act(a));
            alsoActions.getChildren().add(b);
        }
    }

    private void act(SyncModel.Action action) {
        switch (action) {
            case SAVE_VERSION -> save();
            case PUBLISH -> openPublish();
            case SAVE_TO_MY_COPY -> saveToMyCopy();
            case SIGN_IN -> share.signIn(() -> {
                resolveIdentity();
                refresh();
            });
            case GET_UPDATE -> getUpdate(model.facts().availableTag());
            case FINISH_UPDATE -> resumeUpdate();
            case SUGGEST -> suggest();
        }
    }

    // -------------------------------------------------------------------------
    // Publishing (39 §8)
    // -------------------------------------------------------------------------

    /** Opens the publish sheet on the tab's right — <i>Publish…</i> here, and <i>Project ▸ Publish…</i>. */
    public void openPublish() {
        if (publishSheet == null) {
            publishSheet = new PublishSheet(owner, ctx.config(), auth, client,
                    new GitHubGallery(client, auth), new BotPublisher(client, auth), new Publishing());
        }
        root.setRight(publishSheet.node());
        publishSheet.shown();
    }

    /** Whether the publish sheet is showing, for a test. */
    boolean publishing() {
        return publishSheet != null && root.getRight() == publishSheet.node();
    }

    /** The tab's side of a publish: the version it publishes is saved here, like every other. */
    private final class Publishing implements PublishSheet.Host {

        /**
         * Writes the provenance file first, so the version published names its own release, then saves the
         * project as a {@code PUBLISH} version. An unchanged project publishes the version it is at.
         */
        @Override
        public java.util.concurrent.Callable<ProjectVcs> prepare(PublishRequest request, String login) {
            ProjectState.Snapshot editor = ctx.state().snapshot();
            return () -> {
                new BotSource(login, request.repoName(), request.version()).write(projectDir);
                Checkpoints.save(projectDir, editor, VersionOrigin.PUBLISH, request.version() + " published");
                return vcs();
            };
        }

        @Override
        public SyncModel model() {
            return model;
        }

        @Override
        public void changed() {
            shown = null;
            refresh();
        }

        @Override
        public void close() {
            root.setRight(null);
        }
    }

    // -------------------------------------------------------------------------
    // Updates and suggestions (39 §7)
    // -------------------------------------------------------------------------

    /**
     * <i>Get vX.Y</i>: a {@code SAFETY} version of what the user has, the tag fetched (a moved or missing one
     * refuses), then a real merge — committed as {@code UPDATE} when git could merge every file, else the
     * conflict sheet.
     */
    private void getUpdate(String tag) {
        if (tag == null) return;
        String token = auth != null && auth.isAuthenticated() ? auth.token() : null;
        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> {
            Checkpoints.save(projectDir, editor, VersionOrigin.SAFETY, "Before updating to " + tag);
            ProjectVcs vcs = vcs();
            vcs.fetchTag(Remote.ORIGINAL, tag, token);
            return vcs.mergeTag(tag);
        }, merge -> {
            if (merge.upToDate()) {
                status("This project already has " + tag + ".");
            } else if (merge.conflicted()) {
                Platform.runLater(() -> decide(merge.conflicts(), tag));
            } else {
                finishUpdate(tag, Map.of());
            }
        });
    }

    /** Reopens the sheet of an update Studio was closed in the middle of. */
    private void resumeUpdate() {
        run(() -> {
            ProjectVcs vcs = vcs();
            return Map.entry(vcs.unresolved(), Optional.ofNullable(vcs.mergeRelease()).orElse("the new release"));
        }, read -> Platform.runLater(() -> {
            if (read.getKey().isEmpty()) {
                finishUpdate(read.getValue(), Map.of());
            } else {
                decide(read.getKey(), read.getValue());
            }
        }));
    }

    private void decide(List<String> conflicts, String release) {
        decide(conflicts, release, decided -> finishUpdate(release, decided));
    }

    /** The conflict sheet over {@code conflicts}; {@code finish} gets the decisions, a cancel aborts the merge. */
    private void decide(List<String> conflicts, String theirs, Consumer<Map<String, ProjectVcs.Side>> finish) {
        ConflictSheet.show(owner, diffCards, projectDir, conflicts, "MERGE_HEAD", theirs).ifPresentOrElse(
                finish,
                () -> run(() -> {
                    vcs().abortMerge();
                    return null;
                }, done -> status("Merge cancelled — nothing changed.")));
    }

    /** Applies the decisions, records the release in the provenance file and commits the {@code UPDATE}. */
    private void finishUpdate(String release, Map<String, ProjectVcs.Side> decided) {
        run(() -> {
            ProjectVcs vcs = vcs();
            for (var e : decided.entrySet()) vcs.resolve(e.getKey(), e.getValue());
            Optional<BotSource> source = BotSource.read(projectDir);
            // A release is a tag; a resumed update whose commit no tag names leaves the record as it was.
            if (source.isPresent() && !release.matches("[0-9a-f]{7}|the new release")) {
                new BotSource(source.get().owner(), source.get().repo(), release).write(projectDir);
            }
            String by = source.map(s -> " (" + s.owner() + ")").orElse("");
            vcs.finishMerge("Updated to " + release + by);
            return null;
        }, done -> {
            askOriginal = true;
            shown = null;
            status("Updated to " + release + ".");
            ctx.eventBus().publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
        });
    }

    /**
     * <i>Suggest to author…</i>: asks what the change does, saves, and opens (or finds) the pull request from
     * {@code suggest/<title>} on the user's copy ({@link Suggestion}).
     */
    private void suggest() {
        if (client == null || authorName == null) {
            status("Sign in to GitHub to suggest a change.");
            return;
        }
        TextInputDialog ask = new TextInputDialog(nameField.getText() == null ? "" : nameField.getText().strip());
        ThemedWindows.apply(ask);
        ask.initOwner(owner);
        ask.setTitle("Suggest to author");
        ask.setHeaderText("Your versions go to your copy, and the author is asked to take them.");
        ask.setContentText("What does your change do?");
        Optional<String> title = ask.showAndWait().map(String::strip).filter(t -> !t.isEmpty());
        if (title.isEmpty()) return;
        ProjectState.Snapshot editor = ctx.state().snapshot();
        SyncModel now = model;
        String token = auth.token();
        run(() -> {
            Checkpoints.save(projectDir, editor, VersionOrigin.SAVE, title.get());
            String url = new Suggestion(client).suggest(vcs(), now, ctx.config().projectName(), title.get(),
                    "Suggested from BotMaker Studio.", token);
            if (!url.isBlank()) Platform.runLater(() -> BrowserLauncher.open(url));
            return url;
        }, url -> {
            nameField.clear();
            shown = null;
            status(url.isBlank() ? "Suggestion sent." : "Suggestion ready: " + url);
        });
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
                        ProjectVcs vcs = vcs();
                        // Mid-update the disk holds the merge; the editor's older sources must not land on it.
                        if (!vcs.merging()) Checkpoints.flush(editor);
                        SyncModel read = sync(vcs);
                        return new Snapshot(read, vcs.history(), vcs.isRepo() ? vcs.branch() : null,
                                vcs.branches(), vcs.remotes());
                    } catch (Exception ex) {
                        return new Snapshot(SyncModel.of(SyncModel.Facts.none()), List.of(), null, List.of(),
                                List.of());
                    }
                })
                .thenAccept(read -> Platform.runLater(() -> {
                    model = read.model();
                    unsaved = model.facts().unsaved();
                    history = read.history();
                    branch = read.branch();
                    showModel();
                    showBranches(read.branches(), read.remotes());
                    applyView();
                    rebuild();
                }));
    }

    /** What one refresh read: the strip, the history, and Dev's branches and remotes. */
    private record Snapshot(SyncModel model, List<CommitInfo> history, String branch, List<String> branches,
                            List<ProjectVcs.RemoteInfo> remotes) {}

    private void showBranches(List<String> branches, List<ProjectVcs.RemoteInfo> remotes) {
        drawingBranches = true;
        try {
            branchBox.getItems().setAll(branches);
            branchBox.setValue(branch);
        } finally {
            drawingBranches = false;
        }
        String picked = remoteBox.getValue() == null ? null : remoteBox.getValue().name();
        remoteBox.getItems().setAll(remotes);
        remotes.stream().filter(r -> r.name().equals(picked)).findFirst()
                .or(() -> remotes.stream().filter(r -> r.name().equals(Remote.MINE.id())).findFirst())
                .or(() -> remotes.stream().findFirst())
                .ifPresent(remoteBox::setValue);
    }

    /**
     * Reads the three places off git (and, once per pane, the original's releases off the network). A project
     * installed from a zip is linked to its original here, the first time the strip needs it ({@code 39} §7).
     * Off the FX thread.
     */
    private SyncModel sync(ProjectVcs vcs) throws Exception {
        vcs.adoptLegacyBackup();
        boolean updating = vcs.merging();
        Optional<BotSource> source = BotSource.read(projectDir);
        String token = auth != null && auth.isAuthenticated() ? auth.token() : null;
        String originalUrl = vcs.remoteUrl(Remote.ORIGINAL);
        if (originalUrl == null && source.isPresent()) {
            originalUrl = BotInstaller.cloneUrl(source.get().owner(), source.get().repo());
            if (!attachTried && !updating) {
                attachTried = true;
                try {
                    vcs.attach(originalUrl, source.get().tag(),
                            "Linked to " + source.get().slug() + " " + source.get().tag(), token);
                } catch (IOException e) {
                    attachTried = false;
                    System.err.println("Versions: could not link to the original yet: " + e.getMessage());
                }
            }
        }
        String installed = source.map(BotSource::tag).orElse(null);
        if (askOriginal && vcs.remoteUrl(Remote.ORIGINAL) != null) {
            askOriginal = false;
            try {
                available = SyncModel.newest(vcs.remoteTags(Remote.ORIGINAL, token), installed);
            } catch (IOException e) {
                System.err.println("Versions: " + e.getMessage());
            }
        }
        int unsavedNow = vcs.status().labelled().size();
        SyncModel.Facts facts = new SyncModel.Facts(authorName, vcs.remoteUrl(Remote.MINE), originalUrl,
                unsavedNow, -1, installed, available, updating);
        SyncModel first = SyncModel.of(facts);
        if (facts.mineUrl() == null) return first;
        int notIn = vcs.notIn(Remote.MINE, first.remoteBranch(vcs.branch()));
        return SyncModel.of(new SyncModel.Facts(facts.login(), facts.mineUrl(), originalUrl, unsavedNow, notIn,
                installed, available, updating));
    }

    private void rebuild() {
        Timeline.Row keep = shown;
        List<Timeline.Row> rows = view == VersionsView.DEV
                ? Timeline.all(history, unsaved)
                : Timeline.rows(history, unsaved, expanded);
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
        shownChanged = null;
        cards.setContent(null);
        restoreFile.setVisible(false);
        show(discardFile, false);
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
        // Dev reads the unified Java diff; Simple reads blocks (39 §9).
        boolean asBlocks = view != VersionsView.DEV && DiffCards.isJava(path);
        BlockDiff.FileDiff blocks = asBlocks ? BlockDiff.of(sides.beforeText(), sides.afterText()) : null;
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
        boolean dev = view == VersionsView.DEV;
        // Dev's box is a whole commit message; its first line is the title the timeline shows.
        String typed = dev ? message.getText() : nameField.getText();
        String label = typed == null ? "" : typed.strip();
        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> Checkpoints.save(projectDir, editor, VersionOrigin.SAVE, label), sha -> {
            nameField.clear();
            message.clear();
            if (sha != null) shown = null; // the refresh then shows the version just saved

            status(sha == null ? "Nothing to save — no change since the last version."
                    : dev ? "Committed " + sha + "."
                    : "Saved" + (label.isEmpty() ? "." : " “" + label + "”."));
        });
    }

    // -------------------------------------------------------------------------
    // Dev: branches and remotes (39 §9)
    // -------------------------------------------------------------------------

    private String token() {
        return auth != null && auth.isAuthenticated() ? auth.token() : null;
    }

    /** Switches branch: an {@code AUTO} version of what is unsaved first, then the checkout, then a reload. */
    private void switchBranch(String name) {
        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> {
            Checkpoints.save(projectDir, editor, VersionOrigin.AUTO, "Before switching to " + name);
            vcs().switchTo(name);
            return null;
        }, done -> {
            shown = null;
            status("On " + name + ".");
            ctx.eventBus().publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
        });
    }

    private void newBranch() {
        ask("New branch", "A branch starts here, at the current version, and you switch to it.", "Name:", "")
                .ifPresent(name -> {
                    ProjectState.Snapshot editor = ctx.state().snapshot();
                    run(() -> {
                        Checkpoints.save(projectDir, editor, VersionOrigin.AUTO, "Before branching " + name);
                        ProjectVcs vcs = vcs();
                        vcs.createBranch(name);
                        vcs.switchTo(name);
                        return null;
                    }, done -> status("On the new branch " + name + "."));
                });
    }

    private Optional<String> pickOtherBranch(String title, String header) {
        List<String> others = branchBox.getItems().stream().filter(b -> !b.equals(branch)).toList();
        if (others.isEmpty()) {
            status("There is no other branch.");
            return Optional.empty();
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(others.getFirst(), others);
        ThemedWindows.apply(dialog);
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText("Branch:");
        return dialog.showAndWait();
    }

    /** Merges another branch into this one — the §7 merge and conflict sheet, committed as a {@code SAVE}. */
    private void mergeBranch() {
        pickOtherBranch("Merge a branch", "Merge a branch's versions into " + branch + ".")
                .ifPresent(other -> mergeFrom("refs/heads/" + other, other, "Merged " + other + " into " + branch));
    }

    /**
     * A {@code SAFETY} version, then {@code ref} merged uncommitted; a clean merge is committed with
     * {@code label}, a conflicted one opens the sheet with {@code theirs} naming the other side.
     */
    private void mergeFrom(String ref, String theirs, String label) {
        ProjectState.Snapshot editor = ctx.state().snapshot();
        run(() -> {
            Checkpoints.save(projectDir, editor, VersionOrigin.SAFETY, "Before merging " + theirs);
            return vcs().mergeRef(ref);
        }, merge -> {
            if (merge.upToDate()) {
                status("Already up to date with " + theirs + ".");
            } else if (merge.conflicted()) {
                Platform.runLater(() -> decide(merge.conflicts(), theirs, decided -> finishMerge(decided, label)));
            } else {
                finishMerge(Map.of(), label);
            }
        });
    }

    private void finishMerge(Map<String, ProjectVcs.Side> decided, String label) {
        run(() -> {
            ProjectVcs vcs = vcs();
            for (var e : decided.entrySet()) vcs.resolve(e.getKey(), e.getValue());
            return vcs.finishMerge(VersionOrigin.SAVE, label);
        }, sha -> {
            shown = null;
            status(label + ".");
            ctx.eventBus().publish(new CoreApplicationEvents.ProjectReloadRequestedEvent());
        });
    }

    private void deleteBranch() {
        pickOtherBranch("Delete a branch", "Only a branch whose versions another branch has can be deleted.")
                .ifPresent(name -> run(() -> {
                    vcs().deleteBranch(name);
                    return null;
                }, done -> status("Deleted " + name + ".")));
    }

    private void withRemote(Consumer<ProjectVcs.RemoteInfo> action) {
        ProjectVcs.RemoteInfo remote = remoteBox.getValue();
        if (remote == null) {
            status("Add a remote first.");
            return;
        }
        action.accept(remote);
    }

    private void fetch(ProjectVcs.RemoteInfo remote) {
        String token = token();
        run(() -> {
            vcs().fetch(remote.name(), token);
            return null;
        }, done -> status("Fetched " + remote.name() + "."));
    }

    /** Fetch, then the remote's branch of this branch's name merged in, as {@link #mergeBranch} merges. */
    private void pull(ProjectVcs.RemoteInfo remote) {
        String token = token();
        String from = remote.name() + "/" + branch;
        run(() -> {
            vcs().fetch(remote.name(), token);
            return null;
        }, done -> mergeFrom("refs/remotes/" + from, from, "Pulled " + from));
    }

    /**
     * Pushes this branch — to {@code mine} as the strip would ({@code studio/<branch>} on someone else's bot),
     * to any other remote under its own name. Never forced.
     */
    private void push(ProjectVcs.RemoteInfo remote) {
        String token = token();
        String there = remote.name().equals(Remote.MINE.id()) ? model.remoteBranch(branch) : branch;
        run(() -> vcs().push(remote.name(), there, token, false), pushed -> status("Pushed " + pushed
                + " to " + remote.name() + "/" + there + "."));
    }

    private void addRemote() {
        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Add remote");
        dialog.setHeaderText("An HTTPS address, with no user name or token in it.");
        TextField name = new TextField();
        name.setPromptText("backup");
        TextField url = new TextField();
        url.setPromptText("https://github.com/you/bot.git");
        url.setPrefColumnCount(32);
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Name:"), name);
        form.addRow(1, new Label("Address:"), url);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        String n = name.getText() == null ? "" : name.getText().strip();
        String u = url.getText() == null ? "" : url.getText().strip();
        run(() -> {
            vcs().addRemote(n, u);
            return null;
        }, done -> status("Added " + n + "."));
    }

    private Optional<String> ask(String title, String header, String prompt, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        ThemedWindows.apply(dialog);
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(prompt);
        return dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    /** A remote in Dev's list: its name and how this branch stands there; the address as a tooltip. */
    private static final class RemoteCell extends ListCell<ProjectVcs.RemoteInfo> {
        @Override
        protected void updateItem(ProjectVcs.RemoteInfo remote, boolean empty) {
            super.updateItem(remote, empty);
            if (empty || remote == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(remote.name() + (remote.ahead() < 0 ? " · not fetched"
                    : " · ↑" + remote.ahead() + " ↓" + remote.behind()));
            setTooltip(new Tooltip(remote.url()));
        }
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

    /** Reads the signed-in login, which is both the commit author and who {@link SyncModel} decides for. */
    private void resolveIdentity() {
        if (auth == null || client == null || !auth.isAuthenticated()) {
            authorName = null;
            authorEmail = null;
            return;
        }
        auth.login(client).thenAccept(login -> {
            if (login != null && !login.isBlank()) {
                authorName = login;
                authorEmail = login + "@users.noreply.github.com";
                Platform.runLater(this::refresh);
            }
        });
    }

    /**
     * <i>Save to my copy</i>: saves first — a push carries a real version, never an unsaved tree — then makes
     * sure {@code mine} exists and pushes to it ({@link MyCopy}).
     */
    private void saveToMyCopy() {
        if (auth == null || client == null || !auth.isAuthenticated() || authorName == null) {
            status("Sign in to GitHub to save to your copy.");
            return;
        }
        String label = nameField.getText() == null ? "" : nameField.getText().strip();
        ProjectState.Snapshot editor = ctx.state().snapshot();
        SyncModel now = model;
        run(() -> {
            Checkpoints.save(projectDir, editor, VersionOrigin.SAVE, label.isEmpty() ? "Saved to my copy" : label);
            return new MyCopy(client).save(vcs(), now, ctx.config().projectName(), auth.token());
        }, message -> {
            nameField.clear();
            shown = null;
            status(message);
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
                    // Dev reads every commit as git has it: the short SHA and the author before the rest.
                    String who = view == VersionsView.DEV ? c.shortSha() + " · " + c.author() + " · " : "";
                    Label meta = new Label(who + c.origin().displayName() + " · " + ago(c.when(), Instant.now()) + tags);
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
            ContextMenu menu = new ContextMenu(restoreItem, nameItem);
            if (view == VersionsView.DEV) {
                MenuItem copy = new MenuItem("Copy SHA");
                copy.setOnAction(e -> {
                    ClipboardContent content = new ClipboardContent();
                    content.putString(c.sha());
                    Clipboard.getSystemClipboard().setContent(content);
                    status("Copied " + c.shortSha() + ".");
                });
                menu.getItems().add(copy);
            }
            return menu;
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
