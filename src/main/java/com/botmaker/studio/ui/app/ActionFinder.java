package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.toolbar.ActionContext;
import com.botmaker.plugin.api.toolbar.ToolbarItem;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.plugin.PluginHost.PluginBar;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Navigate ▸ Find Action (Ctrl+Shift+A, 2026-10-28): every toolbar button by name, Studio's and each plugin's,
 * whichever tab is selected.
 *
 * <p>The toolbar shows one plugin's buttons at a time, so a button on another tab is two clicks away and a
 * button you only know the name of is a hunt. This list is the bar's own items, pressed through their own
 * {@code onClick} with the bar's {@link ActionContext}: nothing here is a second way to do what a button does.
 * Pressing a plugin's item also selects that plugin's tab, so the bar shows where the button lives.
 *
 * <p>The rows, the match and the press are static and toolkit-free, for {@code ActionFinderTest}.
 */
final class ActionFinder {

    /** What a row names as the owner of Studio's own buttons. */
    static final String STUDIO = "Studio";

    private ActionFinder() {}

    /**
     * One row: the item, its label as read when the list opened, and whose it is.
     *
     * @param plugin the owning plugin, or {@code null} for Studio's own buttons, which have no tab
     */
    record Entry(ToolbarItem item, String label, String owner, PluginCoordinate plugin) {

        /** "Screens · BotMaker SDK". */
        String text() {
            return label + " · " + owner;
        }
    }

    // ── what is listed: pure, for the test ─────────────────────────────────────────────────────────────

    /** Studio's buttons first, in their bar order, then each plugin's, tab by tab. */
    static List<Entry> entries(List<ToolbarItem> studio, List<PluginBar> bars) {
        List<Entry> out = new ArrayList<>();
        for (ToolbarItem item : studio) out.add(new Entry(item, label(item), STUDIO, null));
        for (PluginBar bar : bars) {
            for (ToolbarItem item : bar.items()) out.add(new Entry(item, label(item), bar.name(), bar.plugin()));
        }
        return List.copyOf(out);
    }

    /** The item's label, or its id when the plugin's supplier throws or answers nothing. */
    private static String label(ToolbarItem item) {
        try {
            String text = item.label().get();
            return text == null || text.isBlank() ? item.id() : text;
        } catch (RuntimeException | Error e) {
            return item.id();
        }
    }

    /** {@code label} without the glyph in front of it: "🖼 Manage Pictures" is "Manage Pictures". */
    static String plain(String label) {
        int at = 0;
        while (at < label.length()) {
            int cp = label.codePointAt(at);
            if (Character.isLetterOrDigit(cp)) break;
            at += Character.charCount(cp);
        }
        return label.substring(at).strip();
    }

    /** The rows whose plain label holds {@code filter}, ignoring case; all of them for a blank one. */
    static List<Entry> matching(List<Entry> entries, String filter) {
        if (filter == null || filter.isBlank()) return entries;
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        return entries.stream().filter(e -> plain(e.label()).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    /**
     * Presses {@code entry} when it is enabled: selects its plugin's tab, then runs its action. Guarded as the
     * bar's button is, since the action is a plugin's code.
     *
     * @return whether it was pressed; a greyed row is not
     */
    static boolean press(Entry entry, ActionContext ctx, boolean projectOpen, boolean botRunning,
                         Consumer<PluginCoordinate> selectTab) {
        if (!ToolbarItems.enabled(entry.item().enabledWhen(), projectOpen, botRunning)) return false;
        if (entry.plugin() != null) selectTab.accept(entry.plugin());
        try {
            entry.item().onClick().accept(ctx);
        } catch (RuntimeException | Error ex) {
            System.err.println("Warning: toolbar item '" + entry.item().id() + "' failed: " + ex);
        }
        return true;
    }

    // ── the popup ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the list over {@code owner}, centred near its top like Navigate ▸'s other popups: typing narrows
     * it, the arrows move in it, Enter or a click on a row presses, Escape closes.
     */
    static void show(Stage owner, List<Entry> entries, ActionContext ctx, boolean projectOpen, boolean botRunning,
                     Consumer<PluginCoordinate> selectTab) {
        TextField field = new TextField();
        field.setPromptText("Find a toolbar action");
        ListView<Entry> list = new ListView<>();
        list.setPrefSize(460, 320);
        list.getStyleClass().add("nav-popup-list");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                getStyleClass().remove("action-finder-disabled");
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label owned = new Label(entry.owner());
                owned.getStyleClass().add("plugin-tab-coordinate");
                HBox row = new HBox(8, new Label(entry.label()), owned);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
                if (!ToolbarItems.enabled(entry.item().enabledWhen(), projectOpen, botRunning)) {
                    getStyleClass().add("action-finder-disabled");
                }
            }
        });
        list.getItems().setAll(entries);
        list.getSelectionModel().selectFirst();

        Label heading = new Label("Find action");
        heading.getStyleClass().add("nav-popup-title");
        VBox box = new VBox(6, heading, field, list);
        box.getStyleClass().add("nav-popup");
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(box);
        ThemedWindows.addStylesheet(popup.getScene());
        ThemedWindows.applyThemeClass(box);

        Runnable pick = () -> {
            Entry chosen = list.getSelectionModel().getSelectedItem();
            if (chosen == null || !ToolbarItems.enabled(chosen.item().enabledWhen(), projectOpen, botRunning)) {
                return;
            }
            popup.hide();
            press(chosen, ctx, projectOpen, botRunning, selectTab);
        };
        field.textProperty().addListener((o, was, now) -> {
            list.getItems().setAll(matching(entries, now));
            list.getSelectionModel().selectFirst();
        });
        field.setOnKeyPressed(e -> {
            int at = list.getSelectionModel().getSelectedIndex();
            if (e.getCode() == KeyCode.DOWN) {
                list.getSelectionModel().select(Math.min(at + 1, list.getItems().size() - 1));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                list.getSelectionModel().select(Math.max(at - 1, 0));
                list.scrollTo(list.getSelectionModel().getSelectedIndex());
                e.consume();
            }
        });
        field.setOnAction(e -> pick.run());
        // Only a click on a row presses: the scrollbar and the blank space under the rows are list too.
        list.setOnMouseClicked(e -> {
            for (Node n = e.getPickResult().getIntersectedNode(); n != null && n != list; n = n.getParent()) {
                if (n instanceof ListCell<?> cell && !cell.isEmpty()) {
                    pick.run();
                    return;
                }
            }
        });

        popup.show(owner);
        popup.setX(owner.getX() + (owner.getWidth() - popup.getWidth()) / 2);
        popup.setY(owner.getY() + owner.getHeight() * 0.18);
        field.requestFocus();
    }
}
