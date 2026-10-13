package com.botmaker.studio.ui.app.viewers;

import com.botmaker.studio.ui.render.theme.BlockTheme;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import org.commonmark.node.Image;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A Markdown file drawn as a page, with Edit to change its text (2026-10-13): the bot's {@code GAME.md}, which the
 * assistant writes and the author reads and corrects. Rendered by commonmark-java into a {@link WebView} in the
 * colours of Studio's theme. The file may come from a gallery bot, so its HTML is escaped, its links keep the
 * page where it is, and the page runs no script.
 */
final class MarkdownView {

    private static final Parser PARSER = Parser.builder().build();
    /**
     * Raw HTML is text and a picture is its words: a picture would be fetched as the tab opens, telling a gallery
     * bot's maker who read its notes, and when.
     */
    private static final HtmlRenderer HTML = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true)
            .nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends org.commonmark.node.Node>> getNodeTypes() {
                    return Set.of(Image.class);
                }

                @Override
                public void render(org.commonmark.node.Node image) {
                    for (org.commonmark.node.Node alt = image.getFirstChild(); alt != null; alt = alt.getNext()) {
                        context.render(alt);
                    }
                }
            }).build();
    /** Nothing the page names is loaded, whatever slips past the renderer; its own style only. */
    private static final String POLICY = "<meta http-equiv=\"Content-Security-Policy\" "
            + "content=\"default-src 'none'; style-src 'unsafe-inline'\">";
    /** Set on the view while its text is being edited: the tab keeps it rather than draw the file anew. */
    static final String EDITING = "markdown-view.editing";

    private MarkdownView() {}

    static Node of(Path file, String text) {
        WebView page = new WebView();
        page.setContextMenuEnabled(false);
        WebEngine engine = page.getEngine();
        engine.setJavaScriptEnabled(false);
        String[] shown = {text};
        Runnable render = () -> engine.loadContent(html(shown[0], BlockTheme.getCurrentThemeType()));
        // A link would load its page in place of the notes: the load is stopped and the notes drawn again.
        engine.locationProperty().addListener((o, was, now) -> {
            if (now != null && !now.isEmpty() && !now.equals("about:blank")) {
                engine.getLoadWorker().cancel();
                Platform.runLater(render);
            }
        });
        render.run();

        TextArea source = new TextArea(text);
        source.getStyleClass().addAll("resource-viewer", "resource-viewer-text");
        ToggleButton edit = new ToggleButton("Edit");
        Button save = new Button("Save");
        save.setVisible(false);
        save.managedProperty().bind(save.visibleProperty());
        Label problem = new Label();
        problem.getStyleClass().add("resource-viewer-note");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(8, problem, gap, save, edit);
        bar.setPadding(new Insets(6, 12, 6, 12));

        BorderPane pane = new BorderPane(page);
        pane.setTop(bar);
        edit.selectedProperty().addListener((o, was, editing) -> {
            pane.getProperties().put(EDITING, editing);
            save.setVisible(editing);
            if (editing) {
                source.setText(shown[0]);
                pane.setCenter(source);
            } else {
                render.run();
                pane.setCenter(page);
            }
        });
        save.setOnAction(e -> problem.setText(save(file, shown[0], source.getText()).orElseGet(() -> {
            shown[0] = source.getText();
            edit.setSelected(false);
            return "";
        })));
        Consumer<BlockTheme.ThemeType> themed = type -> {
            if (!edit.isSelected()) render.run();
        };
        // Listened to while shown: a closed tab is not kept alive by the theme.
        pane.sceneProperty().addListener((o, was, now) -> {
            BlockTheme.removeThemeChangeListener(themed);
            if (now != null) BlockTheme.addThemeChangeListener(themed);
        });
        return pane;
    }

    /**
     * Writes {@code text} over {@code file}, written beside and moved over; why not, when the file is no longer
     * {@code began} (the assistant wrote a section meanwhile, and saving would undo it) or is a link to elsewhere.
     */
    static Optional<String> save(Path file, String began, String text) {
        try {
            if (Files.isSymbolicLink(file)) return Optional.of(file.getFileName() + " links elsewhere; not saved.");
            String now = Files.isRegularFile(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
            if (!now.equals(began)) {
                return Optional.of(file.getFileName() + " changed since you began editing; copy your text, close the "
                        + "tab and open it again.");
            }
            Path next = Files.createTempFile(file.getParent(), ".edit", ".md");
            Files.writeString(next, text, StandardCharsets.UTF_8);
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return Optional.empty();
        } catch (IOException failed) {
            return Optional.of(file.getFileName() + " could not be saved (" + failed.getMessage() + ").");
        }
    }

    /** {@code markdown} as a page in {@code theme}'s colours. */
    static String html(String markdown, BlockTheme.ThemeType theme) {
        Colours c = Colours.of(theme);
        return "<!doctype html><html><head><meta charset=\"utf-8\">" + POLICY + "<style>"
                + "body{font-family:system-ui,sans-serif;font-size:14px;line-height:1.5;margin:16px 24px;"
                + "background:" + c.background + ";color:" + c.text + "}"
                + "h1,h2,h3{line-height:1.25}h1,h2{border-bottom:1px solid " + c.rule + ";padding-bottom:.3em}"
                + "a{color:" + c.link + "}"
                + "code,pre{font-family:monospace;background:" + c.code + ";border-radius:4px}"
                + "code{padding:.1em .3em}pre{padding:10px;overflow:auto}pre code{padding:0}"
                + "blockquote{margin:0;padding-left:12px;border-left:3px solid " + c.rule + ";color:" + c.muted + "}"
                + "table{border-collapse:collapse}td,th{border:1px solid " + c.rule + ";padding:4px 8px}"
                + "</style></head><body>" + HTML.render(PARSER.parse(markdown)) + "</body></html>";
    }

    /** The page's colours in each of Studio's themes. */
    private record Colours(String background, String text, String muted, String rule, String code, String link) {
        static Colours of(BlockTheme.ThemeType theme) {
            return switch (theme) {
                case DEFAULT -> new Colours("#ffffff", "#1f2328", "#59636e", "#d1d9e0", "#f0f2f4", "#0969da");
                case DARK -> new Colours("#1e1f22", "#dfe1e5", "#9da0a8", "#3c3f45", "#2b2d30", "#589df6");
                case BLACK -> new Colours("#000000", "#e6e6e6", "#a0a0a0", "#333333", "#1a1a1a", "#4ea1ff");
                case HIGH_CONTRAST -> new Colours("#000000", "#ffffff", "#ffffff", "#ffffff", "#1a1a1a", "#ffff00");
            };
        }
    }
}
