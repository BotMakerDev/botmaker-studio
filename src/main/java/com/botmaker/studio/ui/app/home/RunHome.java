package com.botmaker.studio.ui.app.home;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.config.AppVersion;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.CatalogClient;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.sharing.UnpackedBots;
import com.botmaker.studio.ui.app.ArchivedProjectsDialog;
import com.botmaker.studio.ui.app.GitHubAccountBar;
import com.botmaker.studio.ui.app.NewProjectDialog;
import com.botmaker.studio.ui.app.ProjectWindow;
import com.botmaker.studio.ui.app.botinfo.BotIconView;
import com.botmaker.studio.ui.app.home.MyBots.CompileState;
import com.botmaker.studio.ui.app.home.MyBots.MyBot;
import com.botmaker.studio.ui.app.home.MyBots.State;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The home BotMaker opens on (2026-10-25, spec § 1a, layout A): <i>My bots</i> over <i>Get more bots</i> in one
 * scroll, <i>＋ New bot</i> and the account in the top bar. It replaced the project selection screen: a card's
 * <i>▶ Run</i> opens the bot's runner window, <i>Edit in Studio</i> today's editor on it. There is no Run | Make
 * switch (the user's call): the editor is reached from a card, unchanged.
 *
 * <p>It adds no editor control, as the runner window adds none.
 */
public final class RunHome implements ProjectWindow {

    static final double CARD_WIDTH = 220;
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
    private final GetBots getBots;
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
        this.getBots = new GetBots(shelf, this::agrees, this::reload, ShowCommunity.load(), HiddenBots.load());
    }

    /**
     * Studio's home: the root's projects and the remembered ones, with the catalog's kept tags for the
     * "<tag> available" badge, read again once the catalog answers; the catalog's bots under them.
     */
    public static RunHome studio(Stage stage, Actions actions) {
        ProjectManager projects = new ProjectManager();
        BotInstaller installer = new BotInstaller(new GitHubGallery(new GitHubClient(), new GitHubAuth()));
        RunHome home = new RunHome(stage, actions, () -> {
            var opened = ProjectPreferences.recentOpenTimes();
            return MyBots.scan(MyBots.folders(projects, ProjectPreferences.recentDirectories(),
                            UnpackedBots.newest(UnpackedBots.root())),
                    dir -> MyBots.read(dir, opened),
                    MyBots.latestTags(CatalogClient.session().kept().bots()));
        }, new GetBots.Shelf() {
            @Override
            public Path get(GalleryEntry entry) throws IOException {
                return installer.install(entry, entry.tag());
            }

            @Override
            public boolean has(GalleryEntry entry) {
                return installer.isInstalled(entry.name());
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
        Label version = new Label("v" + AppVersion.get());
        version.getStyleClass().add("gallery-card-note");
        Button newBot = new Button("＋ New bot");
        newBot.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
        newBot.setOnAction(e -> new NewProjectDialog(stage, gitHubClient, gitHubAuth,
                dir -> actions.edit(dir, true)).show());
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

        VBox body = new VBox(10,
                heading("My bots"), myBotsLine, myBots,
                getMoreHeading, getBots.node());
        body.setPadding(new Insets(16));
        scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("np-scroll");

        BorderPane root = new BorderPane(scroll);
        root.setTop(bar);
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
     * Opens a {@code .bmk} ({@link BmkFlow}: the trust sheet when it needs one), then shows it in My bots and opens
     * its runner window. {@code botmaker-studio --open=<file>} comes here too.
     */
    public void openBmk(Path bmk) {
        new BmkFlow(stage, this::say).open(bmk, dir -> {
            reload();
            actions.run(dir);
        });
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
            myBots.getChildren().setAll(found.stream().map(this::card).toList());
            if (scrollPending) {
                // Once, on the first cards: a later reload (a Get, a Remove) leaves the user's scroll alone.
                scrollPending = false;
                Platform.runLater(this::applyScroll);
            }
        }));
    }

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
        Label name = new Label(bot.name());
        name.getStyleClass().add("np-card-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, icon, name, spacer);
        top.setAlignment(Pos.CENTER_LEFT);
        bot.available().ifPresent(tag -> {
            Label badge = new Label(tag + " available");
            badge.getStyleClass().add("mybots-badge");
            top.getChildren().add(badge);
        });

        VBox card = new VBox(4, top);
        card.getStyleClass().addAll("np-card", "mybots-card");
        card.setPrefWidth(CARD_WIDTH);

        if (bot.state() == State.GONE) {
            card.getChildren().add(line("This bot's folder is gone."));
            card.getChildren().add(line(bot.dir().toString()));
            Button remove = new Button("Remove from My bots");
            remove.setOnAction(e -> {
                ProjectPreferences.removeRecent(bot.dir());
                reload();
            });
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
        boolean elsewhere = !projectManager.isUnderRoot(bot.dir());
        MenuItem item = new MenuItem(bot.unpacked() ? "Remove from My bots…" : elsewhere ? "Remove from My bots"
                : "Archive…");
        item.setOnAction(e -> {
            if (bot.unpacked()) {
                removeUnpacked(bot);
            } else if (elsewhere) {
                // Not Studio's folder to move: forgetting it touches no file, and opening it again undoes it.
                ProjectPreferences.removeRecent(bot.dir());
                reload();
            } else {
                archive(bot);
            }
        });
        MenuButton menu = new MenuButton("⋯");
        menu.getItems().add(item);
        return menu;
    }

    /**
     * Deletes a bot opened from a file, every tag of it: there is nothing of the user's in it (its runner's values
     * are kept apart), and opening the file again brings it back.
     */
    private void removeUnpacked(MyBot bot) {
        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                "Remove “" + bot.name() + "” from this computer?\n\nIts settings are kept; open its .bmk file again "
                        + "to get it back.", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Remove this bot?");
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
        try {
            UnpackedBots.remove(bot.dir());
            reload();
        } catch (IOException ex) {
            error("Failed to remove the bot", ex.getMessage());
        }
    }

    private void archive(MyBot bot) {
        Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                "Archive “" + bot.name() + "”?\n\nIt will be moved to the archive and hidden from My bots. "
                        + "You can restore or permanently delete it later via “View archived…”.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Archive this bot?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;
        try {
            projectManager.archiveProject(bot.dir());
            reload();
        } catch (Exception ex) {
            error("Failed to archive the bot", ex.getMessage());
        }
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
