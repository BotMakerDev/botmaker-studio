package com.botmaker.studio.ui.app.versions;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.SemVer;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.BotPublisher;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.PublishPlan;
import com.botmaker.studio.sharing.PublishRequest;
import com.botmaker.studio.ui.app.GitHubAccountBar;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/**
 * Publishing, as the Versions tab's side sheet ({@code docs/refactor/39-versions.md} §8): what is published
 * — <i>Kind</i>, <i>Details</i>, <i>Release</i> — then how it will look and how it went: the
 * Browse Bots card built from the form as it stands, the publish's steps with a Retry for the one that failed,
 * and whether the repository is listed (its topic, 2026-10-23), with Unpublish.
 *
 * <p>It replaced {@code PublishDialog} (2026-09-25), a window of its own beside the tab that also pushed. A
 * publish is now a version like any other: the {@link Host} saves it ({@code PUBLISH}), and the run pushes that
 * version to {@code mine} and tags it before the release and listing steps, which {@link BotPublisher.Run}
 * resumes rather than restarts.
 */
public final class PublishSheet {

    /** What the sheet needs from the tab it sits in. */
    public interface Host {
        /**
         * Called on the FX thread when Publish is pressed; the returned task runs off it and saves the project
         * as the version {@code request} publishes, returning the repository the run pushes from.
         */
        Callable<ProjectVcs> prepare(PublishRequest request, String repoOwner);

        /** The strip's model as last read — whose bot this is, and where its copy lives. */
        SyncModel model();

        /** A step changed the history (a version saved, a tag): the timeline reads it again. */
        void changed();

        void close();
    }

    private final Window owner;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final GitHubGallery gallery;
    private final BotPublisher publisher;
    private final Host host;
    private final String projectName;
    private final Path projectDir;

    // The "Tested on" boxes went on 2026-10-23: what a bot launches is the SDK's business, not the listing's.
    // The Kind choice (a bot, or a starting template for New Project) went on 2026-10-23: every published bot is
    // a starting point, and New Project clones it as Browse Bots installs it.

    private final VBox root = new VBox(8);
    private GitHubAccountBar accountBar;


    private final TextField repoField = new TextField();
    private final TextField descriptionField = new TextField();
    private final TextField tagsField = new TextField();
    private final FlowPane tagChips = new FlowPane(6, 4);

    private final ComboBox<String> versionCombo = new ComboBox<>();
    private final Label lastPublished = new Label();

    private final StackPane previewHolder = new StackPane();
    private final Label previewNote = new Label();
    private final VBox checklist = new VBox(4);
    private final Button retryButton = new Button("Retry");
    private final Label listingLabel = new Label("Sign in to see your listing.");
    private final Button refreshListingButton = new Button("Refresh");
    private final Button unpublishButton = new Button("Unpublish");

    private final Button publishButton = new Button("Publish");
    /** What the last action did. Kept apart from {@link #hintLabel}, which every keystroke rewrites. */
    private final Label statusLabel = new Label();
    /** Why Publish is disabled, when it is. */
    private final Label hintLabel = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();

    /** The repo's latest published release tag (""=none); each new version must be strictly greater. */
    private volatile String latestTag = "";
    /** The signed-in login, once known: who publishes. */
    private volatile String login = "";
    /**
     * Whose repository a publish goes to, once known ({@link BotPublisher#ownerFor}): the login, or the owner of
     * the project's copy when the login can push there — an organization's template. The preview's "by" line,
     * the release looked up, the listing read. Blank until both are read.
     */
    private volatile String repoOwner = "";
    /** The pom's pins nobody else can resolve ({@link PublishRequest#unreleasedPins}); Publish waits on none. */
    private List<String> unreleased = List.of();

    /** The publish shown in the checklist; null before the first. */
    private BotPublisher.Run run;
    private boolean busy;

    public PublishSheet(Window owner, ProjectConfig config, GitHubAuth auth, GitHubClient client,
                        GitHubGallery gallery, BotPublisher publisher, Host host) {
        this.owner = owner;
        this.auth = auth;
        this.client = client;
        this.gallery = gallery;
        this.publisher = publisher;
        this.host = host;
        this.projectName = config.projectName();
        this.projectDir = config.projectPath();
        build();
    }

    public Node node() {
        return root;
    }

    /** Re-reads what the sheet shows from outside it — the account, the listing, the last release. */
    public void shown() {
        if (accountBar == null) return;
        readPins();
        refreshAll();
        onAuthChanged();
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private void build() {
        root.getStyleClass().add("versions-sheet");
        root.setPadding(new Insets(8, 10, 8, 10));
        root.setPrefWidth(420);
        root.setMinWidth(340);

        Label title = new Label("Publish " + projectName);
        title.getStyleClass().add(StyleClasses.DIALOG_SUBHEADING);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("✕");
        close.setTooltip(new Tooltip("Close the publish sheet"));
        close.setOnAction(e -> host.close());
        HBox header = new HBox(6, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        if (auth == null || !auth.isConfigured()) {
            root.getChildren().setAll(header, wrapped("This build has no GitHub OAuth client id, so publishing is "
                    + "disabled. Browsing and installing bots still works."));
            return;
        }
        accountBar = new GitHubAccountBar(owner, auth, client, this::onAuthChanged);

        VBox content = new VBox(8);
        content.getChildren().addAll(buildForm().getChildren());
        content.getChildren().addAll(buildSide().getChildren());
        content.setPadding(new Insets(0, 8, 0, 0));
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().setAll(header, accountBar, scroll, buildButtonBar());
        renderChecklist(PublishPlan.start(true));
        readPins();
    }

    /** Re-reads the pom's unreleased pins — the pom may have changed since the sheet was last shown. */
    private void readPins() {
        unreleased = PublishRequest.unreleasedPins(MavenService.readDeclaredLibraries(projectDir),
                MavenService.readProperties(projectDir));
    }

    private VBox buildForm() {
        // Listed/Unlisted stood here until 2026-09-26. Publishing is listing: a release on GitHub alone is
        // what "Save to my copy" already gives, so the choice only asked a question with one useful answer.
        // The gallery's checks, its 3-a-day limit and its pull request went on 2026-10-23: the topic is the listing.
        Label listingHelp = note("Publishing gives your repository the topic botmaker-bot, which lists it in Browse "
                + "Bots and New Project, where people start from a copy linked to yours. Its description and tags "
                + "are the repository's own. To keep it unlisted, use Save to my copy instead.");

        repoField.setText(projectName);
        repoField.textProperty().addListener((o, was, now) -> refreshAll());
        // Re-propose a version and re-read the listing when the repo name settles, not per keystroke.
        repoField.focusedProperty().addListener((o, was, focused) -> {
            if (was && !focused) {
                proposeVersion();
                refreshListing();
            }
        });
        descriptionField.setPromptText("One sentence shown in Browse Bots");
        descriptionField.textProperty().addListener((o, was, now) -> refreshAll());
        tagsField.setPromptText("Comma-separated, e.g. clicker, farming");
        tagsField.textProperty().addListener((o, was, now) -> refreshAll());

        GridPane details = new GridPane();
        details.setHgap(10);
        details.setVgap(8);
        details.addRow(0, new Label("Repository:"), repoField);
        details.addRow(1, new Label("Description:"), descriptionField);
        details.addRow(2, new Label("Tags:"), tagsField);
        details.add(tagChips, 1, 3);
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
        // A publish pushes versions, not a snapshot: what was saved before is on GitHub with it.
        Label releaseHelp = note("Publishing saves the project as a version, tags it and pushes your versions to "
                + "your copy on GitHub, which becomes public — earlier versions included.");

        return new VBox(8,
                section("Browse Bots"), listingHelp,
                section("Details"), details,
                section("Release"), release, releaseHelp);
    }

    private VBox buildSide() {
        previewNote.getStyleClass().add("gallery-card-note");
        previewNote.setWrapText(true);
        previewHolder.getStyleClass().add("gallery-card-preview");

        retryButton.setVisible(false);
        retryButton.setManaged(false);
        retryButton.setOnAction(e -> resume());

        listingLabel.setWrapText(true);
        refreshListingButton.setOnAction(e -> refreshListing());
        // Delist-only: leaves the author's repo and releases intact, removes the bot from discovery.
        unpublishButton.setOnAction(e -> doUnpublish());
        // A footer rather than a section (2026-09-26): whether it is listed is a status line, not a form.
        Region listingSpacer = new Region();
        HBox.setHgrow(listingSpacer, Priority.ALWAYS);
        HBox.setHgrow(listingLabel, Priority.ALWAYS);
        listingLabel.getStyleClass().add("gallery-card-note");
        HBox listingRow = new HBox(8, listingLabel, listingSpacer, refreshListingButton, unpublishButton);
        listingRow.setAlignment(Pos.CENTER_LEFT);
        VBox listingFooter = new VBox(2, listingRow);
        listingFooter.setPadding(new Insets(8, 0, 0, 0));
        listingFooter.getStyleClass().add("publish-listing-footer");

        return new VBox(8,
                section("Preview"), previewHolder, previewNote,
                section("Progress"), checklist, retryButton,
                listingFooter);
    }

    private VBox buildButtonBar() {
        statusLabel.setWrapText(true);
        hintLabel.setWrapText(true);
        hintLabel.getStyleClass().add("gallery-card-note");
        progress.setVisible(false);
        progress.setPrefSize(18, 18);
        publishButton.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
        publishButton.setOnAction(e -> doPublish());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, progress, spacer, publishButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        return new VBox(4, statusLabel, hintLabel, buttons);
    }

    // -------------------------------------------------------------------------
    // State → view
    // -------------------------------------------------------------------------

    private void onAuthChanged() {
        login = "";
        repoOwner = "";
        if (auth.isAuthenticated()) {
            Optional<SyncModel.Slug> mine = host.model().mineSlug();
            auth.login(client).thenApplyAsync(name -> {
                String who = name == null ? "" : name;
                boolean canPush = !who.isBlank() && mine.isPresent() && !mine.get().owner().equalsIgnoreCase(who)
                        && publisher.canPush(mine.get().owner(), mine.get().repo());
                return new String[]{who, who.isBlank() ? "" : BotPublisher.ownerFor(who, mine, canPush)};
            }).thenAccept(read -> Platform.runLater(() -> {
                login = read[0];
                repoOwner = read[1];
                lockToMyCopy();
                refreshAll();
                proposeVersion();
                refreshListing();
            }));
        }
        refreshAll();
    }

    /**
     * When the project's {@code mine} is the repository a publish goes to — the user's own, or one they can push
     * to — that is the one a publish goes to, so the name is not the user's to type.
     */
    private void lockToMyCopy() {
        Optional<SyncModel.Slug> mine = host.model().mineSlug();
        boolean own = mine.isPresent() && mine.get().owner().equalsIgnoreCase(repoOwner);
        if (own) repoField.setText(mine.get().repo());
        repoField.setDisable(own || busy);
    }

    /** Everything derived from the form: the preview, the tag chips and Publish's state. */
    private void refreshAll() {
        if (accountBar == null) return;
        List<String> tags = effectiveTags();
        tagChips.getChildren().setAll(tags.stream().map(t -> {
            Label chip = new Label(t);
            chip.getStyleClass().add("runner-category-chip");
            return chip;
        }).toList());

        GalleryEntry preview = request(tags).preview(repoOwner.isBlank() ? login : repoOwner);
        previewHolder.getChildren().setAll(GalleryCard.of(preview));
        previewNote.setText("How Browse Bots will show it.");

        lastPublished.setText(latestTag.isBlank() ? "Never published." : "Last published: " + latestTag);
        refreshPublishEnabled();
    }

    /** Publish is enabled only when signed in, idle, the bot is the user's and the version valid. */
    private void refreshPublishEnabled() {
        boolean signedIn = auth.isAuthenticated();
        unpublishButton.setDisable(busy || !signedIn);
        refreshListingButton.setDisable(busy || !signedIn);
        String version = currentVersion();
        String reason = !signedIn ? "Sign in to GitHub to publish."
                : repoOwner.isBlank() ? "Reading your GitHub account…"
                : whoseProblem() != null ? whoseProblem()
                : !unreleased.isEmpty() ? "This bot depends on a build only this computer has: "
                        + String.join(", ", unreleased) + ". Pin a released version in Plugins & Libraries first."
                : repoName().isBlank() ? "A repository name is required."
                : !SemVer.isValid(version) ? "Version must look like MAJOR.MINOR.PATCH (e.g. 1.0.0)."
                : !SemVer.isGreater(version, latestTag)
                        ? "Version must be higher than the last published version (" + latestTag + ")."
                : null;
        publishButton.setDisable(busy || reason != null);
        hintLabel.setText(busy || reason == null ? "" : reason);
    }

    /**
     * Why this user cannot publish this project, or null: someone else's bot is suggested to its author, and a
     * copy on another account is not the user's to release from.
     */
    private String whoseProblem() {
        SyncModel model = host.model();
        if (model.ownership() == SyncModel.Ownership.OTHERS) {
            return "This bot is " + model.originalSlug().map(s -> s.owner() + "'s").orElse("someone else's")
                    + ". Use Suggest to author… to offer them your change.";
        }
        Optional<SyncModel.Slug> mine = model.mineSlug();
        if (!repoOwner.isBlank() && mine.isPresent()
                && !mine.get().owner().toLowerCase(Locale.ROOT).equals(repoOwner.toLowerCase(Locale.ROOT))) {
            return "This project's copy is github.com/" + mine.get() + ", which your account cannot push to, and a "
                    + "publish goes to your own.";
        }
        return null;
    }

    private void renderChecklist(PublishPlan plan) {
        checklist.getChildren().setAll(plan.steps().stream().map(PublishSheet::stepRow).toList());
        boolean failed = plan.failure().isPresent();
        retryButton.setVisible(failed && !busy);
        retryButton.setManaged(failed && !busy);
        if (failed) {
            retryButton.setText("Retry from " + plan.failure().get().step().label().toLowerCase(Locale.ROOT));
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
        row.getStyleClass().add("publish-step-" + state.status().name().toLowerCase(Locale.ROOT));
        return row;
    }

    private void renderListing(BotPublisher.Listing listing) {
        listingLabel.setText(listing.describe());
    }

    // -------------------------------------------------------------------------
    // Background reads
    // -------------------------------------------------------------------------

    /**
     * Resolves the repo's latest release tag (the baseline), seeds the version combo with the patch/minor/major
     * bumps after it, and selects the next patch. Best-effort, off the FX thread.
     */
    private void proposeVersion() {
        if (!auth.isAuthenticated() || busy) return;
        String repo = repoName();
        String name = repoOwner;
        if (repo.isBlank() || name.isBlank()) return;
        CompletableFuture
                .supplyAsync(() -> {
                    String latest = gallery.latestReleaseTag(name, repo).join();
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

    /** The repository's word on its listing: the topic, and the description and tags a re-publish starts from. */
    private void refreshListing() {
        if (!auth.isAuthenticated()) {
            listingLabel.setText("Sign in to see your listing.");
            return;
        }
        String repo = repoName();
        String name = repoOwner;
        if (repo.isBlank() || name.isBlank()) return;
        listingLabel.setText("Reading the repository…");
        CompletableFuture
                .supplyAsync(() -> publisher.listing(name, repo))
                .thenAccept(listing -> Platform.runLater(() -> {
                    renderListing(listing);
                    // A re-publish starts from what the repository already says, so updating a release does not
                    // quietly blank the description and tags the author wrote last time. Only into empty fields,
                    // never over what has been typed.
                    if (!busy) {
                        if (descriptionField.getText().isBlank()) descriptionField.setText(listing.description());
                        if (tagsField.getText().isBlank()) tagsField.setText(String.join(", ", listing.tags()));
                    }
                    refreshAll();
                }));
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /** Saves the version being published (the {@link Host}'s part), then runs the steps from the first. */
    private void doPublish() {
        String to = repoOwner;
        readPins();
        if (whoseProblem() != null || to.isBlank() || !unreleased.isEmpty()
                || !SemVer.isGreater(currentVersion(), latestTag) || repoName().isBlank()) {
            refreshAll();
            return;
        }
        PublishRequest request = request(effectiveTags());
        Callable<ProjectVcs> save = host.prepare(request, to);
        setBusy(true);
        statusLabel.setText("Saving " + request.version() + "…");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return save.call();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((vcs, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    host.changed();
                    if (err != null) {
                        statusLabel.setText("Could not save the version to publish: " + ShareActions.rootMessage(err));
                        return;
                    }
                    run = publisher.start(request, vcs, to);
                    resume();
                }));
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
                    host.changed();
                    if (err != null) {
                        statusLabel.setText("Publish stopped: " + ShareActions.rootMessage(err));
                        return;
                    }
                    renderChecklist(plan);
                    if (plan.finished()) {
                        seedVersions(current.request().version()); // the new baseline
                        statusLabel.setText("Published " + current.request().version() + ".");
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
     * Delists the bot (the reverse of the listing step): the topic comes off. The author's GitHub repo and releases
     * are left intact — this only removes it from discovery. Runs off the FX thread.
     */
    private void doUnpublish() {
        String repo = repoName();
        String from = repoOwner;
        if (repo.isBlank() || from.isBlank()) return;
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                "Remove “" + repo + "” from Browse Bots? Your GitHub repo and releases stay intact — this takes "
                        + "the topic botmaker-bot off it, and people who started from it stay linked.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(owner);
        confirm.setHeaderText("Unpublish from Browse Bots?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        setBusy(true);
        statusLabel.setText("Unpublishing…");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        publisher.unpublish(from, repo);
                        return null;
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((done, err) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (err != null) {
                        statusLabel.setText("Unpublish failed: " + ShareActions.rootMessage(err));
                    } else {
                        statusLabel.setText("Unpublished. Browse Bots stops listing it within a few minutes.");
                        refreshListing();
                    }
                }));
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisible(value);
        for (Node n : List.of(descriptionField, tagsField, versionCombo)) {
            n.setDisable(value);
        }
        lockToMyCopy();
        if (run != null) renderChecklist(run.plan());
        refreshAll();
    }

    // -------------------------------------------------------------------------
    // Form values
    // -------------------------------------------------------------------------

    private PublishRequest request(List<String> tags) {
        return new PublishRequest(projectDir, projectName, repoName(), descriptionField.getText(), currentVersion(),
                tags, true);
    }

    /** The typed tags. */
    private List<String> effectiveTags() {
        return parseTags(tagsField.getText());
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
}
