package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.PluginCatalog;
import com.botmaker.studio.sharing.PluginRegistry;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * New Project's plugin checklist (2026-10-01): the rows <i>Plugins &amp; Libraries ▸ Browse</i> offers
 * ({@link PluginCatalog}), each a tick box.
 *
 * <p>The chosen template's own plugins are ticked and locked, read off its gallery entry's {@code requires}: its
 * code may call them, and creation offers no version choice, so there is nothing to decide about them. What
 * this hands back is only the user's extra ticks. Offline the list is empty with a sentence, and creating still
 * works — a plugin is one visit to Plugins &amp; Libraries away afterwards.
 */
final class NewProjectPlugins {

    private final ListView<PluginRegistry.Plugin> list = new ListView<>();
    private final Set<PluginRegistry.Plugin> ticked = new LinkedHashSet<>();
    private final Runnable onChange;
    private Set<String> locked = Set.of();

    NewProjectPlugins(PluginCatalog catalog, Runnable onChange) {
        this.onChange = onChange;
        list.setPrefHeight(150);
        list.setCellFactory(view -> new Row());
        list.setPlaceholder(new Label("Loading plugins…"));
        catalog.rows().whenComplete((rows, failure) -> Platform.runLater(() -> {
            List<PluginRegistry.Plugin> installable = rows == null ? List.of()
                    : rows.plugins().stream().filter(PluginRegistry.Plugin::isInstallable).toList();
            list.getItems().setAll(installable);
            list.setPlaceholder(new Label("No plugins listed — the registry could not be reached. Add them "
                    + "later from Project ▸ Plugins & Libraries."));
        }));
    }

    Node node() {
        return list;
    }

    /** Re-marks the rows for {@code entry}, or for Studio's own blank project when it is null. */
    void lockFor(GalleryEntry entry) {
        locked = lockedIds(entry);
        list.refresh();
        onChange.run();
    }

    /** The user's ticks, the template's own plugins left out. */
    List<PluginRegistry.Plugin> picked() {
        return ticked.stream().filter(p -> !locked.contains(p.id())).toList();
    }

    /** The registry ids {@code entry} declares, which are its rows to lock. Pure. */
    static Set<String> lockedIds(GalleryEntry entry) {
        if (entry == null) return Set.of();
        return entry.requires().stream().map(GalleryEntry.Requirement::id).collect(Collectors.toUnmodifiableSet());
    }

    private final class Row extends ListCell<PluginRegistry.Plugin> {
        @Override
        protected void updateItem(PluginRegistry.Plugin plugin, boolean empty) {
            super.updateItem(plugin, empty);
            setText(null);
            if (empty || plugin == null) {
                setGraphic(null);
                return;
            }
            boolean own = locked.contains(plugin.id());
            String name = plugin.name().isBlank() ? plugin.coordinate() : plugin.name();
            CheckBox box = new CheckBox(own ? name + "  — comes with the template" : name);
            box.setSelected(own || ticked.contains(plugin));
            box.setDisable(own);
            box.setOnAction(e -> {
                if (box.isSelected()) ticked.add(plugin);
                else ticked.remove(plugin);
                onChange.run();
            });
            if (!plugin.description().isBlank()) {
                box.setTooltip(new javafx.scene.control.Tooltip(plugin.description()));
            }
            setGraphic(box);
        }
    }
}
