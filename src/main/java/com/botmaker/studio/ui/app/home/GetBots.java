package com.botmaker.studio.ui.app.home;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.HiddenBots;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.application.Platform;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The home's <i>Get more bots</i> (2026-10-25, plan phase 2): the catalog's bots as cards under My bots, in the
 * same scroll. It replaced the Browse Bots window: search, {@link ShowCommunity}, <i>Hide</i> and <i>Show hidden</i>
 * ({@link HiddenBots}), the ★ count, and <i>Get</i>, the same linked clone Install made. A bot got moves up into
 * My bots, where its update badge is (Browse Bots' <i>Installed</i> tab went with it).
 */
public final class GetBots {

    /** Where a got bot goes: My bots' folders. */
    public interface Shelf {
        /** Clones {@code entry} at its listed tag, linked to its author. Blocking. */
        Path get(GalleryEntry entry) throws IOException;

        /** Whether {@code entry} is already in My bots. */
        boolean has(GalleryEntry entry);
    }

    private final Shelf shelf;
    private final Predicate<GalleryEntry> agrees;
    private final Runnable onGot;
    private final TextField search = new TextField();
    private final CheckBox showCommunity = new CheckBox(ShowCommunity.LABEL);
    private final CheckBox showHidden = new CheckBox("Show hidden");
    private final Label line = new Label("Reading the catalog…");
    private final TilePane cards = new TilePane(10, 10);
    private List<GalleryEntry> all = List.of();
    private boolean loaded;
    private HiddenBots hidden;
    /**
     * By {@link GalleryEntry#slug}, FX thread only: the gets under way, and the last failure of each. Kept here, not
     * on a card, because every keystroke or catalog answer rebuilds the cards; a card read from them shows the
     * same state again, so a bot being cloned never offers a second <i>Get</i>.
     */
    private final Set<String> getting = new HashSet<>();
    private final Map<String, String> failures = new HashMap<>();

    /**
     * @param agrees asked before a bot is got, with its {@link GalleryEntry#trustWarning}; only a bot that
     *               {@link GalleryEntry#asksFirst} is asked about
     * @param onGot  runs on the FX thread once a bot is in My bots
     */
    public GetBots(Shelf shelf, Predicate<GalleryEntry> agrees, Runnable onGot, ShowCommunity community,
                   HiddenBots hidden) {
        this.shelf = shelf;
        this.agrees = agrees;
        this.onGot = onGot;
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

    /** One bot: the card Publish previews, its ★ count, Hide and Get. */
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
        Label status = new Label();
        status.getStyleClass().add("gallery-card-note");
        status.setWrapText(true);
        status.managedProperty().bind(status.visibleProperty());
        String failure = failures.getOrDefault(entry.slug(), "");
        status.setText(failure);
        status.setVisible(!failure.isEmpty());
        boolean inFlight = getting.contains(entry.slug());
        boolean got = !inFlight && shelf.has(entry);
        Button get = new Button(inFlight ? "Getting…" : got ? "In My bots" : "Get");
        get.setDisable(inFlight || got);
        if (!got) get.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
        get.setOnAction(e -> get(entry));

        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox buttons = new HBox(6, stars, push, hide, get);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(6, GalleryCard.of(entry), buttons, status);
        card.getStyleClass().addAll("np-card", "getbots-card");
        card.setPrefWidth(RunHome.CARD_WIDTH);
        return card;
    }

    /** Clones {@code entry} off the FX thread, once the user agreed to a bot nobody reviewed. */
    private void get(GalleryEntry entry) {
        String slug = entry.slug();
        if (entry.tag().isBlank()) {
            failures.put(slug, "The catalog lists no release of " + slug + ".");
            refilter();
            return;
        }
        // Only a community bot, or one using a community plugin, is confirmed first (2026-10-23).
        if (entry.asksFirst() && !agrees.test(entry)) return;
        failures.remove(slug);
        getting.add(slug);
        refilter();
        CompletableFuture.supplyAsync(() -> {
            try {
                return shelf.get(entry);
            } catch (IOException ex) {
                throw new IllegalStateException(ex.getMessage(), ex);
            }
        }).whenComplete((dir, failed) -> Platform.runLater(() -> {
            getting.remove(slug);
            if (failed != null) failures.put(slug, "Could not get it: " + rootMessage(failed));
            refilter();
            if (failed == null) onGot.run();
        }));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
