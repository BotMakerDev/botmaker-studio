package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.plugin.api.source.RunParameter;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.core.project.managed.ManagedHolders;
import com.botmaker.core.project.params.ParameterRow;
import com.botmaker.core.project.params.Visibility;
import com.botmaker.java.index.nav.Usages;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.project.RunWritten;
import com.botmaker.core.project.params.BotRecords;
import com.botmaker.core.project.params.ClockSwitch;
import com.botmaker.core.project.params.JavaParameter;
import com.botmaker.core.project.params.JavaParameters;
import com.botmaker.core.plugin.HostParamScope;
import com.botmaker.core.plugin.HostPluginValues;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.core.state.SnapshotHistory;
import com.botmaker.studio.ui.app.RefusalDialog;
import com.botmaker.studio.ui.app.StudioWindow;
import com.botmaker.studio.ui.app.params.ParamCards.Chip;
import com.botmaker.studio.ui.app.params.ParamCards.Filter;
import com.botmaker.studio.ui.app.params.ParamCards.From;
import com.botmaker.studio.ui.app.params.ParamCards.Item;
import com.botmaker.studio.ui.app.params.ParamCards.When;
import com.botmaker.studio.ui.app.params.ParamValueWidgets.ValueEditor;
import com.botmaker.core.edit.TypeCatalog;
import com.botmaker.studio.ui.render.components.types.TypeChooser;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one place a bot's parameters are defined: what each is called, what it holds, and what it is set to.
 *
 * <h2>Cards, filtered (layout A, 2026-11-05)</h2>
 *
 * <p>A grid of small cards, one per parameter — name, type, the value, and when a run last wrote it — under a
 * heading per group class, in a section per plugin and one for the bot ({@link ParamCards}). Two rows of chips
 * narrow it: <i>When</i> (outside or during a run) and <i>From</i> (the bot or one plugin), each chip counting what
 * it would show, plus a search box. The window was one long row per parameter with Category / Value / Note lines
 * until then, too wide to read a bot's parameters at a glance.
 *
 * <p><b>The class is the category</b> (2026-11-05). A heading is a group class; <i>+ New group…</i> writes one,
 * {@code params/bot/<Name>.java} and its starting copy. The category rail, its drag and <i>Move parameters to…</i>
 * went with the free-text categories.
 *
 * <p><b>A card opens its editor in a popover</b>: the value's own editor, the same one the canvas uses, a plugin's
 * included, with the name, type, note, when it changes and its starting value beside it. OK keeps what was done
 * there; Cancel puts it all back, as one step ↶ can take back again; <i>Open in code</i> lands on the declaration.
 * A value the bot writes during a run is read-only there, the user's call on 2026-11-05.
 *
 * <h2>Nothing to save</h2>
 *
 * <p>Every change is written as it is made — the parameter is rewritten in its own file, buffer and disk both.
 * There is no Save button, because there is nothing here holding an unwritten state. What is left is ↶ and ↷,
 * which replay the rows as they were as further edits; see {@link #restore}.
 */
public final class ParametersDialog {

    private final Window owner;
    private final ProjectConfig config;

    /**
     * The open buffers. Required rather than optional: a parameter is read from the file's editor buffer where there
     * is one and a rewrite is written to both copies, so a window holding only the {@link ProjectConfig} would show a
     * project as it was ten minutes ago and then overwrite it.
     */
    private final ProjectState state;

    /**
     * Declares the plugin contract before a parameter is added, when the project cannot compile {@code @Param} yet —
     * a blank project names no plugin, so nothing else brings it.
     */
    private final LibraryService libraries;

    private final HBox whenChips = new HBox(6);
    private final HBox fromChips = new HBox(6);
    private final TextField search = new TextField();
    private final VBox cardColumn = new VBox(6);
    private final ComboBox<String> intoPicker = new ComboBox<>();
    private final Label statusLabel = new Label();
    /** Lands on a use a refusal lists, or a declaration, after this window closes; null offers no link. */
    private final Consumer<Usages.Usage> reveal;

    /** What the project currently declares, in scan order — re-read after every change. */
    private final List<JavaParameter> rows = new ArrayList<>();

    /** Each row with where it comes from, re-read with the rows. */
    private final List<Item> items = new ArrayList<>();

    /** When a run last wrote each during-a-run parameter, by {@link JavaParameter#qualified()}. */
    private final Map<String, Instant> keptAt = new HashMap<>();

    /** Readers for the value widgets on screen, which only the open popover has; re-created whenever it is redrawn. */
    private final List<ValueEditor> valueEditors = new ArrayList<>();

    /** The records the bot declares, for a value cell over one of them. Re-read with the rows. */
    private BotRecords records = BotRecords.none();

    private Filter filter = Filter.NONE;

    /** Which group a newly added parameter is declared in: {@code Parameters} until the bot has another. */
    private String selectedClass = JavaParameters.DEFAULT_CLASS;
    private Stage stage;

    /** The open popover's body and the parameter it edits, by class and name; null while none is open. */
    private VBox detailBox;
    private String detailClass;
    private String detailName;
    /** True while a rename is being written, so the redraw it makes cannot start another. */
    private boolean renaming;

    /**
     * Undo/redo over the rows, in the {@linkplain SnapshotHistory#SnapshotHistory(Consumer) restore-only} form: this
     * dialog knows both halves of every step, because it decides when one has happened (see {@link #commitPending}).
     */
    private final SnapshotHistory<List<JavaParameter>> history = new SnapshotHistory<>(this::restore);

    /** The rows as of the last recorded step — the "before" the next one is measured against. */
    private List<JavaParameter> committed = List.of();

    /**
     * Typing is not an event this dialog can hear. The value widgets are twenty shapes with twenty different change
     * signals, so instead of listening, the state is compared against {@link #committed} on a slow tick: identical,
     * nothing happened; different, that is one step. A burst of typing therefore becomes one ↶.
     */
    private static final Duration TYPING_TICK = Duration.millis(700);
    private Timeline typingWatch;

    /** A card's width: three across in the window's default size, one on a narrow one. */
    private static final double CARD_WIDTH = 220;

    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter EARLIER = DateTimeFormatter.ofPattern("d MMM HH:mm");

    public ParametersDialog(Window owner, ProjectConfig config, ProjectState state, LibraryService libraries) {
        this(owner, config, state, libraries, null);
    }

    /** @param reveal opens the block a use is in, after this window closes; null offers no link */
    public ParametersDialog(Window owner, ProjectConfig config, ProjectState state, LibraryService libraries,
                            Consumer<Usages.Usage> reveal) {
        this.owner = owner;
        this.config = config;
        this.state = state;
        this.libraries = libraries;
        this.reveal = reveal;
    }

    public void show() {
        reload();

        // The directory, not only the project: an edit here is written to that directory's files.
        StudioWindow window = StudioWindow.modal("parameters", "Parameters — " + config.displayDirectory(), owner)
                .size(760, 640).minSize(420, 400);
        stage = window.stage();

        BorderPane root = new BorderPane();
        root.setTop(buildFilterBar());
        root.setCenter(buildCardPane());
        // The add bar is pinned above the buttons rather than at the end of the scrolling column, where a bot with
        // twenty parameters hid it.
        root.setBottom(new VBox(buildAddRow(), buildBottomBar()));

        redraw();

        // Wired after the rows are read, so opening the project does not read as an edit by the user: the rows as
        // they were opened are the state undo bottoms out at.
        committed = List.copyOf(rows);
        typingWatch = new Timeline(new KeyFrame(TYPING_TICK, e -> commitPending("the value you typed")));
        typingWatch.setCycleCount(Animation.INDEFINITE);
        typingWatch.play();

        stage.setOnHidden(e -> typingWatch.stop());
        window.show(root);
    }

    /**
     * Re-reads every parameter from the bot's sources, with where each comes from and when a run last wrote it.
     *
     * <p>Asked on every change rather than kept in step by this window: the sources may store something other than
     * what was handed — a canonical spelling from the grammar — so what is on screen after a change has to be what
     * they hold. It is also what makes a file edited behind this window's back cost nothing worse than a stale screen.
     */
    private void reload() {
        rows.clear();
        // A plugin's conditional parameter is listed only while it applies (ManagedParam.ConditionStep.when, 2026-10-12).
        HostParamScope scope = HostParamScope.over(HostPluginValues.live());
        JavaParameters.scan(config, state).stream().filter(scope::shown).forEach(rows::add);
        items.clear();
        for (JavaParameter entry : rows) items.add(new Item(entry, fromOf(entry)));
        keptAt.clear();
        for (RunParameter run : JavaParameters.duringRun(config, state, PluginHost.grammar())) {
            run.keptAt().ifPresent(at -> keptAt.put(run.group() + "." + run.name(), at));
        }
        // Read once per reload, beside the rows they belong to: a value cell for a parameter typed with one of the
        // bot's own records needs that record's components, and a per-cell scan would read every file once per row.
        records = BotRecords.scan(config, state, PluginHost.grammar());
    }

    /**
     * Where {@code entry} comes from: the bot, or the plugin whose folder holds it — {@code params/<group>/<artifact>/}
     * for a group, {@code plugins/<group>/<artifact>/} for an old field — named as the loaded plugin is.
     */
    private From fromOf(JavaParameter entry) {
        if (!entry.declaredByPlugin()) return From.BOT;
        String segment = entry.group() ? groupSegment(entry.file()) : ManagedHolders.segmentOf(entry.file());
        if (segment == null) return new From("plugins", "Plugins");
        for (LoadedPlugin plugin : PluginHost.plugins()) {
            if (segment.equals(ManagedHolders.segment(plugin.coordinate()))) return new From(segment, plugin.name());
        }
        return new From(segment, segment);
    }

    /** The folders between params/ and {@code file}, dotted: a plugin's segment; null for a file directly in params/. */
    private String groupSegment(Path file) {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        Path dir = file.toAbsolutePath().normalize().getParent();
        if (dir == null || !dir.startsWith(params) || dir.equals(params)) return null;
        return params.relativize(dir).toString().replace(File.separatorChar, '.');
    }

    // --- top: the chips and the search box ------------------------------------------------------------------

    /** Built once: the chips are redrawn inside their rows, and the search box is never moved, so typing keeps focus. */
    private Node buildFilterBar() {
        search.setPromptText("Search…");
        search.setPrefWidth(180);
        search.textProperty().addListener((o, was, is) -> {
            filter = filter.searching(is);
            redraw();
        });
        whenChips.setAlignment(Pos.CENTER_LEFT);
        fromChips.setAlignment(Pos.CENTER_LEFT);
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox when = new HBox(6, whenChips, spacer, search);
        when.setAlignment(Pos.CENTER_LEFT);
        VBox bar = new VBox(4, when, fromChips);
        bar.setPadding(new Insets(10, 14, 4, 14));
        return bar;
    }

    /** The two chip rows, redrawn with the counts. */
    private void redrawChips() {
        chipRow(whenChips, "When", ParamCards.whenChips(items, filter), chosen -> filter = filter.with(chosen));
        chipRow(fromChips, "From", ParamCards.fromChips(items, filter), chosen -> filter = filter.with(chosen));
    }

    private <T> void chipRow(HBox row, String caption, List<Chip<T>> chips, Consumer<T> choose) {
        Label label = new Label(caption);
        label.getStyleClass().add("dialog-hint-text");
        label.setMinWidth(44);
        row.getChildren().setAll(label);
        ToggleGroup group = new ToggleGroup();
        for (Chip<T> chip : chips) {
            ToggleButton button = new ToggleButton(chip.label() + "  " + chip.count());
            button.getStyleClass().add("param-chip");
            button.setToggleGroup(group);
            button.setSelected(chip.on());
            button.setOnAction(e -> {
                choose.accept(chip.key());
                redraw();
            });
            row.getChildren().add(button);
        }
    }

    // --- centre: the cards -----------------------------------------------------------------------------------

    private Node buildCardPane() {
        cardColumn.setPadding(new Insets(6, 14, 14, 14));
        ScrollPane scroll = new ScrollPane(cardColumn);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /** Redraws the chips, the cards and the add bar's groups, and the open popover over the rows as they are now. */
    private void redraw() {
        flushValues();
        valueEditors.clear();
        redrawChips();
        cardColumn.getChildren().clear();
        List<ParamCards.Section> sections = ParamCards.sections(items, JavaParameters.ownGroups(config), filter);
        for (ParamCards.Section section : sections) {
            Label heading = new Label(section.from().displayName());
            heading.getStyleClass().add("param-section-heading");
            cardColumn.getChildren().add(heading);
            if (section.groups().isEmpty()) {
                cardColumn.getChildren().add(hintLabel("No parameter yet — make a group, then add one in the bar at"
                        + " the bottom."));
            }
            for (ParamCards.Group group : section.groups()) {
                Label name = new Label(group.className());
                name.getStyleClass().add("param-group-heading");
                cardColumn.getChildren().add(name);
                if (group.cards().isEmpty()) {
                    cardColumn.getChildren().add(hintLabel("Nothing here yet — add one in the bar at the bottom."));
                    continue;
                }
                FlowPane grid = new FlowPane(8, 8);
                grid.getStyleClass().add("param-card-grid");
                for (JavaParameter entry : group.cards()) grid.getChildren().add(card(entry));
                cardColumn.getChildren().add(grid);
            }
        }
        if (sections.isEmpty()) cardColumn.getChildren().add(hintLabel("No parameter matches."));
        refreshGroups();
        refreshDetail();
    }

    /** One parameter's card: name, when it changes, type, value, and when a run last wrote it. Click to edit. */
    private Node card(JavaParameter entry) {
        ParameterRow v = entry.row();
        Label name = new Label(v.name());
        name.getStyleClass().add("param-card-name");
        Label when = new Label(v.duringRun() ? "during" : "outside");
        when.getStyleClass().add("dialog-hint-text");
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(4, name, spacer, when);
        head.setAlignment(Pos.CENTER_LEFT);

        Label type = new Label(ValueTypes.sourceName(entry.form()));
        type.getStyleClass().add("dialog-hint-text");
        String text = entry.editable() ? v.valueText() : entry.initializer();
        Label value = new Label(text.isBlank() ? "—" : shortened(text));
        value.getStyleClass().add("param-card-value");
        value.setWrapText(true);
        value.setMaxWidth(Double.MAX_VALUE);
        value.setTooltip(new Tooltip(text));

        VBox card = new VBox(3, head, type, value);
        Instant at = keptAt.get(entry.qualified());
        if (at != null) card.getChildren().add(hintLabel("written by a run · " + when(at)));
        card.getStyleClass().add("param-card");
        card.setPrefWidth(CARD_WIDTH);
        card.setUserData(entry.qualified());
        card.setFocusTraversable(true);
        String tip = v.description().isBlank() ? "" : v.description() + "\n";
        Tooltip.install(card, new Tooltip(tip + (entry.declaredByPlugin() ? entry.owner() : "Click to edit.")));
        card.setOnMouseClicked(e -> openDetail(entry));
        card.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) openDetail(entry);
        });
        return card;
    }

    private static String shortened(String text) {
        String one = text.strip().replaceAll("\\s+", " ");
        return one.length() <= 80 ? one : one.substring(0, 79) + "…";
    }

    private static String when(Instant at) {
        ZonedDateTime local = at.atZone(ZoneId.systemDefault());
        return (local.toLocalDate().equals(LocalDate.now()) ? TODAY : EARLIER).format(local);
    }

    // --- the popover: one parameter's editor ------------------------------------------------------------------

    /**
     * Opens {@code entry}'s editor over the window. Every change there is written as it is made, like the rest of the
     * window; OK keeps them, Cancel puts back the rows as they were when it opened, as one step.
     */
    private void openDetail(JavaParameter entry) {
        commitPending("the value you typed");
        List<JavaParameter> before = List.copyOf(rows);
        detailClass = entry.className();
        detailName = entry.name();
        detailBox = new VBox(8);
        detailBox.setPadding(new Insets(4));
        detailBox.setPrefWidth(520);
        redraw();

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        dialog.initOwner(stage);
        dialog.setTitle(entry.qualified());
        dialog.setHeaderText(null);
        ButtonType open = new ButtonType("Open in code", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (reveal != null) dialog.getDialogPane().getButtonTypes().add(open);
        dialog.getDialogPane().setContent(detailBox);
        ButtonType answer = dialog.showAndWait().orElse(ButtonType.CANCEL);

        String name = detailName;
        String className = detailClass;
        detailBox = null;
        if (answer == ButtonType.CANCEL) {
            valueEditors.clear();
            cancelTo(before, name);
        } else {
            commitPending("the value of " + name);
        }
        valueEditors.clear();
        redraw();
        if (answer == open) {
            JavaParameter now = find(className, name);
            Optional<Usages.Usage> at = JavaParameters.declaration(state, now);
            if (at.isEmpty()) {
                error(name + " is no longer in its file.");
                return;
            }
            stage.close();
            reveal.accept(at.get());
        }
    }

    /** Puts back {@code before}, recorded as one step so ↶ takes the cancel back. */
    private void cancelTo(List<JavaParameter> before, String name) {
        List<JavaParameter> was = List.copyOf(rows);
        if (was.equals(before)) return;
        restore(before);
        history.record("cancelling the edits of " + name, was, List.copyOf(rows));
    }

    /** The popover's body over the rows as they are now; said gone once its parameter is. */
    private void refreshDetail() {
        if (detailBox == null) return;
        JavaParameter entry = find(detailClass, detailName);
        if (entry == null) {
            detailBox.getChildren().setAll(hintLabel(detailName + " is gone. Cancel puts it back."));
            return;
        }
        detailBox.getChildren().setAll(buildParamDetail(entry));
    }

    /** One parameter's editor: what it is called, what it holds, what it is for, when it changes. */
    private Node buildParamDetail(JavaParameter entry) {
        ParameterRow v = entry.row();
        // A plugin's parameter (2026-10-11): its name, type, note and audience are the plugin's, which the bot's code
        // reads by name; only its value is the user's.
        boolean mine = !entry.declaredByPlugin();
        // A group's parameter (2026-11-04) keeps its type here; its name (a rename rule keeps its value on every
        // copy), value, note and when it changes are edited, and the menu sets its starting value.
        boolean declarable = mine && !entry.group();
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);

        Node name;
        if (mine) {
            TextField field = new TextField(v.name());
            field.focusedProperty().addListener((o, was, is) -> {
                if (!is) commitRename(entry, field);
            });
            field.setOnAction(e -> {
                commitRename(entry, field);
                e.consume();   // Enter commits the name here; it is not also the popover's OK
            });
            name = field;
        } else {
            Label label = new Label(v.name());
            label.getStyleClass().add("param-card-name");
            name = label;
        }

        // The type is the whole rule about the value (2026-10-29): no choices and no range beside it.
        ValueGrammar grammar = PluginHost.grammar();
        Node type;
        if (declarable) {
            TypeChooser picker = typeChooser();
            picker.setType(entry.form());
            picker.setPrefWidth(180);
            picker.typeProperty().addListener((o, was, is) -> {
                if (is == null || is.equals(entry.form())) return;
                retype(entry, is);
            });
            type = picker;
        } else {
            type = new Label(ValueTypes.sourceName(entry.form()));
        }

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, name, type, spacer);
        if (entry.group()) head.getChildren().addAll(changedBar(entry, mine), rowMenu(entry));
        if (declarable) {
            ClockSwitch.other(entry.form()).filter(grammar::known)
                    .ifPresent(other -> head.getChildren().add(head.getChildren().indexOf(type) + 1, clockBar(entry)));
        }
        if (mine) {
            Button drop = new Button("✕");
            drop.getStyleClass().add("row-icon-button");
            drop.setTooltip(new Tooltip("Remove this parameter. The declaration goes; the places your bot reads it "
                    + "are left alone and stop compiling, which is where you decide what they should say."));
            drop.setOnAction(e -> removeParameter(entry));
            head.getChildren().add(drop);
        }
        head.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        grid.add(head, 0, 0, 2, 1);

        int row = 1;
        if (!mine) {
            // Said in the editor, not only in a tooltip: every greyed part of it follows from this one fact.
            grid.add(hintLabel("🔒 Declared by a plugin: only its value is yours. " + entry.owner()), 0, row, 2, 1);
            row++;
        }
        grid.add(new Label("Group"), 0, row);
        grid.add(hintLabel(entry.className()), 1, row);
        row++;

        grid.add(new Label("Value"), 0, row);
        Node widget = valueCell(entry);
        grid.add(widget, 1, row);
        GridPane.setHgrow(widget, Priority.ALWAYS);
        row++;

        Node note;
        if (mine) {
            TextField description = new TextField(v.description());
            description.setPromptText("what this is for (shown as a tooltip, and to the user when shared)");
            // On focus loss rather than per keystroke: every letter would otherwise rewrite the source file.
            description.focusedProperty().addListener((o, was, is) -> {
                if (is || description.getText().equals(entry.row().description())) return;
                edit(entry, "the note", current -> current.toBuilder().description(description.getText()).build());
            });
            note = description;
        } else {
            note = hintLabel(v.description());
        }
        grid.add(new Label("Note"), 0, row);
        grid.add(note, 1, row);
        GridPane.setHgrow(note, Priority.ALWAYS);
        row++;

        // A group has no audience: every parameter is this computer's own (2026-11-04). An old field still has one.
        if (!entry.group()) {
            CheckBox shared = new CheckBox("Show to user");
            shared.setSelected(v.isPublic());
            shared.setDisable(!mine);
            shared.setOnAction(e -> edit(entry, "who sees", current -> current.toBuilder().visibility(
                    shared.isSelected() ? Visibility.PUBLIC : Visibility.EDITOR_ONLY).build()));
            grid.add(shared, 1, row);
        }
        return grid;
    }

    /**
     * The value cell: the type's own editor — the same one the canvas uses, a plugin's included — or, for a value
     * this window cannot rewrite, the expression exactly as its author wrote it and why. A value the bot writes during
     * a run is one: read-only here (the user's call, 2026-11-05); Forget sets it back.
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

    /**
     * <b>Outside a run | During a run</b> (layout-v3): when a group's parameter changes. During a run the bot writes
     * it and the value cell is read-only. A plugin's is shown, not switched.
     */
    private Node changedBar(JavaParameter entry, boolean mine) {
        ToggleGroup group = new ToggleGroup();
        HBox bar = new HBox();
        bar.getStyleClass().add("choice-mode-bar");
        Param.Changes[] sides = Param.Changes.values();
        for (int i = 0; i < sides.length; i++) {
            Param.Changes side = sides[i];
            ToggleButton button = new ToggleButton(side.displayName());
            button.setToggleGroup(group);
            button.setSelected(entry.row().changes() == side);
            // A fact's group is the run's whole (@Facts): it changes during a run, never switched here.
            button.setDisable(!mine || RunWritten.declaredIn(entry.file(), PluginHost.classLoader()));
            button.getStyleClass().add(i == 0 ? "choice-mode-first" : "choice-mode-last");
            button.setTooltip(new Tooltip(side == Param.Changes.DURING_RUN
                    ? "The bot writes it during a run; here it is read-only."
                    : "You set it here; the bot only reads it."));
            button.setOnAction(e -> {
                if (entry.row().changes() == side) {
                    button.setSelected(true);
                    return;
                }
                edit(entry, "when it changes", current -> current.toBuilder().changes(side).build());
            });
            bar.getChildren().add(button);
        }
        return bar;
    }

    /** A group parameter's menu: make this computer's value the one the bot ships with, or go back to that one. */
    private Node rowMenu(JavaParameter entry) {
        MenuItem starting = new MenuItem("Make this the starting value");
        starting.setOnAction(e -> change("making " + entry.name() + "'s value its starting one", () -> {
            flushValues();
            JavaParameter now = find(entry.className(), entry.name());
            if (!JavaParameters.makeStarting(config, state, now == null ? entry : now)) {
                error(entry.name() + " has no starting copy to write, or it already starts at this value.");
                return;
            }
            error("");
        }));
        MenuItem forget = new MenuItem("Forget (back to its starting value)");
        forget.setOnAction(e -> change("forgetting " + entry.name(), () -> {
            if (JavaParameters.forget(config, state, entry).isEmpty()) {
                error(entry.name() + " has no starting copy to go back to.");
                return;
            }
            error("");
            reload();
            redraw();
        }));
        MenuButton menu = new MenuButton("⋯", null, starting, forget);
        menu.getStyleClass().add("row-icon-button");
        menu.setTooltip(new Tooltip("Its starting value: what a fresh copy of the bot begins with."));
        return menu;
    }

    /** The chooser every type on this window is picked with: what a picker can write, and the bot's records. */
    private TypeChooser typeChooser() {
        return new TypeChooser(() -> TypeCatalog.current(TypeCatalog.Purpose.VALUE, records));
    }

    /** A value this window may show but not change — a plugin's note, a group's name. */
    private static Label hintLabel(String text) {
        Label label = new Label(text == null || text.isBlank() ? "—" : text);
        label.getStyleClass().add("dialog-hint-text");
        label.setWrapText(true);
        return label;
    }

    /**
     * Removes a parameter's declaration — or, while the bot still uses it, says where and removes nothing.
     *
     * <p>The uses are not rewritten behind the user's back: what one should become is a judgement, so they are the
     * user's to change, exactly as a function still called cannot be deleted. What the refusal may offer is the one
     * rewrite that is no judgement at all, the parameter's own constant written where it was read (2026-09-27).
     */
    private void removeParameter(JavaParameter entry) {
        change("removing " + entry.row().name(), () -> {
            if (JavaParameters.remove(config, state, entry) instanceof JavaParameters.Outcome.Refused refused) {
                refuse(entry.row().name() + " wasn't removed", refused);
                return;
            }
            error("");
            reload();
            redraw();
        });
    }

    /**
     * <b>Local | UTC</b> beside a time of day's type: the other one retypes the field between {@code LocalTime} and
     * {@code OffsetTime} and keeps the clock reading ({@link ClockSwitch}).
     */
    private Node clockBar(JavaParameter entry) {
        boolean local = entry.form() == java.time.LocalTime.class;
        ToggleGroup group = new ToggleGroup();
        HBox bar = new HBox();
        bar.getStyleClass().add("choice-mode-bar");
        String[][] sides = {{"Local", "This computer's clock, wherever the bot runs."},
                {"UTC", "A time at an offset from UTC — the same moment everywhere, like a game's daily reset."}};
        for (int i = 0; i < sides.length; i++) {
            boolean isLocal = i == 0;
            ToggleButton button = new ToggleButton(sides[i][0]);
            button.setToggleGroup(group);
            button.setSelected(isLocal == local);
            button.getStyleClass().add(isLocal ? "choice-mode-first" : "choice-mode-last");
            button.setTooltip(new Tooltip(sides[i][1] + " Switching keeps the hours and minutes."));
            button.setOnAction(e -> {
                button.setSelected(true);
                if (isLocal == local) return;
                change("the clock of " + entry.row().name(), () -> {
                    JavaParameter held = find(entry.className(), entry.row().name());
                    if (held == null || ClockSwitch.flip(config, state, held, PluginHost.grammar()).isEmpty()) {
                        error("“" + entry.row().name() + "” could not be switched.");
                    }
                    reload();
                    redraw();
                });
            });
            bar.getChildren().add(button);
        }
        return bar;
    }

    /**
     * Retypes {@code entry} to {@code form}. The value is reset to the new type's fresh one: a value written for one
     * type is not a value of another, and carrying it across would leave a bot that does not compile.
     */
    private void retype(JavaParameter entry, Type form) {
        edit(entry, "the type", UnaryOperator.identity(), null, form);
    }

    // --- bottom: + New group… and the add bar --------------------------------------------------------------------

    /**
     * <i>+ New group…</i>, then a new parameter's name, type and group. A parameter Studio adds changes during a run
     * (2026-11-05, the user's call). A plugin's group takes none: its parameters are the plugin's.
     */
    private Node buildAddRow() {
        Button newGroup = new Button("+ New group…");
        newGroup.setTooltip(new Tooltip("A group of parameters, which is their category: params/bot/<Name>.java and "
                + "its starting copy."));
        newGroup.setOnAction(e -> askNewGroup());

        TextField name = new TextField();
        name.setPromptText("parameter name");
        HBox.setHgrow(name, Priority.ALWAYS);
        TypeChooser type = typeChooser();
        type.setType(String.class);
        type.setPrefWidth(160);
        Button add = new Button("Add parameter");
        add.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
        // Ticked by default (2026-09-26): an old field is most often added for the person running the bot. A group's
        // parameter has no audience, so the tick means nothing to it.
        CheckBox shown = new CheckBox("Show to user");
        shown.setSelected(true);
        shown.setTooltip(new Tooltip("Ticked, a field of an old parameters class appears in the Runner window. A "
                + "group's parameter is this computer's own either way."));

        intoPicker.valueProperty().addListener((o, was, is) -> {
            if (is != null) selectedClass = is;
        });
        intoPicker.setTooltip(new Tooltip("Which of your groups the parameter is declared in."));

        Runnable addParameter = () -> {
            String candidate = name.getText() == null ? "" : name.getText().trim();
            if (!isValidIdentifier(candidate)) {
                error("Enter a valid name (letters, digits, _; not starting with a digit).");
                return;
            }
            change("adding " + candidate, () -> {
                boolean fresh = !JavaParameters.classes(config, state).contains(selectedClass)
                        && !JavaParameters.ownGroups(config).contains(selectedClass);
                Type form = type.type();
                if (PluginHost.grammar().freshSpelling(form).isEmpty()) {
                    error("“" + candidate + "” was not written: its type has no blank value to put in the starting"
                            + " copy, which is published.");
                    return;
                }
                Optional<ParameterRow> stored = JavaParameters.add(config, state, selectedClass, candidate,
                        form, PluginHost.grammar().freshSpelling(form).orElse(null), "",
                        Param.Changes.DURING_RUN);  // a bot parameter Studio adds changes during a run, 2026-11-05
                if (stored.isEmpty()) {
                    error("“" + candidate + "” was not written — " + selectedClass + " may already declare "
                            + "a parameter of that name, or no installed plugin declares this type.");
                    return;
                }
                if (shown.isSelected()) {
                    reload();
                    JavaParameter added = find(selectedClass, candidate);
                    if (added != null && !added.group()) {
                        JavaParameters.declare(config, state, added,
                                added.row().toBuilder().visibility(Visibility.PUBLIC).build(), added.form());
                    }
                }
                // After the parameter, not before: a name refused above must not cost the project a dependency.
                boolean declared = libraries != null && libraries.ensureContract();
                String created = fresh ? "Created " + selectedClass + ".java for it." : "";
                error(declared ? (created + " Added botmaker-studio-api to pom.xml for @Param.").trim() : created);
                name.clear();
                reload();
                redraw();
            });
        };
        add.setOnAction(e -> addParameter.run());
        name.setOnAction(e -> {
            addParameter.run();
            e.consume();
        });

        HBox row = new HBox(6, newGroup, new Separator(Orientation.VERTICAL), new Label("New"), name, type,
                intoPicker, shown, add);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 10, 0, 10));
        return row;
    }

    /** The add bar's groups: the bot's own, an empty one too; {@code Parameters} while it has none. */
    private void refreshGroups() {
        Set<String> groups = new LinkedHashSet<>(JavaParameters.ownClasses(config, state));
        groups.addAll(JavaParameters.ownGroups(config));
        if (groups.isEmpty()) groups.add(JavaParameters.DEFAULT_CLASS);
        List<String> choices = List.copyOf(groups);
        String keep = choices.contains(selectedClass) ? selectedClass : choices.getFirst();
        if (!intoPicker.getItems().equals(choices)) intoPicker.getItems().setAll(choices);
        intoPicker.setValue(keep);
        selectedClass = keep;
    }

    /** Asks for a group's name and writes it; the add bar then adds into it. */
    private void askNewGroup() {
        TextInputDialog ask = new TextInputDialog();
        ThemedWindows.apply(ask);
        ask.initOwner(stage);
        ask.setTitle("New group");
        ask.setHeaderText("A group of parameters, in params/bot/");
        ask.setContentText("Name:");
        ask.showAndWait().map(String::strip).filter(text -> !text.isEmpty()).ifPresent(this::createGroup);
    }

    /** Writes the group {@code name} and makes it the add bar's; says why when nothing was written. */
    void createGroup(String name) {
        Optional<String> refused = JavaParameters.newGroup(config, state, name);
        if (refused.isPresent()) {
            error(refused.get());
            return;
        }
        error("Created " + name + ".java and its starting copy.");
        selectedClass = name;
        reload();
        redraw();
    }

    // --- declaring, which is every change but a value ---------------------------------------------------------

    /**
     * Whether {@code s} can be written into Java as-is — a parameter's name reaches a bot's source as a method or a
     * field, so a name this refuses is a name nobody could read back.
     */
    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    private void commitRename(JavaParameter entry, TextField field) {
        // The redraw a rename makes takes the old name field off the screen, whose focus loss lands here again with
        // the row as it was: that is no second rename, nor is one of a row that is no longer there.
        if (renaming || find(entry.className(), entry.row().name()) == null) return;
        String candidate = field.getText() == null ? "" : field.getText().trim();
        if (candidate.equals(entry.row().name())) return;
        if (!isValidIdentifier(candidate)) {
            error("Invalid parameter name — reverted.");
            field.setText(entry.row().name());
            return;
        }
        error("");
        // The popover follows the parameter to its new name; it goes back if nothing was written.
        boolean followed = entry.is(detailClass, detailName);
        if (followed) detailName = candidate;
        boolean stored;
        renaming = true;
        try {
            stored = edit(entry, "the name", UnaryOperator.identity(), candidate);
        } finally {
            renaming = false;
        }
        if (!stored) {
            if (followed) {
                detailName = entry.row().name();
                refreshDetail();
            }
            error("“" + candidate + "” was not written — the group may already declare a parameter of that name.");
            field.setText(entry.row().name());
        }
    }

    /**
     * Declares {@code change} applied to the row {@code entry} names, and redraws.
     *
     * <p><b>By the pair, and the change is a function, not a finished record.</b> Flushing the on-screen widgets
     * first replaces the row a control captured when it was built, so looking it up by equality found nothing; and a
     * pre-built row would carry the stale value back in with it, undoing the flush it was queued behind.
     */
    private void edit(JavaParameter entry, String what, UnaryOperator<ParameterRow> change) {
        edit(entry, what, change, null);
    }

    /** The same, optionally renaming to {@code newName}. Answers whether the sources stored anything. */
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
     * One row, as the source reads it back after the change — or {@code false} when nothing was written. The
     * parameter is looked up again rather than trusted: an earlier edit in the same gesture may have moved it, and an
     * undo replaying an old snapshot lands here too. {@code newForm} is null for an edit that keeps the type.
     */
    private boolean declare(JavaParameter entry, UnaryOperator<ParameterRow> change, Type newForm) {
        JavaParameter held = find(entry.className(), entry.row().name());
        if (held == null) return false;
        ParameterRow wanted = change.apply(held.row());
        JavaParameters.Outcome outcome = JavaParameters.declare(config, state, held, wanted,
                newForm == null ? held.form() : newForm);
        if (outcome instanceof JavaParameters.Outcome.Refused refused) {
            refuse(held.row().name() + " wasn't changed", refused);
        }
        reload();
        redraw();
        return outcome.stored().isPresent();
    }

    private static ParameterRow rename(ParameterRow row, String name) {
        return ParameterRow.named(name, row.typeName())
                .value(row.value())
                .description(row.description())
                .category(row.category())
                .visibility(row.visibility())
                .changes(row.changes())
                .build();
    }

    /**
     * One recorded step: whatever was typed and not yet recorded becomes its own step first, then {@code body} runs
     * and becomes the next one. The editors are dropped between them: a redraw must not flush the old type's widget
     * onto the new type's default.
     */
    private void change(String label, Runnable body) {
        commitPending("the value you typed");
        valueEditors.clear();
        body.run();
        commitPending(label);
    }

    /**
     * Records everything the rows have picked up since the last step, under {@code label} — and does nothing at all
     * when they have picked up nothing, which is what makes it safe to call on a timer.
     */
    private void commitPending(String label) {
        flushValues();
        List<JavaParameter> now = List.copyOf(rows);
        if (now.equals(committed)) return;
        history.record(label, committed, now);
        committed = now;
    }

    /**
     * Puts a snapshot back: ↶, ↷ and a popover's Cancel all land here.
     *
     * <p><b>An undo is replayed as edits, because this window stores nothing.</b> Every row of the snapshot is put
     * back the way the edit that made it would have been made, and a parameter the snapshot does not hold is removed.
     * One the snapshot holds and the project does not is written back in full, which is how ↶ after a delete and ↷
     * after an add both land.
     */
    private void restore(List<JavaParameter> snapshot) {
        valueEditors.clear();
        // A rename first, as a rename: one parameter the snapshot lacks and one it holds that the project lacks, in
        // the same class, are the two ends of it. Replayed as a remove and an add it would be refused whenever the bot
        // uses it, and would lose every use's name even when it was not.
        for (JavaParameter extra : List.copyOf(rows)) {
            if (snapshot.stream().anyMatch(kept -> kept.is(extra.className(), extra.row().name()))) continue;
            List<JavaParameter> extras = rows.stream().filter(e -> e.className().equals(extra.className())
                    && snapshot.stream().noneMatch(kept -> kept.is(e.className(), e.row().name()))).toList();
            List<JavaParameter> missing = snapshot.stream().filter(kept -> kept.className().equals(extra.className())
                    && find(kept.className(), kept.row().name()) == null).toList();
            if (extras.size() == 1 && missing.size() == 1) {
                JavaParameters.declare(config, state, extra, missing.getFirst().row(), missing.getFirst().form());
                reload();
            }
        }
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
                        row.value().orElse(null), row.description(), row.changes());
                held = find(entry.className(), row.name());
            }
            if (held == null) continue;
            JavaParameters.declare(config, state, held, row, entry.form());
            reload();
        }
        reload();
        committed = List.copyOf(rows);
        redraw();
    }

    /**
     * The parameter called {@code name} in the class {@code group}, or null. The pair, not the name: two groups may
     * both declare a {@code timeout}.
     */
    private JavaParameter find(String group, String name) {
        for (JavaParameter entry : rows) {
            if (entry.is(group, name)) return entry;
        }
        return null;
    }

    /** Hands the on-screen value widgets' values to the sources they were read from. */
    private void flushValues() {
        if (valueEditors.isEmpty()) return;
        for (ValueEditor editor : valueEditors) {
            for (int i = 0; i < rows.size(); i++) {
                JavaParameter entry = rows.get(i);
                if (!editor.describes(entry.className(), entry.row().name())) continue;
                // Empty means this cell has no Java for what is in it, and writing nothing is the only honest answer
                // to that. A value shown exactly as the row holds it is not an edit.
                Optional<JavaValue> typed = editor.read().get();
                if (typed.isEmpty() || entry.row().value().filter(typed.get()::sameJava).isPresent()) break;
                // The answer is the row as stored, which may differ from what was typed — a canonical spelling from
                // the grammar — so the next redraw shows what the bot will actually get.
                Optional<ParameterRow> stored = JavaParameters.setValue(config, state, entry, typed.get());
                stored.ifPresent(row -> rows.set(rows.indexOf(entry), entry.withRow(row)));
                break;
            }
        }
    }

    // --- the bottom bar ----------------------------------------------------------------------------------------

    /** ↶ ↷, what the last change said, and Close: there is nothing to save. */
    private Node buildBottomBar() {
        statusLabel.getStyleClass().add("dialog-error-text");

        Button close = new Button("Close");
        close.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
        close.setOnAction(e -> {
            commitPending("the value you typed");
            stage.close();
        });

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusLabel.setWrapText(true);
        HBox bar = new HBox(10, undoButton(), redoButton(), new Separator(Orientation.VERTICAL),
                statusLabel, spacer, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        return bar;
    }

    private Button undoButton() {
        Button undo = new Button("↶");
        Runnable shown = () -> {
            undo.setDisable(!history.canUndo());
            undo.setTooltip(new Tooltip(labelled("Undo", history.undoLabel())));
        };
        shown.run();
        history.addListener(shown);
        // What is on screen but not yet recorded is part of what ↶ takes back, so it becomes a step first.
        undo.setOnAction(e -> {
            commitPending("the value you typed");
            history.undo();
        });
        return undo;
    }

    private Button redoButton() {
        Button redo = new Button("↷");
        Runnable shown = () -> {
            redo.setDisable(!history.canRedo());
            redo.setTooltip(new Tooltip(labelled("Redo", history.redoLabel())));
        };
        shown.run();
        history.addListener(shown);
        redo.setOnAction(e -> history.redo());
        return redo;
    }

    private static String labelled(String verb, String step) {
        return step == null || step.isBlank() ? verb + " — nothing to " + verb.toLowerCase() : verb + " " + step;
    }

    private void error(String message) {
        statusLabel.setText(message);
    }

    /**
     * Shows a refused edit through the one refusal window ({@link RefusalDialog}): why, each use as a link that
     * closes this window and opens the block, and the fix the refusal offers, written as one step here.
     */
    private void refuse(String title, JavaParameters.Outcome.Refused refused) {
        error("");
        Consumer<Usages.Usage> land = reveal == null ? null : use -> {
            commitPending("the value you typed");
            stage.close();
            reveal.accept(use);
        };
        RefusalDialog.show(stage, title, refused.asRefactor(), land, plan -> change(plan.summary(), () -> {
            JavaParameters.write(config, state, plan);
            reload();
            redraw();
        }));
    }
}
