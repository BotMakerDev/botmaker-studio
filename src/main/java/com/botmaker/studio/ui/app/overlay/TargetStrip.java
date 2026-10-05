package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.services.overlay.OverlayTargets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * <em>Where the next block goes</em>: one chip per target the plugins find ({@link OverlayTargets}), under its
 * group's heading, and below them the method being edited, {@code Collect ▸ body()}, whose ▾ lists the open
 * file's other methods.
 *
 * <p>It replaces the Activity and Method combo boxes (2026-10-06). A chip shows at a glance what can be edited
 * and which one is; a combo showed one name and hid the rest. With no plugin target the chips are gone and the
 * breadcrumb alone picks a method of whatever file is open.
 *
 * <p>Nothing here opens a file or moves the caret: a pick leaves through the callbacks.
 */
final class TargetStrip {

    private final VBox node = new VBox(4);
    private final VBox groups = new VBox(4);
    private final Label file = new Label();
    private final MenuButton method = new MenuButton();
    private final Consumer<OverlayTargets.Target> onTarget;
    private final Consumer<String> onMethod;
    private List<OverlayTargets.Target> targets = List.of();
    private String selectedKey;

    TargetStrip(Consumer<OverlayTargets.Target> onTarget, Consumer<String> onMethod) {
        this.onTarget = onTarget;
        this.onMethod = onMethod;
        file.getStyleClass().add("overlay-breadcrumb");
        method.getStyleClass().add("overlay-breadcrumb");
        method.setTooltip(new Tooltip("The method being edited — pick another one of this file"));
        HBox crumb = new HBox(4, file, method);
        crumb.setAlignment(Pos.CENTER_LEFT);
        node.getChildren().addAll(groups, crumb);
    }

    VBox node() {
        return node;
    }

    /** The targets now, drawn by group; {@code selectedKey} is the one being edited, or null. */
    void showTargets(List<OverlayTargets.Target> targets, String selectedKey) {
        this.targets = List.copyOf(targets);
        this.selectedKey = selectedKey;
        redraw();
    }

    /** Marks the target with {@code key} as the one being edited; null marks none. */
    void select(String key) {
        if (java.util.Objects.equals(key, selectedKey)) return;
        selectedKey = key;
        redraw();
    }

    List<OverlayTargets.Target> targets() {
        return targets;
    }

    /**
     * The breadcrumb: {@code fileLabel ▸ methodLabel ▾}, the ▾ offering {@code methods}. A null method reads as
     * the whole file.
     */
    void showMethod(String fileLabel, String methodLabel, List<String> methods) {
        file.setText(fileLabel == null ? "" : fileLabel + " ▸");
        method.setText(methodLabel == null ? "(whole file)" : methodLabel);
        method.getItems().clear();
        for (String label : methods) {
            MenuItem item = new MenuItem(label);
            item.setOnAction(e -> onMethod.accept(label));
            method.getItems().add(item);
        }
        method.setDisable(methods.isEmpty());
    }

    private void redraw() {
        groups.getChildren().clear();
        Map<String, FlowPane> byGroup = new LinkedHashMap<>();
        ToggleGroup toggles = new ToggleGroup();
        for (OverlayTargets.Target target : targets) {
            FlowPane chips = byGroup.computeIfAbsent(target.group(), g -> {
                Label caption = new Label(g);
                caption.getStyleClass().add("overlay-group-caption");
                FlowPane pane = new FlowPane(4, 4);
                groups.getChildren().addAll(caption, pane);
                return pane;
            });
            ToggleButton chip = new ToggleButton(target.label());
            chip.getStyleClass().add("overlay-target-chip");
            chip.setToggleGroup(toggles);
            chip.setSelected(target.key().equals(selectedKey));
            chip.setTooltip(new Tooltip("Edit " + target.label() + "." + target.method() + "() — opens "
                    + target.file().getFileName() + " in the editor"));
            // A toggle un-selects on a second press; a chip stays the one being edited instead.
            chip.setOnAction(e -> {
                chip.setSelected(true);
                onTarget.accept(target);
            });
            chips.getChildren().add(chip);
        }
        groups.setVisible(!targets.isEmpty());
        groups.setManaged(!targets.isEmpty());
    }
}
