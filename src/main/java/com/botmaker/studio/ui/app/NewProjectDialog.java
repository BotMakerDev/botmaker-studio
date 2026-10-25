package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.sharing.CatalogClient;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.NewProjects;
import com.botmaker.studio.sharing.PluginCatalog;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * New Project: a name, where to start from (Blank or a published bot, as cards) and the plugins to add. Moved out
 * of the project selection screen on 2026-10-25, when the bot list became the home and its <i>＋ New bot</i> the
 * door to it; the dialog itself is unchanged.
 */
public final class NewProjectDialog {

    private final Window owner;
    private final ProjectCreator projectCreator = new ProjectCreator();
    private final GitHubClient gitHubClient;
    private final GitHubAuth gitHubAuth;
    /** Hears the new project's folder, on the FX thread, once it is created. */
    private final Consumer<Path> onCreated;

    public NewProjectDialog(Window owner, GitHubClient gitHubClient, GitHubAuth gitHubAuth, Consumer<Path> onCreated) {
        this.owner = owner;
        this.gitHubClient = gitHubClient;
        this.gitHubAuth = gitHubAuth;
        this.onCreated = onCreated;
    }

    /** Asks, and creates the project when the user says Create. */
    public void show() {
        Dialog<CreateRequest> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Create New Project");

        ButtonType createButtonType = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);
        Button createButton = (Button) dialog.getDialogPane().lookupButton(createButtonType);

        // The name alone (2026-10-24): its rule shows only once the typed name breaks one, in ProjectCreator's own
        // sentence under the field. The header text, the five grey rule labels and the green border went.
        TextField projectNameField = new TextField();
        projectNameField.setPromptText("ProjectName");
        Label nameError = new Label();
        nameError.getStyleClass().add("np-error");
        nameError.setWrapText(true);
        nameError.managedProperty().bind(nameError.visibleProperty());
        nameError.setVisible(false);

        // There is no SDK version row here any more (2026-09-04), and its absence is the visible half of the
        // blank-project split. A blank project names no plugin at all, so there is no version to pin; a
        // published bot brings its author's own pom, versions and libraries, and keeping them is the point of
        // starting from it. Neither shape has a question to ask, so the control, its
        // JitPack fetch, the local-build decoration and the show/hide listener that toggled it against the
        // template row are all gone. A project's SDK version is Project ▸ Plugins & Libraries' business, which
        // is where every other version already lives.

        // The standard-resolution dropdown and its landscape/portrait toggle were here until 2026-09-01.
        // The size a project's pictures are captured at is the capturing plugin's setting, seeded by its own
        // toolbar item on the first capture — so New Project no longer asks for it, and a fresh project has
        // no capture.width/capture.height until a picture is taken.

        // The starting point: Studio's own Blank, or any published bot, cloned and linked to its author as Browse
        // Bots installs it (2026-10-23; only bots tagged `template`, copied and renamed, until then), as cards
        // (StartCards, 2026-10-24; a combo until then). Blank leads; below it the bots of the catalog kept from the
        // last read, while the catalog is asked again — on every open since 2026-10-24, so a bot published since
        // Studio started is offered. The ones hidden in Browse Bots on this computer are left out (HiddenBots),
        // and community ones unless the user shows them (ShowCommunity, 2026-10-23). Offline the kept cards stay,
        // not creatable, and the line above them says so.
        CheckBox showCommunity = new CheckBox(ShowCommunity.LABEL);
        showCommunity.setSelected(ShowCommunity.load().shown());
        CompletableFuture<CatalogClient.Reply> asking = CatalogClient.session().refresh();
        AtomicReference<List<GalleryEntry>> bots = new AtomicReference<>(CatalogClient.session().kept().bots());
        AtomicReference<StartingPoints.Fetch> fetch = new AtomicReference<>(StartingPoints.Fetch.PENDING);
        Label catalogLine = shownWhenSaid(new Label());
        // The note under the cards carries the trust warning Browse Bots' Install shows too, by rank (2026-10-23).
        Label startNote = shownWhenSaid(new Label());
        startNote.setWrapText(true);
        // A name the user typed is never replaced by a picked bot's; one they emptied again counts as none.
        boolean[] typedByUser = {false};
        boolean[] filling = {false};
        // The plugins to add at creation (2026-10-01), for any starting point: the rows Plugins & Libraries ▸
        // Browse offers, as cards (2026-10-24), with the bot's own and those a plugin brings ticked and locked.
        NewProjectPlugins[] plugins = new NewProjectPlugins[1];
        StartCards[] starts = new StartCards[1];
        // Create waits for a valid name and for a card it can create from: not a kept bot the catalog has not
        // confirmed.
        Runnable updateCreate = () -> {
            StartChoice picked = starts[0] == null ? null : starts[0].picked();
            createButton.setDisable(!isValidProjectName(projectNameField.getText())
                    || picked == null || !picked.isCreatable());
        };
        starts[0] = new StartCards(picked -> {
            if (plugins[0] != null) plugins[0].lockFor(picked);
            NewProjectForm.prefill(typedByUser[0], picked).ifPresent(name -> {
                filling[0] = true;
                try {
                    projectNameField.setText(name);
                } finally {
                    filling[0] = false;
                }
            });
            say(startNote, NewProjectForm.startNote(picked, fetch.get()));
            updateCreate.run();
        });
        // No line while asking (the one above says it), nor offline with nothing kept (likewise); otherwise why no
        // bot card shows — offline too, when the kept bots are all community ones and those are unticked.
        Runnable refill = () -> starts[0].fill(startRows(bots.get(), fetch.get(), showCommunity.isSelected()),
                bots.get(),
                fetch.get() == StartingPoints.Fetch.PENDING
                        || fetch.get() == StartingPoints.Fetch.FAILED && bots.get().isEmpty()
                        ? "" : NewProjectForm.emptyLine(showCommunity.isSelected()));
        Runnable describeCatalog = () -> say(catalogLine, NewProjectForm.catalogLine(fetch.get(),
                CatalogClient.session().keptAt(), !bots.get().isEmpty(), ZoneId.systemDefault()));
        refill.run();
        describeCatalog.run();
        asking.thenAccept(reply -> Platform.runLater(() -> {
            fetch.set(reply.fresh() ? StartingPoints.Fetch.ANSWERED : StartingPoints.Fetch.FAILED);
            bots.set(reply.index().bots());
            describeCatalog.run();
            refill.run();
            // A kept bot still picked offline keeps its pick, so the note is said again for the new state.
            say(startNote, NewProjectForm.startNote(starts[0].picked(), fetch.get()));
        }));

        // The note that said what Blank or a bot gives you went on 2026-10-24: the cards say it ("An empty
        // project", "by <owner>"), and only the trust warning stays.
        plugins[0] = new NewProjectPlugins(new PluginCatalog(new PluginRegistry()),
                new ShowCommunity(showCommunity.isSelected()), () -> {});
        plugins[0].lockFor(starts[0].picked());
        showCommunity.setOnAction(e -> {
            ShowCommunity choice = new ShowCommunity(showCommunity.isSelected());
            choice.save();
            refill.run();
            plugins[0].show(choice);
        });

        // Both grids scroll in one pane; the name above and the toggle and buttons below stay put.
        Label startHeading = new Label("Start from");
        startHeading.getStyleClass().add("form-section-title");
        Label pluginsHeading = new Label("Plugins");
        pluginsHeading.getStyleClass().add("form-section-title");
        VBox grids = new VBox(8, startHeading, starts[0].node(), startNote, pluginsHeading, plugins[0].node());
        grids.setPadding(new Insets(0, 4, 0, 0));
        ScrollPane scroll = new ScrollPane(grids);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxHeight(420);
        scroll.getStyleClass().add("np-scroll");

        VBox content = new VBox(8, projectNameField, nameError, catalogLine, scroll, showCommunity);
        content.setPadding(new Insets(16));
        content.setPrefWidth(3 * StartCards.CARD_WIDTH + 2 * 8 + 2 * 16 + 24);
        dialog.getDialogPane().setContent(content);

        projectNameField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!filling[0]) typedByUser[0] = !newValue.isEmpty();
            // A keyword ("Class") is refused as well as a bad shape, so the sentence is ProjectCreator's.
            Optional<String> problem = NewProjectForm.nameError(newValue, projectCreator::nameProblem);
            say(nameError, problem.orElse(""));
            projectNameField.getStyleClass().remove("np-name-error");
            if (problem.isPresent()) projectNameField.getStyleClass().add("np-name-error");
            updateCreate.run();
        });
        updateCreate.run();

        Platform.runLater(projectNameField::requestFocus);

        dialog.setResultConverter(dialogButton -> {
            StartChoice choice = starts[0].picked();
            if (dialogButton == createButtonType && choice != null && choice.isCreatable()) {
                return new CreateRequest(projectNameField.getText(), choice, plugins[0].picked());
            }
            return null;
        });

        dialog.showAndWait().ifPresent(this::create);
    }

    // loadBots went on 2026-10-24: New Project asks the catalog itself (CatalogClient.refresh), whose answer says
    // whether GitHub gave it, and it tells offline by that, not by an empty list. Failure is still no error
    // dialog: the line above the cards says it, and New Project works on a plane — the only reason Studio
    // composes a starting project at all ({@code project/StarterSources}).

    /** A label hidden, taking no room, while it says nothing. */
    private static Label shownWhenSaid(Label label) {
        label.getStyleClass().add("gallery-card-note");
        label.managedProperty().bind(label.visibleProperty());
        label.setVisible(false);
        return label;
    }

    private static void say(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isEmpty());
    }

    /**
     * New Project's rows for {@code entries}: those hidden on this computer left out, community ones unless
     * {@code community}, ranked ({@link GalleryEntry#startingPoints}) so the list reads the same on every launch.
     */
    private static List<StartChoice> startRows(List<GalleryEntry> entries, StartingPoints.Fetch fetch,
                                               boolean community) {
        return StartingPoints.rows(GalleryEntry.startingPoints(
                new ShowCommunity(community).bots(HiddenBots.load().without(entries))), fetch);
    }

    // fillStarts, the combo's keep-the-pick refill, became StartCards.fill on 2026-10-24.

    // loadSdkVersions and decorateLocalBuilds were here until 2026-09-04, with the SDK version combo they
    // filled: a JitPack fetch, a "latest" convenience row and a cell factory badging ~/.m2 dev builds. New
    // Project asks for no SDK version now, so they had nothing to fill. MavenService.localSdkVersions()
    // survives for VersionInfo; a project's SDK version is chosen in Plugins & Libraries ▸ Installed.

    /** Result of the create-project dialog: {@code plugins} are the extra ticks, the bot's own left out. */
    private record CreateRequest(String projectName, StartChoice start, List<PluginRegistry.Plugin> plugins) {}

    /**
     * {@link ProjectCreator#nameProblem}'s rules, which the assistant's {@code create_project} applies too.
     *
     * <p>A lowercase first letter is fine: the Java class name is derived from the project name
     * ({@code ProjectConfig.toClassName}) rather than being it, so {@code myBot} yields {@code class MyBot}
     * while the project stays called what the user called it.
     */
    private boolean isValidProjectName(String name) {
        return projectCreator.nameProblem(name) == null;
    }

    /**
     * Creates the project and opens it — Studio's blank one, or a bot's newest release cloned into
     * {@code projectName}, linked to its author — with the ticked plugins declared.
     *
     * <p>Creating a project <em>is</em> asking to work on it, so it opens rather than going back to a list.
     *
     * <p>Both shapes run off the FX thread since 2026-10-01: a ticked plugin's version may be a JitPack lookup,
     * and the clone and the check for a plugin another one brings are slow and failable too.
     * Everything the user sees about a failure is one dialog, because a creation that fails leaves nothing
     * behind ({@link NewProjects#create}).
     */
    private void create(CreateRequest req) {
        String projectName = req.projectName();
        new Thread(() -> {
            try {
                NewProjects.create(projectName, req.start().entry(), req.plugins(), gitHubClient, gitHubAuth);
                Platform.runLater(() -> onCreated.accept(Constants.PROJECTS_ROOT.resolve(projectName)));
            } catch (Exception e) {
                // ProjectCreator's own sentence says it better than a header could.
                Platform.runLater(() -> {
                    Alert error = ThemedWindows.alert(Alert.AlertType.ERROR);
                    error.initOwner(owner);
                    error.setTitle("Error");
                    error.setHeaderText("Could not create the project");
                    error.setContentText(e.getMessage());
                    error.showAndWait();
                });
            }
        }, "project-create").start();
    }
}
