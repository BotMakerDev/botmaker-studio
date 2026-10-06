package com.botmaker.studio.ui.app;

import com.botmaker.studio.services.upgrade.FixList;
import com.botmaker.studio.services.upgrade.FixList.Fix;
import com.botmaker.studio.services.upgrade.FixList.Issue;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.CallSite;
import com.botmaker.studio.services.upgrade.PluginUpgradeService.Decision;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The fixes an upgrade needs, asked one at a time before anything is written (2026-10-06).
 *
 * <p>The list on the left is every place the move breaks, in source order, ticked as each gets a fix; the
 * right shows the one selected and its fixes as buttons. Picking a fix moves to the next one still open.
 * <i>Upgrade</i> stays disabled until every issue has a fix, and Cancel writes nothing — the upgrade happens
 * only once the list is done. See {@link FixList}.
 */
final class FixSheet {

    private FixSheet() {}

    /** One plugin's move and what it breaks. */
    record Part(String plugin, String target, List<Issue> issues) {
        Part {
            issues = List.copyOf(issues);
        }
    }

    /** An issue with the plugin it belongs to, as the list holds it. */
    private record Entry(Part part, Issue issue) {}

    /**
     * Asks, and blocks until answered.
     *
     * @return empty when cancelled, else each part's picks, in the parts' order
     */
    static Optional<List<Map<CallSite, Decision>>> ask(Window owner, List<Part> parts) {
        List<Entry> entries = new ArrayList<>();
        for (Part part : parts) for (Issue issue : part.issues()) entries.add(new Entry(part, issue));
        Map<Issue, Fix> chosen = new IdentityHashMap<>();

        StudioWindow window = StudioWindow.modal("upgrade-fixes", title(parts), owner).size(820, 480).minSize(560, 320);
        Stage stage = window.stage();
        boolean[] confirmed = {false};

        Label progress = new Label();
        Button upgrade = new Button(parts.size() == 1 ? "Upgrade to " + parts.getFirst().target() : "Upgrade");
        upgrade.setDefaultButton(true);
        Button rest = new Button();

        ListView<Entry> list = new ListView<>();
        list.getItems().setAll(entries);
        list.setPrefWidth(300);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String mark = chosen.containsKey(item.issue()) ? "✓ " : "• ";
                setText(mark + item.issue().where() + (parts.size() > 1 ? "  (" + item.part().plugin() + ")" : ""));
            }
        });

        VBox detail = new VBox(10);
        detail.setPadding(new Insets(0, 0, 0, 14));
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            long left = entries.stream().filter(e -> !chosen.containsKey(e.issue())).count();
            progress.setText(progressText(entries.size(), (int) left));
            upgrade.setDisable(left > 0);
            rest.setText("Use the suggested fix for the " + left + " left");
            rest.setVisible(left > 1);
            rest.setManaged(left > 1);
            list.refresh();
        };

        list.getSelectionModel().selectedItemProperty().addListener((o, was, entry) -> {
            detail.getChildren().clear();
            if (entry == null) return;
            Issue issue = entry.issue();
            Label what = new Label(issue.what());
            what.setWrapText(true);
            what.setStyle("-fx-font-weight: bold;");
            Label where = new Label(issue.where());
            where.getStyleClass().add("text-muted");
            detail.getChildren().addAll(what, where);
            if (!issue.code().isBlank()) {
                Label code = new Label(issue.code());
                code.setWrapText(true);
                code.getStyleClass().add("sdk-upgrade-detail");
                detail.getChildren().add(code);
            }
            ToggleGroup group = new ToggleGroup();
            VBox fixes = new VBox(6);
            for (Fix fix : issue.fixes()) {
                ToggleButton button = new ToggleButton(fix.label());
                button.setToggleGroup(group);
                button.setMaxWidth(Double.MAX_VALUE);
                button.setWrapText(true);
                button.setSelected(chosen.get(issue) == fix);
                button.setOnAction(e -> {
                    chosen.put(issue, fix);
                    refresh[0].run();
                    nextOpen(entries, chosen, entries.indexOf(entry)).ifPresent(i -> list.getSelectionModel().select(i));
                });
                fixes.getChildren().add(button);
            }
            detail.getChildren().add(fixes);
        });

        rest.setOnAction(e -> {
            for (Entry entry : entries) chosen.putIfAbsent(entry.issue(), entry.issue().suggestedFix());
            refresh[0].run();
        });
        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        upgrade.setOnAction(e -> {
            confirmed[0] = true;
            stage.close();
        });

        Label intro = new Label("Nothing is written until each of these has a fix. Then Studio saves a version, "
                + "writes the fixes and moves the pom.");
        intro.setWrapText(true);
        intro.getStyleClass().add("sdk-upgrade-empty");
        VBox top = new VBox(4, intro, progress);
        top.setPadding(new Insets(0, 0, 10, 0));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, rest, spacer, cancel, upgrade);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(10, 0, 0, 0));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setLeft(list);
        root.setCenter(detail);
        root.setBottom(buttons);
        root.setPadding(new Insets(14));

        refresh[0].run();
        list.getSelectionModel().select(0);
        window.showAndWait(root);
        if (!confirmed[0]) return Optional.empty();
        List<Map<CallSite, Decision>> out = new ArrayList<>();
        for (Part part : parts) out.add(FixList.picks(part.issues(), chosen));
        return Optional.of(out);
    }

    /** The line over the list. Pure, for the test. */
    static String progressText(int total, int left) {
        if (left == 0) return total == 1 ? "The one fix is chosen." : "All " + total + " fixes are chosen.";
        return (total - left) + " of " + total + " fixed · " + left + " left";
    }

    /** The next entry after {@code from} with no fix, wrapping round; empty when none is left. */
    private static Optional<Integer> nextOpen(List<Entry> entries, Map<Issue, Fix> chosen, int from) {
        for (int step = 1; step <= entries.size(); step++) {
            int i = (from + step) % entries.size();
            if (!chosen.containsKey(entries.get(i).issue())) return Optional.of(i);
        }
        return Optional.empty();
    }

    private static String title(List<Part> parts) {
        return parts.size() == 1 ? "Upgrade " + parts.getFirst().plugin() + " to " + parts.getFirst().target()
                : "Upgrade " + parts.size() + " plugins";
    }
}
