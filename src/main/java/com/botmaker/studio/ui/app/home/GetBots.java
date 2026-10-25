package com.botmaker.studio.ui.app.home;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.sharing.BmkDownload;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.app.botinfo.BotIconView;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The home's <i>Get more bots</i> (2026-10-25, plan phase 2): the catalog's bots as cards under My bots, in the
 * same scroll. It replaced the Browse Bots window: search, {@link ShowCommunity}, <i>Hide</i> and <i>Show hidden</i>
 * ({@link HiddenBots}), the ★ count, <i>Get</i> and <i>Edit in Studio</i>. <i>Get</i> downloads the listed release's
 * {@code .bmk} and opens it to run; <i>Edit in Studio</i> clones the release linked to its author (2026-10-25, the
 * user's call). A bot with no {@code .bmk} pinned yet offers only <i>Edit in Studio</i>. A bot already in My bots,
 * by its author's repository, says so.
 */
public final class GetBots {

    /**
     * Where a got bot comes from. Both blocking, off the FX thread, on the loading page's first step: the download
     * ({@link Got#run}) and the clone ({@link Got#edit}).
     */
    public interface Shelf {
        /** Downloads {@code entry}'s listed {@code .bmk} ({@link BmkDownload}), saying how far it got. */
        Path download(GalleryEntry entry, BmkDownload.Progress progress) throws IOException;

        /** Clones {@code entry} at its listed tag, linked to its author. */
        Path cloneOf(GalleryEntry entry) throws IOException;
    }

    /** What is done with a bot the user gets, on the FX thread. */
    public interface Got {
        /** Get on {@code entry}, agreed to: the loading page downloads it and opens it to run (spec § 1b, P1). */
        void run(GalleryEntry entry);

        /** Edit in Studio on {@code entry}, agreed to: the loading page clones it and opens it in the editor (N13). */
        void edit(GalleryEntry entry);
    }

    /** The two ways to get a bot: its card's Get and Edit in Studio. */
    private enum Way { RUN, EDIT }

    private final Predicate<GalleryEntry> agrees;
    private final Got got;
    private final TextField search = new TextField();
    private final CheckBox showCommunity = new CheckBox(ShowCommunity.LABEL);
    private final CheckBox showHidden = new CheckBox("Show hidden");
    private final Label line = new Label("Reading the catalog…");
    private final TilePane cards = new TilePane(10, 10);
    private List<GalleryEntry> all = List.of();
    private boolean loaded;
    private HiddenBots hidden;
    /** My bots' authors' repositories, {@link MyBots#key}: a bot among them is already here. FX thread only. */
    private Set<String> mine = Set.of();
    /**
     * By {@link GalleryEntry#slug}, FX thread only: why a get was refused before the loading page. Kept here, not on
     * a card, because every keystroke or catalog answer rebuilds the cards. A get under way is on the loading page
     * (2026-10-25, N13), so no card shows one.
     */
    private final Map<String, String> failures = new HashMap<>();

    /**
     * @param agrees asked before a bot is got, with its {@link GalleryEntry#trustWarning}; only a bot that
     *               {@link GalleryEntry#asksFirst} is asked about
     */
    public GetBots(Predicate<GalleryEntry> agrees, Got got, ShowCommunity community,
                   HiddenBots hidden) {
        this.agrees = agrees;
        this.got = got;
        this.hidden = hidden;
        showCommunity.setSelected(community.shown());
    }

    /**
     * The bots shown: the ones {@code community} shows ({@link GalleryEntry#RANKED}) that match {@code query} by
     * name, owner, description or tag, without case, the hidden ones left out unless {@code showHidden}.
     */
    public static List<GalleryEntry> shown(List<GalleryEntry> all, String query, ShowCommunity community,
                                           HiddenBots hidden, boolean showHidden) {
        return community.bots(all).stream()
                .filter(e -> e.matches(query) && (showHidden || !hidden.hides(e)))
                .toList();
    }

    /** The section: its search row, a line when there is something to say, the cards, the catalog's link. */
    public Node node() {
        search.setPromptText("Search bots…");
        search.textProperty().addListener((o, was, now) -> refilter());
        HBox.setHgrow(search, Priority.ALWAYS);
        // Off until the user turns it on, on this computer (ShowCommunity, 2026-10-23).
        showCommunity.setOnAction(e -> {
            new ShowCommunity(showCommunity.isSelected()).save();
            refilter();
        });
        showHidden.setOnAction(e -> refilter());
        HBox controls = new HBox(10, search, showCommunity, showHidden);
        controls.setAlignment(Pos.CENTER_LEFT);

        line.getStyleClass().add("gallery-card-note");
        line.managedProperty().bind(line.visibleProperty());
        cards.setPrefTileWidth(RunHome.CARD_WIDTH);
        cards.setTileAlignment(Pos.TOP_LEFT);

        // Every listing is one file of the catalog repository (2026-10-23).
        String catalogRepo = GitHubConfig.CATALOG_OWNER + "/" + GitHubConfig.CATALOG_REPO;
        Hyperlink repoLink = new Hyperlink("Every listing, in " + catalogRepo + " on GitHub");
        repoLink.setOnAction(e -> BrowserLauncher.open("https://github.com/" + catalogRepo));
        return new VBox(10, controls, line, cards, repoLink);
    }

    /** Shows the catalog's bots; {@code fresh} false when it is the copy kept from an earlier read. */
    public void show(List<GalleryEntry> bots, boolean fresh) {
        all = List.copyOf(bots);
        loaded = true;
        refilter();
        if (all.isEmpty()) say(fresh ? "The catalog lists no bot yet." : "The catalog could not be read.");
        else if (!fresh) say("Offline: the catalog as it was at its last read.");
    }

    /** My bots as now read: {@code keys} their authors' repositories ({@link MyBots#key}). */
    public void mine(Set<String> keys) {
        mine = Set.copyOf(keys);
        refilter();
    }

    private boolean isMine(GalleryEntry entry) {
        return mine.contains(MyBots.key(entry.owner(), entry.repo()));
    }

    private void refilter() {
        List<GalleryEntry> shown = shown(all, search.getText(), new ShowCommunity(showCommunity.isSelected()),
                hidden, showHidden.isSelected());
        // Until the catalog answers, "Reading the catalog…" stays whatever is typed.
        if (loaded) say(shown.isEmpty() && !all.isEmpty() ? "No bot matches." : "");
        cards.getChildren().setAll(shown.stream().map(this::card).toList());
    }

    private void say(String text) {
        line.setText(text);
        line.setVisible(!text.isEmpty());
    }

    /** One bot: the card Publish previews, its ★ count, Hide, Edit in Studio and Get. */
    private Node card(GalleryEntry entry) {
        Label stars = new Label("★ " + entry.stars());
        stars.getStyleClass().add("gallery-card-note");
        boolean isHidden = hidden.hides(entry);
        Button hide = new Button(isHidden ? "Unhide" : "Hide");
        hide.setTooltip(new Tooltip(isHidden
                ? "Show this bot again here and in New Project"
                : "Hide this bot here and in New Project, on this computer"));
        hide.setOnAction(e -> {
            hidden = hidden.with(entry, !isHidden);
            hidden.save();
            refilter();
        });
        String slug = entry.slug();
        boolean here = isMine(entry);
        boolean ready = BmkDownload.ready(entry);
        String note = failures.getOrDefault(slug,
                ready || here ? "" : "No ready-to-run file yet: Edit in Studio gets its code.");
        Label status = new Label(note);
        status.getStyleClass().add("gallery-card-note");
        status.setWrapText(true);
        status.managedProperty().bind(status.visibleProperty());
        status.setVisible(!note.isEmpty());

        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(6, stars, push, hide);
        buttons.setAlignment(Pos.CENTER_LEFT);
        if (here) {
            Button in = new Button("In My bots");
            in.setDisable(true);
            buttons.getChildren().add(in);
        } else {
            Button edit = new Button("Edit in Studio");
            edit.setTooltip(new Tooltip("Get its code, linked to its author, to change it"));
            edit.setOnAction(e -> get(entry, Way.EDIT));
            buttons.getChildren().add(edit);
            if (ready) {
                Button get = new Button("Get");
                get.setTooltip(new Tooltip("Download it and run it"));
                get.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
                get.setOnAction(e -> get(entry, Way.RUN));
                buttons.getChildren().add(get);
            }
        }
        Node icon = BotIconView.remote(entry.name(), entry.owner(), entry.repo(), entry.commitOf(entry.tag()),
                RunHome.ICON_SIZE);
        VBox text = GalleryCard.of(entry);
        // The text takes the card's room and wraps in it, never cut (2026-10-25, spec § 1b, A).
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox top = new HBox(8, icon, text);
        VBox card = new VBox(6, top, buttons, status);
        card.getStyleClass().addAll("np-card", "getbots-card");
        card.setPrefWidth(RunHome.CARD_WIDTH);
        return card;
    }

    /** Gets {@code entry} off the FX thread, once the user agreed to a bot nobody reviewed. */
    private void get(GalleryEntry entry, Way way) {
        String slug = entry.slug();
        if (entry.tag().isBlank()) {
            failures.put(slug, "The catalog lists no release of " + slug + ".");
            refilter();
            return;
        }
        // Only a community bot, or one using a community plugin, is confirmed first (2026-10-23).
        if (entry.asksFirst() && !agrees.test(entry)) return;
        failures.remove(slug);
        // Straight to the loading page, its first step Downloading or Copying (2026-10-25, spec § 1b, P1; N13).
        if (way == Way.RUN) got.run(entry);
        else got.edit(entry);
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }
}
