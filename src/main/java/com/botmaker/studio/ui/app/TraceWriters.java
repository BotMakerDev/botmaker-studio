package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.TraceLine;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Trace tab's writer filter (2026-09-29): every class and method that wrote a line, grouped by who ships the
 * class (this bot, a plugin by name, or a library), each with a checkbox. Unticking hides that writer's lines.
 *
 * <p>What is kept is what is <em>hidden</em>, as keys: a class's binary name hides the whole class, and {@code
 * class#method} one method. Hidden rather than shown so a writer never seen before appears, the same reason the
 * toolbar keeps its hidden groups. The keys are the project's ({@code StudioProjectSettings.hiddenTraceWriters}),
 * so a noisy method stays quiet from one run, and one day, to the next.
 *
 * <p>The tree lists the writers seen since the window opened, plus every hidden one, so what is hidden can
 * always be shown again. {@link #hides} and {@link #key} are {@code static} and free of JavaFX.
 */
final class TraceWriters {

    static final String THIS_BOT = "This bot";
    static final String LIBRARIES = "Libraries";

    /** Whether {@code hidden} hides {@code line}'s writer: its class, or its class and method. */
    static boolean hides(Collection<String> hidden, TraceLine line) {
        if (hidden.isEmpty() || line.writerClass().isEmpty()) return false;
        return hidden.contains(line.writerClass()) || hidden.contains(key(line.writerClass(), line.writerMethod()));
    }

    /** The key that hides one method of {@code className}. */
    static String key(String className, String method) {
        return className + "#" + method;
    }

    /** The simple name of a binary class name, a nested class keeping its outer one ({@code Outer$Inner}). */
    static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Class → the methods seen writing, sorted, so the tree reads in a stable order. */
    private final Map<String, Set<String>> seen = new TreeMap<>();
    /** Who ships each class, asked once per class. */
    private final Map<String, String> groups = new HashMap<>();
    private final Function<String, String> groupOf;
    private final Consumer<Set<String>> onChange;
    private final Set<String> expanded = new HashSet<>();
    private final TreeView<String> tree = new TreeView<>();
    private final VBox node;
    private Set<String> hidden = new LinkedHashSet<>();
    /** Set while the tree is rebuilt, so the rebuild's own ticks are not taken for the user's. */
    private boolean building;

    /**
     * @param groupOf  who ships a class: {@link #THIS_BOT}, a plugin's name, or {@link #LIBRARIES}
     * @param onChange hears the new hidden keys whenever a box is ticked or unticked
     */
    TraceWriters(Function<String, String> groupOf, Consumer<Set<String>> onChange) {
        this.groupOf = groupOf;
        this.onChange = onChange;
        tree.setShowRoot(false);
        tree.setCellFactory(v -> new CheckBoxTreeCell<>(item -> ((CheckBoxTreeItem<String>) item).selectedProperty(),
                new StringConverter<>() {
                    @Override
                    public String toString(TreeItem<String> item) {
                        boolean group = item.getParent() != null && item.getParent().getParent() == null;
                        return group ? item.getValue() : label(item.getValue());
                    }

                    @Override
                    public TreeItem<String> fromString(String text) {
                        return null;
                    }
                }));
        VBox.setVgrow(tree, Priority.ALWAYS);
        Label title = new Label("Show lines from");
        title.getStyleClass().add("review-summary");
        node = new VBox(4, title, tree);
        node.getStyleClass().add("trace-writers");
        node.setPrefWidth(260);
    }

    VBox node() {
        return node;
    }

    Set<String> hidden() {
        return Set.copyOf(hidden);
    }

    /** Starts from the project's saved keys; lists each hidden writer so it can be shown again. */
    void load(Collection<String> keys) {
        hidden = new LinkedHashSet<>(keys);
        for (String key : keys) {
            int hash = key.indexOf('#');
            if (hash < 0) seen.computeIfAbsent(key, k -> new TreeSet<>());
            else seen.computeIfAbsent(key.substring(0, hash), k -> new TreeSet<>()).add(key.substring(hash + 1));
        }
        rebuild();
    }

    /**
     * Lists the classes whose calls the run traces (the project's offered plugin classes, 2026-09-30) before any
     * of them wrote a line, so one can be unticked ahead of a run. An unticked class is not traced at all from the
     * next run on: Studio passes what is hidden to the trace agent.
     */
    void offer(Collection<String> classNames) {
        boolean added = false;
        for (String className : classNames) {
            if (!seen.containsKey(className)) {
                seen.put(className, new TreeSet<>());
                added = true;
            }
        }
        if (added && node.isVisible()) rebuild();
    }

    /** Notes {@code line}'s writer; the tree grows only when the writer is new. */
    void saw(TraceLine line) {
        if (line.writerClass().isEmpty()) return;
        boolean added = seen.computeIfAbsent(line.writerClass(), k -> new TreeSet<>()).add(line.writerMethod());
        if (added && node.isVisible()) rebuild();
    }

    /** Hides {@code key} (a class, or {@code class#method}) as a row's right-click does. */
    void hide(String key) {
        hidden.add(key);
        rebuild();
        onChange.accept(hidden());
    }

    void showAll() {
        hidden.clear();
        rebuild();
        onChange.accept(hidden());
    }

    /** Rebuilds the tree from what was seen and what is hidden, keeping which classes were open. */
    void rebuild() {
        building = true;
        try {
            CheckBoxTreeItem<String> root = new CheckBoxTreeItem<>("");
            Map<String, CheckBoxTreeItem<String>> byGroup = new TreeMap<>((a, b) -> rank(a) != rank(b)
                    ? Integer.compare(rank(a), rank(b)) : a.compareToIgnoreCase(b));
            for (var entry : seen.entrySet()) {
                String className = entry.getKey();
                String group = groups.computeIfAbsent(className, groupOf);
                CheckBoxTreeItem<String> groupItem = byGroup.computeIfAbsent(group, g -> {
                    CheckBoxTreeItem<String> item = new CheckBoxTreeItem<>(g);
                    item.setExpanded(true);
                    return item;
                });
                groupItem.getChildren().add(classItem(className, entry.getValue()));
            }
            byGroup.values().forEach(TraceWriters::summarise);
            root.getChildren().addAll(byGroup.values());
            tree.setRoot(root);
        } finally {
            building = false;
        }
    }

    private CheckBoxTreeItem<String> classItem(String className, Set<String> methods) {
        CheckBoxTreeItem<String> item = new CheckBoxTreeItem<>(className);
        boolean classHidden = hidden.contains(className);
        for (String method : methods) {
            CheckBoxTreeItem<String> leaf = new CheckBoxTreeItem<>(key(className, method));
            leaf.setSelected(!classHidden && !hidden.contains(key(className, method)));
            leaf.selectedProperty().addListener((o, was, now) -> changed());
            item.getChildren().add(leaf);
        }
        if (methods.isEmpty()) item.setSelected(!classHidden);
        else summarise(item);
        item.selectedProperty().addListener((o, was, now) -> changed());
        item.setExpanded(expanded.contains(className));
        item.expandedProperty().addListener((o, was, now) -> {
            if (now) expanded.add(className);
            else expanded.remove(className);
        });
        return item;
    }

    /** Reads the hidden keys back off the tree: a class unticked whole is one key, else its unticked methods. */
    private void changed() {
        if (building || tree.getRoot() == null) return;
        Set<String> next = new LinkedHashSet<>();
        for (TreeItem<String> group : tree.getRoot().getChildren()) {
            for (TreeItem<String> child : group.getChildren()) {
                CheckBoxTreeItem<String> classItem = (CheckBoxTreeItem<String>) child;
                if (!classItem.isSelected() && !classItem.isIndeterminate()) {
                    next.add(classItem.getValue());
                    continue;
                }
                for (TreeItem<String> leaf : classItem.getChildren()) {
                    if (!((CheckBoxTreeItem<String>) leaf).isSelected()) next.add(leaf.getValue());
                }
            }
        }
        if (next.equals(hidden)) return;
        hidden = next;
        onChange.accept(hidden());
    }

    /**
     * Sets {@code parent}'s box from its children's, which a {@link CheckBoxTreeItem} does only when a child's
     * box changes, never when children are added: ticked when all are, empty when none are, else a dash.
     */
    private static void summarise(CheckBoxTreeItem<String> parent) {
        long ticked = parent.getChildren().stream()
                .map(c -> (CheckBoxTreeItem<String>) c)
                .filter(c -> c.isSelected() && !c.isIndeterminate())
                .count();
        boolean partly = parent.getChildren().stream()
                .anyMatch(c -> ((CheckBoxTreeItem<String>) c).isIndeterminate());
        // A fresh item is unticked, and setting a parent's box sets its children's: so a mixed parent is only
        // marked, never set, or it would untick the very children that make it mixed.
        if (!partly && ticked == parent.getChildren().size()) parent.setSelected(true);
        else if (partly || ticked > 0) parent.setIndeterminate(true);
    }

    /** This bot first, then plugins by name, then libraries. */
    private static int rank(String group) {
        return THIS_BOT.equals(group) ? 0 : LIBRARIES.equals(group) ? 2 : 1;
    }

    /** A group's name, a class's simple name, or a method's name with its parentheses. */
    private static String label(String value) {
        if (value == null) return "";
        int hash = value.indexOf('#');
        if (hash >= 0) return value.substring(hash + 1) + "()";
        return value.contains(".") ? simpleName(value) : value;
    }
}
