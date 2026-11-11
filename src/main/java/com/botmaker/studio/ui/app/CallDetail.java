package com.botmaker.studio.ui.app;

import com.botmaker.core.event.CoreEvent.ToolCallEnded;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Changed;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Part;
import com.botmaker.core.event.CoreEvent.ToolCallEnded.Point;
import com.botmaker.core.event.CoreEvent.ToolCallStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polyline;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Detail pane's drawing of one call (spec {@code 2026-11-11-assistant-window-design.md}): its arguments, its
 * progress while a step reports one, its reply part by part by MCP kind (text as written, an image at the pane's
 * width), the extras its reply's {@code _meta} said (a tap's point drawn on the frame after it, the changed methods as
 * links), and its device steps. {@link #blocks} decides what is drawn, with no screen; {@link #draw} draws it.
 */
final class CallDetail {

    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Indented with {@code \n} on every system: Jackson's default ends a line as the system does. */
    private static final DefaultPrettyPrinter PRETTY =
            new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
    private static final String MONO = "-fx-font-family: \"Consolas\", \"Menlo\", monospace;";
    /** Images decoded lately, by their base64: a running call is redrawn at each step. */
    private static final int IMAGES_CACHED = 8;

    /** What opens a changed method in the editor. */
    interface MethodOpener {
        void open(Path file, String className, String method);
    }

    /** One piece of a call's detail, in the order drawn. */
    sealed interface Block {
        record Section(String title) implements Block {}

        /** Text kept as written, monospace: a table or a JSON lines up. */
        record Code(String text) implements Block {}

        record Note(String text) implements Block {}

        record Progress(String device, String command, long done, long total) implements Block {}

        /** An image, the points the call touched on it (none for a plain one), and what it is when not the call's own. */
        record Picture(Part image, List<Point> touched, String caption) implements Block {}

        record Methods(List<Changed> changed) implements Block {}

        record Steps(List<ToolCallStep> steps, long more, long elsewhere) implements Block {}
    }

    private final MethodOpener opener;
    private final Map<String, Image> decoded = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > IMAGES_CACHED;
        }
    };

    CallDetail(MethodOpener opener) {
        this.opener = opener;
    }

    /** The call's tool, status, plugin, start and duration, on one line. */
    static String heading(ToolCalls.Call call) {
        StringBuilder line = new StringBuilder(call.tool()).append(" · ").append(call.status().displayName());
        if (!call.plugin().isEmpty()) line.append(" · ").append(call.plugin());
        line.append(" · ").append(CLOCK.format(Instant.ofEpochMilli(call.started().atMillis())));
        if (call.ended() != null) line.append(" · ").append(call.ended().millis()).append(" ms");
        return line.toString();
    }

    /** What {@code call}'s detail draws, in order; {@code calls} holds the frame after a tap. */
    static List<Block> blocks(ToolCalls.Call call, ToolCalls calls) {
        List<Block> out = new ArrayList<>();
        out.add(new Block.Section("Arguments"));
        String arguments = call.started().arguments().strip();
        out.add(arguments.isEmpty() || arguments.equals("{}") ? new Block.Note("None.") : new Block.Code(pretty(arguments)));
        if (call.progress() != null) {
            out.add(new Block.Progress(call.progress().device(), call.progress().command(), call.progress().done(),
                    call.progress().total()));
        }
        ToolCallEnded ended = call.ended();
        List<Point> touched = ended == null ? List.of() : ended.extras().tap();
        out.add(new Block.Section("Reply"));
        if (ended == null) {
            out.add(new Block.Note("Running…"));
        } else {
            boolean drawnOn = false;
            for (Part part : ended.reply()) {
                switch (part.kind()) {
                    case TEXT -> {
                        if (!part.value().isBlank()) out.add(new Block.Code(part.value().stripTrailing()));
                    }
                    case IMAGE -> {
                        out.add(new Block.Picture(part, drawnOn ? List.of() : touched, ""));
                        drawnOn = true;
                    }
                }
            }
            if (ended.reply().isEmpty()) out.add(new Block.Note("Nothing."));
            if (!touched.isEmpty() && !drawnOn) out.add(frameAfter(call, calls, touched));
            if (!ended.extras().changed().isEmpty()) {
                out.add(new Block.Section("Changed methods"));
                out.add(new Block.Methods(ended.extras().changed()));
            }
        }
        long elsewhere = ended == null ? 0 : ended.elsewhere();
        if (!call.steps().isEmpty() || call.moreSteps() > 0 || elsewhere > 0) {
            out.add(new Block.Section("Device steps"));
            out.add(new Block.Steps(call.steps(), call.moreSteps(), elsewhere));
        }
        return out;
    }

    private static Block frameAfter(ToolCalls.Call call, ToolCalls calls, List<Point> touched) {
        Optional<ToolCalls.Framed> framed = calls.frameAfter(call);
        if (framed.isEmpty()) return new Block.Note("Touched at " + said(touched) + "; no frame after it yet.");
        ToolCalls.Call later = framed.get().call();
        return new Block.Picture(framed.get().image(), touched, "Touched at " + said(touched) + ", on the frame after it: "
                + later.tool() + " at " + CLOCK.format(Instant.ofEpochMilli(later.started().atMillis())));
    }

    private static String said(List<Point> points) {
        return String.join(" → ", points.stream().map(p -> p.x() + "," + p.y()).toList());
    }

    /** {@code json} indented, as written when it is not JSON. */
    static String pretty(String json) {
        try {
            return JSON.writer(PRETTY).writeValueAsString(JSON.readTree(json));
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    // ---- drawing ----------------------------------------------------------------------------------------------

    /** {@code blocks} drawn, one under the other. */
    Node draw(List<Block> blocks) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(4, 6, 10, 6));
        for (Block block : blocks) box.getChildren().add(drawn(block));
        return box;
    }

    private Node drawn(Block block) {
        return switch (block) {
            case Block.Section section -> {
                Label title = new Label(section.title());
                title.setStyle("-fx-font-weight: bold;");
                title.setPadding(new Insets(6, 0, 0, 0));
                yield title;
            }
            case Block.Code code -> copyable(code.text(), MONO);
            case Block.Note note -> {
                Label said = new Label(note.text());
                said.setWrapText(true);
                said.setStyle("-fx-opacity: 0.75;");
                yield said;
            }
            case Block.Progress progress -> {
                ProgressBar bar = new ProgressBar(progress.total() > 0 ? (double) progress.done() / progress.total()
                        : ProgressBar.INDETERMINATE_PROGRESS);
                bar.setMaxWidth(Double.MAX_VALUE);
                Label what = new Label(progress.command() + " · " + amount(progress.done()) + " of "
                        + amount(progress.total()) + (progress.device().isEmpty() ? "" : " · " + progress.device()));
                what.setWrapText(true);
                yield new VBox(2, what, bar);
            }
            case Block.Picture picture -> {
                Optional<Image> image = image(picture.image().value());
                if (image.isEmpty()) yield new Label("[an image that could not be drawn]");
                Node framed = new Touched(image.get(), picture.image(), picture.touched());
                if (picture.caption().isEmpty()) yield framed;
                Label caption = new Label(picture.caption());
                caption.setWrapText(true);
                yield new VBox(2, caption, framed);
            }
            case Block.Methods methods -> {
                FlowPane links = new FlowPane(8, 2);
                for (Changed changed : methods.changed()) {
                    Hyperlink link = new Hyperlink(changed.className() + "." + changed.method() + "()");
                    link.setOnAction(e -> opener.open(Path.of(changed.file()), changed.className(), changed.method()));
                    links.getChildren().add(link);
                }
                yield links;
            }
            case Block.Steps steps -> steps(steps);
        };
    }

    private static Node steps(Block.Steps steps) {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(2);
        int row = 0;
        for (ToolCallStep step : steps.steps()) {
            Label mark = new Label(step.ok() ? "✓" : "✗");
            Label command = new Label(step.command());
            command.setStyle(MONO);
            command.setWrapText(true);
            Label device = new Label(step.device());
            device.setStyle("-fx-opacity: 0.75;");
            Label millis = new Label(step.millis() + " ms");
            grid.addRow(row++, mark, command, device, millis);
            GridPane.setHgrow(command, Priority.ALWAYS);
            if (!step.result().isBlank()) {
                Node result = copyable("→ " + step.result().strip(), MONO + " -fx-opacity: 0.8;");
                grid.add(result, 1, row++, 3, 1);
            }
        }
        VBox box = new VBox(4, grid);
        if (steps.more() > 0) box.getChildren().add(new Label("… and " + steps.more() + " more steps"));
        if (steps.elsewhere() > 0) {
            Label elsewhere = new Label(steps.elsewhere() + " steps ran meanwhile outside any call: perhaps this one's.");
            elsewhere.setWrapText(true);
            box.getChildren().add(elsewhere);
        }
        return box;
    }

    /** {@code text} wrapped, with a Copy on its menu: a label cannot be selected. */
    private static Label copyable(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle(style);
        label.setMinHeight(Label.USE_PREF_SIZE);
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        });
        label.setContextMenu(new ContextMenu(copy));
        return label;
    }

    private static String amount(long bytes) {
        if (bytes >= 1L << 30) return String.format("%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format("%.1f MB", bytes / (double) (1L << 20));
        if (bytes >= 1L << 10) return String.format("%.0f KB", bytes / (double) (1L << 10));
        return bytes + " B";
    }

    private Optional<Image> image(String base64) {
        Image cached = decoded.get(base64);
        if (cached != null) return Optional.of(cached);
        try {
            Image image = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            if (image.isError()) return Optional.empty();
            decoded.put(base64, image);
            return Optional.of(image);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * An image at the pane's width (never above its own), with the points a call touched drawn on it: a ring for a
     * tap, a line with a ring at its end for a swipe. The points are in the touched screen's pixels, the image's own
     * size before the core cut it down ({@link Part#width()}).
     */
    private static final class Touched extends Pane {
        private final ImageView view;
        private final double sourceWidth;
        private final double ratio;
        private final List<Point> touched;
        private final Polyline path = new Polyline();
        private final Circle ring = new Circle(9);

        Touched(Image image, Part part, List<Point> touched) {
            this.view = new ImageView(image);
            this.sourceWidth = part.width() > 0 ? part.width() : image.getWidth();
            this.ratio = image.getHeight() / Math.max(1, image.getWidth());
            this.touched = touched;
            view.setPreserveRatio(true);
            view.setSmooth(true);
            getChildren().add(view);
            if (!touched.isEmpty()) {
                path.setStroke(Color.web("#ff3b30"));
                path.setStrokeWidth(3);
                ring.setFill(Color.web("#ff3b30", 0.25));
                ring.setStroke(Color.web("#ff3b30"));
                ring.setStrokeWidth(2.5);
                getChildren().addAll(path, ring);
            }
            setMinWidth(0);
        }

        private double shownWidth(double width) {
            return Math.max(1, Math.min(width, view.getImage().getWidth()));
        }

        @Override
        protected double computePrefWidth(double height) {
            return view.getImage().getWidth();
        }

        @Override
        protected double computePrefHeight(double width) {
            return shownWidth(width < 0 ? view.getImage().getWidth() : width) * ratio;
        }

        @Override
        protected void layoutChildren() {
            double shown = shownWidth(getWidth());
            view.setFitWidth(shown);
            view.relocate(0, 0);
            if (touched.isEmpty()) return;
            double scale = shown / sourceWidth;
            path.getPoints().clear();
            for (Point p : touched) path.getPoints().addAll(p.x() * scale, p.y() * scale);
            path.setVisible(touched.size() > 1);
            Point last = touched.getLast();
            ring.setCenterX(last.x() * scale);
            ring.setCenterY(last.y() * scale);
        }
    }
}
