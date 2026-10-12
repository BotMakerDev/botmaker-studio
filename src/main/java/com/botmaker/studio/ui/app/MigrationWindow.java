package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectWrites;
import com.botmaker.studio.project.managed.ManagedHolders;
import com.botmaker.studio.project.managed.ManagedMigrations;
import com.botmaker.studio.project.managed.MigrationSteps;
import com.botmaker.studio.project.managed.MigrationSteps.Choice;
import com.botmaker.studio.project.managed.MigrationSteps.Shape;
import com.botmaker.studio.project.managed.MigrationSteps.Step;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.ui.app.params.ValueEditors;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The migration window (2026-10-12, {@code docs/refactor/25-compatibility.md} §12): a retired type's values copied
 * into another type in guided steps, the steps saved in the bot as {@code @Migrate} methods ({@link MigrationSteps})
 * and run by {@link ManagedMigrations}. Opened from the Review tab's row of a retired type, and from a managed
 * value's <i>Migrate type…</i> ({@link MigrationMenu}).
 *
 * <p>One step per pair of types. A pair of calls lists the new type's parts, each made of an old part, built from
 * one, or a fixed value in the type's own editor; a part taken from one of another type opens that pair's step
 * below. A pair of enums lists the old constants, each paired with a new one. A pair the bot already has a method
 * for opens as that method says; one written by hand that the window cannot show is said, and replaced on Apply.
 *
 * <p><b>Preview</b> runs the whole migration on a copy of the bot and lists each value before and after;
 * <b>Apply</b> writes the methods and the copies as one version, after a safety one. The originals are never
 * touched by it: <b>Switch uses</b> and <b>Remove originals…</b> are the author's next steps
 * ({@link MigrationActions}). Planning compiles the bot, so it runs off the FX thread; the buffers are read before and written
 * after on it.
 */
final class MigrationWindow {

    private final ProjectConfig config;
    private final ProjectState state;
    private final Class<?> from;
    private final Consumer<Path> redraw;
    private final Runnable after;
    private final ValueGrammar grammar = PluginHost.grammar();
    private final List<ManagedValue<?>> declared = PluginHost.managedValues();

    private final Stage stage = new Stage();
    private final ComboBox<Class<?>> target = new ComboBox<>();
    private final VBox stepsBox = new VBox(8);
    private final Label status = new Label();
    private final TableView<MigrationSteps.Row> rows = new TableView<>();
    private final Button preview = new Button("Preview");
    private final Button apply = new Button("Apply");

    /** Every pair's step on screen, the migrated pair first, keyed by its two types. */
    private final Map<Map.Entry<Class<?>, Class<?>>, StepView> steps = new LinkedHashMap<>();
    /** The bot's own methods, read at open and after an Apply, which seed a pair's step. */
    private List<ManagedMigrations.Method> methods = List.of();
    /** Whether the steps on screen were just applied: Apply waits for a change. */
    private boolean applied;

    private MigrationWindow(ProjectConfig config, ProjectState state, Class<?> from, Consumer<Path> redraw,
                            Runnable after) {
        this.config = config;
        this.state = state;
        this.from = from;
        this.redraw = redraw;
        this.after = after;
    }

    /**
     * Opens the window on {@code from}'s values, {@code to} preselected — {@code null} for the plugin's replacement,
     * else the first type that fits. {@code redraw} is told each file written; {@code after} runs once a migration
     * is applied.
     */
    static void open(Window owner, ProjectConfig config, ProjectState state, Class<?> from, Class<?> to,
                     Consumer<Path> redraw, Runnable after) {
        new MigrationWindow(config, state, from, redraw, after).show(owner, to);
    }

    private void show(Window owner, Class<?> to) {
        List<Class<?>> targets = MigrationSteps.targets(grammar, declared, from);
        methods = ManagedMigrations.methods(BotIndex.of(config, state), grammar);

        Label heading = new Label("Copy every " + from.getSimpleName() + " into");
        target.getItems().setAll(targets);
        target.setConverter(new StringConverter<>() {
            @Override
            public String toString(Class<?> type) {
                if (type == null) return "";
                return type.getSimpleName() + (type.equals(MigrationSteps.replacementOf(grammar, from))
                        ? "  (its replacement)" : "");
            }

            @Override
            public Class<?> fromString(String text) {
                return null;
            }
        });
        HBox top = new HBox(8, heading, target);
        top.setAlignment(Pos.CENTER_LEFT);

        Label explain = new Label("The values are copied beside the old ones, under the same names, marked @Migrated;"
                + " the old ones stay until you delete them. The steps are saved in the bot as @Migrate methods.");
        explain.setWrapText(true);

        ScrollPane scroll = new ScrollPane(stepsBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        TableColumn<MigrationSteps.Row, String> name = new TableColumn<>("Value");
        name.setCellValueFactory(new PropertyValueFactory<>("name"));
        TableColumn<MigrationSteps.Row, String> before = new TableColumn<>("Now");
        before.setCellValueFactory(new PropertyValueFactory<>("before"));
        TableColumn<MigrationSteps.Row, String> made = new TableColumn<>("Migrated");
        made.setCellValueFactory(new PropertyValueFactory<>("after"));
        rows.getColumns().setAll(List.of(name, before, made));
        rows.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        rows.setPlaceholder(new Label("Preview lists each value as it is and as it would be copied."));
        rows.setPrefHeight(170);

        status.setWrapText(true);
        preview.setOnAction(e -> run(false));
        apply.setOnAction(e -> run(true));
        apply.setDisable(state.isReaderMode());
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, status, spacer, preview, apply, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(status, Priority.SOMETIMES);

        // Once copied: the bot's uses moved over, then the originals nothing uses any more removed.
        MigrationActions then = new MigrationActions(config, state, () -> stage, redraw, after);
        Button switchUses = new Button("Switch uses to the copies");
        switchUses.setOnAction(e -> then.switchUses(from, target.getValue(), Set.of(), status::setText));
        Button removeOriginals = new Button("Remove originals…");
        removeOriginals.setOnAction(e -> then.removeOriginals(from, target.getValue(), status::setText));
        for (Button each : List.of(switchUses, removeOriginals)) {
            each.disableProperty().bind(target.valueProperty().isNull());
        }
        Label thenLabel = new Label("Then:");
        HBox afterwards = new HBox(8, thenLabel, switchUses, removeOriginals);
        afterwards.setAlignment(Pos.CENTER_LEFT);
        afterwards.setDisable(state.isReaderMode());

        VBox root = new VBox(10, top, explain, scroll, rows, buttons, afterwards);
        root.setPadding(new Insets(14));

        target.valueProperty().addListener((o, was, now) -> {
            steps.clear();
            stepsBox.getChildren().clear();
            rows.getItems().clear();
            rebuild();
        });
        Class<?> preselected = to != null && targets.contains(to) ? to
                : targets.isEmpty() ? null : targets.getFirst();
        target.setValue(preselected);
        // No type fits: the listener never fires, and the buttons must still say so.
        if (preselected == null) state();

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Migrate " + from.getSimpleName());
        stage.setScene(ThemedWindows.scene(root, 820, 640));
        stage.show();
    }

    /**
     * Lays out the steps the pair needs now: the migrated pair's, then each pair a part goes through, for as deep
     * as the choices reach. A step already on screen keeps what was picked in it.
     */
    private void rebuild() {
        applied = false;
        Class<?> to = target.getValue();
        if (to == null) {
            state();
            return;
        }
        Map<Map.Entry<Class<?>, Class<?>>, StepView> needed = new LinkedHashMap<>();
        List<Map.Entry<Class<?>, Class<?>>> queue = new ArrayList<>(List.of(Map.entry(from, to)));
        while (!queue.isEmpty()) {
            Map.Entry<Class<?>, Class<?>> pair = queue.removeFirst();
            if (needed.containsKey(pair)) continue;
            StepView view = steps.containsKey(pair) ? steps.get(pair) : new StepView(pair.getKey(), pair.getValue());
            needed.put(pair, view);
            queue.addAll(view.nested());
        }
        steps.clear();
        steps.putAll(needed);
        stepsBox.getChildren().setAll(needed.values().stream().map(StepView::node).toList());
        state();
    }

    /**
     * The status and the buttons for the steps as they stand: Preview needs a target and no step with a problem;
     * Apply also a writable project and a change since the last Apply, which would only run it again.
     */
    private void state() {
        String problem = target.getValue() == null
                ? "No type a plugin declares can take " + from.getSimpleName() + "'s values."
                : steps.values().stream().map(StepView::problem).filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null);
        if (problem != null || !applied) status.setText(problem == null ? "" : problem);
        preview.setDisable(problem != null);
        apply.setDisable(problem != null || applied || state.isReaderMode());
    }

    /** A fixed value edited: the state again, without laying the steps out anew under the caret. */
    private void edited() {
        applied = false;
        state();
    }

    /** Plans on a worker; shows the preview, and with {@code write} applies a plan that compiles. */
    private void run(boolean write) {
        Class<?> to = target.getValue();
        List<Step> answered = new ArrayList<>();
        for (StepView view : steps.values()) {
            Optional<Step> step = view.step();
            if (step.isEmpty()) {
                status.setText(view.problem() == null ? "A value could not be read." : view.problem());
                return;
            }
            answered.add(step.get());
        }
        BotIndex index = BotIndex.of(config, state);
        Map<Path, String> created = holderFor(index, to);
        Path migrations = config.mainPackageDir().resolve(MigrationSteps.CLASS + ".java");
        preview.setDisable(true);
        apply.setDisable(true);
        status.setText(write ? "Migrating…" : "Working out the preview…");
        Thread worker = new Thread(() -> {
            MigrationSteps.Draft draft;
            try {
                draft = MigrationSteps.plan(index, grammar, declared, migrations, config.mainPackage(), answered,
                        from, to, created);
            } catch (RuntimeException | LinkageError e) {
                // Plugin code runs under the plan (factories, parts); a throw must not leave the window waiting.
                draft = new MigrationSteps.Draft(new Refactor.Refused("The migration could not be worked out: "
                        + e), new ManagedMigrations.Report(List.of(), List.of(), List.of()), List.of());
            }
            MigrationSteps.Draft done = draft;
            Platform.runLater(() -> shown(done, write, index));
        }, "migration-plan");
        worker.setDaemon(true);
        worker.start();
    }

    private void shown(MigrationSteps.Draft draft, boolean write, BotIndex index) {
        if (write && draft.outcome() instanceof Refactor.Planned) applied = true;
        state();
        rows.getItems().setAll(draft.rows());
        ManagedMigrations.Report report = draft.report();
        String kept = report.kept().isEmpty() ? "" : " Kept as you made them: " + String.join(", ", report.kept()) + ".";
        String failed = report.failed().isEmpty() ? "" : " Not migrated: " + String.join("; ", report.failed()) + ".";
        switch (draft.outcome()) {
            case Refactor.Refused refused -> status.setText(refused.reason() + kept + failed);
            case Refactor.Planned plan when write -> {
                List<String> changed = MigrationActions.changedSince(config, state, index.sources());
                if (!changed.isEmpty()) {
                    applied = false;
                    state();
                    status.setText(String.join(", ", changed) + " changed while this was worked out, so nothing"
                            + " was changed. Apply again.");
                    return;
                }
                written(plan, index);
                // What the steps are seeded from is now in the bot.
                methods = ManagedMigrations.methods(BotIndex.of(config, state), grammar);
                status.setText(plan.summary() + "." + kept + failed);
                after.run();
            }
            case Refactor.Planned plan -> status.setText(report.written().size() + " to copy." + kept + failed);
        }
    }

    /** {@code plan} as one version: a safety one first, new files created, the rest through the buffers. */
    private void written(Refactor.Planned plan, BotIndex index) {
        Checkpoints.take(config.projectPath(), state.snapshot(), VersionOrigin.SAFETY, "Before: " + plan.summary());
        plan.rewrites().forEach((file, source) -> {
            if (!index.sources().containsKey(file)) ProjectWrites.create(config, file, source, plan.summary());
        });
        BotSources.forEach(config, state, (file, source) -> plan.rewrites().get(file));
        plan.rewrites().keySet().forEach(redraw);
    }

    /** The holder of {@code to}'s open set as its plugin writes it, when the bot has none; else nothing. */
    private Map<Path, String> holderFor(BotIndex index, Class<?> to) {
        Optional<ManagedValue<?>> missing = MigrationSteps.missingHolder(index, declared, to);
        if (missing.isEmpty()) return Map.of();
        for (StudioPlugin plugin : PluginHost.plugins()) {
            List<ManagedValue<?>> own;
            try {
                own = plugin.managedValues();
            } catch (RuntimeException | LinkageError e) {
                continue;
            }
            if (own == null || own.stream().noneMatch(value -> value != null && value.id().equals(missing.get().id()))) {
                continue;
            }
            if (ManagedHolders.plan(config, plugin.id(), missing.get(), own, grammar)
                    instanceof ManagedHolders.Plan.Write holder) {
                return Map.of(holder.file(), holder.source());
            }
        }
        return Map.of();
    }

    // --- one pair's step ------------------------------------------------------------------------------------

    /** One pair's step on screen: its parts or its constants, seeded from the bot's method or a first guess. */
    private final class StepView {

        private final Class<?> source;
        private final Class<?> made;
        private final Shape shape;
        private final List<PartRow> parts = new ArrayList<>();
        private final List<ComboBox<String>> arms = new ArrayList<>();
        private final TitledPane node;

        StepView(Class<?> source, Class<?> made) {
            this.source = source;
            this.made = made;
            this.shape = MigrationSteps.shape(grammar, source, made);
            Optional<ManagedMigrations.Method> existing = methods.stream().filter(m -> m.maps(source, made)).findFirst();
            Optional<Step> saved = existing.flatMap(method -> MigrationSteps.read(grammar, method));

            VBox content = new VBox(6);
            if (existing.isPresent() && saved.isEmpty()) {
                Label note = new Label(existing.get().qualified() + " is written by hand, so it is not shown here;"
                        + " Apply replaces it with these steps.");
                note.setWrapText(true);
                content.getChildren().add(note);
            }
            switch (shape) {
                case Shape.Parts each -> content.getChildren().add(partsGrid(each, saved));
                case Shape.Constants each -> content.getChildren().add(constantsGrid(each, saved));
                case Shape.Refused refused -> content.getChildren().add(new Label(refused.reason()));
            }
            node = new TitledPane(source.getSimpleName() + " → " + made.getSimpleName(), content);
            node.setCollapsible(false);
        }

        Node node() {
            return node;
        }

        private GridPane partsGrid(Shape.Parts each, Optional<Step> saved) {
            List<Choice> seeded = saved.filter(Step.Calls.class::isInstance).map(s -> ((Step.Calls) s).choices())
                    .orElseGet(() -> MigrationSteps.matched(grammar, each));
            GridPane grid = grid();
            for (MigrationSteps.Part part : each.to()) {
                PartRow row = new PartRow(each, part, seeded.get(part.index()));
                parts.add(row);
                grid.addRow(part.index(), new Label(part.describe()), row.pick, row.editor);
            }
            return grid;
        }

        private GridPane constantsGrid(Shape.Constants each, Optional<Step> saved) {
            Map<String, String> seeded = saved.filter(Step.Arms.class::isInstance).map(s -> ((Step.Arms) s).arms())
                    .orElseGet(() -> MigrationSteps.matched(each));
            GridPane grid = grid();
            for (int i = 0; i < each.from().size(); i++) {
                ComboBox<String> pick = new ComboBox<>();
                pick.getItems().setAll(each.to());
                pick.setValue(seeded.get(each.from().get(i)));
                pick.valueProperty().addListener((o, was, now) -> rebuild());
                arms.add(pick);
                grid.addRow(i, new Label(each.from().get(i)), new Label("becomes"), pick);
            }
            return grid;
        }

        private static GridPane grid() {
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(6);
            return grid;
        }

        /** The pairs this step's choices migrate a part through. */
        List<Map.Entry<Class<?>, Class<?>>> nested() {
            return shape instanceof Shape.Parts each ? MigrationSteps.nested(each, choices(false)) : List.of();
        }

        /** Why this step cannot be written yet, or {@code null}. */
        String problem() {
            String why = switch (shape) {
                case Shape.Parts each -> MigrationSteps.problem(each, choices(true));
                case Shape.Constants each -> MigrationSteps.problem(each, armsChosen());
                case Shape.Refused refused -> refused.reason();
            };
            return why == null ? null : source.getSimpleName() + " → " + made.getSimpleName() + ": " + why;
        }

        Optional<Step> step() {
            if (problem() != null) return Optional.empty();
            return switch (shape) {
                case Shape.Parts ignored -> Optional.of(new Step.Calls(source, made, choices(true)));
                case Shape.Constants ignored -> Optional.of(new Step.Arms(source, made, armsChosen()));
                case Shape.Refused ignored -> Optional.empty();
            };
        }

        /** Each part's choice; a fixed value read from its editor only when {@code read}, else as seeded. */
        private List<Choice> choices(boolean read) {
            List<Choice> out = new ArrayList<>();
            for (PartRow row : parts) out.add(row.choice(read));
            return java.util.Collections.unmodifiableList(out);
        }

        private Map<String, String> armsChosen() {
            Map<String, String> out = new LinkedHashMap<>();
            Shape.Constants each = (Shape.Constants) shape;
            for (int i = 0; i < arms.size(); i++) out.put(each.from().get(i), arms.get(i).getValue());
            return out;
        }
    }

    /** What one part of a new type is picked from, and the editor of a fixed value when that is the pick. */
    private final class PartRow {

        /** A pick: an old part, the factory built from one, or a fixed value ({@code choice} null). */
        private record Option(Choice choice, String label) {}

        private final MigrationSteps.Part part;
        private final ComboBox<Option> pick = new ComboBox<>();
        private final StackPane editor = new StackPane();
        private Choice.Fixed seed;
        private ValueEditors.Editor fixed;

        PartRow(Shape.Parts shape, MigrationSteps.Part part, Choice seeded) {
            this.part = part;
            List<Option> options = new ArrayList<>();
            for (Choice choice : MigrationSteps.offered(grammar, shape, part.index())) {
                options.add(new Option(choice, label(shape, choice)));
            }
            Option fixedOption = new Option(null, "a fixed value");
            options.add(fixedOption);
            pick.getItems().setAll(options);
            pick.setCellFactory(list -> new OptionCell());
            pick.setButtonCell(new OptionCell());
            seed = seeded instanceof Choice.Fixed value ? value
                    : MigrationSteps.startingValue(grammar, part.type()).map(Choice.Fixed::new).orElse(null);
            pick.setValue(seeded == null || seeded instanceof Choice.Fixed ? fixedOption
                    : options.stream().filter(o -> seeded.equals(o.choice())).findFirst().orElse(fixedOption));
            showEditor();
            pick.valueProperty().addListener((o, was, now) -> {
                showEditor();
                rebuild();
            });
        }

        /** The part's own editor, seeded fully qualified so a value kept as it stands needs no import. */
        private void showEditor() {
            if (pick.getValue() == null || pick.getValue().choice() != null) {
                editor.getChildren().clear();
                fixed = null;
                return;
            }
            String source = seed == null ? null : grammar.read(part.type(), seed.value().source())
                    .flatMap(value -> grammar.initializer(part.type(), value)).map(JavaValue::source).orElse(null);
            fixed = ValueEditors.editorFor(part.type(), source, ValueEditors.Context.of(config),
                    value -> edited());
            editor.getChildren().setAll(fixed.node());
        }

        Choice choice(boolean read) {
            Option option = pick.getValue();
            if (option == null) return null;
            if (option.choice() != null) return option.choice();
            if (!read || fixed == null) return seed;
            return fixed.read().get().map(Choice.Fixed::new).orElse(null);
        }

        private static String label(Shape.Parts shape, Choice choice) {
            return switch (choice) {
                case Choice.From from -> {
                    MigrationSteps.Part old = shape.from().get(from.part());
                    yield "its " + old.describe();
                }
                case Choice.Built built -> "built from its " + shape.from().get(built.part()).describe();
                case Choice.Fixed ignored -> "a fixed value";
            };
        }

        private static final class OptionCell extends ListCell<Option> {
            @Override
            protected void updateItem(Option item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        }
    }
}
