package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.ProjectInfo;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Browse / install / update published bots (no GitHub account required). Finds them on GitHub by their topic via
 * {@link GitHubGallery}; installs and updates run off the FX thread via {@link BotInstaller}. Installing a bot
 * means running someone else's automation code, so installs are gated by a trust/attribution confirmation.
 *
 * <p>Anyone can publish, so anyone's bot can turn up: <i>Hide</i> keeps one out of this computer's lists
 * ({@link HiddenBots}, 2026-10-23), and <i>Show hidden</i> brings them back to unhide.
 */
public class GalleryDialog {

    /** How the browse list is ordered. */
    private enum Sort { STARS("Stars"), UPDATED("Recently updated"), NAME("Name");
        final String label; Sort(String label) { this.label = label; }
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
    private final CheckBox showHidden = new CheckBox("Show hidden");
    private HiddenBots hidden = HiddenBots.load();
    private String currentQuery = "";
    private Stage stage;

    /** The published bots, searched once per window, for Browse. */
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
        searchField.setPromptText("Search published bots…");
        searchField.textProperty().addListener((o, old, q) -> { currentQuery = q; applyFilter(); });
        HBox.setHgrow(searchField, Priority.ALWAYS);

        sortBox.getItems().setAll(Sort.values());
        sortBox.getSelectionModel().select(Sort.STARS);
        sortBox.setOnAction(e -> applyFilter());
        Label sortLabel = new Label("Sort:");

        showHidden.setOnAction(e -> applyFilter());

        browseProgress.setVisible(false);
        browseProgress.setPrefSize(18, 18);
        HBox searchRow = new HBox(8, searchField, sortLabel, sortBox, showHidden, browseProgress);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        ListView<GalleryEntry> list = new ListView<>(shownEntries);
        list.setPlaceholder(new Label("No bots found. Be the first to publish one!"));
        list.setCellFactory(lv -> new BrowseCell());
        VBox.setVgrow(list, Priority.ALWAYS);

        // Every listing is one file of the catalog repository (2026-10-23).
        String catalogRepo = GitHubConfig.CATALOG_OWNER + "/" + GitHubConfig.CATALOG_REPO;
        Hyperlink repoLink = new Hyperlink("Every listing, in " + catalogRepo + " on GitHub");
        repoLink.setOnAction(e -> BrowserLauncher.open("https://github.com/" + catalogRepo));

        VBox box = new VBox(10, searchRow, list, repoLink);
        box.setPadding(new Insets(14));
        return box;
    }

    private void refreshBrowse() {
        browseProgress.setVisible(true);
        catalog.whenComplete((entries, err) -> Platform.runLater(() -> {
            browseProgress.setVisible(false);
            // Every listed bot, since templates went (2026-10-23): Install here and New Project's bot row are the
            // same linked clone (BotInstaller.install), only the folder's name differs.
            allEntries.setAll(entries == null ? List.of() : entries);
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
        for (GalleryEntry e : allEntries) {
            if (e.matches(currentQuery) && (showHidden.isSelected() || !hidden.hides(e))) filtered.add(e);
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

    /** A gallery row: name, author + description, a link to the repo, with an Install button. */
    private final class BrowseCell extends ListCell<GalleryEntry> {
        @Override
        protected void updateItem(GalleryEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            // The same card the publish dialog previews, so an author sees the row a browser will.
            VBox text = GalleryCard.of(entry);

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

            boolean isHidden = hidden.hides(entry);
            Button hideBtn = new Button(isHidden ? "Unhide" : "Hide");
            hideBtn.setTooltip(new Tooltip(isHidden
                    ? "Show this bot again in Browse Bots and New Project"
                    : "Hide this bot from Browse Bots and New Project on this computer"));
            hideBtn.setOnAction(e -> setHidden(entry, !isHidden));

            HBox row = new HBox(10, text, spacer, starCount, starBtn, installBtn, hideBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }

    /** Hides {@code entry} on this computer, or shows it again, and remembers the list. */
    private void setHidden(GalleryEntry entry, boolean hide) {
        hidden = hidden.with(entry, hide);
        hidden.save();
        applyFilter();
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
        Alert confirm = ThemedWindows.alert(Alert.AlertType.WARNING,
                entry.trustWarning() + "\n\nInstall it now?", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setHeaderText("Install this bot?");
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) return;

        installBtn.setDisable(true);
        installBtn.setText("Installing…");
        CompletableFuture
                .supplyAsync(() -> {
                    String tag = entry.tag();
                    if (tag.isBlank()) {
                        throw new RuntimeException("The catalog lists no release of " + entry.slug() + ".");
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
            // A bot whose author removed the repository has no original to merge an update from; it keeps
            // running and keeps its own history; nobody else can install it any more. null: gone. An empty Optional: up to date.
            CompletableFuture
                    .supplyAsync(() -> installer.authorGone(bot.info().projectPath()) ? null
                            : installer.checkForUpdate(bot.info().projectPath()))
                    .whenComplete((latest, err) -> Platform.runLater(() -> {
                        if (err == null && latest == null) {
                            status.setText("author removed the repository · no updates");
                        } else if (err != null || latest.isEmpty()) {
                            status.setText("up to date");
                        } else {
                            status.setText("update available: " + latest.get());
                            status.setStyle("-fx-font-size: 11px; -fx-text-fill: #1a7f37;");
                            updateBtn.setDisable(false);
                            updateBtn.setOnAction(e -> updateBot(bot, latest.get()));
                        }
                    }));
        }
    }

    /**
     * An update is a merge of the new release with the user's changes, made in the bot's own Versions tab
     * ({@code docs/refactor/39-versions.md} §7) — this only says where. It used to replace the project in place.
     */
    private void updateBot(InstalledBot bot, String release) {
        info("Update " + bot.info().name(),
                "Open “" + bot.info().name() + "” and click Get " + release + " at the top of its Versions tab.\n\n"
                        + "Your changes are kept: the new release is merged with them, and any file you both "
                        + "changed is shown side by side for you to choose.");
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
