package com.botmaker.studio.ui.app;

import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.PluginJarFiles;
import com.botmaker.studio.plugin.PluginLogs;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The two parts of a row in Plugins &amp; Libraries ▸ Installed that come out of the plugin itself: its icon, out
 * of its jar ({@link PluginJarFiles#ICON}), and its log ({@link PluginLogs}), behind a <i>Log</i> button that
 * opens the lines in a fold under the row.
 */
final class PluginRowParts {

    static final double ICON_SIZE = 20;

    /** A puzzle piece, drawn in the text colour, for a plugin whose jar has no icon or that did not load. */
    private static final String DEFAULT_ICON = "M4 6h4a2 2 0 1 1 4 0h4v4a2 2 0 1 1 0 4v4h-4a2 2 0 1 0-4 0H4v-4"
            + "a2 2 0 1 0 0-4z";

    private PluginRowParts() {}

    /** {@code name} with the plugin's icon before it. */
    static Node named(Label name, Optional<PluginHost.LoadedJar> loaded) {
        HBox cell = new HBox(6, icon(loaded.map(jar -> Path.of(jar.jar()))), name);
        cell.setAlignment(Pos.CENTER_LEFT);
        return cell;
    }

    /** The jar's icon, scaled to {@value #ICON_SIZE}px, or the default piece when it has none. */
    static Node icon(Optional<Path> jar) {
        Optional<Image> image = jar.flatMap(path -> PluginJarFiles.read(path, PluginJarFiles.ICON))
                .map(bytes -> new Image(new ByteArrayInputStream(bytes), ICON_SIZE, ICON_SIZE, true, true))
                .filter(loaded -> !loaded.isError());
        if (image.isPresent()) return new ImageView(image.get());
        SVGPath piece = new SVGPath();
        piece.setContent(DEFAULT_ICON);
        piece.getStyleClass().add("plugin-icon-default");
        StackPane box = new StackPane(piece);
        box.setMinSize(ICON_SIZE, ICON_SIZE);
        box.setPrefSize(ICON_SIZE, ICON_SIZE);
        return box;
    }

    /** A plugin's <i>Log</i> button and the fold it opens; the fold goes on its own line under the row. */
    static final class LogFold {

        final Button button = new Button();
        final ScrollPane fold;
        private final VBox lines = new VBox(1);
        private final Optional<PluginCoordinate> plugin;

        /**
         * {@code plugin} is empty for a plugin that did not load: it wrote nothing, and the button is greyed. A
         * press reads the lines again, so the count catches up with what the plugin wrote since the tab was drawn.
         */
        LogFold(Optional<PluginCoordinate> plugin) {
            this.plugin = plugin;
            fold = new ScrollPane(lines);
            fold.setFitToWidth(true);
            fold.setMaxHeight(160);
            fold.getStyleClass().add("plugin-log");
            show(false);
            button.setOnAction(e -> {
                refresh();
                show(!fold.isVisible() && !lines.getChildren().isEmpty());
            });
            refresh();
        }

        private void show(boolean open) {
            fold.setVisible(open);
            fold.setManaged(open);
        }

        /** Reads the plugin's lines again: the count on the button, and the fold's contents. */
        private void refresh() {
            List<PluginLogs.Line> written = plugin.map(PluginLogs::lines).orElse(List.of());
            button.setText(label(written));
            // Never greyed while the plugin is loaded: it may write after the tab is drawn, and a press reads again.
            button.setDisable(plugin.isEmpty());
            button.getStyleClass().remove("text-warning");
            if (written.stream().anyMatch(line -> line.level().notable())) button.getStyleClass().add("text-warning");
            button.setTooltip(new Tooltip(plugin.isEmpty() ? "The plugin did not load, so it wrote nothing."
                    : written.isEmpty() ? "The plugin has written nothing this session."
                    : "What the plugin wrote this session, newest last."));
            lines.getChildren().setAll(written.stream().map(LogFold::line).toList());
        }

        static String label(List<PluginLogs.Line> written) {
            return written.isEmpty() ? "Log" : "Log (" + written.size() + ")";
        }

        private static Label line(PluginLogs.Line line) {
            Label label = new Label(line.display());
            label.setWrapText(true);
            label.getStyleClass().addAll("trace-cell", "trace-cell--" + line.level().name().toLowerCase());
            return label;
        }
    }
}
