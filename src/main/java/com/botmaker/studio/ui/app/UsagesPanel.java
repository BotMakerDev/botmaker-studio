package com.botmaker.studio.ui.app;

import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.services.BotSources;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.IBinding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The <b>Usages</b> bottom tab (2026-09-26): <i>Navigate ▸ Find Usages</i> on the selected block lists every
 * place in the bot its function, variable or class is named, grouped by file, the declaration first-marked.
 * A click lands on the block.
 *
 * <p>The sources are read on the FX thread (open buffers win over disk, as {@link BotSources} does
 * everywhere) and parsed with bindings on a worker: a bot of a few dozen files parses in well under a second,
 * but not in one frame. A search started while one runs replaces it.
 */
final class UsagesPanel {

    /** A tree row: a file heading, or one occurrence. */
    private sealed interface Row {
        record FileRow(Path file, int count) implements Row {}

        record UsageRow(Usages.Usage usage) implements Row {}
    }

    private final ProjectConfig config;
    private final ProjectState state;
    private final Consumer<Usages.Usage> onReveal;
    private final Label summary = new Label("Select a block and choose Navigate ▸ Find Usages (Alt+F7).");
    private final TreeView<Row> tree = new TreeView<>();
    private final VBox node;
    private int search;

    UsagesPanel(ProjectConfig config, ProjectState state, Consumer<Usages.Usage> onReveal) {
        this.config = config;
        this.state = state;
        this.onReveal = onReveal;
        summary.getStyleClass().add("review-summary");
        HBox bar = new HBox(summary);
        bar.getStyleClass().add("diagnostics-filter-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                setText(empty || row == null ? null : switch (row) {
                    case Row.FileRow f -> f.file().getFileName() + "  (" + f.count() + ")";
                    case Row.UsageRow u -> u.usage().enclosing() + " · line " + u.usage().line() + ":  "
                            + u.usage().text() + (u.usage().declaration() ? "   — declaration" : "");
                });
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null && now.getValue() instanceof Row.UsageRow u) onReveal.accept(u.usage());
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        node = new VBox(bar, tree);
    }

    VBox node() {
        return node;
    }

    /** Looks for every use of {@code binding}. FX thread. */
    void search(IBinding binding) {
        String key = Usages.keyOf(binding);
        String name = binding.getName();
        int id = ++search;
        tree.getRoot().getChildren().clear();
        if (key == null) {
            summary.setText(name + " cannot be searched for.");
            return;
        }
        summary.setText("Searching for " + name + "…");
        Map<Path, String> sources = new LinkedHashMap<>();
        BotSources.scan(config, state, sources::put);
        BotParser parser = BotParser.of(state);
        Thread worker = new Thread(() -> {
            List<Usages.Usage> found = new ArrayList<>();
            sources.forEach((file, source) -> {
                // A cheap guard first: a file that never spells the name cannot use it.
                if (source.contains(name)) found.addAll(Usages.in(file, source, parser.parse(file, source), key));
            });
            Platform.runLater(() -> {
                if (id == search) show(name, found);
            });
        }, "find-usages");
        worker.setDaemon(true);
        worker.start();
    }

    private void show(String name, List<Usages.Usage> found) {
        Map<Path, List<Usages.Usage>> byFile = new LinkedHashMap<>();
        for (Usages.Usage u : found) byFile.computeIfAbsent(u.file(), f -> new ArrayList<>()).add(u);
        long uses = found.stream().filter(u -> !u.declaration()).count();
        summary.setText(uses == 0 ? name + " is not used anywhere in this bot."
                : name + ": " + uses + (uses == 1 ? " use" : " uses") + " in " + byFile.size()
                        + (byFile.size() == 1 ? " file" : " files"));
        for (var entry : byFile.entrySet()) {
            TreeItem<Row> file = new TreeItem<>(new Row.FileRow(entry.getKey(), entry.getValue().size()));
            file.setExpanded(true);
            for (Usages.Usage u : entry.getValue()) file.getChildren().add(new TreeItem<>(new Row.UsageRow(u)));
            tree.getRoot().getChildren().add(file);
        }
    }

    /** What is listed now, for a test. */
    List<Usages.Usage> shown() {
        List<Usages.Usage> out = new ArrayList<>();
        for (TreeItem<Row> file : tree.getRoot().getChildren()) {
            for (TreeItem<Row> row : file.getChildren()) {
                if (row.getValue() instanceof Row.UsageRow u) out.add(u.usage());
            }
        }
        return out;
    }

    String summary() {
        return summary.getText();
    }
}
