package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.SemVer;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.TemplateProject;
import com.botmaker.studio.project.launch.SupportedTargets;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GalleryTier;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.ListingStatus;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublishPlan;
import com.botmaker.studio.sharing.PublishRequest;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes the current project, and shows what the gallery made of it.
 *
 * <p>One window, in two columns. The left says <b>what</b> is published, in the order an author decides it:
 * <i>Kind</i> (a bot, or a starting template), <i>Listing</i> (in the gallery, or on GitHub only),
 * <i>Details</i> and <i>Release</i>. The right says <b>how it will look and how it went</b>: the Browse Bots card
 * built from the form as it stands, a checklist of the publish's steps with a Retry for the one that failed, and
 * the listing as the gallery currently has it, with Unpublish beside it.
 *
 * <p>It replaced a flat form whose whole outcome was one alert. A publish is five network operations, and the
 * old window could say only that "it" failed — after which pressing Publish again started from the repository
 * and was refused at the release, whose tag already existed. {@link BotPublisher.Run} resumes instead.
 *
 * <p>Authentication (and sign-out / switch-account) is the shared {@link GitHubAccountBar}. Degrades to a
 * sentence when this build has no OAuth client id.
 */
public class PublishDialog {

    private final Window owner;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final GitHubGallery gallery;
    private final BotPublisher publisher;
    private final ProjectConfig config;
    private final String projectName;
    private final Path projectDir;

    private Stage stage;
    private GitHubAccountBar accountBar;

    // Kind
    private final RadioButton kindBot = new RadioButton("A bot people install");
    private final RadioButton kindTemplate = new RadioButton("A starting template for New Project");
    private final Label templateProblem = new Label();

    // Listing
    private final RadioButton listed = new RadioButton("List it in the gallery");
    private final RadioButton unlisted = new RadioButton("Release it on GitHub only");

    // Details
    private final TextField repoField = new TextField();
    private final TextField descriptionField = new TextField();
    private final TextField tagsField = new TextField();
    private final FlowPane tagChips = new FlowPane(6, 4);
    private final Label targetsLabel = new Label();
    private final Label requiresLabel = new Label("Reading the plugin registry…");

    // Release
    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final Label lastPublished = new Label();

    // Preview, progress, listing
    private final StackPane previewHolder = new StackPane();
    private final Label previewNote = new Label();
    private final VBox checklist = new VBox(4);
    private final Button retryButton = new Button("Retry");
    private final Label listingLabel = new Label("Sign in to see your listing.");
    private final Label tierLabel = new Label();
    private final Hyperlink pullRequestLink = new Hyperlink();
    private final Button refreshListingButton = new Button("Refresh");
    private final Button unpublishButton = new Button("Unpublish");

    private final Button publishButton = new Button("Publish");
    /** What the last action did. Kept apart from {@link #hintLabel}, which every keystroke rewrites. */
    private final Label statusLabel = new Label();
    /** Why Publish is disabled, when it is. */
    private final Label hintLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    private final SupportedTargets launchTargets;

    /** The repo's latest published release tag (""=none); each new version must be strictly greater. */
    private volatile String latestTag = "";
    /** The signed-in login, once known, for the preview's "by" line. */
    private volatile String login = "";
    private volatile List<GalleryEntry.Requirement> requires = List.of();
    /** The gallery's listing of this repo, when it has one: its tier is what the preview shows. */
    private volatile GalleryEntry currentListing;

    /** The publish shown in the checklist; null before the first. */
    private BotPublisher.Run run;
    private boolean busy;

    /**
     * @param config the open project. The "tested on" declaration is persisted into the project's own
     *               properties, so the dialog needs the resources dir as well as the name and directory.
     */
    public PublishDialog(Window owner, GitHubAuth auth, GitHubClient client, GitHubGallery gallery,
                         BotPublisher publisher, ProjectConfig config) {
        this.owner = owner;
        this.auth = auth;
        this.client = client;
        this.gallery = gallery;
        this.publisher = publisher;
        this.config = config;
        this.projectName = config.projectName();
        this.projectDir = config.projectPath();
        this.launchTargets = ProjectCreator.readSupportedTargets(config.resourcesRoot());
    }

    public void show() {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Publish " + projectName);

        if (!auth.isConfigured()) {
            VBox root = new VBox(14, section("Publishing is not configured"),
                    wrapped("This build has no GitHub OAuth client id, so publishing is disabled. "
                            + "Browsing and installing bots still works."),
                    closeBar());
            root.setPadding(new Insets(16));
            stage.setScene(ThemedWindows.scene(root, 460, 200));
            stage.show();
            return;
        }

        accountBar = new GitHubAccountBar(stage, auth, client, this::onAuthChanged);

        HBox columns = new HBox(18, scrolled(buildForm()), buildSide());
        HBox.setHgrow(columns.getChildren().get(0), Priority.ALWAYS);
        VBox.setVgrow(columns, Priority.ALWAYS);

        VBox root = new VBox(12, accountBar, columns, buildButtonBar());
        root.setPadding(new Insets(16));
        stage.setScene(ThemedWindows.scene(root, 900, 640));
        stage.show();

        renderChecklist(PublishPlan.start(true));
        refreshAll();
        loadRequirements();
        onAuthChanged();
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private VBox buildForm() {
        ToggleGroup kind = new ToggleGroup();
        kindBot.setToggleGroup(kind);
        kindTemplate.setToggleGroup(kind);
        // A project carrying the template file was almost certainly written to be one; that is only the
        // starting choice, and the author can change it.
        boolean wasTemplate = Files.exists(projectDir.resolve(TemplateProject.FILE_NAME));
        (wasTemplate ? kindTemplate : kindBot).setSelected(true);
        kind.selectedToggleProperty().addListener((o, was, now) -> refreshAll());
        templateProblem.getStyleClass().add("form-problem");
        templateProblem.setWrapText(true);
        Label kindHelp = note("A template is listed in New Project, where people start their own project from it "
                + "under their own package. It needs a " + TemplateProject.FILE_NAME + " naming yours.");

        ToggleGroup listing = new ToggleGroup();
        listed.setToggleGroup(listing);
        unlisted.setToggleGroup(listing);
        listed.setSelected(true);
        listing.selectedToggleProperty().addListener((o, was, now) -> refreshAll());
        Label listingHelp = note("Listed bots join the gallery as Community once its checks pass, with no one to "
                + "wait for: the entry is well formed, you own the repository, and the release downloads. Up to "
                + "3 new listings a day; updating yours never counts. A maintainer may later vet a release.");

        repoField.setText(projectName);
        repoField.textProperty().addListener((o, was, now) -> refreshAll());
        // Re-propose a version and re-read the listing when the repo name settles, not per keystroke.
        repoField.focusedProperty().addListener((o, was, focused) -> {
            if (was && !focused) {
                proposeVersion();
                refreshListing();
            }
        });
        descriptionField.setPromptText("One sentence shown in the gallery");
        descriptionField.textProperty().addListener((o, was, now) -> refreshAll());
        tagsField.setPromptText("Comma-separated, e.g. clicker, farming");
        tagsField.textProperty().addListener((o, was, now) -> refreshAll());
        targetsLabel.setWrapText(true);
        targetsLabel.setText(launchTargets.declared()
                ? launchTargets.describe()
                : "Not declared, which reads as \"the author never said\". It is the "
                        + SupportedTargets.KEY + " key in botmaker-project.properties.");
        requiresLabel.setWrapText(true);

        GridPane details = new GridPane();
        details.setHgap(10);
        details.setVgap(8);
        details.addRow(0, new Label("Repository:"), repoField);
        details.addRow(1, new Label("Description:"), descriptionField);
        details.addRow(2, new Label("Tags:"), tagsField);
        details.add(tagChips, 1, 3);
        details.addRow(4, new Label("Tested on:"), targetsLabel);
        details.addRow(5, new Label("Requires:"), requiresLabel);
        GridPane.setHgrow(repoField, Priority.ALWAYS);

        versionCombo.setEditable(true);
        versionCombo.setValue(SemVer.FIRST);
        versionCombo.setMaxWidth(Double.MAX_VALUE);
        versionCombo.valueProperty().addListener((o, was, now) -> refreshAll());
        versionCombo.getEditor().textProperty().addListener((o, was, now) -> refreshAll());
        lastPublished.getStyleClass().add("gallery-card-note");
        GridPane release = new GridPane();
        release.setHgap(10);
        release.setVgap(8);
        release.addRow(0, new Label("Version (tag):"), versionCombo);
        release.add(lastPublished, 1, 1);
        GridPane.setHgrow(versionCombo, Priority.ALWAYS);

        VBox form = new VBox(8,
                section("Kind"), kindBot, kindTemplate, kindHelp, templateProblem,
                section("Listing"), listed, unlisted, listingHelp,
                section("Details"), details,
                section("Release"), release);
        form.setPadding(new Insets(0, 8, 0, 0));
        return form;
    }

    private VBox buildSide() {
        previewNote.getStyleClass().add("gallery-card-note");
        previewNote.setWrapText(true);
        previewHolder.getStyleClass().add("gallery-card-preview");

        retryButton.setVisible(false);
        retryButton.setManaged(false);
        retryButton.setOnAction(e -> resume());

        tierLabel.getStyleClass().add("gallery-card-note");
        listingLabel.setWrapText(true);
        pullRequestLink.setVisible(false);
        pullRequestLink.setManaged(false);
        pullRequestLink.setOnAction(e -> BrowserLauncher.open(pullRequestLink.getText()));
        refreshListingButton.setOnAction(e -> refreshListing());
        // Delist-only: leaves the author's repo and releases intact, removes the bot from discovery.
        unpublishButton.setOnAction(e -> doUnpublish());
        HBox listingButtons = new HBox(8, refreshListingButton, unpublishButton);

        VBox side = new VBox(8,
                section("Preview"), previewHolder, previewNote,
                section("Progress"), checklist, retryButton,
                section("Your listing"), listingLabel, tierLabel, pullRequestLink, listingButtons);
        side.setPrefWidth(360);
        side.setMinWidth(320);
        return side;
    }

    private HBox buildButtonBar() {
        statusLabel.setWrapText(true);
        hintLabel.setWrapText(true);
        hintLabel.getStyleClass().add("gallery-card-note");
        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        publishButton.setDefaultButton(true);
        publishButton.setOnAction(e -> doPublish());
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        VBox messages = new VBox(2, statusLabel, hintLabel);
        HBox.setHgrow(messages, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, messages, close, publishButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // -------------------------------------------------------------------------
    // State → view
    // -------------------------------------------------------------------------

    private void onAuthChanged() {
        login = "";
        if (auth.isAuthenticated()) {
            auth.login(client).thenAccept(name -> Platform.runLater(() -> {
                login = name == null ? "" : name;
                refreshAll();
            }));
        }
        refreshAll();
        proposeVersion();
        refreshListing();
    }

    /** Everything derived from the form: the preview, the tag chips, the template check and Publish's state. */
    private void refreshAll() {
        List<String> tags = effectiveTags();
        tagChips.getChildren().setAll(tags.stream().map(t -> {
            Label chip = new Label(t);
            chip.getStyleClass().add("runner-category-chip");
            return chip;
        }).toList());

        String problem = kindTemplate.isSelected() ? templateProblem() : null;
        templateProblem.setText(problem == null ? "" : problem);
        templateProblem.setVisible(problem != null);
        templateProblem.setManaged(problem != null);

        GalleryEntry listing = currentListing;
        GalleryTier tier = listing != null ? listing.tier() : GalleryTier.COMMUNITY;
        String vetted = listing != null ? listing.vettedVersion() : "";
        GalleryEntry preview = request(tags).preview(login, tier, vetted);
        previewHolder.getChildren().setAll(GalleryCard.of(preview));
        previewHolder.setOpacity(listed.isSelected() ? 1.0 : 0.5);
        previewNote.setText(!listed.isSelected()
                ? "Not listed: nobody browsing the gallery sees this card."
                : listing != null && listing.isVetted()
                        ? "Vetted at " + vetted + ". People keep installing that release; this one is not vetted "
                                + "until a maintainer looks at it."
                        : kindTemplate.isSelected()
                                ? "Shown in New Project behind \"Show community templates\" until a maintainer vets it."
                                : "How Browse Bots will show it.");

        lastPublished.setText(latestTag.isBlank() ? "Never published." : "Last published: " + latestTag);
        refreshPublishEnabled(problem);
    }

    /** Publish is enabled only when signed in, idle, the template (if any) is sound and the version is valid. */
    private void refreshPublishEnabled(String templateProblem) {
        boolean signedIn = auth.isAuthenticated();
        unpublishButton.setDisable(busy || !signedIn);
        refreshListingButton.setDisable(busy || !signedIn);
        String version = currentVersion();
        String reason = !signedIn ? "Sign in to GitHub to publish."
                : repoName().isBlank() ? "A repository name is required."
                : templateProblem != null ? "Fix the template first, or publish it as a bot."
                : !SemVer.isValid(version) ? "Version must look like MAJOR.MINOR.PATCH (e.g. 1.0.0)."
                : !SemVer.isGreater(version, latestTag)
                        ? "Version must be higher than the last published version (" + latestTag + ")."
                : null;
        publishButton.setDisable(busy || reason != null);
        hintLabel.setText(busy || reason == null ? "" : reason);
    }

    private void renderChecklist(PublishPlan plan) {
        checklist.getChildren().setAll(plan.steps().stream().map(PublishDialog::stepRow).toList());
        boolean failed = plan.failure().isPresent();
        retryButton.setVisible(failed && !busy);
        retryButton.setManaged(failed && !busy);
        if (failed) {
            retryButton.setText("Retry from " + plan.failure().get().step().label().toLowerCase());
        }
    }

    private static HBox stepRow(PublishPlan.StepState state) {
        String mark = switch (state.status()) {
            case PENDING -> "○";
            case RUNNING -> "…";
            case DONE -> "✓";
            case FAILED -> "✗";
            case SKIPPED -> "–";
        };
        Label markLabel = new Label(mark);
        markLabel.getStyleClass().add("publish-step-mark");
        Label name = new Label(state.step().label());
        name.setTooltip(new Tooltip(state.step().description()));
        Label detail = new Label(state.detail());
        detail.getStyleClass().add("publish-step-detail");
        detail.setWrapText(true);
        VBox text = new VBox(1, name);
        if (!state.detail().isBlank()) text.getChildren().add(detail);
        HBox row = new HBox(6, markLabel, text);
        row.getStyleClass().add("publish-step-" + state.status().name().toLowerCase());
        return row;
    }

    private void renderListing(ListingStatus status) {
        listingLabel.setText(status.describe());
        boolean hasPr = !status.url().isBlank();
        pullRequestLink.setText(status.url());
        pullRequestLink.setVisible(hasPr);
        pullRequestLink.setManaged(hasPr);
    }

    private void renderTier() {
        GalleryEntry listing = currentListing;
        tierLabel.setText(listing == null ? ""
                : listing.isVetted()
                        ? "Tier: Vetted" + (listing.vettedVersion().isEmpty() ? "" : " at " + listing.vettedVersion())
                        : "Tier: Community");
    }

    // -------------------------------------------------------------------------
    // Background reads
    // -------------------------------------------------------------------------

    /**
     * Resolves the repo's latest release tag (the baseline), seeds the version combo with the patch/minor/major
     * bumps after it, and selects the next patch. Best-effort, off the FX thread.
     */
    private void proposeVersion() {
        if (!auth.isAuthenticated()) return;
        String repo = repoName();
        if (repo.isBlank()) return;
        CompletableFuture
                .supplyAsync(() -> {
                    String name = auth.login(client).join();
                    String latest = name.isBlank() ? "" : gallery.latestReleaseTag(name, repo).join();
                    if (latest.isBlank()) {
                        // No GitHub release yet — fall back to local provenance (if any).
                        latest = BotSource.read(projectDir).map(BotSource::tag).filter(SemVer::isValid).orElse("");
                    }
                    return latest;
                })
                .thenAccept(latest -> Platform.runLater(() -> seedVersions(latest)));
    }

    /** Re-seeds the version combo with bump suggestions after {@code baseline} and selects the next patch. */
    private void seedVersions(String baseline) {
        latestTag = baseline == null ? "" : baseline;
        String basis = SemVer.isValid(latestTag) ? latestTag : SemVer.FIRST;
        // When there is a baseline, bump it; when there is none, FIRST itself is the first valid value.
        String patch = SemVer.isValid(latestTag) ? SemVer.next(basis) : SemVer.FIRST;
        List<String> options = new ArrayList<>();
        options.add(patch);
        options.add(SemVer.nextMinor(basis));
        options.add(SemVer.nextMajor(basis));
        versionCombo.getItems().setAll(options.stream().distinct().toList());
        versionCombo.setValue(patch);
        refreshAll();
    }

    /**
     * The plugins this project's pom declares, named as the registry names them — what the entry's
     * {@code requires} will say. An unreachable registry lists nothing, and says so, rather than blocking a
     * publish: {@code requires} is information for a reader, not something the gallery checks strictly.
     */
    private void loadRequirements() {
        new PluginRegistry(client).browse()
                .thenApply(registry -> PublishRequest.requires(MavenService.readDeclaredLibraries(projectDir),
                        MavenService.readProperties(projectDir), registry))
                .whenComplete((found, err) -> Platform.runLater(() -> {
                    requires = found == null ? List.of() : found;
                    requiresLabel.setText(err != null ? "The plugin registry could not be read; none will be listed."
                            : requires.isEmpty() ? "No plugins from the registry."
                            : String.join(", ", requires.stream().map(GalleryEntry.Requirement::describe).toList()));
                    refreshAll();
                }));
    }

    /** The gallery's word on this repo: its tier from the catalog, and the listing pull request's state. */
    private void refreshListing() {
        if (!auth.isAuthenticated()) {
            listingLabel.setText("Sign in to see your listing.");
            return;
        }
        String repo = repoName();
        if (repo.isBlank()) return;
        listingLabel.setText("Reading the gallery…");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return publisher.listingStatus(repo);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((status, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        listingLabel.setText("Couldn't read the listing: " + rootMessage(err));
                    } else {
                        renderListing(status);
                    }
                }));
        gallery.browse().thenAccept(catalog -> auth.login(client).thenAccept(name -> Platform.runLater(() -> {
            currentListing = GitHubGallery.find(catalog, name, repo).orElse(null);
            // A re-publish starts from what the gallery already says, so updating a release does not quietly
            // blank the description and tags the author wrote last time. Only into empty fields, never over
            // what has been typed.
            if (currentListing != null && !busy) {
                if (descriptionField.getText().isBlank()) descriptionField.setText(currentListing.description());
                if (tagsField.getText().isBlank()) {
                    tagsField.setText(String.join(", ", currentListing.tags().stream()
                            .filter(t -> !GalleryEntry.TEMPLATE_TAG.equalsIgnoreCase(t)).toList()));
                }
            }
            renderTier();
            refreshAll();
        })));
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void doPublish() {
        String problem = kindTemplate.isSelected() ? templateProblem() : null;
        if (problem != null || !SemVer.isGreater(currentVersion(), latestTag) || repoName().isBlank()) {
            refreshAll();
            return;
        }
        run = publisher.start(request(effectiveTags()));
        resume();
    }

    /** Runs {@link #run} from its first unfinished step, off the FX thread, mirroring each step as it goes. */
    private void resume() {
        if (run == null || busy) return;
        BotPublisher.Run current = run;
        setBusy(true);
        statusLabel.setText("Publishing " + current.request().version() + "…");
        CompletableFuture
                .supplyAsync(() -> current.resume(plan -> Platform.runLater(() -> renderChecklist(plan))))
                .whenComplete((plan, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        statusLabel.setText("Publish stopped: " + rootMessage(err));
                        return;
                    }
                    renderChecklist(plan);
                    if (plan.finished()) {
                        seedVersions(current.request().version()); // the new baseline
                        statusLabel.setText("Published " + current.request().version() + ".");
                        if (current.request().listed()) renderListing(current.listing());
                        refreshListing();
                    } else {
                        statusLabel.setText(plan.failure()
                                .map(f -> f.step().label() + " failed. Fix the cause and retry; the steps done "
                                        + "are not repeated.")
                                .orElse(""));
                    }
                }));
    }

    /**
     * Delists the bot (the reverse of the listing step). The author's GitHub repo and releases are left intact —
     * this only removes it from discovery. Runs off the FX thread.
     */
    private void doUnpublish() {
        String repo = repoName();
        if (repo.isBlank()) return;
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                "Remove “" + repo + "” from the gallery? Your GitHub repo and releases stay intact — "
                        + "this only delists it from discovery, through the same kind of pull request a listing is.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Unpublish from the gallery?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        setBusy(true);
        statusLabel.setText("Unpublishing…");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return publisher.unpublish(repo);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((status, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        statusLabel.setText("Unpublish failed: " + rootMessage(err));
                    } else {
                        statusLabel.setText("Removal submitted.");
                        renderListing(status);
                    }
                }));
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisible(value);
        for (javafx.scene.Node n : List.of(kindBot, kindTemplate, listed, unlisted, repoField, descriptionField,
                tagsField, versionCombo)) {
            n.setDisable(value);
        }
        if (run != null) renderChecklist(run.plan());
        refreshAll();
    }

    // -------------------------------------------------------------------------
    // Form values
    // -------------------------------------------------------------------------

    private PublishRequest request(List<String> tags) {
        return new PublishRequest(projectDir, projectName, repoName(), descriptionField.getText(), currentVersion(),
                tags, launchTargets, requires, listed.isSelected());
    }

    /** The typed tags, with {@code template} added for a template and taken out for a bot. */
    private List<String> effectiveTags() {
        List<String> tags = new ArrayList<>(parseTags(tagsField.getText()));
        tags.removeIf(GalleryEntry.TEMPLATE_TAG::equalsIgnoreCase);
        if (kindTemplate.isSelected()) tags.add(GalleryEntry.TEMPLATE_TAG);
        return tags;
    }

    private String repoName() {
        return repoField.getText() == null ? "" : repoField.getText().trim();
    }

    /** What the version box shows, typed or picked: an editable combo commits its value only on Enter. */
    private String currentVersion() {
        String typed = versionCombo.getEditor().getText();
        String v = typed == null || typed.isBlank() ? versionCombo.getValue() : typed;
        return v == null ? "" : v.trim();
    }

    /**
     * Why this project cannot be published as a template, or null when it can.
     *
     * <p>The one check worth making, and it is made here because it is the only failure whose result still
     * <em>compiles</em>: a template whose declared package is not the one its sources are in unpacks into
     * somebody's New Project, has nothing replaced, and hands them a working project sitting in the author's
     * package. Every other way a template can be wrong shows up as an ordinary broken project.
     */
    private String templateProblem() {
        try {
            return TemplateProject.read(projectDir).matches(projectDir)
                    ? null
                    : "Your " + TemplateProject.FILE_NAME + " names a package that has no sources in it, so "
                            + "nothing would be renamed when somebody starts from this template.";
        } catch (java.io.IOException missing) {
            return missing.getMessage();
        }
    }

    /** Splits a comma-separated tag string into trimmed, de-duped, non-blank tags. */
    static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : Arrays.asList(raw.split(","))) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) seen.add(trimmed);
        }
        return new ArrayList<>(seen);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ScrollPane scrolled(VBox content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private HBox closeBar() {
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(spacer, close);
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-section-title");
        return l;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("gallery-card-note");
        l.setWrapText(true);
        return l;
    }

    private static Label wrapped(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        return l;
    }

    private static String rootMessage(Throwable t) {
        if (t == null) return "unknown error";
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
