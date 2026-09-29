package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.nav.SourceNavigation;
import com.botmaker.studio.nav.TextSearch;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Navigate ▸ Find…</b> (Ctrl+F, 2026-09-29): a bar floating over the canvas's top-right corner, as in
 * IntelliJ. Typing lands on the first block whose source holds the text; Enter and the ↓ button go to the next,
 * Shift+Enter and ↑ to the previous, Escape and ✕ close it.
 *
 * <p>A match is found in the open file's text ({@link TextSearch}) and shown as the block that owns its
 * offset, so a name, a literal and a comment are all found, and two matches inside one block are one stop.
 * The matches are read again on every step: the bot may have been edited since the last one, and a list of
 * blocks from before the edit points at blocks that are no longer drawn.
 */
final class FindBar {

    private final ProjectState state;
    private final EditorCanvas canvas;
    private final TextField field = new TextField();
    private final Label count = new Label();
    private final HBox bar;
    private int index;

    FindBar(ProjectState state, EditorCanvas canvas) {
        this.state = state;
        this.canvas = canvas;

        field.setPromptText("Find in this file");
        field.setPrefColumnCount(18);
        field.textProperty().addListener((o, was, now) -> {
            index = 0;
            step(0);
        });
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                step(e.isShiftDown() ? -1 : 1);
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            }
        });
        count.getStyleClass().add("find-bar-count");
        count.setMinWidth(Region.USE_PREF_SIZE);

        Button previous = button("↑", "Previous match (Shift+Enter)", () -> step(-1));
        Button next = button("↓", "Next match (Enter)", () -> step(1));
        Button close = button("✕", "Close (Escape)", this::close);

        bar = new HBox(6, field, count, previous, next, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().addAll("nav-popup", "find-bar");
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        bar.setVisible(false);
    }

    private static Button button(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("find-bar-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(e -> action.run());
        return button;
    }

    /** Shows the bar with its text selected, so typing replaces the last search and Enter repeats it. */
    void open() {
        canvas.overlay(bar);
        bar.setVisible(true);
        field.requestFocus();
        field.selectAll();
        step(0);
    }

    private void close() {
        bar.setVisible(false);
        state.getHighlightedBlock().map(CodeBlock::getUINode).ifPresent(javafx.scene.Node::requestFocus);
    }

    /** Moves {@code delta} matches from the current one (0: stay) and lands on it. */
    private void step(int delta) {
        List<CodeBlock> hits = hits();
        if (hits.isEmpty()) {
            count.setText(countText(-1, 0, field.getText()));
            return;
        }
        index = Math.floorMod(index + delta, hits.size());
        count.setText(countText(index, hits.size(), field.getText()));
        canvas.revealBlock(hits.get(index), false);
    }

    /** The blocks holding a match in the open file, in source order, each once. */
    private List<CodeBlock> hits() {
        ProjectFile file = state.getActiveFile();
        CompilationUnit cu = state.getCompilationUnit().orElse(null);
        if (file == null || cu == null) return List.of();
        Map<CodeBlock, Boolean> seen = new IdentityHashMap<>();
        List<CodeBlock> out = new ArrayList<>();
        for (int offset : TextSearch.offsets(file.getContent(), field.getText())) {
            SourceNavigation.blockAtOffset(cu, offset, state.getNodeToBlockMap())
                    .filter(block -> seen.put(block, Boolean.TRUE) == null)
                    .ifPresent(out::add);
        }
        return out;
    }

    /** What the bar says beside the field: {@code "3 of 12"}, {@code "No results"}, or nothing for no query. */
    static String countText(int index, int total, String query) {
        if (query == null || query.isEmpty()) return "";
        if (total == 0) return "No results";
        return (index + 1) + " of " + total;
    }
}
