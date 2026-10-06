package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.upgrade.PluginHolders;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Break;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Report;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * The question <i>Remove…</i> asks: a short summary, the plugin's files as a list, and the two buttons.
 *
 * <p>It replaced an {@code Alert} on 2026-10-06. The alert put everything in one label and a one-line
 * checkbox, so a plugin with many calls opened taller than the screen with its buttons below the edge, and
 * one with many files opened as a single line wider than the screen. This window has a size, a minimum and a
 * scrolling list, and its buttons are pinned to the bottom.
 */
final class RemovalSheet {

    private RemovalSheet() {}

    /**
     * Asks, and blocks until answered.
     *
     * @return empty when cancelled, else whether the plugin's files go too
     */
    static Optional<Boolean> ask(Window owner, String plugin, Report report, PluginHolders holders) {
        // Two keys: the remembered size of a sheet with no files would cramp the one with a list.
        StudioWindow window = StudioWindow.modal(holders.isEmpty() ? "plugin-removal" : "plugin-removal-files",
                        "Remove " + plugin, owner)
                .size(560, holders.isEmpty() ? 240 : 420).minSize(420, 220);
        Stage stage = window.stage();
        boolean[] confirmed = {false};

        Label summary = new Label(summary(plugin, report));
        summary.setWrapText(true);
        VBox body = new VBox(10, summary);
        if (report.isIncomplete()) {
            Label partial = new Label("Not everything could be read: " + report.problems().getFirst());
            partial.setWrapText(true);
            partial.getStyleClass().add("text-muted");
            body.getChildren().add(partial);
        }

        CheckBox deleteFiles = new CheckBox();
        if (!holders.isEmpty()) {
            List<String> names = holders.files().keySet().stream().map(f -> f.getFileName().toString()).toList();
            deleteFiles.setText("Also delete its " + names.size() + (names.size() == 1 ? " file" : " files"));
            deleteFiles.setSelected(true);
            ListView<String> files = new ListView<>();
            files.getItems().setAll(names);
            files.setPrefHeight(Region.USE_COMPUTED_SIZE);
            VBox.setVgrow(files, Priority.ALWAYS);
            files.disableProperty().bind(deleteFiles.selectedProperty().not());
            body.getChildren().addAll(deleteFiles, files);
            if (!holders.references().isEmpty()) {
                Label refs = new Label(InstalledPluginsTab.holderReferences(holders.references()));
                refs.setWrapText(true);
                refs.getStyleClass().add("text-muted");
                TitledPane where = new TitledPane("Where your code names them", refs);
                where.setExpanded(false);
                body.getChildren().add(where);
            }
        }

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        Button remove = new Button("Remove");
        remove.setDefaultButton(true);
        remove.setOnAction(e -> {
            confirmed[0] = true;
            stage.close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, remove);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        BorderPane root = new BorderPane(body);
        root.setBottom(buttons);
        root.setPadding(new Insets(14));
        window.showAndWait(root);
        return confirmed[0] ? Optional.of(!holders.isEmpty() && deleteFiles.isSelected()) : Optional.empty();
    }

    /** The sheet's one sentence: what changes in the bot. Pure, for the test. */
    static String summary(String plugin, Report report) {
        long types = report.leftForYou().stream().map(Break::type).distinct().count();
        // Call sites of the breaks the repair makes; a type left as written is counted once, below.
        int sites = report.breaks().stream().filter(b -> !b.leavesWork()).mapToInt(b -> b.sites().size()).sum();
        String calls = report.breaks().isEmpty()
                ? "This bot calls nothing in " + plugin + ", so only the pom changes."
                : sites == 0 ? "No call needs repairing."
                : sites + (sites == 1 ? " call" : " calls")
                        + " will become a default value or be deleted, marked for review.";
        String left = types == 0 ? ""
                : " " + types + (types == 1 ? " type it writes stays" : " types it writes stay")
                        + " as written, for you to change.";
        return calls + left + " A version of the project is saved first.";
    }
}
