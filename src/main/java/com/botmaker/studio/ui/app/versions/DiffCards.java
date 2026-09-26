package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.BlockDiff;
import com.botmaker.studio.project.vcs.VersionReader;
import com.botmaker.studio.ui.render.theme.BlockFontPreference;
import com.botmaker.studio.ui.render.theme.BlockStylePreference;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What one file's change looks like in the Versions tab ({@code docs/refactor/39-versions.md} §5): a card per
 * changed function, <b>Before | After</b> as blocks with the changed ones marked, the unchanged functions folded
 * into one line, each changed field its own Was | Now card drawn as blocks, pictures as thumbnails, and anything
 * that is not Java — or does not parse —
 * as its text diff. Each function card switches between <i>Blocks</i> and <i>Java</i>. Built on the FX thread
 * from what {@link VersionsPane} read off it.
 */
final class DiffCards {

    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    /** Node property: this side, or this pair, has blocks to show — else a card opens on its Java. */
    private static final String DRAWN = "versions-drawn";

    /** What the cards may put back — the pane's, since it knows which row is shown and what is open. */
    interface Actions {

        /** The label of the button that puts this function back, or null when it cannot be from here. */
        String restoreLabel(BlockDiff.MethodChange change);

        /** Puts the function back: {@code source} is the whole file on the side it is taken from. */
        void restoreFunction(BlockDiff.MethodChange change);
    }

    /**
     * Everything read off the FX thread for one file.
     *
     * @param diff     the block comparison, or null for a file that is not Java
     * @param textDiff the unified diff, for a card that falls back to text
     */
    record Input(String path, VersionReader.Sides sides, BlockDiff.FileDiff diff, String textDiff,
                 String beforeHeading, String afterHeading) {

        /** A version's change: Before | After. */
        Input(String path, VersionReader.Sides sides, BlockDiff.FileDiff diff, String textDiff) {
            this(path, sides, diff, textDiff, "Before", "After");
        }
    }

    /** The column headings of the file being built — Before | After, or Yours | Theirs in an update. */
    private String beforeHeading = "Before";
    private String afterHeading = "After";

    private final ProjectConfig config;
    private final ProjectState live;

    DiffCards(ProjectConfig config, ProjectState live) {
        this.config = config;
        this.live = live;
    }

    static boolean isJava(String path) {
        return path.endsWith(".java");
    }

    static boolean isImage(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && IMAGES.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    Node build(Input in, Actions actions) {
        beforeHeading = in.beforeHeading();
        afterHeading = in.afterHeading();
        VBox out = new VBox(10);
        out.getStyleClass().add("versions-diff");
        out.setPadding(new Insets(8));
        if (in.sides().renamed()) out.getChildren().add(note("Renamed from " + in.sides().oldPath() + "."));

        if (isImage(in.path())) {
            out.getChildren().add(pictures(in.sides()));
        } else if (in.diff() == null) {
            out.getChildren().add(text(in.textDiff()));
        } else if (!in.diff().readable()) {
            out.getChildren().addAll(note(in.diff().problem()), text(in.textDiff()));
        } else {
            javaCards(in, actions, out);
        }
        return out;
    }

    private void javaCards(Input in, Actions actions, VBox out) {
        BlockDiff.FileDiff diff = in.diff();
        Path file = config.projectPath().resolve(in.path());
        BlockPreview before = in.sides().before() == null ? null
                : new BlockPreview(config, live, file, in.sides().beforeText());
        BlockPreview after = in.sides().after() == null ? null
                : new BlockPreview(config, live, file, in.sides().afterText());

        for (BlockDiff.FieldChange field : diff.fields()) {
            out.getChildren().add(fieldCard(field, before, after));
        }
        for (BlockDiff.MethodChange change : diff.methods()) {
            out.getChildren().add(card(change, before, after, in.sides(), actions));
        }
        if (diff.unchanged() > 0) {
            out.getChildren().add(note(diff.unchanged() + (diff.unchanged() == 1 ? " function" : " functions")
                    + " unchanged."));
        }
        if (diff.methods().isEmpty() && diff.fields().isEmpty()) {
            out.getChildren().addAll(note("No function or field changed — only layout, comments or imports."),
                    text(in.textDiff()));
        }
    }

    // -------------------------------------------------------------------------
    // A function's card
    // -------------------------------------------------------------------------

    private Node card(BlockDiff.MethodChange change, BlockPreview before, BlockPreview after,
                      VersionReader.Sides sides, Actions actions) {
        Label title = new Label(change.name() + "()");
        title.getStyleClass().add("versions-card-title");
        Label kind = new Label(switch (change.kind()) {
            case ADDED -> "new";
            case REMOVED -> "removed";
            case CHANGED -> change.header() && change.before().isEmpty() && change.after().isEmpty()
                    ? "header changed" : "changed";
        });
        kind.getStyleClass().addAll("versions-card-kind", "versions-card-kind--" + change.kind().name().toLowerCase(Locale.ROOT));

        Node blocks = sideBySide(
                side(before, change.beforeStart(), change.before(),
                        change.kind() == BlockDiff.Mark.REMOVED ? BlockDiff.Mark.REMOVED : null),
                side(after, change.afterStart(), change.after(),
                        change.kind() == BlockDiff.Mark.ADDED ? BlockDiff.Mark.ADDED : null));
        Node java = sideBySide(
                code(sides.beforeText(), change.beforeStart()),
                code(sides.afterText(), change.afterStart()));
        boolean drawn = blocks.getProperties().containsKey(DRAWN);

        ToggleGroup mode = new ToggleGroup();
        ToggleButton asBlocks = new ToggleButton("Blocks");
        ToggleButton asJava = new ToggleButton("Java");
        asBlocks.setToggleGroup(mode);
        asJava.setToggleGroup(mode);
        VBox content = new VBox();
        mode.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                mode.selectToggle(was);
                return;
            }
            content.getChildren().setAll(now == asJava ? java : blocks);
        });
        mode.selectToggle(drawn ? asBlocks : asJava);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(6, title, kind, spacer, asBlocks, asJava);
        header.setAlignment(Pos.CENTER_LEFT);
        String label = actions.restoreLabel(change);
        if (label != null) {
            Button restore = new Button(label);
            restore.setOnAction(e -> actions.restoreFunction(change));
            header.getChildren().add(restore);
        }
        VBox card = new VBox(6, header, content);
        card.getStyleClass().add("versions-card");
        card.setPadding(new Insets(8));
        return card;
    }

    private Node sideBySide(Node before, Node after) {
        VBox left = column(beforeHeading, before);
        VBox right = column(afterHeading, after);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(12, left, right);
        if (before.getProperties().containsKey(DRAWN) || after.getProperties().containsKey(DRAWN)) {
            row.getProperties().put(DRAWN, true);
        }
        return row;
    }

    private static VBox column(String heading, Node body) {
        Label h = new Label(heading);
        h.getStyleClass().add("versions-meta");
        VBox box = new VBox(4, h, body);
        box.setMinWidth(0);
        box.setPrefWidth(1);
        return box;
    }

    /**
     * One side as blocks, or a line saying why not. {@code whole} is the mark the whole function takes — added
     * on the after side of a new one, removed on the before side of a deleted one — or null.
     */
    private static Node side(BlockPreview preview, int start, List<BlockDiff.Span> spans, BlockDiff.Mark whole) {
        if (preview == null || start < 0) return note("—");
        Node method = preview.method(start, spans);
        if (method == null) {
            List<String> why = preview.problems();
            return note(why.isEmpty() ? "This function could not be drawn; see Java." : why.getFirst());
        }
        if (whole != null) method.pseudoClassStateChanged(BlockPreview.pseudoClass(whole), true);
        // The canvas's classes and font, read once: a card lives until the next row is picked, and the
        // editor's own canvas is what follows a preference change.
        VBox canvas = new VBox(method);
        canvas.getStyleClass().addAll("blocks-canvas", "reader-mode", BlockStylePreference.style().styleClass());
        BlockFontPreference.font().applyTo(canvas);
        canvas.getProperties().put(DRAWN, true);
        return canvas;
    }

    private static Node code(String source, int start) {
        if (source == null || start < 0) return note("—");
        int end = start;
        var cu = com.botmaker.studio.parser.helpers.SourceParser.parse(source);
        for (var m : BlockDiff.methods(cu).values()) {
            if (m.getStartPosition() == start) end = start + m.getLength();
        }
        TextArea area = new TextArea(source.substring(start, end));
        area.setEditable(false);
        area.getStyleClass().add("console-area");
        area.setStyle("-fx-font-family: monospace;");
        area.setPrefRowCount(Math.min(24, (int) area.getText().lines().count() + 1));
        return area;
    }

    // -------------------------------------------------------------------------
    // Fields, pictures, text
    // -------------------------------------------------------------------------

    /**
     * One changed field — a {@code @Param}, a constant — drawn Was | Now as the canvas draws it (2026-09-26). It
     * was a row of Java text, so turning a parameter from a list into a map showed two initialisers to read
     * rather than the two value editors the user had actually seen. A side the canvas did not draw falls back
     * to its text.
     */
    private Node fieldCard(BlockDiff.FieldChange f, BlockPreview before, BlockPreview after) {
        Label title = new Label(f.name().substring(f.name().indexOf('.') + 1));
        title.getStyleClass().add("versions-card-title");
        Label kind = new Label(f.was() == null ? "new" : f.now() == null ? "removed"
                : f.parameter() ? "parameter changed" : "changed");
        BlockDiff.Mark mark = f.was() == null ? BlockDiff.Mark.ADDED
                : f.now() == null ? BlockDiff.Mark.REMOVED : BlockDiff.Mark.CHANGED;
        kind.getStyleClass().addAll("versions-card-kind", "versions-card-kind--" + mark.name().toLowerCase(Locale.ROOT));
        HBox header = new HBox(6, title, kind);
        header.setAlignment(Pos.CENTER_LEFT);

        Node was = fieldSide(before, f.beforeStart(), f.was());
        Node now = fieldSide(after, f.afterStart(), f.now());
        VBox card = new VBox(6, header, sideBySide(was, now));
        card.getStyleClass().add("versions-card");
        card.setPadding(new Insets(8));
        return card;
    }

    private static Node fieldSide(BlockPreview preview, int start, String text) {
        Node drawn = preview == null ? null : preview.field(start);
        if (drawn != null) {
            drawn.getProperties().put(DRAWN, true);
            return drawn;
        }
        return new Label(text == null ? "—" : text);
    }

    private Node pictures(VersionReader.Sides sides) {
        return sideBySide(picture(sides.before()), picture(sides.after()));
    }

    private static Node picture(byte[] bytes) {
        if (bytes == null) return note("—");
        ImageView view = new ImageView(new Image(new ByteArrayInputStream(bytes)));
        view.setPreserveRatio(true);
        view.setFitWidth(Math.min(240, view.getImage().getWidth()));
        return view;
    }

    private static Node text(String diff) {
        TextArea area = new TextArea(diff == null || diff.isBlank()
                ? "(no text to compare — a new, binary or unchanged file)" : diff);
        area.setEditable(false);
        area.getStyleClass().add("console-area");
        area.setStyle("-fx-font-family: monospace;");
        area.setPrefRowCount(20);
        return area;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("versions-meta");
        return l;
    }
}
