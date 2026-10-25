package com.botmaker.studio.ui.app.home;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.tools.UserDirs;
import com.botmaker.studio.config.AppVersion;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.OpenStep;
import com.botmaker.studio.services.ProgressReporter;
import com.botmaker.studio.sharing.BmkDownload;
import com.botmaker.studio.sharing.BmkOpen;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.CatalogClient;
import com.botmaker.studio.sharing.CatalogIndex;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.ProgramEntries;
import com.botmaker.studio.sharing.ProgramsMenuChoice;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.sharing.UnpackedBots;
import com.botmaker.studio.ui.app.ArchivedProjectsDialog;
import com.botmaker.studio.ui.app.GitHubAccountBar;
import com.botmaker.studio.ui.app.NewProjectDialog;
import com.botmaker.studio.ui.app.ProjectWindow;
import com.botmaker.studio.ui.app.botinfo.BotIconView;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import com.botmaker.studio.ui.app.home.MyBots.CompileState;
import com.botmaker.studio.ui.app.home.MyBots.MyBot;
import com.botmaker.studio.ui.app.home.MyBots.Removal;
import com.botmaker.studio.ui.app.home.MyBots.State;
import com.botmaker.studio.ui.app.loading.LoadingPage;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The home BotMaker opens on (2026-10-25, spec § 1a, layout A): <i>My bots</i> over <i>Get more bots</i> in one
 * scroll, <i>＋ New bot</i> and the account in the top bar. It replaced the project selection screen: a card's
 * <i>▶ Run</i> opens the bot's runner window, <i>Edit in Studio</i> today's editor on it. There is no Run | Make
 * switch (the user's call): the editor is reached from a card, unchanged.
 *
 * <p>It adds no editor control, as the runner window adds none.
 */
public final class RunHome implements ProjectWindow {

    static final double CARD_WIDTH = GalleryCard.WIDTH;
    /** The side of a card's bot icon, on My bots and Get more bots. */
    static final double ICON_SIZE = 36;
    private static final double GAP = 10;
    private static final DateTimeFormatter USED = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    /** What a card's buttons ask of the shell. */
    public interface Actions {
        /** Opens the bot's runner window. */
        void run(Path dir);

        /** Opens the editor on the project; {@code freshlyCreated} for one New Project just made. */
        void edit(Path dir, boolean freshlyCreated);

        /** Makes a bot on the loading page — ＋ New bot's copy, Get's download — then opens it (spec § 1b, P1). */
        void make(LoadingPage.Making making);
    }

    private final Stage stage;
    private final Actions actions;
    private final Supplier<List<MyBot>> bots;
    private final Function<Path, CompileState> compiles;
    private final ProjectManager projectManager = new ProjectManager();
    private final GitHubClient gitHubClient = new GitHubClient();
    private final GitHubAuth gitHubAuth = new GitHubAuth();
    private final TilePane myBots = new TilePane(GAP, GAP);
    private final Label myBotsLine = new Label("Looking for your bots…");
    private final Label getMoreHeading = heading("Get more bots");
    /** Package-private for {@code RunHomeFxTest}. */
    final GetBots getBots;
    private final Button select = new Button("Select");
    private final Label selectedCount = new Label();
    private final Button archiveSelected = new Button("Archive");
    private final Button removeSelected = new Button("Remove from My bots");
    private final HBox selectionBar = new HBox(8);
    /** The cards last read, shown again as they are when Select mode turns on or off; FX thread only. */
    private List<MyBot> shownBots = List.of();
    /** Whether Select mode is on, and the folders ticked in it; FX thread only. */
    private boolean selecting;
    private final Set<Path> selected = new LinkedHashSet<>();
    private final GetBots.Shelf shelf;
    private ScrollPane scroll;
    /** Asked by {@link #scrollToGetBots} and not yet made after My bots' cards came; FX thread only. */
    private boolean scrollPending;
    /** The reads {@link #reload} started, FX thread only. */
    private int reads;

    /**
     * {@code bots} is read off the FX thread on every {@link #reload}: it opens each project's repository.
     * {@code shelf} is where <i>Get more bots</i> puts a bot. {@code compiles} checks a bot's folder in the
     * background, once per change ({@link MyBots#compileState}).
     */
    public RunHome(Stage stage, Actions actions, Supplier<List<MyBot>> bots, GetBots.Shelf shelf,
                   Function<Path, CompileState> compiles) {
        this.stage = stage;
        this.actions = actions;
        this.bots = bots;
        this.compiles = compiles;
        this.shelf = shelf;
        this.getBots = new GetBots(this::agrees, new GetBots.Got() {
            @Override
            public void run(GalleryEntry entry) {
                actions.make(new LoadingPage.Making(shown(entry), OpenStep.DOWNLOADING, progress -> got(entry, progress),
                        LoadingPage.Opens.RUNNER));
            }

            @Override
            public void edit(GalleryEntry entry) {
                // On the loading page's Copying step, not on the card (2026-10-25, N13).
                actions.make(new LoadingPage.Making(shown(entry), OpenStep.COPYING, progress -> {
                    progress.message("Getting " + entry.name() + " from " + entry.slug() + "…");
                    return shelf.cloneOf(entry);
                }, LoadingPage.Opens.EDITOR));
            }
        }, ShowCommunity.load(), HiddenBots.load());
    }

    /**
     * Studio's home: the root's projects and the remembered ones, with the catalog's kept tags for the
     * "<tag> available" badge, read again once the catalog answers; the catalog's bots under them.
     */
    public static RunHome studio(Stage stage, Actions actions) {
        ProjectManager projects = new ProjectManager();
        GitHubAuth auth = new GitHubAuth();
        BotInstaller installer = new BotInstaller(new GitHubGallery(new GitHubClient(), auth));
        RunHome home = new RunHome(stage, actions, () -> {
            var opened = ProjectPreferences.recentOpenTimes();
            List<MyBot> found = MyBots.scan(MyBots.folders(projects, ProjectPreferences.recentDirectories(),
                            UnpackedBots.newest(UnpackedBots.root())),
                    dir -> MyBots.read(dir, opened),
                    MyBots.latestTags(CatalogClient.session().kept().bots()));
            syncProgramsMenu(found);
            return found;
        }, new GetBots.Shelf() {
            @Override
            public Path download(GalleryEntry entry, BmkDownload.Progress progress) throws IOException {
                return new BmkDownload(auth.isAuthenticated() ? auth.token() : null)
                        .fetch(entry, UserDirs.cache(), progress);
            }

            @Override
            public Path cloneOf(GalleryEntry entry) throws IOException {
                return installer.install(entry, entry.tag());
            }
        }, MyBots::compile);
        // Asked again on every visit, as New Project does, so a bot published since Studio started is offered.
        CatalogClient.session().refresh().thenAccept(reply -> Platform.runLater(() -> {
            home.getBots.show(reply.index().bots(), reply.fresh());
            if (reply.fresh()) home.reload();
        }));
        return home;
    }

    /**
     * Makes the programs menu hold {@code bots} (when the user turned it off, only those with a desktop shortcut,
     * which stands for both), each time My bots is read: a bot removed, archived or gone loses its entry here.
     * Nothing on a development run ({@link ProgramEntries}).
     */
    private static void syncProgramsMenu(List<MyBot> bots) {
        ProgramEntries.forThisComputer().ifPresent(entries -> {
            try {
                List<ProgramEntries.Entry> all = MyBots.programEntries(bots);
                Optional<ProgramEntries> desktop = ProgramEntries.desktopOfThisComputer();
                entries.sync(ProgramsMenuChoice.load().on() ? all
                        : all.stream().filter(e -> desktop.map(d -> d.has(e)).orElse(false)).toList());
            } catch (IOException | RuntimeException e) {
                System.err.println("Could not update the programs menu: " + e.getMessage());
            }
        });
    }

    /**
     * Scrolls the home to <i>Get more bots</i>: where the editor's menu item goes. My bots' cards arrive later, off
     * the FX thread, and push the section down, so the scroll is made again once they are shown ({@link #reload}).
     */
    public void scrollToGetBots() {
        scrollPending = true;
        Platform.runLater(this::applyScroll);
    }

    private void applyScroll() {
        if (scroll == null || scroll.getScene() == null) return;
        scroll.applyCss();
        scroll.layout();
        double content = scroll.getContent().getBoundsInLocal().getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        if (content <= viewport) return;
        scroll.setVvalue(Math.min(1, getMoreHeading.getBoundsInParent().getMinY() / (content - viewport)));
    }

    /** What the loading page says about a catalog bot being got: "by <owner> · <tag>" and its description. */
    private static LoadingPage.Bot shown(GalleryEntry entry) {
        return new LoadingPage.Bot(entry.name(), "by " + entry.owner() + " · " + entry.tag(),
                Optional.of(entry.description()).filter(d -> !d.isBlank()), Optional.empty());
    }

    /**
     * Get's work on the loading page's Downloading step: {@code entry}'s listed {@code .bmk} downloaded, a percentage
     * and the size said, then opened as the file the click agreed to ({@link BmkOpen#openGot}) and deleted, opened or
     * not. Blocking; the unpacked folder, or why not.
     */
    private Path got(GalleryEntry entry, ProgressReporter progress) throws Exception {
        Path bmk = shelf.download(entry, (done, total) -> progress.report(total > 0 ? (double) done / total : -1,
                "Downloading " + GetBots.megabytes(done) + (total > 0 ? " of " + GetBots.megabytes(total) : "")));
        try {
            progress.message("Checking " + bmk.getFileName() + "…");
            BmkOpen.Fetcher fetch = coordinates -> MavenService.fetch(coordinates, ProgressReporter.NONE);
            CatalogIndex catalog;
            try {
                catalog = CatalogClient.session().fetch().join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
            BmkOpen.Result result = BmkOpen.openGot(bmk, catalog, entry, UnpackedBots.root(), fetch);
            return switch (result) {
                case BmkOpen.Opened opened -> opened.dir();
                case BmkOpen.Refused refused -> throw new IOException(refused.why());
                // A got file is the one the click agreed to, so openGot never asks; said plainly should it ever.
                case BmkOpen.NeedsTrust trust -> throw new IOException("The catalog lists no ready-to-run file for "
                        + entry.slug() + " " + entry.tag() + ".");
            };
        } finally {
            Files.deleteIfExists(bmk);
        }
    }

    /** The trust warning of a bot nobody reviewed, confirmed before it is got. */
    private boolean agrees(GalleryEntry entry) {
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                entry.trustWarning() + "\n\nGet it now?", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Get this bot?");
        return confirm.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /** Nothing to release: the screen holds no listener outside its own scene. */
    @Override
    public void dispose() {}

    @Override
    public Scene createScene() {
        Label title = new Label("BotMaker");
        title.getStyleClass().add("runner-title");
        Label version = new Label("v" + AppVersion.label());
        version.getStyleClass().add("gallery-card-note");
        Button newBot = new Button("＋ New bot");
        newBot.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
        newBot.setOnAction(e -> new NewProjectDialog(stage, gitHubClient, gitHubAuth, actions::make).show());
        Button openFolder = new Button("Open folder…");
        openFolder.setOnAction(e -> openFolder());
        Button archived = new Button("View archived…");
        archived.setOnAction(e -> new ArchivedProjectsDialog(stage, projectManager, this::reload).show());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, title, version, spacer, newBot, openFolder, archived,
                new GitHubAccountBar(stage, gitHubAuth, gitHubClient, () -> {}));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("runner-header");

        myBots.setPrefTileWidth(CARD_WIDTH);
        myBots.setTileAlignment(Pos.TOP_LEFT);
        myBotsLine.getStyleClass().add("gallery-card-note");
        myBotsLine.managedProperty().bind(myBotsLine.visibleProperty());

        select.setOnAction(e -> {
            if (selecting) leaveSelection();
            else enterSelection();
        });
        Region headingSpacer = new Region();
        HBox.setHgrow(headingSpacer, Priority.ALWAYS);
        HBox myBotsHeading = new HBox(10, heading("My bots"), headingSpacer, select);
        myBotsHeading.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(10, myBotsHeading);
        if (ProgramEntries.forThisComputer().isPresent()) {
            CheckBox inMenu = new CheckBox(ProgramsMenuChoice.LABEL);
            inMenu.setSelected(ProgramsMenuChoice.load().on());
            inMenu.setOnAction(e -> {
                new ProgramsMenuChoice(inMenu.isSelected()).save();
                reload();
            });
            body.getChildren().add(inMenu);
        }
        body.getChildren().addAll(myBotsLine, myBots, getMoreHeading, getBots.node());
        body.setPadding(new Insets(16));
        scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("np-scroll");

        archiveSelected.setOnAction(e -> takeOff(selectedBots(), true));
        removeSelected.setOnAction(e -> takeOff(selectedBots(), false));
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> leaveSelection());
        Region barSpacer = new Region();
        HBox.setHgrow(barSpacer, Priority.ALWAYS);
        selectionBar.getChildren().setAll(selectedCount, barSpacer, archiveSelected, removeSelected, cancel);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.setPadding(new Insets(8, 16, 8, 16));
        selectionBar.getStyleClass().add("runner-header");
        selectionBar.managedProperty().bind(selectionBar.visibleProperty());
        selectionBar.setVisible(false);

        BorderPane root = new BorderPane(scroll);
        root.setTop(bar);
        root.setBottom(selectionBar);
        // A .bmk dropped anywhere on the home opens it (spec § 2 "Opening one").
        root.setOnDragOver(e -> {
            if (droppedBmk(e.getDragboard().getFiles()).isPresent()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        root.setOnDragDropped(e -> {
            Optional<Path> bmk = droppedBmk(e.getDragboard().getFiles());
            bmk.ifPresent(this::openBmk);
            e.setDropCompleted(bmk.isPresent());
            e.consume();
        });
        reload();
        // Unsized, as every scene swapped onto the shell's one stage: a sized one would resize the user's window.
        return ThemedWindows.scene(root);
    }

    /** The one {@code .bmk} among {@code files}, when the drop is exactly one. */
    static Optional<Path> droppedBmk(List<File> files) {
        if (files == null || files.size() != 1) return Optional.empty();
        Path file = files.getFirst().toPath();
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bmk") ? Optional.of(file)
                : Optional.empty();
    }

    /**
     * Opens a dropped or double-clicked {@code .bmk} on the loading page ({@link BmkFlow#opening}: the trust sheet
     * when it needs one), then its runner window (N12). {@code botmaker-studio --open=<file>} goes there directly.
     */
    public void openBmk(Path bmk) {
        actions.make(new BmkFlow(stage).opening(bmk));
    }

    /** Reads My bots again, off the FX thread, and shows the cards. */
    public void reload() {
        // Reads overlap (a catalog answer during a Remove); only the latest one asked for is shown.
        int asked = ++reads;
        CompletableFuture.supplyAsync(bots).whenComplete((found, failed) -> Platform.runLater(() -> {
            if (asked != reads) return;
            if (failed != null) {
                say("Could not list your bots: " + failed.getMessage());
                myBots.getChildren().clear();
                return;
            }
            say(found.isEmpty() ? "No bots yet: ＋ New bot starts one, or get one below." : "");
            shownBots = List.copyOf(found);
            selected.retainAll(found.stream().map(MyBot::dir).collect(Collectors.toSet()));
            showCards();
            getBots.mine(found.stream().flatMap(b -> b.original().stream())
                    .map(s -> MyBots.key(s.owner(), s.repo())).collect(Collectors.toSet()));
            if (scrollPending) {
                // Once, on the first cards: a later reload (a Get, a Remove) leaves the user's scroll alone.
                scrollPending = false;
                Platform.runLater(this::applyScroll);
            }
        }));
    }

    /** The cards of {@link #shownBots}, and Select mode's bar. */
    private void showCards() {
        myBots.getChildren().setAll(shownBots.stream().map(this::card).toList());
        select.setText(selecting ? "Done" : "Select");
        select.setDisable(!selecting && shownBots.isEmpty());
        selectionBar.setVisible(selecting);
        if (selecting) updateSelectionBar();
    }

    // creatingCard, a card with a bar while ＋ New bot made a bot, went on 2026-10-25: the loading page's Copying
    // step says it now (spec § 1b, P1).

    private void say(String text) {
        myBotsLine.setText(text);
        myBotsLine.setVisible(!text.isEmpty());
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-section-title");
        return label;
    }

    /** One My bots card: icon, name, who and which version, when last used, an update badge, its buttons. */
    private Node card(MyBot bot) {
        Node icon = bot.state() == State.GONE ? BotIconView.letter(bot.name(), ICON_SIZE)
                : BotIconView.local(bot.name(), bot.dir(), ICON_SIZE);
        Label name = GalleryCard.cardName(bot.name());
        HBox top = new HBox(8, icon, name);
        top.setAlignment(Pos.CENTER_LEFT);
        bot.available().ifPresent(tag -> {
            Label badge = new Label(tag + " available");
            badge.getStyleClass().add("mybots-badge");
            // Whole beside a long name, which wraps instead (spec § 1b, A).
            badge.setMinWidth(Region.USE_PREF_SIZE);
            top.getChildren().add(badge);
        });

        VBox card = new VBox(4, top);
        card.getStyleClass().addAll("np-card", "mybots-card");
        card.setPrefWidth(CARD_WIDTH);

        if (selecting) {
            // Select mode: a check box in place of the buttons, and a click anywhere on the card ticks it.
            CheckBox tick = new CheckBox();
            tick.setSelected(selected.contains(bot.dir()));
            tick.selectedProperty().addListener((o, was, now) -> {
                if (now) selected.add(bot.dir());
                else selected.remove(bot.dir());
                updateSelectionBar();
            });
            top.getChildren().addFirst(tick);
            card.setOnMouseClicked(e -> {
                // A click on the box itself (its inner box or mark) is the box's own toggle.
                for (Node n = e.getTarget() instanceof Node t ? t : null; n != null; n = n.getParent()) {
                    if (n == tick) return;
                }
                tick.setSelected(!tick.isSelected());
            });
            card.getChildren().add(line(bot.state() == State.GONE ? "This bot's folder is gone." : who(bot)));
            return card;
        }

        if (bot.state() == State.GONE) {
            card.getChildren().add(line("This bot's folder is gone."));
            card.getChildren().add(line(bot.dir().toString()));
            Button remove = new Button("Remove from My bots");
            remove.setOnAction(e -> takeOff(List.of(bot), false));
            card.getChildren().add(remove);
            return card;
        }

        card.getChildren().add(line(who(bot)));
        if (!bot.lastUsed().equals(LocalDateTime.MIN)) card.getChildren().add(line("Used " + bot.lastUsed().format(USED)));
        Button edit = new Button("Edit in Studio");
        edit.setOnAction(e -> actions.edit(bot.dir(), false));
        HBox buttons = new HBox(6);
        buttons.setAlignment(Pos.CENTER_LEFT);
        if (bot.runnable()) {
            Button run = new Button("▶ Run");
            run.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
            run.setOnAction(e -> actions.run(bot.dir()));
            buttons.getChildren().add(run);
            // Checked in the background on the card's first show, then again only when a source changes.
            Label broken = line("Doesn't compile: open it in Studio to fix it.");
            broken.getStyleClass().add("mybots-broken");
            broken.managedProperty().bind(broken.visibleProperty());
            broken.setVisible(false);
            card.getChildren().add(broken);
            MyBots.compileState(bot.dir(), compiles).thenAccept(state -> Platform.runLater(() -> {
                boolean errors = state == CompileState.ERRORS;
                broken.setVisible(errors);
                run.setDisable(errors);
            }));
        } else {
            card.getChildren().add(line("No class with main yet: nothing to run."));
        }
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        buttons.getChildren().addAll(edit, push, more(bot));
        card.getChildren().add(buttons);
        return card;
    }

    /** "by <owner> · <version>", or "mine · <version>"; no version for a bot never released. */
    static String who(MyBot bot) {
        String by = bot.owner().map(o -> "by " + o).orElse("mine");
        return bot.version().map(v -> by + " · " + v).orElse(by);
    }

    /** The card's ⋯: archive a root project, forget one kept elsewhere, delete one opened from a file. */
    private Node more(MyBot bot) {
        Removal removal = removalOf(bot);
        MenuItem item = new MenuItem(switch (removal) {
            case ARCHIVE -> "Archive…";
            case FORGET -> "Remove from My bots";
            case DELETE -> "Remove from My bots…";
        });
        item.setOnAction(e -> takeOff(List.of(bot), removal == Removal.ARCHIVE));
        MenuButton menu = new MenuButton("⋯");
        menu.getItems().add(item);
        return menu;
    }

    private Removal removalOf(MyBot bot) {
        return MyBots.removalOf(bot, projectManager.isUnderRoot(bot.dir()));
    }

    /**
     * Takes {@code bots} off My bots (one card's ⋯, or the selection's bar): {@code archive} moves the root's
     * projects among them into the archive; otherwise a folder elsewhere or gone is forgotten (it is not Studio's
     * to move, and opening it again undoes it) and a bot opened from a file is deleted, every tag of it (nothing of
     * the user's is in it; its runner's values are kept apart). Asked first, except a forget alone, which touches
     * no file. Each one's entry leaves the recents. Failures are said together, each in a sentence.
     */
    private void takeOff(List<MyBot> bots, boolean archive) {
        List<MyBot> acting = bots.stream()
                .filter(b -> archive == (removalOf(b) == Removal.ARCHIVE)).toList();
        if (acting.isEmpty()) return;
        boolean deletes = acting.stream().anyMatch(b -> removalOf(b) == Removal.DELETE);
        if ((archive || deletes) && !confirmTakeOff(acting, archive)) return;
        List<String> failures = new ArrayList<>();
        for (MyBot bot : acting) {
            try {
                switch (removalOf(bot)) {
                    case ARCHIVE -> projectManager.archiveProject(bot.dir());
                    case DELETE -> UnpackedBots.remove(bot.dir());
                    case FORGET -> { }
                }
                ProjectPreferences.removeRecent(bot.dir());
            } catch (IOException ex) {
                failures.add(ex.getMessage());
            }
        }
        leaveSelection();
        reload();
        if (!failures.isEmpty()) {
            error(archive ? "Could not archive every bot" : "Could not remove every bot",
                    String.join("\n\n", failures));
        }
    }

    private boolean confirmTakeOff(List<MyBot> bots, boolean archive) {
        boolean one = bots.size() == 1;
        String names = one ? "“" + bots.getFirst().name() + "”" : bots.size() + " bots";
        String body = archive
                ? "Archive " + names + "?\n\n" + (one ? "It moves" : "They move") + " to the archive and "
                        + (one ? "leaves" : "leave") + " My bots. You can restore or delete " + (one ? "it" : "them")
                        + " later via “View archived…”."
                : "Remove " + names + " from My bots?\n\nA bot opened from a file is deleted from this computer; "
                        + "its settings are kept, and opening its .bmk file again gets it back. Any other folder "
                        + "stays where it is.";
        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText(archive ? "Archive?" : "Remove from My bots?");
        return confirm.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    /** Select mode (spec § 1b, layout S1): a check box per card, the cards' buttons hidden, the bar below. */
    private void enterSelection() {
        selecting = true;
        selected.clear();
        showCards();
    }

    private void leaveSelection() {
        if (!selecting) return;
        selecting = false;
        selected.clear();
        showCards();
    }

    /** The bar's count and its two buttons, each acting on the selected bots it applies to. */
    private void updateSelectionBar() {
        List<MyBot> picked = selectedBots();
        long archivable = picked.stream().filter(b -> removalOf(b) == Removal.ARCHIVE).count();
        selectedCount.setText(picked.size() + " selected");
        archiveSelected.setText(archivable == picked.size() || archivable == 0 ? "Archive"
                : "Archive " + archivable);
        archiveSelected.setDisable(archivable == 0);
        long removable = picked.size() - archivable;
        removeSelected.setText(removable == picked.size() || removable == 0 ? "Remove from My bots"
                : "Remove " + removable + " from My bots");
        removeSelected.setDisable(removable == 0);
    }

    private List<MyBot> selectedBots() {
        return shownBots.stream().filter(b -> selected.contains(b.dir())).toList();
    }

    /**
     * Opens a project from a folder the user picks, anywhere, in the editor; refused, with the reason, when the
     * folder is not a Maven project. It is remembered, so it shows in My bots from then on.
     */
    private void openFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open a project folder");
        // A directory that does not exist is no directory at all — JavaFX throws rather than ignoring it.
        File home = new File(System.getProperty("user.home"));
        if (home.isDirectory()) chooser.setInitialDirectory(home);
        File picked = chooser.showDialog(stage);
        if (picked == null) return;
        Path dir = picked.toPath().toAbsolutePath().normalize();
        if (projectManager.projectAt(dir).isEmpty()) {
            error("Not a project", dir + " has no " + ProjectManager.PROJECT_SHAPE
                    + ", so there is no project here to open.");
            return;
        }
        actions.edit(dir, false);
    }

    private static Label line(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("gallery-card-note");
        label.setWrapText(true);
        return label;
    }

    private void error(String header, String body) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(body);
        alert.showAndWait();
    }
}
