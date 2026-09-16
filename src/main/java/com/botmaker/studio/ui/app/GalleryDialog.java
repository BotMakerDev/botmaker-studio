package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.ProjectInfo;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GalleryTier;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Browse / install / update bots from the federated GitHub gallery (no GitHub account required). Reads the
 * curated catalog via {@link GitHubGallery}; installs and updates run off the FX thread via
 * {@link BotInstaller}. Installing a bot means running someone else's automation code, so installs are gated
 * by a trust/attribution confirmation.
 */
public class GalleryDialog {

    /** How the browse list is ordered. */
    private enum Sort { STARS("Stars"), UPDATED("Recently updated"), NAME("Name");
        final String label; Sort(String label) { this.label = label; }
        @Override public String toString() { return label; } }

    /** Which tiers the browse list shows. {@code tier} is null for both. */
    private enum TierFilter { ALL("All bots", null), VETTED("Vetted", GalleryTier.VETTED),
        COMMUNITY("Community", GalleryTier.COMMUNITY);
        final String label; final GalleryTier tier;
        TierFilter(String label, GalleryTier tier) { this.label = label; this.tier = tier; }
        boolean admits(GalleryEntry e) { return tier == null || e.tier() == tier; }
        @Override public String toString() { return label; } }

    private final Window owner;
    private final GitHubGallery gallery;
    private final BotInstaller installer;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final ProjectManager projectManager = new ProjectManager();

    private final ObservableList<GalleryEntry> allEntries = FXCollections.observableArrayList();
    private final ObservableList<GalleryEntry> shownEntries = FXCollections.observableArrayList();
    private final ObservableList<InstalledBot> installed = FXCollections.observableArrayList();

    /** Live per-repo signals (stars, last-push) keyed by {@code owner/repo}, filled in lazily after browse. */
    private final java.util.Map<String, GitHubGallery.RepoMeta> metaBySlug = new java.util.concurrent.ConcurrentHashMap<>();

    private final ProgressIndicator browseProgress = new ProgressIndicator();
    private final javafx.scene.control.ComboBox<Sort> sortBox = new javafx.scene.control.ComboBox<>();
    private final javafx.scene.control.ComboBox<TierFilter> tierBox = new javafx.scene.control.ComboBox<>();
    private String currentQuery = "";
    private Stage stage;

    /**
     * The gallery's listings, fetched once per window. Both tabs read it: Browse to list, Installed to know
     * whether an installed bot is Vetted, which decides the release it is offered.
     */
    private CompletableFuture<List<GalleryEntry>> catalog = CompletableFuture.completedFuture(List.of());

    public GalleryDialog(Window owner, GitHubGallery gallery, BotInstaller installer,
                         GitHubAuth auth, GitHubClient client) {
        this.owner = owner;
        this.gallery = gallery;
        this.installer = installer;
        this.auth = auth;
        this.client = client;
    }

    public void show() {
        show(null);
    }

    /** Shows the gallery; {@code onClosed} (if non-null) runs when the window is dismissed. */
    public void show(Runnable onClosed) {
        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Bot Gallery");
        if (onClosed != null) stage.setOnHidden(e -> onClosed.run());

        TabPane tabs = new TabPane();
        Tab browseTab = new Tab("Browse", buildBrowseTab());
        browseTab.setClosable(false);
        Tab installedTab = new Tab("Installed", buildInstalledTab());
        installedTab.setClosable(false);
        tabs.getTabs().addAll(browseTab, installedTab);

        stage.setScene(ThemedWindows.scene(tabs, 680, 520));
        stage.show();

        catalog = gallery.browse().exceptionally(err -> List.of());
        refreshBrowse();
        refreshInstalled();
    }

    // -------------------------------------------------------------------------
    // Browse tab
    // -------------------------------------------------------------------------

    private VBox buildBrowseTab() {
        TextField searchField = new TextField();
        searchField.setPromptText("Search the gallery…");
        searchField.textProperty().addListener((o, old, q) -> { currentQuery = q; applyFilter(); });
        HBox.setHgrow(searchField, Priority.ALWAYS);

        sortBox.getItems().setAll(Sort.values());
        sortBox.getSelectionModel().select(Sort.STARS);
        sortBox.setOnAction(e -> applyFilter());
        Label sortLabel = new Label("Sort:");

        tierBox.getItems().setAll(TierFilter.values());
        tierBox.getSelectionModel().select(TierFilter.ALL);
        tierBox.setOnAction(e -> applyFilter());
        tierBox.setTooltip(new javafx.scene.control.Tooltip(
                "Vetted: a maintainer looked at one release of it.\n"
                        + "Community: listed automatically. Nobody reviewed its code."));

        browseProgress.setVisible(false);
        browseProgress.setPrefSize(18, 18);
        HBox searchRow = new HBox(8, searchField, tierBox, sortLabel, sortBox, browseProgress);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        ListView<GalleryEntry> list = new ListView<>(shownEntries);
        list.setPlaceholder(new Label(GitHubConfig.isGalleryConfigured()
                ? "No bots found. Be the first to publish one!"
                : "The gallery is not configured yet."));
        list.setCellFactory(lv -> new BrowseCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        Hyperlink repoLink = new Hyperlink("View gallery repo on GitHub");
        repoLink.setOnAction(e -> BrowserLauncher.open(
                "https://github.com/" + GitHubConfig.INDEX_OWNER + "/" + GitHubConfig.INDEX_REPO));

        VBox box = new VBox(10, searchRow, list, repoLink);
        box.setPadding(new Insets(14));
        return box;
    }

    private void refreshBrowse() {
        browseProgress.setVisible(true);
        catalog.whenComplete((entries, err) -> Platform.runLater(() -> {
            browseProgress.setVisible(false);
            // Templates are in the same index and are not bots to install: they are starting points, offered
            // by New Project. Installing one would give the user somebody else's package and a project with
            // no name of their own, which is exactly what the template path exists to avoid.
            allEntries.setAll(entries == null
                    ? List.of()
                    : entries.stream().filter(e -> !e.isTemplate()).toList());
            applyFilter();
            // Fetch each repo's live stars/last-push so the Stars/Recently-updated sorts and the ★ counts work.
            for (GalleryEntry e : allEntries) {
                String slug = slug(e);
                gallery.repoMeta(e.owner(), e.repo()).thenAccept(meta -> Platform.runLater(() -> {
                    if (meta == null) return;   // unreachable — keep whatever count we already show
                    metaBySlug.put(slug, meta);
                    applyFilter();     // re-sort as counts arrive
                }));
            }
        }));
    }

    private void applyFilter() {
        List<GalleryEntry> filtered = new ArrayList<>();
        TierFilter tiers = tierBox.getSelectionModel().getSelectedItem();
        if (tiers == null) tiers = TierFilter.ALL;
        for (GalleryEntry e : allEntries) {
            if (e.matches(currentQuery) && tiers.admits(e)) filtered.add(e);
        }
        Sort sort = sortBox.getSelectionModel().getSelectedItem();
        if (sort == null) sort = Sort.STARS;
        java.util.Comparator<GalleryEntry> cmp = switch (sort) {
            case NAME -> java.util.Comparator.comparing(e -> e.name().toLowerCase());
            case STARS -> java.util.Comparator.comparingInt((GalleryEntry e) -> metaOf(e).stars()).reversed();
            case UPDATED -> java.util.Comparator.comparingLong((GalleryEntry e) -> metaOf(e).pushedAt()).reversed();
        };
        filtered.sort(cmp);
        shownEntries.setAll(filtered);
    }

    private GitHubGallery.RepoMeta metaOf(GalleryEntry e) {
        return metaBySlug.getOrDefault(slug(e), GitHubGallery.RepoMeta.UNKNOWN);
    }

    private static String slug(GalleryEntry e) {
        return e.owner() + "/" + e.repo();
    }

    /** A gallery row: name, tier + author + description, a link to the repo, with an Install button. */
    private final class BrowseCell extends ListCell<GalleryEntry> {
        @Override
        protected void updateItem(GalleryEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(entry.name());
            name.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            HBox title = new HBox(8, name, tierBadge(entry));
            title.setAlignment(Pos.CENTER_LEFT);
            Label meta = new Label("by " + entry.owner()
                    + (entry.description().isBlank() ? "" : " — " + entry.description()));
            meta.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
            meta.setWrapText(true);
            VBox text = new VBox(2, title, meta);
            // Only when the entry says: an empty list is every entry written before the field existed, and
            // reads as "unknown", which is not worth a line saying "Requires: nothing".
            if (!entry.requires().isEmpty()) {
                Label requires = new Label("Requires: " + entry.requires().stream()
                        .map(GalleryEntry.Requirement::describe)
                        .collect(java.util.stream.Collectors.joining(", ")));
                requires.getStyleClass().add("gallery-card-note");
                requires.setWrapText(true);
                text.getChildren().add(requires);
            }
            // Only shown when the author declared something: "tested on: any launch target" would be noise on
            // every entry published before the field existed, which is most of them. "Tested on" rather than
            // "runs on" because installing never restricts what you may launch — see LaunchTargetDialog.
            if (entry.launchTargets().declared()) {
                Label runsOn = new Label("Tested on: " + entry.launchTargets().describe());
                runsOn.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
                runsOn.setWrapText(true);
                text.getChildren().add(runsOn);
            }
            // The one way to read what you are about to run before running it. A Hyperlink rather than a
            // button: it leaves Studio, and a button reads as something done to the bot.
            Hyperlink repoLink = new Hyperlink("Open on GitHub");
            repoLink.getStyleClass().add("gallery-repo-link");
            repoLink.setTooltip(new javafx.scene.control.Tooltip(entry.htmlUrl()));
            repoLink.setOnAction(e -> BrowserLauncher.open(entry.htmlUrl()));
            text.getChildren().add(repoLink);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            int stars = metaOf(entry).stars();
            Label starCount = new Label("★ " + stars);
            starCount.setStyle("-fx-text-fill: #9a6700; -fx-font-size: 11px;");

            Button starBtn = new Button("Star");
            starBtn.setOnAction(e -> toggleStar(entry, starBtn, starCount));
            reflectStarState(entry, starBtn);

            Button installBtn = new Button(installer.isInstalled(entry.name()) ? "Installed" : "Install");
            installBtn.setDisable(installer.isInstalled(entry.name()));
            installBtn.setOnAction(e -> installEntry(entry, installBtn));

            HBox row = new HBox(10, text, spacer, starCount, starBtn, installBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }

    /**
     * The tier as a small badge, with the sentence behind it on hover. A Vetted badge names the release that
     * was looked at, because that — not the bot — is what a maintainer vouched for.
     */
    private static Label tierBadge(GalleryEntry entry) {
        Label badge = new Label(entry.tier().displayName());
        badge.getStyleClass().addAll("gallery-tier-badge",
                entry.isVetted() ? "gallery-tier-vetted" : "gallery-tier-community");
        String tip = entry.isVetted()
                ? "A maintainer looked at "
                        + (entry.vettedVersion().isEmpty() ? "a release" : "release " + entry.vettedVersion())
                        + " and chose to list it. Not a security review."
                : "Listed automatically: its author owns the repository and its release downloads. "
                        + "Nobody reviewed its code.";
        badge.setTooltip(new javafx.scene.control.Tooltip(tip));
        return badge;
    }

    /** Reflects whether the signed-in user has starred this bot (best-effort; leaves "Star" when signed out). */
    private void reflectStarState(GalleryEntry entry, Button starBtn) {
        if (auth == null || !auth.isAuthenticated()) return;
        gallery.isStarred(entry.owner(), entry.repo(), auth.token())
                .thenAccept(starred -> Platform.runLater(() -> starBtn.setText(starred ? "Starred" : "Star")));
    }

    private void toggleStar(GalleryEntry entry, Button starBtn, Label starCount) {
        if (auth == null || !auth.isAuthenticated()) {
            info("Sign in to star", "Sign in to GitHub (the account button, top-right of the editor) to star "
                    + "bots. Your stars count on github.com too.");
            return;
        }
        boolean wasStarred = "Starred".equals(starBtn.getText());
        boolean nowStarred = !wasStarred;
        starBtn.setDisable(true);
        gallery.setStarred(entry.owner(), entry.repo(), nowStarred, auth.token())
                .whenComplete((v, err) -> Platform.runLater(() -> {
                    starBtn.setDisable(false);
                    if (err != null) {
                        error("Couldn't update star", rootMessage(err));
                        return;
                    }
                    starBtn.setText(nowStarred ? "Starred" : "Star");
                    // Reflect the optimistic count locally and refresh from GitHub.
                    GitHubGallery.RepoMeta m = metaOf(entry);
                    int updated = Math.max(0, m.stars() + (nowStarred ? 1 : -1));
                    metaBySlug.put(slug(entry), new GitHubGallery.RepoMeta(updated, m.pushedAt()));
                    starCount.setText("★ " + updated);
                }));
    }

    private void installEntry(GalleryEntry entry, Button installBtn) {
        String vouched = entry.isVetted()
                ? "A maintainer looked at "
                        + (entry.vettedVersion().isEmpty()
                                ? "a release of it" : "release " + entry.vettedVersion() + ", which is the one installed")
                        + ". That is not a security review."
                : "It is a Community bot: it was listed automatically, and nobody reviewed its code.";
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                "“" + entry.name() + "” by " + entry.owner() + " is a bot that can control your mouse, "
                        + "keyboard and screen. " + vouched + " Only install bots from authors you trust."
                        + "\n\nInstall it now?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText(entry.isVetted() ? "Install a vetted bot?" : "Install a community bot?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        installBtn.setDisable(true);
        installBtn.setText("Installing…");
        CompletableFuture
                .supplyAsync(() -> {
                    // The vetted release when there is one, so a Vetted bot installs what was looked at rather
                    // than whatever its author released since.
                    String tag = gallery.installTag(entry).join();
                    if (tag.isBlank()) {
                        throw new RuntimeException("No release was found for " + entry.owner() + "/"
                                + entry.repo() + ". Either its author hasn't published one yet, or the repo "
                                + "isn't public — signing in to GitHub lets the gallery read your own.");
                    }
                    try {
                        return installer.install(entry, tag).getFileName().toString();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((dirName, err) -> Platform.runLater(() -> {
                    if (err != null) {
                        installBtn.setDisable(false);
                        installBtn.setText("Install");
                        error("Install failed", rootMessage(err));
                    } else {
                        installBtn.setText("Installed");
                        info("Installed", "“" + entry.name() + "” was installed as project “" + dirName
                                + "”.\nOpen it from File → Select Project.");
                        refreshInstalled();
                    }
                }));
    }

    // -------------------------------------------------------------------------
    // Installed tab
    // -------------------------------------------------------------------------

    private VBox buildInstalledTab() {
        ListView<InstalledBot> list = new ListView<>(installed);
        list.setPlaceholder(new Label("No bots installed from the gallery yet."));
        list.setCellFactory(lv -> new InstalledCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        VBox box = new VBox(10, new Label("Bots installed from the gallery:"), list);
        box.setPadding(new Insets(14));
        return box;
    }

    private void refreshInstalled() {
        List<InstalledBot> bots = new ArrayList<>();
        for (ProjectInfo info : projectManager.listProjects()) {
            BotSource.read(info.projectPath())
                    .ifPresent(src -> bots.add(new InstalledBot(info, src)));
        }
        installed.setAll(bots);
    }

    /** An installed bot with provenance; the latest tag is resolved lazily per row. */
    private record InstalledBot(ProjectInfo info, BotSource source) {}

    private final class InstalledCell extends ListCell<InstalledBot> {
        @Override
        protected void updateItem(InstalledBot bot, boolean empty) {
            super.updateItem(bot, empty);
            if (empty || bot == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(bot.info().name());
            name.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
            Label meta = new Label("from " + bot.source().slug() + " @ " + bot.source().tag());
            meta.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
            VBox text = new VBox(2, name, meta);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label status = new Label("checking…");
            status.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            Button updateBtn = new Button("Update");
            updateBtn.setDisable(true);

            HBox row = new HBox(10, text, spacer, status, updateBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);

            // Resolve update availability off-thread.
            CompletableFuture
                    .supplyAsync(() -> installer.checkForUpdate(bot.info().projectPath(), catalog.join()))
                    .whenComplete((latest, err) -> Platform.runLater(() -> {
                        if (err != null || latest == null || latest.isEmpty()) {
                            status.setText("up to date");
                        } else {
                            status.setText("update available: " + latest.get());
                            status.setStyle("-fx-font-size: 11px; -fx-text-fill: #1a7f37;");
                            updateBtn.setDisable(false);
                            updateBtn.setOnAction(e -> updateBot(bot, updateBtn, status));
                        }
                    }));
        }
    }

    private void updateBot(InstalledBot bot, Button updateBtn, Label status) {
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                "Updating will overwrite any local changes to “" + bot.info().name() + "”.\n\nContinue?",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Update this bot?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        updateBtn.setDisable(true);
        updateBtn.setText("Updating…");
        Path dir = bot.info().projectPath();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return installer.update(dir, catalog.join());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex.getMessage(), ex);
                    }
                })
                .whenComplete((updatedTo, err) -> Platform.runLater(() -> {
                    updateBtn.setText("Update");
                    if (err != null) {
                        updateBtn.setDisable(false);
                        error("Update failed", rootMessage(err));
                    } else if (updatedTo != null && updatedTo.isPresent()) {
                        status.setText("updated to " + updatedTo.get());
                        info("Updated", "“" + bot.info().name() + "” is now at " + updatedTo.get() + ".");
                        refreshInstalled();
                    } else {
                        status.setText("up to date");
                    }
                }));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void info(String header, String body) {
        Alert a = ThemedWindows.alert(Alert.AlertType.INFORMATION, body, ButtonType.OK);
        a.initOwner(stage);
        a.setHeaderText(header);
        a.showAndWait();
    }

    private void error(String header, String body) {
        Alert a = ThemedWindows.alert(Alert.AlertType.ERROR, body, ButtonType.OK);
        a.initOwner(stage);
        a.setHeaderText(header);
        a.showAndWait();
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
