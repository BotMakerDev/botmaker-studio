package com.botmaker.studio.ui.app.viewers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What a file that is not Java looks like when the explorer opens it (2026-09-27): a picture as itself, JSON as
 * a tree, a {@code .properties} file as a table of keys and values, and any other text as text. View only —
 * nothing here writes, because every one of these files is either a plugin's (its window edits it) or the
 * user's to change in whatever editor they like.
 *
 * <p>Nothing is parsed by hand: a picture is JavaFX's {@link Image}, JSON is Jackson's tree, a properties file
 * is {@link Properties}. A file that does not parse as what its name says is shown as text, under a line saying
 * why — never as an empty viewer.
 */
public final class ResourceViewers {

    private ResourceViewers() {}

    /** Past this a file is described, not shown: a viewer is for reading, and a TextArea of 40 MB is not. */
    static final long MAX_BYTES = 4L * 1024 * 1024;

    /** How a file is shown, by its extension. */
    public enum Format {
        IMAGE("image", "Picture", List.of("png", "jpg", "jpeg", "gif", "bmp", "webp")),
        JSON("json", "JSON", List.of("json")),
        PROPERTIES("properties", "Properties", List.of("properties")),
        TEXT("text", "Text", List.of("txt", "yml", "yaml", "xml", "csv", "md"));

        private final String id;
        private final String displayName;
        private final List<String> extensions;

        Format(String id, String displayName, List<String> extensions) {
            this.id = id;
            this.displayName = displayName;
            this.extensions = extensions;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        /** The format {@code file}'s extension names, or empty for a file no viewer shows. */
        public static Optional<Format> of(Path file) {
            String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : name.substring(dot + 1);
            for (Format format : values()) {
                if (format.extensions.contains(extension)) return Optional.of(format);
            }
            return Optional.empty();
        }
    }

    /** True when {@link #view} shows {@code file}. */
    public static boolean canView(Path file) {
        return Format.of(file).isPresent();
    }

    /** {@code file}, drawn by its format; a line saying why when it cannot be read. FX thread. */
    public static Node view(Path file) {
        Format format = Format.of(file).orElse(Format.TEXT);
        try {
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                return note(file.getFileName() + " is " + size / 1024 / 1024 + " MB — too large to show here.");
            }
            byte[] bytes = Files.readAllBytes(file);
            return switch (format) {
                case IMAGE -> picture(bytes);
                case JSON -> json(new String(bytes, StandardCharsets.UTF_8));
                case PROPERTIES -> properties(new String(bytes, StandardCharsets.UTF_8));
                case TEXT -> text(new String(bytes, StandardCharsets.UTF_8));
            };
        } catch (IOException e) {
            return note(file.getFileName() + " could not be read (" + e.getMessage() + ").");
        }
    }

    // --- pictures ----------------------------------------------------------------------------------------------

    /** A picture at its own size, scrolling when it is larger than the tab, with its dimensions under it. */
    static Node picture(byte[] bytes) {
        Image image = new Image(new ByteArrayInputStream(bytes));
        if (image.isError()) return note("This picture could not be decoded.");
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        Label size = new Label((int) image.getWidth() + " × " + (int) image.getHeight() + " px");
        size.getStyleClass().add("resource-viewer-note");
        VBox column = new VBox(8, view, size);
        column.setPadding(new Insets(16));
        ScrollPane scroll = new ScrollPane(column);
        scroll.getStyleClass().add("resource-viewer");
        return scroll;
    }

    /**
     * A thumbnail no wider than {@code maxWidth} — the picture diff's side ({@code DiffCards}) and anywhere else a
     * picture is shown inside something.
     */
    public static ImageView thumbnail(byte[] bytes, double maxWidth) {
        ImageView view = new ImageView(new Image(new ByteArrayInputStream(bytes)));
        view.setPreserveRatio(true);
        view.setFitWidth(Math.min(maxWidth, view.getImage().getWidth()));
        return view;
    }

    // --- JSON --------------------------------------------------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A JSON document as a tree, every level open to the second; text under a note when it is not JSON. */
    static Node json(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return withNote("Not valid JSON (" + e.getOriginalMessage() + ") — shown as text.", text(text));
        }
        if (root == null || root.isMissingNode()) return text(text);
        TreeItem<String> item = jsonTree("", root);
        item.setExpanded(true);
        item.getChildren().forEach(child -> child.setExpanded(true));
        TreeView<String> tree = new TreeView<>(item);
        tree.getStyleClass().addAll("resource-viewer", "resource-viewer-tree");
        return tree;
    }

    /**
     * {@code node} as a tree row labelled {@code key}: an object or array is a row with one child per member
     * ({@code key {3}}, {@code key [2]}), a value is one row reading {@code key: value}.
     */
    static TreeItem<String> jsonTree(String key, JsonNode node) {
        String prefix = key.isEmpty() ? "" : key + (node.isContainerNode() ? " " : ": ");
        if (node.isObject()) {
            TreeItem<String> item = new TreeItem<>(prefix + "{" + node.size() + "}");
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                item.getChildren().add(jsonTree(field.getKey(), field.getValue()));
            }
            return item;
        }
        if (node.isArray()) {
            TreeItem<String> item = new TreeItem<>(prefix + "[" + node.size() + "]");
            for (int i = 0; i < node.size(); i++) item.getChildren().add(jsonTree("[" + i + "]", node.get(i)));
            return item;
        }
        return new TreeItem<>(prefix + node);
    }

    // --- properties --------------------------------------------------------------------------------------------

    /** A properties file as a two-column table, by key. */
    static Node properties(String text) {
        List<Map.Entry<String, String>> rows;
        try {
            rows = propertyRows(text);
        } catch (IOException | IllegalArgumentException e) {
            return withNote("Not a readable properties file (" + e.getMessage() + ") — shown as text.", text(text));
        }
        TableView<Map.Entry<String, String>> table = new TableView<>();
        table.getStyleClass().add("resource-viewer");
        TableColumn<Map.Entry<String, String>, String> key = new TableColumn<>("Key");
        key.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().getKey()));
        TableColumn<Map.Entry<String, String>, String> value = new TableColumn<>("Value");
        value.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().getValue()));
        table.getColumns().addAll(List.of(key, value));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getItems().setAll(rows);
        table.setPlaceholder(new Label("This file sets no keys."));
        return table;
    }

    /** Every key {@link Properties} reads out of {@code text}, sorted by key. */
    static List<Map.Entry<String, String>> propertyRows(String text) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(text));
        Map<String, String> sorted = new TreeMap<>();
        properties.stringPropertyNames().forEach(name -> sorted.put(name, properties.getProperty(name)));
        return new ArrayList<>(sorted.entrySet());
    }

    // --- text --------------------------------------------------------------------------------------------------

    static Node text(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.getStyleClass().addAll("resource-viewer", "resource-viewer-text");
        return area;
    }

    private static Node withNote(String note, Node below) {
        BorderPane pane = new BorderPane(below);
        pane.setTop(note(note));
        return pane;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setPadding(new Insets(8, 12, 8, 12));
        label.getStyleClass().add("resource-viewer-note");
        return label;
    }
}
