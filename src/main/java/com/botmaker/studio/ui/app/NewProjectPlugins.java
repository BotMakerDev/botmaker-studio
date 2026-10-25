package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.PluginCatalog;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublishedBotPlugins;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.sharing.ShowCommunity;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * New Project's plugin cards (2026-10-24; a checklist from 2026-10-01): the rows <i>Plugins &amp; Libraries ▸
 * Browse</i> offers ({@link PluginCatalog}), each a card the ✓ toggles, as the <i>Start from</i> cards look.
 *
 * <p>A card is locked, shown ticked and not tickable, when the project gets the plugin anyway ({@link PluginLock}):
 * the chosen bot's own plugins, as the catalog read them off the pom of the release New Project clones
 * ({@link GalleryEntry#plugins}, 2026-10-23), "used by" the bot; and every plugin one the project gets brings,
 * "brought by" it. What this hands back is only the user's ticks still unlocked. Each card names the version the
 * project gets ({@link #version}). Offline the grid is empty with a sentence, and creating still works — a plugin
 * is one visit to Plugins &amp; Libraries away afterwards.
 */
final class NewProjectPlugins {

    private final TilePane tiles = new TilePane(StartCards.GAP, StartCards.GAP);
    private final Label empty = StartCards.line("Loading plugins…");
    private final VBox node = new VBox(6, tiles, empty);
    private final Set<String> ticked = new LinkedHashSet<>();
    private final Runnable onChange;
    private List<PluginRegistry.Plugin> installable = List.of();
    private boolean answered;
    private String botName = "";
    private Map<String, PublishedBotPlugins.Pin> botOwn = Map.of();
    private Map<String, PluginLock> locks = Map.of();
    private ShowCommunity community;

    NewProjectPlugins(PluginCatalog catalog, ShowCommunity community, Runnable onChange) {
        this(catalog.rows(), community, onChange);
    }

    /** The cards for the catalog's {@code rows} once they come, an empty grid with a sentence if they fail. */
    NewProjectPlugins(CompletableFuture<List<PluginRegistry.Plugin>> rows, ShowCommunity community,
                      Runnable onChange) {
        this.onChange = onChange;
        this.community = community;
        tiles.setPrefColumns(3);
        tiles.setPrefTileWidth(StartCards.CARD_WIDTH);
        tiles.setTileAlignment(Pos.TOP_LEFT);
        empty.managedProperty().bind(empty.visibleProperty());
        rows.whenComplete((plugins, failure) -> Platform.runLater(() -> {
            installable = plugins == null ? List.of()
                    : plugins.stream().filter(PluginRegistry.Plugin::isInstallable).toList();
            answered = plugins != null;
            showCards();
        }));
    }

    Node node() {
        return node;
    }

    /**
     * Re-marks the cards for {@code picked}, or for Studio's own blank project when it is Blank or null: a bot's
     * own plugins lock. Creation still skips a coordinate the cloned pom declares.
     */
    void lockFor(StartChoice picked) {
        GalleryEntry entry = picked == null ? null : picked.entry();
        botName = entry == null ? "" : picked.displayName();
        botOwn = entry == null ? Map.of() : locked(entry.plugins());
        showCards();
        onChange.run();
    }

    /** Shows or hides the community cards, as New Project's <i>Show community bots and plugins</i> says. */
    void show(ShowCommunity choice) {
        community = choice;
        showCards();
        onChange.run();
    }

    /** The user's ticks still shown and not locked: neither the bot's own nor one another plugin brings. */
    List<PluginRegistry.Plugin> picked() {
        List<PluginRegistry.Plugin> shown = visible(installable, community, locks.keySet());
        return shown.stream().filter(p -> ticked.contains(p.id()) && !locks.containsKey(p.id())).toList();
    }

    /** {@code rows} as {@code choice} shows them, ranked; a locked plugin is shown whatever its rank. Pure. */
    static List<PluginRegistry.Plugin> visible(List<PluginRegistry.Plugin> rows, ShowCommunity choice,
                                               Set<String> locked) {
        return rows.stream()
                .filter(p -> choice.shown() || p.rank() != PublisherRank.COMMUNITY || locked.contains(p.id()))
                .sorted(PluginRegistry.Plugin.RANKED)
                .toList();
    }

    /** A bot's plugins by registry id: its cards to lock, with the version its pom pins. Pure. */
    static Map<String, PublishedBotPlugins.Pin> locked(List<PublishedBotPlugins.Pin> pins) {
        return pins.stream().collect(Collectors.toUnmodifiableMap(
                PublishedBotPlugins.Pin::id, pin -> pin, (first, again) -> first));
    }

    /** A card's name: the plugin's, or its coordinate when the catalog gave none. */
    static String name(PluginRegistry.Plugin plugin) {
        return plugin.name().isBlank() ? plugin.coordinate() : plugin.name();
    }

    /**
     * The version a card names, the one the project gets: the bot's pin for its own plugins, the catalog's tag for
     * the rest; empty when nobody gave one.
     */
    static String version(PluginRegistry.Plugin plugin, PublishedBotPlugins.Pin own) {
        String version = own != null ? own.version() : plugin.version();
        if (version.isBlank()) return "";
        return Character.isDigit(version.charAt(0)) ? "v" + version : version;
    }

    /** The line under an empty grid; also when the catalog is reached and lists no plugin this shows. */
    static String emptyLine(boolean communityShown) {
        return communityShown ? "No plugins listed yet."
                : "No official plugins yet — show community plugins to see the rest.";
    }

    private void showCards() {
        // The ticks a hidden rank leaves out bring nothing: they are not picked either.
        List<PluginRegistry.Plugin> ranked = installable.stream().sorted(PluginRegistry.Plugin.RANKED).toList();
        Set<String> shownTicks = ranked.stream()
                .filter(p -> ticked.contains(p.id()) && (community.shown() || p.rank() != PublisherRank.COMMUNITY))
                .map(PluginRegistry.Plugin::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<String>> brings = ranked.stream().collect(Collectors.toMap(
                PluginRegistry.Plugin::id, PluginRegistry.Plugin::brings, (first, again) -> first, LinkedHashMap::new));
        Map<String, String> names = ranked.stream().collect(Collectors.toMap(
                PluginRegistry.Plugin::id, NewProjectPlugins::name, (first, again) -> first));
        locks = PluginLock.of(botName, inOrder(botOwn, brings.keySet()), shownTicks, brings, names);

        // A redraw while the user is on the keyboard keeps them on the same card.
        Object focusedId = tiles.getChildren().stream().filter(Node::isFocused).findFirst()
                .map(Node::getUserData).orElse(null);
        List<PluginRegistry.Plugin> shown = visible(installable, community, locks.keySet());
        tiles.getChildren().setAll(shown.stream().map(this::card).toList());
        if (focusedId != null) {
            tiles.getChildren().stream().filter(n -> focusedId.equals(n.getUserData()) && n.isFocusTraversable())
                    .findFirst().ifPresent(Node::requestFocus);
        }
        String why = !answered
                ? "No plugins listed — the catalog could not be reached. Add them later from Project ▸ Plugins & "
                + "Libraries."
                : emptyLine(community.shown());
        empty.setText(shown.isEmpty() ? why : "");
        empty.setVisible(shown.isEmpty());
    }

    /** {@code botOwn} with the catalog's plugins first, in their order, so the first bringer named is the first shown. */
    private static Map<String, PublishedBotPlugins.Pin> inOrder(Map<String, PublishedBotPlugins.Pin> botOwn,
                                                               Set<String> listed) {
        Map<String, PublishedBotPlugins.Pin> ranked = new LinkedHashMap<>();
        listed.stream().filter(botOwn::containsKey).forEach(id -> ranked.put(id, botOwn.get(id)));
        botOwn.forEach(ranked::putIfAbsent);
        return ranked;
    }

    private void toggle(PluginRegistry.Plugin plugin) {
        if (locks.containsKey(plugin.id())) return;
        if (!ticked.remove(plugin.id())) ticked.add(plugin.id());
        showCards();
        onChange.run();
    }

    private Node card(PluginRegistry.Plugin plugin) {
        PluginLock lock = locks.get(plugin.id());
        boolean isTicked = lock != null || ticked.contains(plugin.id());
        Label name = GalleryCard.cardName(name(plugin));
        Label check = new Label("✓");
        check.getStyleClass().add("np-card-check");
        check.setMinWidth(Region.USE_PREF_SIZE);
        check.setVisible(isTicked);
        VBox card = new VBox(3, new HBox(4, name, check));
        card.getStyleClass().add("np-card");
        if (isTicked) card.getStyleClass().add("np-card-picked");
        card.setPrefWidth(StartCards.CARD_WIDTH);
        card.setUserData(plugin.id());

        if (lock != null) {
            card.getStyleClass().add("np-card-locked");
            Label why = StartCards.line(lock.text());
            why.getStyleClass().add("np-card-lock");
            card.getChildren().add(why);
        }
        if (!plugin.repo().isBlank()) card.getChildren().add(StartCards.line("by " + owner(plugin.repo())));
        HBox facts = new HBox(6, GalleryCard.badge(plugin.rank()));
        facts.setAlignment(Pos.CENTER_LEFT);
        String version = version(plugin, botOwn.get(plugin.id()));
        if (!version.isEmpty()) facts.getChildren().add(StartCards.line(version));
        card.getChildren().add(facts);
        if (!plugin.description().isBlank()) {
            // Whole: nothing on a card is cut (2026-10-25, spec § 1b, A).
            card.getChildren().add(StartCards.line(plugin.description()));
            Tooltip.install(card, new Tooltip(plugin.description()));
        }

        // A locked card takes no focus and no click: the arrow keys pass over it.
        card.setFocusTraversable(lock == null);
        if (lock == null) {
            card.setOnMouseClicked(e -> {
                card.requestFocus();
                toggle(plugin);
            });
            StartCards.keys(card, tiles, () -> toggle(plugin));
        }
        return card;
    }

    /** The account of {@code owner/name}. */
    private static String owner(String repo) {
        int slash = repo.indexOf('/');
        return slash < 0 ? repo : repo.substring(0, slash);
    }
}
