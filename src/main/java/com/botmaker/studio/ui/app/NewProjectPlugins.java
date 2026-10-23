package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.PluginCatalog;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublishedBotPlugins;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * New Project's plugin checklist (2026-10-01): the rows <i>Plugins &amp; Libraries ▸ Browse</i> offers
 * ({@link PluginCatalog}), each a tick box.
 *
 * <p>The chosen bot's own plugins are ticked and locked, read off the pom of the release New Project clones
 * ({@link PublishedBotPlugins}, since 2026-10-12; a gallery entry's {@code requires} said it until then, and went
 * stale): its code may call them, and creation offers no version choice, so there is nothing to decide about
 * them. What this hands back is only the user's extra ticks. Each row names the version the project gets
 * ({@link #label}). Offline the list is empty with a sentence, and creating still works — a plugin is one visit
 * to Plugins &amp; Libraries away afterwards.
 */
final class NewProjectPlugins {

    private final ListView<PluginRegistry.Plugin> list = new ListView<>();
    private final Set<PluginRegistry.Plugin> ticked = new LinkedHashSet<>();
    private final Runnable onChange;
    private final CompletableFuture<List<PluginRegistry.Plugin>> rows;
    private final Function<GalleryEntry, CompletableFuture<String>> pomOf;
    private Map<String, PublishedBotPlugins.Pin> locked = Map.of();
    /** The entry {@link #lockFor} was last asked about; an older entry's pom that answers late is dropped. */
    private GalleryEntry lockingFor;

    /**
     * @param pomOf the pom of the release a bot is cloned at ({@code GitHubGallery.pomAtInstallTag}),
     *              {@code ""} when it cannot be had
     */
    NewProjectPlugins(PluginCatalog catalog, Function<GalleryEntry, CompletableFuture<String>> pomOf,
                      Runnable onChange) {
        this.onChange = onChange;
        this.pomOf = pomOf;
        list.setPrefHeight(150);
        list.setCellFactory(view -> new Row());
        list.setPlaceholder(new Label("Loading plugins…"));
        rows = catalog.rows();
        rows.whenComplete((answered, failure) -> Platform.runLater(() -> {
            List<PluginRegistry.Plugin> installable = answered == null ? List.of()
                    : answered.stream().filter(PluginRegistry.Plugin::isInstallable).toList();
            list.getItems().setAll(installable);
            list.setPlaceholder(new Label("No plugins listed — the registry could not be reached. Add them "
                    + "later from Project ▸ Plugins & Libraries."));
        }));
    }

    Node node() {
        return list;
    }

    /**
     * Re-marks the rows for {@code entry}, or for Studio's own blank project when it is null. A bot's rows
     * lock once its pom answers; until then, and when it cannot be read, nothing is locked — creation still skips
     * a coordinate the cloned pom declares.
     */
    void lockFor(GalleryEntry entry) {
        lockingFor = entry;
        locked = Map.of();
        list.refresh();
        onChange.run();
        if (entry == null) return;
        pomOf.apply(entry)
                .thenCombine(rows.exceptionally(failure -> List.of()), PublishedBotPlugins::of)
                .whenComplete((pins, failure) -> Platform.runLater(() -> {
                    if (entry != lockingFor || pins == null) return;
                    locked = locked(pins);
                    list.refresh();
                    onChange.run();
                }));
    }

    /** The user's ticks, the bot's own plugins left out. */
    List<PluginRegistry.Plugin> picked() {
        return ticked.stream().filter(p -> !locked.containsKey(p.id())).toList();
    }

    /** A bot's plugins by registry id: its rows to lock, with the version its pom pins. Pure. */
    static Map<String, PublishedBotPlugins.Pin> locked(List<PublishedBotPlugins.Pin> pins) {
        return pins.stream().collect(Collectors.toUnmodifiableMap(
                PublishedBotPlugins.Pin::id, pin -> pin, (first, again) -> first));
    }

    /**
     * A row's text: the plugin's name and the version the project gets — the bot's pin for its own
     * plugins, JitPack's newest build for the rest. A version JitPack did not give is left out: the plugin
     * resolves its newest release only at creation.
     */
    static String label(PluginRegistry.Plugin plugin, PublishedBotPlugins.Pin own) {
        String name = plugin.name().isBlank() ? plugin.coordinate() : plugin.name();
        String version = own != null ? own.version() : plugin.version();
        String named = version.isBlank() ? name : name + "  " + displayVersion(version);
        return own != null ? named + "  — comes with the bot" : named;
    }

    /** {@code 1.2.3} and {@code v1.2.3} both read {@code v1.2.3}; anything else as written. */
    private static String displayVersion(String version) {
        return Character.isDigit(version.charAt(0)) ? "v" + version : version;
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
            PublishedBotPlugins.Pin pin = locked.get(plugin.id());
            boolean own = pin != null;
            CheckBox box = new CheckBox(label(plugin, pin));
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
