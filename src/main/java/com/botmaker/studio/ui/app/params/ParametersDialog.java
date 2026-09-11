package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.ParameterDeclaration;
import com.botmaker.plugin.api.ParameterEdit;
import com.botmaker.plugin.api.ParameterGroup;
import com.botmaker.plugin.api.ParameterRow;
import com.botmaker.plugin.api.value.Range;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueChoice;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.VariableRailModel;
import com.botmaker.studio.state.SnapshotHistory;
import com.botmaker.studio.ui.app.StudioWindow;
import com.botmaker.studio.ui.app.params.ParamValueWidgets.ValueEditor;
import com.botmaker.studio.ui.render.components.ValueTypePicker;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.css.PseudoClass;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one place a bot's parameters are defined: what each is called, what it holds, who it is for, and what
 * it is set to.
 *
 * <h2>The window is the host's; the data is not</h2>
 *
 * <p><b>Nothing in this class knows what an activity is, or what file a parameter lives in.</b> It asks each
 * loaded plugin for the rows of the sections that plugin declared ({@code StudioPlugin.parameterRows}), draws
 * them, and hands every change back as data — a value through {@link ParameterEdit}, everything else through
 * {@link ParameterDeclaration}. Until 2026-09-10 it parsed {@code activities.json} itself, which meant the
 * host knew one plugin's storage format and a second plugin could not have had parameters at all.
 *
 * <p><b>A declaration states the row as wanted, never the transition.</b> Adding, renaming, retyping,
 * re-optioning, bounding, refiling and removing are all one call, and what each costs — a value reset by a
 * retype, options that survive a change of shape, a number clamped into a new range — is the owning plugin's
 * rule. So this window never coerces: it renders what comes back, which is the row as it was actually stored.
 *
 * <h2>One list, organised by category</h2>
 *
 * <p>Every parameter belongs to the project. What the rail on the left offers is a <em>view</em> of that one
 * list — <i>All</i>, <i>General</i> for the unfiled, then the categories the loaded plugins declare on their
 * own {@link ParameterGroup}s. Filing a parameter under "Timing" does not scope it to anything: a category
 * only says where it is listed.
 *
 * <h2>Why it is not in the flow editor</h2>
 *
 * <p>Values used to be edited in the Activity Flow dialog's side panel, which meant the graph editor was also
 * the settings editor: to change a number you opened the canvas, found the card, and edited a cramped column
 * beside it. The flow editor is about where the bot goes next; this is about what it is configured with.
 *
 * <h2>Nothing to save</h2>
 *
 * <p>Every change is stored by its owner as it is made — there is no autosave here any more, because there is
 * no file here to write. What is left is ↶ and ↷, which replay the rows as they were as further declarations;
 * see {@link #restore}.
 */
public final class ParametersDialog {

    private final Window owner;
    private final ProjectConfig config;

    /** Lit on the rail row a dragged parameter is over — styled in {@code blocks.css}, never inline. */
    private static final PseudoClass RAIL_DROP = PseudoClass.getPseudoClass("rail-drop");

    private final ListView<VariableRailModel.Row> rail = new ListView<>();
    private final Button moveHere = new Button("Move parameters here…");
    private final VBox paramColumn = new VBox(10);
    private final Label statusLabel = new Label();

    /**
     * One row and the section it belongs to.
     *
     * <p>A row carries no group of its own — the host asked for it by group id — so the pair is what a
     * handle has to be: a name identifies a parameter only inside its own plugin's section, and two plugins
     * may both offer a {@code Timeout}.
     */
    private record Owned(String group, ParameterRow row) {

        boolean is(String otherGroup, String name) {
            return group.equals(otherGroup) && row.name().equals(name);
        }
    }

    /** What the plugins currently hold, in section order — re-read after every change. */
    private final List<Owned> rows = new ArrayList<>();

    /** Readers for the value widgets currently on screen; re-created whenever the column is rebuilt. */
    private final List<ValueEditor> valueEditors = new ArrayList<>();

    /** The categories the loaded plugins declare, merged across their sections — the rail's whole vocabulary. */
    private List<String> categories = List.of();
    private String selectedTag = VariableRailModel.ALL;

    /**
     * Which plugin's section a newly added parameter is filed under — the first declared section until a
     * second plugin is installed and the user picks its section in the add row.
     */
    private String selectedGroup = ParameterGroup.DEFAULT_ID;
    private Stage stage;

    /**
     * Undo/redo over the rows, in the {@linkplain SnapshotHistory#SnapshotHistory(Consumer) restore-only}
     * form: this dialog knows both halves of every step, because it decides when one has happened (see
     * {@link #commitPending}).
     */
    private final SnapshotHistory<List<Owned>> history = new SnapshotHistory<>(this::restore);

    /** The rows as of the last recorded step — the "before" the next one is measured against. */
    private List<Owned> committed = List.of();

    /**
     * Typing is not an event this dialog can hear. The value widgets are twenty shapes with twenty different
     * change signals and the note field fires per keystroke, so instead of listening, the state is compared
     * against {@link #committed} on a slow tick: identical, nothing happened; different, that is one step.
     * A burst of typing therefore becomes one ↶, which is what a person means by "take back what I typed".
     */
    private static final Duration TYPING_TICK = Duration.millis(700);
    private Timeline typingWatch;

    public ParametersDialog(Window owner, ProjectConfig config) {
        this.owner = owner;
        this.config = config;
    }

    public void show() {
        // Every section's own declared categories, in section order — the second layer inside a group.
        categories = VariableRailModel.categoriesOf(PluginHost.parameterGroups(sdkPin()));
        reload();

        StudioWindow window = StudioWindow.modal("parameters", "Parameters", owner)
                .size(900, 640).minSize(700, 460);
        stage = window.stage();

        BorderPane root = new BorderPane();
        root.setLeft(buildRail());
        root.setCenter(buildParamPane());
        // The add bar is pinned above the buttons rather than sitting at the end of the scrolling column,
        // which is where it was and where a project with twenty parameters hid it: adding one meant scrolling
        // past every parameter you already had, and in the category you were looking at it was the first thing
        // you wanted. It reads the selected category when it fires, so one bar serves every category.
        root.setBottom(new VBox(buildAddRow(), buildBottomBar()));

        rebuildRail();

        // Wired after the rows are read, so opening the project does not read as an edit by the user: the
        // rows as they were opened are the state undo bottoms out at.
        committed = List.copyOf(rows);
        typingWatch = new Timeline(new KeyFrame(TYPING_TICK, e -> commitPending("the value you typed")));
        typingWatch.setCycleCount(Animation.INDEFINITE);
        typingWatch.play();

        stage.setOnHidden(e -> typingWatch.stop());
        window.show(root);
    }

    /**
     * Re-reads every section from the plugin that owns it.
     *
     * <p>Asked on every change rather than kept in step by this window: the plugin stores the row and may
     * store something other than what it was handed, so what is on screen after a change has to be what the
     * owner says it holds. That is also what makes a plugin's own dialog, editing the same data behind this
     * window's back, cost nothing worse than a stale screen until the next change.
     */
    private void reload() {
        rows.clear();
        for (ParameterGroup group : sections()) {
            for (ParameterRow row : PluginHost.parameterRows(group.id())) {
                rows.add(new Owned(group.id(), row));
            }
        }
    }

    // --- left: the tag rail ------------------------------------------------------------------------------

    private Node buildRail() {
        rail.setPrefWidth(220);
        rail.setCellFactory(list -> new ListCell<>() {
            {
                // Dropping a parameter onto a tag files it there — the same edit the row's picker makes, for
                // people who reach for the rail they are already looking at.
                setOnDragOver(e -> {
                    if (acceptsDrop(e)) e.acceptTransferModes(TransferMode.MOVE);
                    e.consume();
                });
                setOnDragEntered(e -> {
                    if (acceptsDrop(e)) pseudoClassStateChanged(RAIL_DROP, true);
                });
                setOnDragExited(e -> pseudoClassStateChanged(RAIL_DROP, false));
                setOnDragDropped(e -> {
                    pseudoClassStateChanged(RAIL_DROP, false);
                    if (!acceptsDrop(e)) return;
                    e.setDropCompleted(fileUnder(e.getDragboard().getString(),
                            ((VariableRailModel.TagRow) getItem()).tag()));
                    e.consume();
                });
            }

            /** A drop lands only on a real tag row, and only from this dialog's own drag. */
            private boolean acceptsDrop(DragEvent e) {
                return getItem() instanceof VariableRailModel.TagRow tag
                        && !VariableRailModel.ALL.equals(tag.tag())
                        && e.getGestureSource() != this
                        && e.getDragboard().hasString();
            }

            @Override protected void updateItem(VariableRailModel.Row row, boolean empty) {
                super.updateItem(row, empty);
                getStyleClass().remove("rail-heading");
                if (empty || row == null) {
                    setText(null);
                    setDisable(false);
                    return;
                }
                switch (row) {
                    case VariableRailModel.Heading heading -> {
                        setText(heading.text());
                        // A heading is a label that happens to live in a list, so it must not look or behave
                        // like a row you can land on — arrow-keying onto one would select nothing at all.
                        setDisable(true);
                        getStyleClass().add("rail-heading");
                    }
                    case VariableRailModel.TagRow tag -> {
                        // "General" beside "All variables" reads as a second everything-bucket; saying what
                        // it holds is cheaper than a heading alone at telling the two apart.
                        String label = ParameterRow.GENERAL.equals(tag.tag())
                                ? tag.tag() + " (no category)" : tag.tag();
                        setText(label + "  (" + tag.count() + ")");
                        setDisable(false);
                    }
                }
            }
        });
        rail.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> {
            if (is instanceof VariableRailModel.TagRow tag) {
                flushValues();
                selectedTag = tag.tag();
                rebuildParams();
                refreshRailActions();
            }
        });

        moveHere.setMaxWidth(Double.MAX_VALUE);
        moveHere.setTooltip(new Tooltip("File several parameters under this category at once, instead of "
                + "dragging them one at a time."));
        moveHere.setOnAction(e -> moveIntoSelected());

        VBox column = new VBox(6, rail, moveHere);
        VBox.setVgrow(rail, Priority.ALWAYS);
        return column;
    }

    /** Both rail buttons say what they act on, so neither is offered where it would mean nothing. */
    private void refreshRailActions() {
        boolean real = !VariableRailModel.ALL.equals(selectedTag);
        moveHere.setText(real ? "Move parameters to “" + selectedTag + "”…"
                : "Move parameters here…");
        moveHere.setDisable(!real);
    }

    /**
     * Files a batch of parameters under the selected category. The rail already takes one at a time by drag;
     * this is the same edit for the case the drag is tedious in — a category being populated for the first
     * time, where every parameter in the project is somewhere else.
     */
    private void moveIntoSelected() {
        if (VariableRailModel.ALL.equals(selectedTag)) return;
        String home = ParameterRow.GENERAL.equals(selectedTag) ? "" : selectedTag;
        List<Owned> inside = shown(selectedTag);
        List<Owned> outside = rows.stream().filter(entry -> !inside.contains(entry)).toList();
        if (outside.isEmpty()) {
            error("Every parameter is already filed under “" + selectedTag + "”.");
            return;
        }
        List<Owned> chosen = pickParameters(outside);
        if (chosen.isEmpty()) return;
        // One step for the batch: filing eight parameters at once should be one ↶, not eight.
        change("filing " + chosen.size() + " parameters under " + selectedTag, () -> {
            for (Owned picked : chosen) {
                declare(picked, current -> current.toBuilder().category(home).build());
            }
            error("");
        });
    }

    /**
     * A tick box per parameter, in one modal. Returns the rows ticked, or an empty list if cancelled.
     *
     * <p>The pairs themselves rather than their names: a name is unique only inside its plugin's section, so
     * a list of names could no longer say <em>which</em> {@code Timeout} was ticked.
     */
    private List<Owned> pickParameters(List<Owned> offered) {
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(4);
        for (Owned entry : offered) {
            CheckBox box = new CheckBox(entry.row().name() + "   ·   " + entry.row().categoryOrGeneral());
            box.setUserData(entry);
            boxes.add(box);
            column.getChildren().add(box);
        }
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(320);

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.initOwner(stage);
        dialog.setTitle("Move parameters");
        dialog.setHeaderText(null);
        ButtonType move = new ButtonType("Move", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(move, ButtonType.CANCEL);
        Label hint = new Label("Tick the parameters to file under “" + selectedTag + "”.");
        VBox box = new VBox(8, hint, scroll);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);

        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != move) return List.of();
        return boxes.stream().filter(CheckBox::isSelected).map(b -> (Owned) b.getUserData()).toList();
    }

    /** Redraws the rail (the counts move on every add, delete and re-tag) and keeps the selection. */
    private void rebuildRail() {
        List<VariableRailModel.Row> railRows =
                VariableRailModel.rowsOf(rows.stream().map(Owned::row).toList(), categories);
        rail.getItems().setAll(railRows);
        VariableRailModel.Row keep = railRows.stream()
                .filter(r -> r instanceof VariableRailModel.TagRow t && t.tag().equals(selectedTag))
                .findFirst()
                .orElse(railRows.isEmpty() ? null : railRows.getFirst());
        rail.getSelectionModel().select(keep);
        if (keep instanceof VariableRailModel.TagRow tag) selectedTag = tag.tag();
        rebuildParams();
        refreshRailActions();
    }

    /**
     * Files the dragged parameter under {@code tag} — what a drop onto the rail does, and the same edit the
     * card's own picker makes. Returns whether anything moved, which is what a drop reports back.
     *
     * <p>{@code dragged} is the {@code group\nname} pair the grip put on the dragboard; a payload with no
     * newline is read as a bare name in the default section, which is what every earlier drag was.
     */
    private boolean fileUnder(String dragged, String tag) {
        if (dragged == null) return false;
        int cut = dragged.indexOf('\n');
        String group = cut < 0 ? ParameterGroup.DEFAULT_ID : dragged.substring(0, cut);
        String name = cut < 0 ? dragged : dragged.substring(cut + 1);
        Owned entry = find(group, name);
        if (entry == null) return false;
        String home = ParameterRow.GENERAL.equals(tag) ? "" : tag;
        edit(entry, "the category", current -> current.toBuilder().category(home).build());
        return true;
    }

    /** The tags a parameter may be filed under: the declared ones, plus "no tag". */
    private List<String> filingChoices() {
        List<String> choices = new ArrayList<>();
        choices.add(ParameterRow.GENERAL);
        choices.addAll(categories);
        return choices;
    }

    // --- right: the parameters of the selected tag ---------------------------------------------------------

    private Node buildParamPane() {
        paramColumn.setPadding(new Insets(14));
        ScrollPane scroll = new ScrollPane(paramColumn);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /** Rebuilds the whole column. Values on screen are flushed first, so no rebuild loses a typed number. */
    private void rebuildParams() {
        flushValues();
        valueEditors.clear();
        paramColumn.getChildren().clear();

        Label title = new Label(ParameterRow.GENERAL.equals(selectedTag)
                ? selectedTag + " (no category)" : selectedTag);
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label explain = new Label("Every parameter belongs to the whole bot and is read from your code by "
                + "name. A category only says where it is listed — never who may read it.");
        explain.setWrapText(true);
        explain.getStyleClass().add("dialog-hint-text");
        paramColumn.getChildren().addAll(title, explain);

        List<Owned> visible = shown(selectedTag);
        for (ParameterGroup group : sections()) {
            List<Owned> mine = visible.stream().filter(entry -> entry.group().equals(group.id())).toList();
            paramColumn.getChildren().add(sectionHeader(group, mine.isEmpty()));
            for (Owned entry : mine) paramColumn.getChildren().add(buildParamCard(entry));
        }
    }

    /** The rows the rail's selected bucket holds, in section order. */
    private List<Owned> shown(String tag) {
        List<ParameterRow> visible = VariableRailModel.rowsIn(
                rows.stream().map(Owned::row).toList(), tag, categories);
        return rows.stream().filter(entry -> visible.contains(entry.row())).toList();
    }

    /**
     * The sections to draw, in order: one per plugin that declares a {@link ParameterGroup}, then one per
     * group this project's rows name that no installed plugin claims.
     *
     * <p><b>Every declared group gets a heading even when it is empty</b>, because the heading is how a user
     * discovers that a plugin has parameters at all — an empty section reads as "nothing set up yet", an
     * absent one as "this plugin has no settings", and only the first is true.
     */
    private List<ParameterGroup> sections() {
        List<ParameterGroup> groups = new ArrayList<>(PluginHost.parameterGroups(sdkPin()));
        if (groups.isEmpty()) groups.add(ParameterGroup.of(ParameterGroup.DEFAULT_ID, "Parameters"));
        Set<String> ids = new LinkedHashSet<>(groups.stream().map(ParameterGroup::id).toList());
        for (Owned entry : rows) {
            if (ids.add(entry.group())) {
                groups.add(ParameterGroup.of(entry.group(),
                        entry.group().isEmpty() ? "Parameters" : entry.group()));
            }
        }
        return groups;
    }

    /** The heading of one plugin's section, and — when it holds nothing yet — the line that says so. */
    private Node sectionHeader(ParameterGroup group, boolean empty) {
        Label heading = new Label(group.title());
        heading.getStyleClass().add("param-section-heading");
        VBox box = new VBox(2, heading);
        box.setPadding(new Insets(6, 0, 0, 0));
        if (empty) {
            Label none = new Label("Nothing filed here yet — add one in the bar at the bottom.");
            none.getStyleClass().add("dialog-hint-text");
            box.getChildren().add(none);
        }
        return box;
    }

    /**
     * The SDK version the open project pins, or null when it declares none or cannot be read.
     *
     * <p>Read from the pom each time rather than cached: the *Upgrade SDK* dialog moves it while this window
     * can be open, and a stale pin would draw the previous version's sections.
     *
     * <p>Null is passed straight to {@code parameters(pin)}, which every plugin answers totally — so a
     * project with no SDK simply has no section from it, which is the truth rather than a degradation.
     */
    private String sdkPin() {
        try {
            return MavenService.readSdkVersion(config.projectPath()).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One parameter: what it is called, what it holds, who it is for, where it is filed, what it is set to. */
    private Node buildParamCard(Owned entry) {
        ParameterRow v = entry.row();
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.getStyleClass().add("param-card");

        TextField name = new TextField(v.name());
        name.focusedProperty().addListener((o, was, is) -> {
            if (!is) commitRename(entry, name);
        });
        name.setOnAction(e -> {
            commitRename(entry, name);
            e.consume();   // Enter commits the name here; it is not also a keystroke for anything else
        });

        ValueTypePicker type = new ValueTypePicker();
        type.setChoice(v.type());
        type.setPrefWidth(180);
        type.choiceProperty().addListener((o, was, is) -> {
            if (is == null || is.equals(v.type())) return;
            // The row is handed back with the new type and the value it still holds; what a retype costs —
            // the reset, the dropped bounds, the options that do or do not survive — is the owner's rule.
            edit(entry, "the type", current -> rebuilt(current, is));
        });

        CheckBox shared = new CheckBox("Show to user");
        shared.setSelected(v.isPublic());
        shared.setTooltip(new Tooltip("Ticked, this appears in the Runner window under its category's "
                + "heading. Unticked, it is yours alone and never leaves this dialog."));
        shared.setOnAction(e -> editQuietly(entry, current -> current.toBuilder().visibility(
                shared.isSelected() ? Visibility.PUBLIC : Visibility.EDITOR_ONLY).build()));

        Button drop = new Button("✕");
        drop.getStyleClass().add("row-icon-button");
        drop.setTooltip(new Tooltip("Remove this parameter. Any code reading it stops compiling, so check "
                + "first — nothing here scans your source."));
        drop.setOnAction(e -> change("removing " + v.name(), () -> {
            PluginHost.parameterDeclared(ParameterDeclaration.removed(entry.group(), v.name()));
            reload();
            rebuildRail();
        }));

        // The one thing on the card that starts a drag. The name field cannot be it: a TextField's own drag
        // is how text is selected, and stealing that would cost more than the shortcut is worth.
        Label grip = new Label("⠿");
        grip.getStyleClass().add("dialog-hint-text");
        grip.setTooltip(new Tooltip("Drag onto a category on the left to file it there."));
        grip.setOnDragDetected(e -> {
            Dragboard board = grip.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            // The pair, not the name: a name only identifies a parameter inside its own plugin's section, and
            // a drop has to move the one that was picked up. \n cannot occur in either half — a group id is
            // trimmed and a parameter name is a Java identifier.
            content.putString(entry.group() + "\n" + v.name());
            board.setContent(content);
            e.consume();
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, grip, name, type, spacer, drop);
        head.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        grid.add(head, 0, 0, 2, 1);

        int row = 1;
        grid.add(new Label("Category"), 0, row);
        grid.add(buildTagPicker(entry), 1, row);
        row++;

        // A closed-set type brings its own choices (every direction, every mouse button), so there is nothing
        // here for the author to write down — offering an "add a choice" row over them would invite a second,
        // hand-typed copy of a list the plugin already owns.
        if (v.type().hasOptions() && v.type().type().options().isEmpty()) {
            Label heading = new Label("Choices");
            heading.setTooltip(new Tooltip(v.type().isList()
                    ? "The set this parameter's values are picked from. The user ticks any number of them."
                    : "The set this parameter's value is picked from. The user picks exactly one."));
            grid.add(heading, 0, row);
            grid.add(buildOptionsEditor(entry), 1, row);
            row++;
        }

        if (v.type().type().bounded()) {
            grid.add(new Label("Range"), 0, row);
            grid.add(buildBoundsEditor(entry), 1, row);
            row++;
        }

        grid.add(new Label("Value"), 0, row);
        Node widget = ParamValueWidgets.build(entry.group(), v, config, valueEditors);
        grid.add(widget, 1, row);
        GridPane.setHgrow(widget, Priority.ALWAYS);
        row++;

        TextField description = new TextField(v.description());
        description.setPromptText("what this is for (shown as a tooltip, and to the user when shared)");
        description.textProperty().addListener((o, was, is) ->
                editQuietly(entry, current -> current.toBuilder().description(is).build()));
        grid.add(new Label("Note"), 0, row);
        grid.add(description, 1, row);
        GridPane.setHgrow(description, Priority.ALWAYS);
        row++;

        grid.add(shared, 1, row);

        VBox card = new VBox(grid);
        card.setPadding(new Insets(4, 0, 4, 0));
        return card;
    }

    /**
     * The same row under a different type.
     *
     * <p>A rebuild rather than a {@code with…}: {@link ParameterRow}'s builder is named and typed at
     * construction, because a row is a value a plugin builds and those two components are what identify it.
     */
    private static ParameterRow rebuilt(ParameterRow row, ValueChoice type) {
        return ParameterRow.named(row.name(), type)
                .value(row.value())
                .description(row.description())
                .category(row.category())
                .visibility(row.visibility())
                .options(row.options())
                .bounds(row.bounds())
                .build();
    }

    /** Where this parameter is listed — one category or none, never several: a parameter has one home. */
    private Node buildTagPicker(Owned entry) {
        ComboBox<String> picker = new ComboBox<>();
        picker.getItems().setAll(filingChoices());
        picker.setValue(VariableRailModel.isDeclared(categories, entry.row().category())
                ? entry.row().category() : ParameterRow.GENERAL);
        picker.setOnAction(e -> {
            String chosen = picker.getValue();
            String home = ParameterRow.GENERAL.equals(chosen) ? "" : chosen;
            edit(entry, "the category", current -> current.toBuilder().category(home).build());
        });
        return picker;
    }

    /**
     * The declared choices for a set-shaped parameter: one editable row each, plus an add row. Replacing the
     * list is a declaration like any other, so a choice that is deleted cannot survive as a stored value
     * nobody can see any more — the owner prunes it.
     */
    private Node buildOptionsEditor(Owned entry) {
        ParameterRow v = entry.row();
        ValueType base = v.type().type();
        ValueEditors.Context ctx = new ValueEditors.Context(config, v.bounds());
        VBox box = new VBox(4);
        List<String> options = v.options();

        for (int i = 0; i < options.size(); i++) {
            box.getChildren().add(optionRow(entry, base, ctx, options, i));
        }

        // The add row is the base type's own editor, not a text field. A choice is a value of the parameter's
        // type, so writing one down should be the same gesture as setting one: a template comes out of the
        // gallery with its picture, a colour off the screen, a duration as hours and minutes. Typed as text it
        // was a name recalled from memory — and a misremembered one is a choice that silently matches nothing.
        ValueEditors.Editor fresh = ValueEditors.editorFor(base, null, ctx);
        HBox.setHgrow(fresh.node(), Priority.ALWAYS);
        Button add = new Button("Add");
        Runnable addOption = () -> {
            String typed = fresh.read().get();
            typed = typed == null ? "" : typed.trim();
            if (typed.isEmpty()) return;
            if (options.contains(typed)) {
                error("'" + typed + "' is already a choice here.");
                return;
            }
            List<String> updated = new ArrayList<>(options);
            updated.add(typed);
            replaceOptions(entry, updated);
        };
        add.setOnAction(e -> addOption.run());
        if (fresh.node() instanceof TextField field) {
            field.setPromptText("new choice");
            field.setOnAction(e -> {
                addOption.run();
                e.consume();
            });
        }
        HBox addRow = new HBox(6, fresh.node(), add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(addRow);
        return box;
    }

    /**
     * One declared choice.
     *
     * <p>Text stays editable in place — a typo in a label is fixed by fixing it. Every other type is shown the
     * way it is shown everywhere else (a thumbnail, a swatch, a spelled-out length) and changed by removing it
     * and adding the right one: an in-place editor for those would need a commit gesture per row, and a
     * three-item choice list is not where that ceremony earns its keep.
     */
    private Node optionRow(Owned entry, ValueType base, ValueEditors.Context ctx,
                           List<String> options, int at) {
        String option = options.get(at);
        Node shown;
        if (ValueCatalog.TEXT_ID.equals(base.id())) {
            TextField field = new TextField(option);
            HBox.setHgrow(field, Priority.ALWAYS);
            Runnable commit = () -> {
                String typed = field.getText() == null ? "" : field.getText().trim();
                if (typed.isEmpty() || typed.equals(option)) {
                    field.setText(option);
                    return;
                }
                if (options.contains(typed)) {
                    error("'" + typed + "' is already a choice here.");
                    field.setText(option);
                    return;
                }
                List<String> updated = new ArrayList<>(options);
                updated.set(at, typed);
                replaceOptions(entry, updated);
            };
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commit.run();
            });
            field.setOnAction(e -> {
                commit.run();
                e.consume();
            });
            shown = field;
        } else {
            Label label = new Label(option, ValueEditors.optionGraphic(base, option, ctx));
            HBox.setHgrow(label, Priority.ALWAYS);
            shown = label;
        }

        Button remove = new Button("✕");
        remove.getStyleClass().add("row-icon-button");
        remove.setOnAction(e -> {
            List<String> updated = new ArrayList<>(options);
            updated.remove(at);
            replaceOptions(entry, updated);
        });
        HBox row = new HBox(6, shown, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * The declared range of a number: smallest and largest, both optional and <b>independent</b>. Leaving
     * both blank is what most numbers want and is the state a parameter starts in; filling in only one is
     * "at most 10" or "at least 1", which is a sentence people say and which used to be unsayable here.
     *
     * <p>Declaring either clamps a stored value that falls outside it — the owner's rule, applied when the
     * declaration lands — so committing a bound rebuilds the card: the widget the range describes is not the
     * widget that was there before it.
     *
     * <p>There is no step field. For a whole number the step is 1 and saying so adds nothing; for a decimal
     * it was worse than nothing — a declared step of 0.1 puts 0.05 out of the arrows' reach, making the
     * editor a coarser instrument than the type it edits.
     */
    private Node buildBoundsEditor(Owned entry) {
        TextField min = boundField(entry.row().bounds().min(), "no minimum");
        TextField max = boundField(entry.row().bounds().max(), "no maximum");
        Runnable commit = () -> {
            Range declared = new Range(min.getText(), max.getText());
            Owned held = find(entry.group(), entry.row().name());
            if (held == null || declared.equals(held.row().bounds())) return;
            edit(held, "the range", current -> current.toBuilder().bounds(declared).build());
        };
        for (TextField field : List.of(min, max)) {
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commit.run();
            });
            field.setOnAction(e -> {
                commit.run();
                e.consume();
            });
        }
        HBox row = new HBox(6, min, new Label("to"), max);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static TextField boundField(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        field.setPrefColumnCount(7);
        return field;
    }

    /**
     * A new parameter lands in the category being looked at, which is where somebody adding one means to put
     * it — and in the plugin section the picker names, which is a different question.
     *
     * <p>The category is a view and the section is a scope: the first says where it is listed, the second
     * says which plugin stores it and whose namespace its name has to be unique in. The section picker only
     * appears once there is more than one plugin to choose between.
     */
    private Node buildAddRow() {
        TextField name = new TextField();
        name.setPromptText("parameter name");
        HBox.setHgrow(name, Priority.ALWAYS);
        ValueTypePicker type = new ValueTypePicker();
        type.setPrefWidth(180);
        Button add = new Button("Add parameter");
        add.getStyleClass().add("primary-button");

        Runnable addParameter = () -> {
            String candidate = name.getText() == null ? "" : name.getText().trim();
            if (!isValidIdentifier(candidate)) {
                error("Enter a valid name (letters, digits, _; not starting with a digit).");
                return;
            }
            change("adding " + candidate, () -> {
                String tag = VariableRailModel.ALL.equals(selectedTag)
                        || ParameterRow.GENERAL.equals(selectedTag) ? "" : selectedTag;
                ParameterRow wanted = ParameterRow.named(candidate, type.choice()).category(tag).build();
                // The owner decides whether the name is free in its own section — this window cannot know,
                // because the rows it holds are what the plugins chose to show it. Empty is the refusal.
                Optional<ParameterRow> stored =
                        PluginHost.parameterDeclared(ParameterDeclaration.added(selectedGroup, wanted));
                if (stored.isEmpty()) {
                    error("'" + candidate + "' was refused — it may already be the name of a parameter in "
                            + "this section.");
                    return;
                }
                error("");
                name.clear();
                reload();
                rebuildRail();
            });
        };
        add.setOnAction(e -> addParameter.run());
        name.setOnAction(e -> {
            addParameter.run();
            e.consume();
        });

        HBox row = new HBox(6, new Label("New"), name, type, add);
        List<ParameterGroup> groups = PluginHost.parameterGroups(sdkPin());
        if (groups.size() > 1) {
            ComboBox<ParameterGroup> section = new ComboBox<>();
            section.getItems().setAll(groups);
            section.setButtonCell(groupCell());
            section.setCellFactory(list -> groupCell());
            section.getSelectionModel().select(groups.stream()
                    .filter(g -> g.id().equals(selectedGroup)).findFirst().orElse(groups.getFirst()));
            selectedGroup = section.getSelectionModel().getSelectedItem().id();
            section.valueProperty().addListener((o, was, is) -> {
                if (is != null) selectedGroup = is.id();
            });
            row.getChildren().add(3, section);
        } else if (!groups.isEmpty()) {
            selectedGroup = groups.getFirst().id();
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 10, 0, 10));
        return row;
    }

    // --- declaring, which is every change but a value ------------------------------------------------------

    /** A section's row: its title, which is the plugin's word for it and not its id. */
    private static ListCell<ParameterGroup> groupCell() {
        return new ListCell<>() {
            @Override protected void updateItem(ParameterGroup item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
            }
        };
    }

    /**
     * Whether {@code s} can be written into Java as-is — a parameter's name reaches a bot's source as a
     * field, so a name this refuses is a name nobody could read back.
     *
     * <p>Four lines here rather than a call into the flow editor's {@code FlowNames}, which is what it was
     * until 2026-09-11: that class is the SDK plugin's now, and the host asking a plugin whether a name is
     * legal would be the host taking one plugin's judgement for every plugin's rows.
     */
    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    private void commitRename(Owned entry, TextField field) {
        String candidate = field.getText() == null ? "" : field.getText().trim();
        if (candidate.equals(entry.row().name())) return;
        if (!isValidIdentifier(candidate)) {
            error("Invalid parameter name — reverted.");
            field.setText(entry.row().name());
            return;
        }
        error("");
        if (!edit(entry, "the name", UnaryOperator.identity(), candidate)) {
            error("'" + candidate + "' was refused — it may already be taken in this section.");
            field.setText(entry.row().name());
        }
    }

    private void replaceOptions(Owned entry, List<String> options) {
        error("");
        edit(entry, "the choices", current -> current.toBuilder().options(options).build());
    }

    /**
     * Declares {@code change} applied to the row {@code entry} names, and redraws.
     *
     * <p><b>By the pair, and the change is a function, not a finished record.</b> Both halves matter and both
     * were wrong once. Flushing the on-screen widgets first replaces the row a control captured when its card
     * was built, so looking it up by equality found nothing and the edit was dropped — which is what made
     * changing a parameter's type do nothing at all once its value had been touched. And a pre-built row
     * would carry the stale value back in with it, undoing the flush it was queued behind.
     */
    private void edit(Owned entry, String what, UnaryOperator<ParameterRow> change) {
        edit(entry, what, change, null);
    }

    /** The same, optionally renaming to {@code newName}. Answers whether the owner stored anything. */
    private boolean edit(Owned entry, String what, UnaryOperator<ParameterRow> change, String newName) {
        boolean[] stored = {false};
        change(what + " of " + entry.row().name(), () -> stored[0] = declare(entry, current -> {
            ParameterRow changed = change.apply(current);
            return newName == null ? changed : rename(changed, newName);
        }));
        return stored[0];
    }

    /** One row, as the plugin that owns it now holds it — or {@code false} when it refused. */
    private boolean declare(Owned entry, UnaryOperator<ParameterRow> change) {
        Owned held = find(entry.group(), entry.row().name());
        if (held == null) return false;
        ParameterRow wanted = change.apply(held.row());
        boolean stored = PluginHost.parameterDeclared(
                new ParameterDeclaration(held.group(), held.row().name(), wanted)).isPresent();
        reload();
        rebuildRail();
        return stored;
    }

    private static ParameterRow rename(ParameterRow row, String name) {
        return ParameterRow.named(name, row.type())
                .value(row.value())
                .description(row.description())
                .category(row.category())
                .visibility(row.visibility())
                .options(row.options())
                .bounds(row.bounds())
                .build();
    }

    /**
     * One recorded step: whatever was typed and not yet recorded becomes its own step first, then {@code body}
     * runs and becomes the next one.
     *
     * <p>Two steps rather than one because they are two things the user did, and folding a retype into the
     * number typed before it would make ↶ take back both. The editors are dropped between them: a rebuild
     * must not flush the old type's widget onto the new type's default.
     */
    private void change(String label, Runnable body) {
        commitPending("the value you typed");
        valueEditors.clear();
        body.run();
        commitPending(label);
    }

    /**
     * Records everything the rows have picked up since the last step, under {@code label} — and does nothing
     * at all when they have picked up nothing, which is what makes it safe to call on a timer.
     *
     * <p>{@link ParameterRow} has value equality, so "has anything changed" is list equality and needs no
     * dirty flag per field. That is also what lets the note field and the visibility tick write straight
     * through without announcing themselves: the tick notices.
     */
    private void commitPending(String label) {
        flushValues();
        List<Owned> now = List.copyOf(rows);
        if (now.equals(committed)) return;
        history.record(label, committed, now);
        committed = now;
    }

    /**
     * Puts a snapshot back: ↶ and ↷ both land here, and so nothing else may.
     *
     * <p><b>An undo is replayed as declarations, because this window stores nothing.</b> Every row of the
     * snapshot is declared back — which puts a name, a type, a value, a range and a category back in one
     * call each — and any row the plugins hold that the snapshot does not is removed. The owner reconciles
     * each one exactly as it did the edit being taken back, so an undo cannot restore a state the owner
     * would have refused.
     */
    private void restore(List<Owned> snapshot) {
        valueEditors.clear();
        for (Owned entry : rows) {
            if (snapshot.stream().noneMatch(kept -> kept.is(entry.group(), entry.row().name()))) {
                PluginHost.parameterDeclared(
                        ParameterDeclaration.removed(entry.group(), entry.row().name()));
            }
        }
        for (Owned entry : snapshot) {
            Owned held = find(entry.group(), entry.row().name());
            String handle = held == null ? "" : entry.row().name();
            PluginHost.parameterDeclared(
                    new ParameterDeclaration(entry.group(), handle, entry.row()));
        }
        reload();
        committed = List.copyOf(rows);
        rebuildRail();
    }

    /**
     * {@link #edit} without the redraw <em>and</em> without a step of its own — for the fields that fire on
     * every keystroke or a click, and would otherwise rebuild the column out from under the cursor.
     *
     * <p>Nothing is lost by not recording here: {@link #commitPending} runs on a timer and picks the change
     * up on its next tick, which is what turns a typed note into one step instead of one per letter.
     */
    private void editQuietly(Owned entry, UnaryOperator<ParameterRow> change) {
        Owned held = find(entry.group(), entry.row().name());
        if (held == null) return;
        ParameterRow wanted = change.apply(held.row());
        PluginHost.parameterDeclared(
                new ParameterDeclaration(held.group(), held.row().name(), wanted))
                .ifPresent(stored -> replaceInPlace(held, stored));
    }

    /** Puts one stored row back into the working list, without redrawing anything. */
    private void replaceInPlace(Owned held, ParameterRow stored) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).is(held.group(), held.row().name())) {
                rows.set(i, new Owned(held.group(), stored));
                return;
            }
        }
    }

    /**
     * The row called {@code name} in {@code group}, or null.
     *
     * <p><b>The handle is the pair, not the name.</b> A name is unique inside its plugin's section and only
     * there, so two plugins may both have a {@code Timeout} — and a widget holding just the name would find
     * whichever came first and write the other one's value into it.
     */
    private Owned find(String group, String name) {
        for (Owned entry : rows) {
            if (entry.is(group, name)) return entry;
        }
        return null;
    }

    /** Hands the on-screen value widgets to the plugins that own the rows they were built from. */
    private void flushValues() {
        if (valueEditors.isEmpty()) return;
        for (ValueEditor editor : valueEditors) {
            for (int i = 0; i < rows.size(); i++) {
                Owned entry = rows.get(i);
                if (!editor.describes(entry.group(), entry.row().name())) continue;
                List<String> typed = editor.read().get();
                if (typed.equals(entry.row().value())) break;
                // The answer is the row as *stored*, which may differ from what was typed — a clamp, a
                // canonical spelling, a value pruned to the choices still on offer. That is what goes into
                // the list, so the next redraw shows what the bot will actually get.
                Optional<ParameterRow> stored = PluginHost.parameterEdited(
                        new ParameterEdit(entry.group(), entry.row().name(), typed));
                stored.ifPresent(row -> rows.set(rows.indexOf(entry), new Owned(entry.group(), row)));
                break;
            }
        }
    }

    // --- the bottom bar ------------------------------------------------------------------------------------

    /**
     * No Save button, and since 2026-09-10 no autosave either — there is no file here to write.
     *
     * <p>Every change is handed to the plugin that owns it as it is made, and that plugin decides when its
     * own file is written. What is left on this bar is the pair of arrows, which is what makes an editor with
     * no "close without saving" bearable: every mutation has to be reversible in the editor itself.
     */
    private Node buildBottomBar() {
        statusLabel.getStyleClass().add("dialog-error-text");

        Button close = new Button("Close");
        close.getStyleClass().add("primary-button");
        close.setOnAction(e -> {
            commitPending("the value you typed");
            stage.close();
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, undoButton(), redoButton(),
                new Separator(javafx.geometry.Orientation.VERTICAL),
                statusLabel, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        return bar;
    }

    private Button undoButton() {
        Button undo = new Button("↶");
        undo.disableProperty().bind(history.canUndoProperty().not());
        undo.tooltipProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                () -> new Tooltip(labelled("Undo", history.undoLabelProperty().get())),
                history.undoLabelProperty()));
        // What is on screen but not yet recorded is part of what ↶ takes back, so it becomes a step first —
        // otherwise the number you just typed would survive the undo of the edit before it.
        undo.setOnAction(e -> {
            commitPending("the value you typed");
            history.undo();
        });
        return undo;
    }

    private Button redoButton() {
        Button redo = new Button("↷");
        redo.disableProperty().bind(history.canRedoProperty().not());
        redo.tooltipProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(
                () -> new Tooltip(labelled("Redo", history.redoLabelProperty().get())),
                history.redoLabelProperty()));
        redo.setOnAction(e -> history.redo());
        return redo;
    }

    private static String labelled(String verb, String step) {
        return step == null || step.isBlank() ? verb + " — nothing to " + verb.toLowerCase() : verb + " " + step;
    }

    private void error(String message) {
        statusLabel.setText(message);
    }
}
