package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.Visibility;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.params.JavaParameter;
import com.botmaker.studio.project.params.JavaParameters;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.services.LibraryService;
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

import java.lang.reflect.Type;
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
 * <h2>One kind of section: a class of the bot</h2>
 *
 * <p><b>Every parameter is a {@code @Param} field in the bot's own Java</b> (2026-09-17), one section per
 * class that declares any. Adding, renaming, retyping, refiling and removing one is an edit to that source
 * file, made through {@link JavaParameters} — so the declaration a user reads in their editor and the row
 * they read here are the same thing, and a name they misspell in their bot is a compile error rather than a
 * silent fallback.
 *
 * <p><b>A plugin's rows were a second kind until 2026-09-22</b> — read through a contract surface, with only
 * their value editable here. Nothing ever declared one, and a plugin that wants a row of its own now puts a
 * {@code @Param} field in the file it ships, which this window finds like any other. So the asymmetry is
 * gone and with it the section id: a section is a class, which javac already keeps distinct.
 *
 * <p><b>Nothing in this class knows what an activity is, or what a plugin's file looks like.</b> That was the
 * point of the 2026-09-10 rewrite and it still holds.
 *
 * <h2>One list, organised by category</h2>
 *
 * <p>Every parameter belongs to the project. What the rail on the left offers is a <em>view</em> of that one
 * list — <i>All</i>, <i>General</i> for the unfiled, then every category in use: whatever the bot's own
 * {@code @Param}s say. A field's category is
 * free text, so the only way one exists is that something is filed under it. Filing a parameter under
 * "Timing" does not scope it to anything: a category only says where it is listed.
 *
 * <h2>Why it is not in the flow editor</h2>
 *
 * <p>Values used to be edited in the Activity Flow dialog's side panel, which meant the graph editor was also
 * the settings editor: to change a number you opened the canvas, found the card, and edited a cramped column
 * beside it. The flow editor is about where the bot goes next; this is about what it is configured with.
 *
 * <h2>Nothing to save</h2>
 *
 * <p>Every change is written as it is made — the field is rewritten in its own file, buffer and disk both.
 * There is no autosave and no Save button, because there
 * is nothing here holding an unwritten state. What is left is ↶ and ↷, which replay the rows as they were as
 * further edits; see {@link #restore}.
 */
public final class ParametersDialog {

    private final Window owner;
    private final ProjectConfig config;

    /**
     * The open buffers. Required rather than optional: a {@code @Param} field is read from the file's editor
     * buffer where there is one and a rewrite is written to both copies, so a window holding only the
     * {@link ProjectConfig} would show a project as it was ten minutes ago and then overwrite it.
     */
    private final ProjectState state;

    /**
     * Declares the plugin contract before a field is added, when the project cannot compile {@code @Param}
     * yet — a blank project names no plugin, so nothing else brings it.
     */
    private final LibraryService libraries;

    /** Lit on the rail row a dragged parameter is over — styled in {@code blocks.css}, never inline. */
    private static final PseudoClass RAIL_DROP = PseudoClass.getPseudoClass("rail-drop");

    private final ListView<VariableRailModel.Row> rail = new ListView<>();
    private final Button moveHere = new Button("Move parameters here…");
    private final VBox paramColumn = new VBox(10);
    private final Label statusLabel = new Label();

    // The (group, row) pair was a record of this class until 2026-09-17, then ParameterSurface.Entry, and
    // since 2026-09-22 it is JavaParameter — the field itself, because a field is the only thing a row ever
    // comes from now. The Runner reads the same list through the same shape.

    /** What the project currently declares, in class order — re-read after every change. */
    private final List<JavaParameter> rows = new ArrayList<>();

    /** Readers for the value widgets currently on screen; re-created whenever the column is rebuilt. */
    private final List<ValueEditor> valueEditors = new ArrayList<>();

    /** Every category in use — whatever the bot's own fields say. */
    private List<String> categories = List.of();

    /** The records the bot declares, for a value cell over one of them. Re-read with the rows. */
    private BotRecords records = BotRecords.none();
    private String selectedTag = VariableRailModel.ALL;

    /**
     * Which class a newly added parameter is declared in — {@code Parameters} until the bot has a second
     * {@code @Param} class and the user picks one in the add row.
     */
    private String selectedClass = JavaParameters.DEFAULT_CLASS;
    private Stage stage;

    /**
     * Undo/redo over the rows, in the {@linkplain SnapshotHistory#SnapshotHistory(Consumer) restore-only}
     * form: this dialog knows both halves of every step, because it decides when one has happened (see
     * {@link #commitPending}).
     */
    private final SnapshotHistory<List<JavaParameter>> history = new SnapshotHistory<>(this::restore);

    /** The rows as of the last recorded step — the "before" the next one is measured against. */
    private List<JavaParameter> committed = List.of();

    /**
     * Typing is not an event this dialog can hear. The value widgets are twenty shapes with twenty different
     * change signals and the note field fires per keystroke, so instead of listening, the state is compared
     * against {@link #committed} on a slow tick: identical, nothing happened; different, that is one step.
     * A burst of typing therefore becomes one ↶, which is what a person means by "take back what I typed".
     */
    private static final Duration TYPING_TICK = Duration.millis(700);
    private Timeline typingWatch;

    public ParametersDialog(Window owner, ProjectConfig config, ProjectState state, LibraryService libraries) {
        this.owner = owner;
        this.config = config;
        this.state = state;
        this.libraries = libraries;
    }

    public void show() {
        reload();

        // The directory, not only the project: an edit here is written to that directory's files.
        StudioWindow window = StudioWindow.modal("parameters", "Parameters — " + config.displayDirectory(), owner)
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
     * Re-reads every section from whoever owns it — the bot's sources for a class, the plugin for its rows.
     *
     * <p>Asked on every change rather than kept in step by this window: an owner stores the row and may
     * store something other than what it was handed — a clamp from a plugin, a rewritten initialiser from a
     * codec — so what is on screen after a change has to be what the owner says it holds. It is also what
     * makes a file edited behind this window's back cost nothing worse than a stale screen.
     *
     * <p>The categories come with the rows for the same reason: a field's category is free text, so the
     * vocabulary changes the moment somebody types a new one into a card.
     */
    private void reload() {
        rows.clear();
        rows.addAll(JavaParameters.scan(config, state));
        categories = JavaParameters.categories(config, state);
        // Read once per reload, beside the rows they belong to: a value cell for a field typed with one of
        // the bot's own records needs that record's components, and a per-cell scan would read every source
        // file once per row on screen.
        records = BotRecords.scan(config, state, PluginHost.grammar());
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
        List<JavaParameter> inside = shown(selectedTag);
        List<JavaParameter> outside = rows.stream().filter(entry -> !inside.contains(entry)).toList();
        if (outside.isEmpty()) {
            error("Every parameter is already filed under “" + selectedTag + "”.");
            return;
        }
        List<JavaParameter> chosen = pickParameters(outside);
        if (chosen.isEmpty()) return;
        // One step for the batch: filing eight parameters at once should be one ↶, not eight.
        change("filing " + chosen.size() + " parameters under " + selectedTag, () -> {
            for (JavaParameter picked : chosen) {
                declare(picked, current -> current.toBuilder().category(home).build(), null);
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
    private List<JavaParameter> pickParameters(List<JavaParameter> offered) {
        List<CheckBox> boxes = new ArrayList<>();
        VBox column = new VBox(4);
        for (JavaParameter entry : offered) {
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
        return boxes.stream().filter(CheckBox::isSelected).map(b -> (JavaParameter) b.getUserData()).toList();
    }

    /** Redraws the rail (the counts move on every add, delete and re-tag) and keeps the selection. */
    private void rebuildRail() {
        List<VariableRailModel.Row> railRows =
                VariableRailModel.rowsOf(rows.stream().map(JavaParameter::row).toList(), categories);
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
     * <p>{@code dragged} is the {@code class\nname} pair the grip put on the dragboard; a payload with no
     * newline is read as a bare name in the default class, which is what every earlier drag was.
     */
    private boolean fileUnder(String dragged, String tag) {
        if (dragged == null) return false;
        int cut = dragged.indexOf('\n');
        String group = cut < 0 ? JavaParameters.DEFAULT_CLASS : dragged.substring(0, cut);
        String name = cut < 0 ? dragged : dragged.substring(cut + 1);
        JavaParameter entry = find(group, name);
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

        List<JavaParameter> visible = shown(selectedTag);
        for (String className : sections()) {
            List<JavaParameter> mine =
                    visible.stream().filter(entry -> entry.className().equals(className)).toList();
            paramColumn.getChildren().add(sectionHeader(className, mine.isEmpty()));
            for (JavaParameter entry : mine) paramColumn.getChildren().add(buildParamCard(entry));
        }
    }

    /** The rows the rail's selected bucket holds, in section order. */
    private List<JavaParameter> shown(String tag) {
        List<ParameterRow> visible = VariableRailModel.rowsIn(
                rows.stream().map(JavaParameter::row).toList(), tag, categories);
        return rows.stream().filter(entry -> visible.contains(entry.row())).toList();
    }

    /**
     * The sections to draw: one per class of the bot that declares a {@code @Param} field.
     *
     * <p><b>A section is a class</b> (2026-09-22). It was a group id — {@code java:<Class>} for a field and
     * a plugin's own id otherwise — while a plugin could declare rows of its own. None ever did, and a
     * plugin that wants one now puts a {@code @Param} field in the file it ships, so the two kinds of
     * section collapsed into the one javac already keeps distinct.
     *
     * <p><b>A project with no fields still gets a heading</b>, the one every project can have: an empty
     * section reads as "nothing set up yet", and no section at all reads as "this window is not for me".
     */
    private List<String> sections() {
        List<String> classes = new ArrayList<>(JavaParameters.classes(config, state));
        if (classes.isEmpty()) classes.add(JavaParameters.DEFAULT_CLASS);
        for (JavaParameter entry : rows) {
            if (!classes.contains(entry.className())) classes.add(entry.className());
        }
        return classes;
    }

    /** The heading of one section, what it is, and — when it holds nothing yet — the line that says so. */
    private Node sectionHeader(String className, boolean empty) {
        Label heading = new Label(className + ".java");
        heading.getStyleClass().add("param-section-heading");
        VBox box = new VBox(2, heading);
        box.setPadding(new Insets(6, 0, 0, 0));
        Label what = new Label("Your bot's own fields. The bot reads them as " + className + ".<name>.");
        what.getStyleClass().add("dialog-hint-text");
        box.getChildren().add(what);
        if (empty) {
            Label none = new Label("Nothing here yet — add one in the bar at the bottom.");
            none.getStyleClass().add("dialog-hint-text");
            box.getChildren().add(none);
        }
        return box;
    }

    // sdkPin() stood here until 2026-09-22, reading the pom on every draw because the sections were asked of
    // each plugin at the version the project pins. A section is a class the bot declares now, which no
    // version qualifies.

    /** One parameter: what it is called, what it holds, who it is for, where it is filed, what it is set to. */
    private Node buildParamCard(JavaParameter entry) {
        ParameterRow v = entry.row();
        boolean mine = true;
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.getStyleClass().add("param-card");

        // A plugin's row is shown as a label, not a disabled text field: a field you may not type in reads
        // as something broken, where a label reads as a fact about who owns the row.
        Node name;
        if (mine) {
            TextField field = new TextField(v.name());
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commitRename(entry, field);
            });
            field.setOnAction(e -> {
                commitRename(entry, field);
                e.consume();   // Enter commits the name here; it is not also a keystroke for anything else
            });
            name = field;
        } else {
            Label label = new Label(v.name());
            label.setTooltip(new Tooltip("Declared by a plugin — its name and type are the plugin's."));
            name = label;
        }

        Node type;
        if (mine) {
            ValueTypePicker picker = new ValueTypePicker();
            picker.setForm(entry.form());
            picker.setPrefWidth(180);
            picker.formProperty().addListener((o, was, is) -> {
                if (is == null || is.equals(entry.form())) return;
                // Retyping rewrites the field's declared Java type and resets its initialiser to the new
                // type's fresh value: a value written for one type is not a value of another, and carrying
                // it across would leave a bot that does not compile.
                edit(entry, "the type", UnaryOperator.identity(), null, is);
            });
            type = picker;
        } else {
            type = new Label(ValueTypes.sourceName(entry.form()));
        }

        CheckBox shared = new CheckBox("Show to user");
        shared.setSelected(v.isPublic());
        shared.setDisable(!mine);
        shared.setTooltip(new Tooltip(mine
                ? "Ticked, this appears in the Runner window under its category's heading. Unticked, it is "
                        + "yours alone and never leaves this dialog."
                : "Who this row is offered to is the plugin's decision."));
        // A visibility tick writes the annotation, so it is a declaration and not a quiet edit: the member
        // has to land in the file before the next scan reads it back.
        shared.setOnAction(e -> edit(entry, "who sees", current -> current.toBuilder().visibility(
                shared.isSelected() ? Visibility.PUBLIC : Visibility.EDITOR_ONLY).build()));

        Button drop = new Button("✕");
        drop.getStyleClass().add("row-icon-button");
        drop.setTooltip(new Tooltip("Remove this parameter. The declaration goes; the places your bot reads "
                + "it are left alone and stop compiling, which is where you decide what they should say."));
        drop.setOnAction(e -> removeParameter(entry));

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
            content.putString(entry.className() + "\n" + v.name());
            board.setContent(content);
            e.consume();
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = mine ? new HBox(8, grip, name, type, spacer, drop)
                : new HBox(8, name, type, spacer);
        head.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        grid.add(head, 0, 0, 2, 1);

        int row = 1;
        grid.add(new Label("Category"), 0, row);
        grid.add(mine ? buildTagPicker(entry) : hintLabel(v.categoryOrGeneral()), 1, row);
        row++;

        // A closed-set type brings its own choices (every direction, every mouse button), so there is nothing
        // here for the author to write down — offering an "add a choice" row over them would invite a second,
        // hand-typed copy of a list the plugin already owns.
        // A closed set is declared by writing the choices down, and there is no shape to pick first any more
        // (2026-09-20): a set is not a type, so a leaf or a list of one always offers this row and a value is
        // free until something is written in it. A closed-set type brings its own choices (every direction,
        // every mouse button), so there is nothing here for the author to write — offering an "add a choice"
        // row over them would invite a second, hand-typed copy of a list the plugin already owns.
        Type leaf = ValueTypes.leaf(entry.form());
        ValueGrammar grammar = PluginHost.grammar();
        if (mine && leaf != null && grammar.known(leaf) && !isEnum(grammar, leaf)) {
            Label heading = new Label("Choices");
            heading.setTooltip(new Tooltip(entry.form() instanceof ValueTypes.Parameterized
                    ? "The set this parameter's values are picked from. The user ticks any number of them."
                    : "The set this parameter's value is picked from. The user picks exactly one."));
            grid.add(heading, 0, row);
            grid.add(buildOptionsEditor(entry), 1, row);
            row++;
        }

        if (mine && leaf != null && isNumber(grammar, leaf)) {
            grid.add(new Label("Range"), 0, row);
            grid.add(buildBoundsEditor(entry), 1, row);
            row++;
        }

        grid.add(new Label("Value"), 0, row);
        Node widget = valueCell(entry);
        grid.add(widget, 1, row);
        GridPane.setHgrow(widget, Priority.ALWAYS);
        row++;

        Node note;
        if (mine) {
            TextField description = new TextField(v.description());
            description.setPromptText("what this is for (shown as a tooltip, and to the user when shared)");
            // On focus loss rather than per keystroke: every letter would otherwise rewrite the source file
            // and re-parse it, and a note is typed a sentence at a time.
            description.focusedProperty().addListener((o, was, is) -> {
                if (is || description.getText().equals(entry.row().description())) return;
                edit(entry, "the note", current ->
                        current.toBuilder().description(description.getText()).build());
            });
            note = description;
        } else {
            note = hintLabel(v.description());
        }
        grid.add(new Label("Note"), 0, row);
        grid.add(note, 1, row);
        GridPane.setHgrow(note, Priority.ALWAYS);
        row++;

        grid.add(shared, 1, row);

        VBox card = new VBox(grid);
        card.setPadding(new Insets(4, 0, 4, 0));
        return card;
    }

    /**
     * The value cell: the type's own editor — the same one the canvas uses, a plugin's included — or, for a
     * field this window cannot rewrite, the initialiser exactly as its author wrote it.
     *
     * <p><b>A field it cannot rewrite is still listed, with its value and the reason.</b> The bot reads it
     * either way, so hiding it would reproduce the fault the JSON era had: a declaration the author cannot
     * see. {@code java.time.Duration.ofSeconds(3)} is a duration nobody's codec would emit, and showing it as
     * written beats offering an editor whose first keystroke would rewrite the author's own expression.
     */
    private Node valueCell(JavaParameter entry) {
        if (entry.editable()) {
            return ParamValueWidgets.build(entry.className(), entry.row(), entry.form(), config, records,
                    valueEditors);
        }
        Label written = new Label(entry.initializer());
        written.getStyleClass().add("dialog-hint-text");
        written.setTooltip(new Tooltip("Shown as written, not editable here: " + entry.note()));
        Label why = new Label(entry.note());
        why.getStyleClass().add("dialog-hint-text");
        why.setWrapText(true);
        return new VBox(2, written, why);
    }

    /** A value this window may show but not change — a plugin's category, a plugin's note. */
    private static Label hintLabel(String text) {
        Label label = new Label(text == null || text.isBlank() ? "—" : text);
        label.getStyleClass().add("dialog-hint-text");
        label.setWrapText(true);
        return label;
    }

    /**
     * Removes a field's declaration, after saying how many places read it.
     *
     * <p><b>The uses are left alone on purpose.</b> What a use should become is a judgement — a literal
     * default, a different parameter, a deleted line — and a removal whose uses were silently rewritten is a
     * bot that still compiles and behaves differently. So the count is shown before, and the compiler is what
     * finds them afterwards.
     */
    private void removeParameter(JavaParameter entry) {
        if (!true) return;
        int uses = JavaParameters.uses(config, state, entry).size() - 1;   // the declaration itself
        change("removing " + entry.row().name(), () -> {
            if (!JavaParameters.remove(config, state, entry)) {
                error("“" + entry.row().name() + "” could not be removed — it may already be gone.");
                return;
            }
            error(uses > 0
                    ? "Removed. " + uses + (uses == 1 ? " place" : " places") + " in your bot still name "
                            + entry.qualified() + " — the compiler will point at them."
                    : "");
            reload();
            rebuildRail();
        });
    }

    /**
     * Whether {@code leaf} is an enum a plugin declares — a closed set that brings its own choices (every
     * direction, every mouse button), so a hand-typed copy of that list would be a second one to drift.
     */
    private static boolean isEnum(ValueGrammar grammar, Type leaf) {
        return leaf instanceof Class<?> cls && cls.isEnum() && grammar.type(cls).isPresent();
    }

    /** Whether a declared range means anything for {@code leaf}: one of Java's numbers, boxed or not. */
    private static boolean isNumber(ValueGrammar grammar, Type leaf) {
        return leaf instanceof Class<?> cls && NUMBERS.contains(cls);
    }

    private static final java.util.Set<Class<?>> NUMBERS = java.util.Set.of(
            byte.class, short.class, int.class, long.class, float.class, double.class,
            Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);

    /**
     * A choice as the author writes it into {@code @Param(options = …)}: the text itself for a string, the
     * constant's name for an enum, and the value's Java for everything else — which is what
     * {@code ParamValueWidgets.optionSource} reads back as the same value.
     */
    private static String optionText(ValueGrammar grammar, Type leaf, Optional<JavaValue> written) {
        Optional<Object> value = written.flatMap(tree -> grammar.valueOf(leaf, tree.written()));
        if (value.isEmpty()) return "";
        if (value.get() instanceof String text) return text;
        if (value.get() instanceof Enum<?> constant) return constant.name();
        return grammar.initializer(leaf, value.get()).map(JavaValue::source).orElse("");
    }

    /** Where this parameter is listed — one category or none, never several: a parameter has one home. */
    private Node buildTagPicker(JavaParameter entry) {
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
    private Node buildOptionsEditor(JavaParameter entry) {
        ParameterRow v = entry.row();
        Type base = ValueTypes.leaf(entry.form());
        ValueGrammar grammar = PluginHost.grammar();
        ValueEditors.Context ctx = ValueEditors.Context.of(config);
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
            String typed = optionText(grammar, base, fresh.read().get());
            if (typed.isBlank()) return;
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
            // Chained, never replaced: the editor's own Enter is what hands the typed text to its value, and
            // without it Enter added the empty value read before that commit — nothing was declared, and the
            // text stayed in the field to be glued onto the next choice.
            javafx.event.EventHandler<javafx.event.ActionEvent> commit = field.getOnAction();
            field.setOnAction(e -> {
                if (commit != null) commit.handle(e);
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
    private Node optionRow(JavaParameter entry, Type base, ValueEditors.Context ctx,
                           List<String> options, int at) {
        String option = options.get(at);
        ValueGrammar grammar = PluginHost.grammar();
        Node shown;
        if (base == String.class) {
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
            Label label = new Label(option, ParamValueWidgets.optionSource(grammar, base, option)
                    .map(written -> ValueEditors.optionGraphic(base, written.source(), ctx))
                    .orElse(null));
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
    private Node buildBoundsEditor(JavaParameter entry) {
        TextField min = boundField(boundText(entry.row().min()), "no minimum");
        TextField max = boundField(boundText(entry.row().max()), "no maximum");
        Runnable commit = () -> {
            double low = bound(min.getText(), Double.NEGATIVE_INFINITY);
            double high = bound(max.getText(), Double.POSITIVE_INFINITY);
            JavaParameter held = find(entry.className(), entry.row().name());
            if (held == null || (Double.compare(low, held.row().min()) == 0
                    && Double.compare(high, held.row().max()) == 0)) {
                return;
            }
            edit(held, "the range", current -> current.toBuilder().bounds(low, high).build());
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

    /** A bound as its field shows it: blank for none, {@code 1} rather than {@code 1.0} for a whole one. */
    private static String boundText(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) return "";
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : Double.toString(value);
    }

    /** What a bound field says, or {@code absent} for blank or anything that is not a number. */
    private static double bound(String text, double absent) {
        if (text == null || text.isBlank()) return absent;
        try {
            double parsed = Double.parseDouble(text.strip().replace(',', '.'));
            return Double.isNaN(parsed) ? absent : parsed;
        } catch (NumberFormatException e) {
            return absent;
        }
    }

    private static TextField boundField(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        field.setPrefColumnCount(7);
        return field;
    }

    /**
     * A new parameter lands in the category being looked at, which is where somebody adding one means to put
     * it — and in the class the picker names, which is a different question.
     *
     * <p>The category is a view and the class is where the declaration is written: the first says where it
     * is listed, the second is what the bot spells in front of the name. The class picker only appears once
     * the bot has more than one {@code @Param} class; until then a project's first parameter creates
     * {@code Parameters.java} and says so.
     *
     * <p><b>A plugin's section has no Add.</b> Its rows are the plugin's own and it declares them in its own
     * code; the bar adds a field to the bot, which is the only kind of parameter a person writes here.
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
                boolean fresh = !JavaParameters.classes(config, state).contains(selectedClass);
                Type form = type.form();
                Optional<ParameterRow> stored = JavaParameters.add(config, state, selectedClass, candidate,
                        form, PluginHost.grammar().freshSpelling(form).orElse(null), tag, "");
                if (stored.isEmpty()) {
                    error("“" + candidate + "” was not written — " + selectedClass + " may already declare "
                            + "a field of that name, or no installed plugin declares this type.");
                    return;
                }
                // After the field, not before: a name refused above must not cost the project a dependency.
                boolean declared = libraries.ensureContract();
                String created = fresh ? "Created " + selectedClass + ".java for it." : "";
                error(declared ? (created + " Added botmaker-studio-api to pom.xml for @Param.").trim() : created);
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
        List<String> classes = JavaParameters.classes(config, state);
        if (classes.size() > 1) {
            ComboBox<String> into = new ComboBox<>();
            into.getItems().setAll(classes);
            into.getSelectionModel().select(classes.contains(selectedClass) ? selectedClass
                    : classes.getFirst());
            selectedClass = into.getSelectionModel().getSelectedItem();
            into.valueProperty().addListener((o, was, is) -> {
                if (is != null) selectedClass = is;
            });
            into.setTooltip(new Tooltip("Which of your classes the field is declared in."));
            row.getChildren().add(3, into);
        } else {
            selectedClass = classes.isEmpty() ? JavaParameters.DEFAULT_CLASS : classes.getFirst();
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 10, 0, 10));
        return row;
    }

    // --- declaring, which is every change but a value ------------------------------------------------------

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

    private void commitRename(JavaParameter entry, TextField field) {
        String candidate = field.getText() == null ? "" : field.getText().trim();
        if (candidate.equals(entry.row().name())) return;
        if (!isValidIdentifier(candidate)) {
            error("Invalid parameter name — reverted.");
            field.setText(entry.row().name());
            return;
        }
        error("");
        if (!edit(entry, "the name", UnaryOperator.identity(), candidate)) {
            error("“" + candidate + "” was not written — the class may already declare a field of that name.");
            field.setText(entry.row().name());
        }
    }

    private void replaceOptions(JavaParameter entry, List<String> options) {
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
    private void edit(JavaParameter entry, String what, UnaryOperator<ParameterRow> change) {
        edit(entry, what, change, null);
    }

    /** The same, optionally renaming to {@code newName}. Answers whether the owner stored anything. */
    private boolean edit(JavaParameter entry, String what, UnaryOperator<ParameterRow> change, String newName) {
        return edit(entry, what, change, newName, null);
    }

    /** The same, optionally retyping to {@code newForm} as well. */
    private boolean edit(JavaParameter entry, String what, UnaryOperator<ParameterRow> change, String newName,
                         Type newForm) {
        boolean[] stored = {false};
        change(what + " of " + entry.row().name(), () -> stored[0] = declare(entry, current -> {
            ParameterRow changed = change.apply(current);
            return newName == null ? changed : rename(changed, newName);
        }, newForm));
        return stored[0];
    }

    /**
     * One row, as the source reads it back after the change — or {@code false} when nothing was written.
     *
     * <p>The field is looked up again rather than trusted: an earlier edit in the same gesture may have moved
     * it, and an undo replaying an old snapshot lands here too. {@code newForm} is null for an edit that
     * keeps the type.
     */
    private boolean declare(JavaParameter entry, UnaryOperator<ParameterRow> change, Type newForm) {
        JavaParameter held = find(entry.className(), entry.row().name());
        if (held == null) return false;
        ParameterRow wanted = change.apply(held.row());
        boolean stored = JavaParameters.declare(config, state, held, wanted,
                newForm == null ? held.form() : newForm).isPresent();
        reload();
        rebuildRail();
        return stored;
    }

    private static ParameterRow rename(ParameterRow row, String name) {
        return ParameterRow.named(name, row.typeName())
                .value(row.value())
                .description(row.description())
                .category(row.category())
                .visibility(row.visibility())
                .options(row.options())
                .bounds(row.min(), row.max())
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
        List<JavaParameter> now = List.copyOf(rows);
        if (now.equals(committed)) return;
        history.record(label, committed, now);
        committed = now;
    }

    /**
     * Puts a snapshot back: ↶ and ↷ both land here, and so nothing else may.
     *
     * <p><b>An undo is replayed as edits, because this window stores nothing.</b> Every row of the snapshot
     * is put back the way the edit that made it would have been made — a field's declaration rewritten, a
     * plugin's value handed back to its plugin — and a field the snapshot does not hold is removed. So an
     * undo can only restore a state its owner would have accepted in the first place.
     *
     * <p><b>A field the snapshot holds and the project does not is written back in full</b>, which is how ↶
     * after a delete and ↷ after an add both land: the row carries its type, value, category and note, and
     * {@link JavaParameters#add} writes the declaration from them.
     */
    private void restore(List<JavaParameter> snapshot) {
        valueEditors.clear();
        for (JavaParameter entry : List.copyOf(rows)) {
            if (snapshot.stream().noneMatch(kept -> kept.is(entry.className(), entry.row().name()))) {
                JavaParameters.remove(config, state, entry);
            }
        }
        for (JavaParameter entry : snapshot) {
            ParameterRow row = entry.row();
            JavaParameter held = find(entry.className(), row.name());
            if (held == null) {
                // The snapshot's value is Java this file held, so it goes back as that expression, kept.
                JavaParameters.add(config, state, entry.className(), row.name(), entry.form(),
                        JavaValue.parse(row.value()).orElse(null), row.category(), row.description());
                held = find(entry.className(), row.name());
            }
            if (held == null) continue;
            JavaParameters.declare(config, state, held, row, entry.form());
            reload();
        }
        reload();
        committed = List.copyOf(rows);
        rebuildRail();
    }

    /**
     * The field called {@code name} in the class {@code group}, or null.
     *
     * <p><b>The handle is the pair, not the name.</b> A name is unique inside its own class and only there,
     * so two classes may both declare a {@code timeout} — and a widget holding just the name would find
     * whichever came first and write the other one's value into it.
     */
    private JavaParameter find(String group, String name) {
        for (JavaParameter entry : rows) {
            if (entry.is(group, name)) return entry;
        }
        return null;
    }

    /** Hands the on-screen value widgets to whoever owns the rows they were built from. */
    private void flushValues() {
        if (valueEditors.isEmpty()) return;
        for (ValueEditor editor : valueEditors) {
            for (int i = 0; i < rows.size(); i++) {
                JavaParameter entry = rows.get(i);
                if (!editor.describes(entry.className(), entry.row().name())) continue;
                // The widget reads back the value the field takes, as a tree. Empty means this cell has no Java
                // for what is in it — an empty radio group, a leaf no editor draws — and writing nothing is the
                // only honest answer to that. A value shown exactly as the row holds it is not an edit.
                Optional<JavaValue> typed = editor.read().get();
                if (typed.isEmpty() || typed.get().source().equals(entry.row().value())) break;
                // The answer is the row as *stored*, which may differ from what was typed — a canonical
                // spelling from the grammar, a constant for a value one holds. That is what goes into the
                // list, so the next redraw shows what the bot will actually get.
                Optional<ParameterRow> stored = JavaParameters.setValue(config, state, entry, typed.get());
                stored.ifPresent(row -> rows.set(rows.indexOf(entry), entry.withRow(row)));
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
