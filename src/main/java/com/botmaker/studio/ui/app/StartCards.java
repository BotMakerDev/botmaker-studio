package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.ui.app.StartingPoints.Kind;
import com.botmaker.studio.ui.app.StartingPoints.StartChoice;
import com.botmaker.studio.ui.app.gallery.GalleryCard;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * New Project's <i>Start from</i> cards (2026-10-24, a combo until then): Blank, then one card per bot, exactly
 * one picked. Click, Space or Enter picks; the arrow keys move between cards.
 *
 * <p>The rows are {@link StartingPoints#rows}'; the loading row is no card, the line above the grid says the
 * catalog is being asked. A refill keeps the pick by {@link StartingPoints#same}, and a picked bot the catalog no
 * longer lists is not swapped for another: nothing is picked and a line says why, as the combo's prompt did.
 */
final class StartCards {

    static final double CARD_WIDTH = 160;
    static final double GAP = 8;

    private final TilePane tiles = new TilePane(GAP, GAP);
    private final Label empty = muted();
    private final Label dropped = muted();
    private final VBox node = new VBox(6, tiles, empty, dropped);
    private final Consumer<StartChoice> onPick;
    private List<StartChoice> rows = List.of();
    private StartChoice picked;

    /** {@code onPick} hears every change of the pick, the user's and a refill's, null when none is left. */
    StartCards(Consumer<StartChoice> onPick) {
        this.onPick = onPick;
        tiles.setPrefColumns(3);
        tiles.setPrefTileWidth(CARD_WIDTH);
        tiles.setTileAlignment(Pos.TOP_LEFT);
        say(empty, "");
        say(dropped, "");
    }

    Node node() {
        return node;
    }

    StartChoice picked() {
        return picked;
    }

    /**
     * Shows {@code rows}, keeping the pick when it is still offered, else Blank — unless a picked bot left
     * {@code listed}, the catalog's bots before any filter: then nothing is picked until the user picks again,
     * however often the cards refill. A bot only hidden by a filter (community ones unticked) falls back to Blank.
     * {@code whenNoBots} is the line shown when no bot card is, empty for none.
     */
    void fill(List<StartChoice> rows, List<GalleryEntry> listed, String whenNoBots) {
        Optional<StartChoice> same = StartingPoints.same(rows, picked);
        boolean lost = same.isEmpty() && picked != null && picked.entry() != null
                && listed.stream().noneMatch(e -> e.slug().equalsIgnoreCase(picked.entry().slug()));
        this.rows = rows.stream().filter(r -> r.kind() != Kind.LOADING).toList();
        boolean stillLost = picked == null && dropped.isVisible();
        StartChoice now = lost || stillLost ? null : same.orElse(this.rows.getFirst());
        if (lost) say(dropped, picked.displayName() + " is no longer listed — pick where to start");
        else if (now != null) say(dropped, "");
        say(empty, this.rows.stream().anyMatch(r -> r.entry() != null) ? "" : whenNoBots);
        boolean changed = !Objects.equals(now, picked);
        picked = now;
        redraw();
        if (changed) onPick.accept(now);
    }

    /** Picks {@code choice}, as a click on its card does. */
    void pick(StartChoice choice) {
        if (Objects.equals(choice, picked)) return;
        picked = choice;
        say(dropped, "");
        redraw();
        onPick.accept(choice);
    }

    private void redraw() {
        int focused = focusedIndex();
        tiles.getChildren().setAll(rows.stream().map(this::card).toList());
        // A refill while the user is on the keyboard keeps them on a card, the picked one.
        if (focused >= 0) {
            int at = rows.indexOf(picked);
            tiles.getChildren().get(at >= 0 ? at : Math.min(focused, rows.size() - 1)).requestFocus();
        }
    }

    private int focusedIndex() {
        List<Node> cards = tiles.getChildren();
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).isFocused()) return i;
        return -1;
    }

    private Node card(StartChoice choice) {
        boolean isPicked = choice.equals(picked);
        Label name = new Label(choice.entry() == null ? "Blank" : choice.displayName());
        name.getStyleClass().add("np-card-name");
        Label check = new Label("✓");
        check.getStyleClass().add("np-card-check");
        check.setVisible(isPicked);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox card = new VBox(3, new HBox(4, name, spacer, check));
        card.getStyleClass().add("np-card");
        if (isPicked) card.getStyleClass().add("np-card-picked");
        if (choice.kind() == Kind.REMEMBERED) card.getStyleClass().add("np-card-remembered");
        card.setPrefWidth(CARD_WIDTH);
        card.setFocusTraversable(true);

        GalleryEntry entry = choice.entry();
        if (entry == null) {
            card.getChildren().add(line("An empty project"));
        } else {
            card.getChildren().add(line("by " + entry.owner()));
            if (!entry.basedOnLine().isBlank()) card.getChildren().add(line(entry.basedOnLine()));
            HBox facts = new HBox(6, GalleryCard.badge(entry.trust()));
            facts.setAlignment(Pos.CENTER_LEFT);
            if (!entry.tag().isBlank()) facts.getChildren().add(line(entry.tag()));
            card.getChildren().add(facts);
            if (!entry.description().isBlank()) {
                Label description = line(entry.description());
                description.setMaxHeight(30);   // two lines at the note's 11px, then an ellipsis
                card.getChildren().add(description);
            }
        }
        card.setOnMouseClicked(e -> {
            card.requestFocus();
            pick(choice);
        });
        keys(card, tiles, () -> pick(choice));
        return card;
    }

    /** A card of {@code tiles}: Space or Enter does {@code act}, the arrow keys move to the card that way. */
    static void keys(Node card, TilePane tiles, Runnable act) {
        card.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            switch (e.getCode()) {
                case SPACE, ENTER -> act.run();
                case LEFT -> move(tiles, card, -1);
                case RIGHT -> move(tiles, card, 1);
                case UP -> move(tiles, card, -columns(tiles));
                case DOWN -> move(tiles, card, columns(tiles));
                default -> {
                    return;
                }
            }
            e.consume();
        });
    }

    /** Focuses the card {@code by} away from {@code from}, skipping a card that takes no focus (a locked one). */
    private static void move(TilePane tiles, Node from, int by) {
        List<Node> cards = tiles.getChildren();
        for (int to = cards.indexOf(from) + by; to >= 0 && to < cards.size(); to += by) {
            if (cards.get(to).isFocusTraversable()) {
                cards.get(to).requestFocus();
                return;
            }
        }
    }

    /** The columns the grid shows now, which its width decides. */
    private static int columns(TilePane tiles) {
        return Math.max(1, (int) ((tiles.getWidth() + GAP) / (CARD_WIDTH + GAP)));
    }

    /** A card's small line, wrapped. */
    static Label line(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("gallery-card-note");
        label.setWrapText(true);
        return label;
    }

    private static Label muted() {
        Label label = line("");
        label.managedProperty().bind(label.visibleProperty());
        return label;
    }

    private static void say(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isEmpty());
    }
}
