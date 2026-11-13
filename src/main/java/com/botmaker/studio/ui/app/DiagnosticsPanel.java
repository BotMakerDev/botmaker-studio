package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.core.validation.DiagnosticLevel;
import com.botmaker.studio.validation.DiagnosticsManager;
import com.botmaker.core.validation.ErrorTranslator;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The status bar's <b>Problems</b> ({@link ProjectStatusBar}; the Errors bottom tab until 2026-11-13): the severity
 * filter bar and the diagnostic list, each row's <i>Show</i> (or a click on it) revealing the offending block.
 *
 * <p>The filtering and the counts are {@code static} and free of JavaFX ({@link #matches}, {@link #counts})
 * so the one piece of logic here that can be wrong is testable headlessly.
 */
final class DiagnosticsPanel {

    /** What the filter bar reports, and the only arithmetic in this class. */
    record Counts(long errors, long warnings, long infos) {

        /** What the status bar counts as a problem: an error or a warning; an info is a hint, not a problem. */
        int problems() {
            return (int) (errors + warnings);
        }
    }

    private final DiagnosticsManager diagnosticsManager;
    private final Consumer<CodeBlock> onRevealBlock;
    /** Told the problem count after each update — the status bar's badge. */
    private final IntConsumer onProblems;

    private static final String SEVERITY_FILTER = "severity-filter";
    private static final String DIAGNOSTIC_CELL = "diagnostic-cell";
    private static final String DIAGNOSTIC_ICON = "diagnostic-icon";

    private final ListView<Diagnostic> list = new ListView<>();
    private final ToggleButton errorFilter = severityFilter("Errors", DiagnosticLevel.ERROR);
    private final ToggleButton warningFilter = severityFilter("Warnings", DiagnosticLevel.WARNING);
    private final ToggleButton infoFilter = severityFilter("Infos/Hints", DiagnosticLevel.INFO);
    private final VBox node;

    private List<Diagnostic> all = new ArrayList<>();

    DiagnosticsPanel(DiagnosticsManager diagnosticsManager,
                     Consumer<CodeBlock> onRevealBlock,
                     IntConsumer onProblems) {
        this.diagnosticsManager = diagnosticsManager;
        this.onRevealBlock = onRevealBlock;
        this.onProblems = onProblems;

        configureList();
        VBox.setVgrow(list, Priority.ALWAYS);

        // Spacing, padding, the band's fill and its hairline are all in blocks.css over the theme tokens:
        // as inline styles they stayed a light-grey band in Dark, Black and High Contrast.
        HBox filterBar = new HBox(new Label("Filter: "), errorFilter, warningFilter, infoFilter);
        filterBar.getStyleClass().add("diagnostics-filter-bar");
        filterBar.setAlignment(Pos.CENTER_LEFT);

        this.node = new VBox(filterBar, list);
    }

    VBox node() {
        return node;
    }

    /** Replaces the diagnostic set, re-filters, re-counts, and tells the status bar how many problems are left. */
    void update(List<Diagnostic> diagnostics) {
        this.all = diagnostics != null ? new ArrayList<>(diagnostics) : new ArrayList<>();
        applyFilters();

        Counts counts = counts(all);
        errorFilter.setText("Errors (%d)".formatted(counts.errors()));
        warningFilter.setText("Warnings (%d)".formatted(counts.warnings()));
        infoFilter.setText("Infos (%d)".formatted(counts.infos()));

        onProblems.accept(counts.problems());
    }

    /**
     * Whether {@code d} survives the three severity toggles. A diagnostic with an unrecognised (or absent)
     * severity always passes — hiding something nobody asked to hide is the worse failure.
     */
    static boolean matches(Diagnostic d, boolean showErrors, boolean showWarnings, boolean showInfos) {
        DiagnosticSeverity severity = d.getSeverity();
        if (severity == null) return true;
        return switch (severity) {
            case Error -> showErrors;
            case Warning -> showWarnings;
            case Information, Hint -> showInfos;
        };
    }

    /** Per-severity totals over the unfiltered set; {@code Hint} counts as an info, as the filter treats it. */
    static Counts counts(List<Diagnostic> diagnostics) {
        long errors = 0;
        long warnings = 0;
        long infos = 0;
        for (Diagnostic d : diagnostics) {
            DiagnosticSeverity severity = d.getSeverity();
            if (severity == null) continue;
            switch (severity) {
                case Error -> errors++;
                case Warning -> warnings++;
                case Information, Hint -> infos++;
            }
        }
        return new Counts(errors, warnings, infos);
    }

    private void applyFilters() {
        list.getItems().setAll(all.stream()
                .filter(d -> matches(d, errorFilter.isSelected(), warningFilter.isSelected(), infoFilter.isSelected()))
                .toList());
    }

    private ToggleButton severityFilter(String label, DiagnosticLevel level) {
        ToggleButton b = new ToggleButton(label);
        b.setSelected(true);
        b.getStyleClass().addAll(SEVERITY_FILTER, level.styleClass(SEVERITY_FILTER));
        b.setOnAction(e -> applyFilters());
        return b;
    }

    private void configureList() {
        list.setPlaceholder(new Label("No issues found."));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Diagnostic diagnostic, boolean empty) {
                super.updateItem(diagnostic, empty);
                if (empty || diagnostic == null) {
                    setText(null);
                    setGraphic(null);
                    // A recycled cell keeps its classes, so a blank row would otherwise stay the colour of
                    // whatever diagnostic it last showed.
                    severityClass(this, DIAGNOSTIC_CELL, null);
                    setOnMouseClicked(null);
                    return;
                }
                render(this, diagnostic);
            }
        });

        ContextMenu cm = new ContextMenu();
        MenuItem copy = new MenuItem("Copy Selection");
        copy.setOnAction(e -> {
            Diagnostic selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(ErrorTranslator.messageOf(selected));
            Clipboard.getSystemClipboard().setContent(content);
        });
        cm.getItems().add(copy);
        list.setContextMenu(cm);
    }

    private void render(ListCell<Diagnostic> cell, Diagnostic diagnostic) {
        String message = ErrorTranslator.getShortSummary(diagnostic);
        int line = diagnostic.getRange().getStart().getLine() + 1;
        DiagnosticLevel level = DiagnosticLevel.of(diagnostic);

        Label iconLabel = new Label(level.glyph());
        severityClass(iconLabel, DIAGNOSTIC_ICON, level);

        Label text = new Label("%sLine %d: %s".formatted(fileNamePrefix(diagnostic), line, message));
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button show = new Button("Show");
        show.setOnAction(e -> show(diagnostic));
        // The button's click is its own: the row's handler below would reveal the block a second time.
        show.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        HBox row = new HBox(6, iconLabel, text, show);
        row.setAlignment(Pos.CENTER_LEFT);
        cell.setText(null);
        severityClass(cell, DIAGNOSTIC_CELL, level);
        cell.setGraphic(row);
        cell.setOnMouseClicked(event -> show(diagnostic));
    }

    /** <i>Show</i>: the block the diagnostic is on, on the canvas. */
    private void show(Diagnostic diagnostic) {
        diagnosticsManager.findBlockForDiagnostic(diagnostic).ifPresent(onRevealBlock);
    }

    /** The rows the filters leave, as shown. */
    List<Diagnostic> shown() {
        return List.copyOf(list.getItems());
    }

    /**
     * Puts {@code base} and exactly one {@code base--<severity>} modifier on {@code node}, dropping any
     * modifier it already carried. A {@code null} severity leaves the base class alone and removes the colour.
     */
    private static void severityClass(Node node, String base, DiagnosticLevel level) {
        for (DiagnosticLevel l : DiagnosticLevel.values()) node.getStyleClass().remove(l.styleClass(base));
        if (!node.getStyleClass().contains(base)) node.getStyleClass().add(base);
        if (level != null) node.getStyleClass().add(level.styleClass(base));
    }

    /** {@code "[Main.java] "} when the diagnostic carries its source URI, empty otherwise. */
    private static String fileNamePrefix(Diagnostic diagnostic) {
        if (!(diagnostic.getData() instanceof String uri)) return "";
        try {
            return "[" + Path.of(new URI(uri)).getFileName() + "] ";
        } catch (Exception e) {
            // Fallback for non-standard URIs.
            return "[" + (uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri) + "] ";
        }
    }
}
