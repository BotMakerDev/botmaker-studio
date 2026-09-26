package com.botmaker.studio.ui.app;

import com.botmaker.studio.nav.LibrarySource;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.nio.file.Path;

/**
 * Go to Definition's window for a library member (2026-09-26): the class's text, read-only, with the member's
 * line selected. A banner says whether it is the library's own source or an outline of its signatures, so a
 * listing is never mistaken for the code.
 */
final class LibrarySourceWindow {

    private LibrarySourceWindow() {}

    static void show(Stage owner, LibrarySource.View view) {
        Label banner = new Label(banner(view));
        banner.setWrapText(true);
        banner.getStyleClass().add("library-source-banner");
        TextArea text = new TextArea(view.text());
        text.setEditable(false);
        text.getStyleClass().add("library-source-text");
        BorderPane root = new BorderPane(text);
        root.setTop(banner);

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(view.title() + " (read-only)");
        stage.setScene(ThemedWindows.scene(root, 860, 640));
        stage.show();
        if (view.line() > 0) {
            // Selecting once the skin exists is what scrolls the caret, and so the member, into view.
            Platform.runLater(() -> {
                int start = offsetOfLine(view.text(), view.line());
                int end = view.text().indexOf('\n', start);
                text.requestFocus();
                text.selectRange(end < 0 ? view.text().length() : end, start);
            });
        }
    }

    private static String banner(LibrarySource.View view) {
        String from = view.from() == null ? "the Java runtime" : fileName(view.from());
        return switch (view.origin()) {
            case SOURCES -> "Read-only. From " + from + ".";
            case OUTLINE -> "Read-only outline. No sources were found for " + from
                    + ", so its public signatures are listed without their code.";
        };
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    /** The character offset where 1-based {@code line} starts; the end of the text past the last line. */
    static int offsetOfLine(String text, int line) {
        int at = 0;
        for (int n = 1; n < line; n++) {
            int next = text.indexOf('\n', at);
            if (next < 0) return text.length();
            at = next + 1;
        }
        return at;
    }
}
