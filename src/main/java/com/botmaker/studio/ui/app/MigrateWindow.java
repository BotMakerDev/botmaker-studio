package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.events.CoreApplicationEvents.RevealRequestedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.migrate.ApiCache;
import com.botmaker.studio.migrate.Broken;
import com.botmaker.studio.migrate.Migration;
import com.botmaker.studio.migrate.OldElement;
import com.botmaker.studio.migrate.PluginRules;
import com.botmaker.studio.migrate.Rewrite;
import com.botmaker.studio.migrate.Rule;
import com.botmaker.studio.migrate.Sites;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.managed.MigrationSteps;
import com.botmaker.studio.project.managed.MigrationSummary;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.event.EventHandler;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * <b>Migrate</b> (2026-10-16, {@code docs/refactor/25-compatibility.md} §12): the bot carried over a plugin change
 * by its author's {@code @Migrate} rules. One row per old element the bot still uses that the change broke or a
 * rule names ({@link Broken}), each with its uses and how far its rule has got.
 *
 * <p>The old side is what the project loaded before its last change ({@link Migration#sinceLastChange}): an upgrade
 * or removal moved the jars, or a dev build was rebuilt under the same version. <b>Write rule</b> seeds the old
 * element's signature and a default body, marked to be written, and opens it in the editor — Studio never guesses
 * what replaces what. <b>Preview</b> lists each use before and after; <b>Apply</b> rewrites every use a written rule
 * covers as one version, after a safety one. <b>Remove finished rules</b> removes each rule whose element the bot no
 * longer uses. A plugin type's values are copied, not rewritten: <b>Copy values…</b> opens {@link MigrationWindow}
 * on a retired type, whose <i>Switch uses</i> and <i>Remove originals</i> follow.
 *
 * <p>Opened from Project ▸ Migrate…, the Review tab, the canvas's banner after a change broke something, and the
 * upgrade's <i>Migrate first</i>. Each read compiles the bot twice, so it runs off the FX thread; the buffers are
 * read before and written after on it.
 */
final class MigrateWindow {

    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus bus;
    private final Consumer<Path> redraw;
    private final Runnable after;
    private final Consumer<Class<?>> copyValues;

    private final Stage stage = new Stage();
    /** The project's window, brought in front when the editor is shown something. */
    private Window owner;
    private final Label heading = new Label();
    private final TableView<Broken> rows = new TableView<>();
    private final ListView<Sites.Site> uses = new ListView<>();
    private final TableView<Rewrite.Change> changes = new TableView<>();
    private final VBox values = new VBox(6);
    private final Label status = new Label();
    private final Button writeRule = new Button("Write rule");
    private final Button openRule = new Button("Open rule");
    private final Button preview = new Button("Preview");
    private final Button apply = new Button("Apply");
    private final Button removeDone = new Button("Remove finished rules…");

    /** What the last read found, and the parse it was found in: what a write is checked against. */
    private Read read;
    /** Whether a worker is out: every button waits for it. */
    private boolean working;
    /** Which read is the newest: an older one's answer is dropped. */
    private long readAsked;
    /** The element whose rule was just seeded: once read again, its rule is opened in the editor. */
    private String revealSeeded;

    private record Read(BotIndex index, Migration migration, List<Broken> broken, List<Rule> done,
                        List<MigrationSteps.Pending> pending) {}

    private MigrateWindow(ProjectConfig config, ProjectState state, EventBus bus, Consumer<Path> redraw,
                          Runnable after, Consumer<Class<?>> copyValues) {
        this.config = config;
        this.state = state;
        this.bus = bus;
        this.redraw = redraw;
        this.after = after;
        this.copyValues = copyValues;
    }

    /**
     * Opens Migrate over the project. {@code redraw} is told each file written; {@code after} runs once something
     * was; {@code copyValues} opens the value migration on a retired type.
     */
    static void open(Window owner, ProjectConfig config, ProjectState state, EventBus bus, Consumer<Path> redraw,
                     Runnable after, Consumer<Class<?>> copyValues) {
        new MigrateWindow(config, state, bus, redraw, after, copyValues).show(owner);
    }

    private void show(Window owner) {
        heading.setWrapText(true);

        TableColumn<Broken, String> element = column("Old element", b -> b.element().display());
        TableColumn<Broken, String> count = column("Uses", b -> String.valueOf(b.sites().size()));
        TableColumn<Broken, String> rule = column("Rule", MigrateWindow::ruleText);
        count.setMaxWidth(70);
        rows.getColumns().setAll(List.of(element, count, rule));
        rows.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        rows.setPlaceholder(new Label("Nothing the bot uses was broken by the last plugin change."));
        rows.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> selected(now));

        uses.setPlaceholder(new Label("Pick a row to see where the bot uses it."));
        uses.setCellFactory(list -> new SiteCell());
        uses.setOnMouseClicked(e -> {
            Sites.Site site = uses.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && site != null) goTo(site.file(), site.start());
        });
        Label usesLabel = new Label("Uses — double-click one to go to it");
        writeRule.setOnAction(e -> writeRule());
        openRule.setOnAction(e -> {
            Broken chosen = rows.getSelectionModel().getSelectedItem();
            if (chosen != null && chosen.rule() != null) reveal(chosen.rule());
        });
        HBox ruleButtons = new HBox(8, writeRule, openRule);
        VBox right = new VBox(6, usesLabel, uses, ruleButtons);
        VBox.setVgrow(uses, Priority.ALWAYS);
        SplitPane top = new SplitPane(rows, right);
        top.setDividerPositions(0.62);

        TableColumn<Rewrite.Change, String> where = column("Where",
                c -> c.site().file().getFileName() + ":" + c.site().line());
        TableColumn<Rewrite.Change, String> before = column("Before", Rewrite.Change::before);
        TableColumn<Rewrite.Change, String> made = column("After",
                c -> c.after().isEmpty() ? "(call deleted)" : c.after());
        where.setMaxWidth(160);
        changes.getColumns().setAll(List.of(where, before, made));
        changes.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        changes.setPlaceholder(new Label("Preview lists each use as it is and as its rule rewrites it."));
        changes.setPrefHeight(170);

        values.managedProperty().bind(values.visibleProperty());
        values.setVisible(false);

        status.setWrapText(true);
        status.setMinWidth(0);
        HBox.setHgrow(status, Priority.ALWAYS);
        preview.setOnAction(e -> plan(false));
        apply.setOnAction(e -> plan(true));
        removeDone.setOnAction(e -> removeDone());
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        HBox buttons = new HBox(8, status, spacer, refresh, removeDone, preview, apply, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        for (Button each : List.of(refresh, removeDone, preview, apply, close)) each.setMinWidth(Region.USE_PREF_SIZE);

        VBox root = new VBox(10, heading, top, values, changes, buttons);
        VBox.setVgrow(top, Priority.ALWAYS);
        root.setPadding(new Insets(14));

        // Not modal: Write rule opens the rule in the editor behind it, for its author to write before Apply. Not
        // owned either: an owned window stays above its owner, so Open rule and a use's double-click landed out of
        // sight (2026-10-16). It goes when the project's window does.
        this.owner = owner;
        stage.initModality(Modality.NONE);
        if (owner != null) {
            EventHandler<WindowEvent> closing = e -> stage.close();
            owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, closing);
            stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN,
                    closing));
            if (!Double.isNaN(owner.getX()) && !Double.isNaN(owner.getY())) {
                stage.setX(owner.getX() + 60);
                stage.setY(owner.getY() + 60);
            }
        }
        stage.setTitle("Migrate");
        stage.setScene(ThemedWindows.scene(root, 900, 680));
        stage.show();
        reload();
    }

    private static <T> TableColumn<T, String> column(String title, Function<T, String> text) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
        return column;
    }

    /** How far {@code broken}'s rule has got, as its row says it. */
    static String ruleText(Broken broken) {
        return switch (broken.state()) {
            case NONE -> "none — Write rule";
            case SEEDED -> broken.rule().qualified() + ", to write";
            case WRITTEN -> broken.rule().qualified();
            case REFUSED -> broken.rule().qualified() + ": " + broken.rule().problem();
        };
    }

    /** {@code N old elements …} over the rows, from what the last read found. */
    static String headingText(List<Broken> broken, boolean unchanged) {
        String since = unchanged ? "No plugin changed since the project last loaded its jars; a rule still applies"
                + " to what the bot uses now." : "Compared with the jars the project loaded before its last plugin"
                + " change.";
        if (broken.isEmpty()) return since;
        long open = broken.stream().filter(b -> b.state() == Broken.State.NONE).count();
        return broken.size() + (broken.size() == 1 ? " old element the bot uses" : " old elements the bot uses")
                + (open == 0 ? ", each with its rule." : ", " + open + " without a rule.") + " " + since
                + " Write a rule for each, then Apply: Studio rewrites every use the rule covers.";
    }

    /**
     * What the canvas's banner and the Review row say: how many old elements the bot still uses, and whether they
     * are deprecated ones a plugin ships a rule for rather than what the last plugin change broke (2026-10-28).
     */
    record Pending(int count, boolean deprecated) {

        static final Pending NONE = new Pending(0, false);

        /** {@code 3 old elements the bot uses were broken by the last plugin change…}, or their plugins' rules. */
        String text() {
            if (deprecated) {
                return count + (count == 1 ? " deprecated element the bot uses" : " deprecated elements the bot uses")
                        + " can be migrated by its plugin's own rule. Migrate rewrites each use.";
            }
            return count + (count == 1 ? " old element the bot uses was" : " old elements the bot uses were")
                    + " broken by the last plugin change. Migrate carries the bot over by your @Migrate rules.";
        }
    }

    /**
     * How many old elements {@code bot} still uses that the last plugin change broke or a rule names. With nothing
     * changed, the deprecated elements a plugin's own rule covers; {@link Pending#NONE}, with nothing parsed, when
     * no plugin on the classpath ships a rule. Off the FX thread: it may download a plugin's sources jar.
     */
    static Pending pendingSince(BotIndex bot, Path project) {
        List<String> before = ApiCache.shared().previous(project, bot.classpath());
        boolean unchanged = before.equals(bot.classpath());
        if (unchanged && !PluginRules.shared().ships(before)) return Pending.NONE;
        List<Broken> broken = Migration.of(bot, before).broken();
        // With nothing changed, only what a plugin's own rule covers: the bot's own live rules are no news.
        int count = unchanged
                ? (int) broken.stream().filter(b -> b.rule() != null && b.rule().shipped()).count()
                : broken.size();
        return count == 0 ? Pending.NONE : new Pending(count, unchanged);
    }

    /** The most uses, changes or rows the assistant's answers list one by one. */
    static final int LISTED = 40;

    /**
     * The whole of the assistant's {@code migration list}: Migrate's rows, each with its first uses and its rule,
     * the rules that name nothing, the finished rules and the values of retired types.
     */
    static String listing(Migration migration, List<Broken> broken, List<Rule> done,
                          List<MigrationSteps.Pending> pending) {
        List<String> lines = new ArrayList<>();
        String since = migration.unchanged() ? "no plugin changed since the project last loaded its jars"
                : "compared with the jars the project loaded before its last plugin change";
        if (broken.isEmpty()) lines.add("Nothing the bot uses is broken or named by a rule (" + since + ").");
        else {
            lines.add(broken.size() + (broken.size() == 1 ? " old element the bot uses" : " old elements the bot uses")
                    + " (" + since + "):");
            broken.stream().limit(LISTED).forEach(each -> lines.add("- " + each.element().display() + ": "
                    + uses(each.sites()) + "; " + toolRuleText(each)));
            if (broken.size() > LISTED) lines.add("… and " + (broken.size() - LISTED) + " more.");
        }
        List<Rule> unresolved = migration.rules().stream().filter(rule -> rule.element() == null).toList();
        if (!unresolved.isEmpty()) {
            lines.add("Rules that name no old element: " + String.join("; ", unresolved.stream()
                    .map(rule -> rule.qualified() + ": " + rule.problem()).toList()) + ".");
        }
        if (!done.isEmpty()) {
            lines.add("Finished, their element used nowhere now (remove_finished): " + String.join(", ",
                    done.stream().map(Rule::qualified).toList()) + ".");
        }
        if (!pending.isEmpty()) {
            lines.add("Values of retired types, copied rather than rewritten (copy_values):");
            pending.forEach(each -> lines.add("- " + MigrationSummary.pending(each)));
        }
        return String.join("\n", lines);
    }

    /** {@code 36 uses: Screens.java:12, Screens.java:14, …}. */
    private static String uses(List<Sites.Site> sites) {
        List<String> shown = sites.stream().limit(3).map(site -> site.file().getFileName() + ":" + site.line())
                .toList();
        return sites.size() + (sites.size() == 1 ? " use: " : " uses: ") + String.join(", ", shown)
                + (sites.size() > shown.size() ? ", …" : "");
    }

    /** {@code broken}'s rule as the assistant reads it, with what to do next. */
    static String toolRuleText(Broken broken) {
        return switch (broken.state()) {
            case NONE -> "no rule (seed)";
            case SEEDED -> "rule " + broken.rule().qualified() + " still seeded: write its body";
            case WRITTEN -> "rule " + broken.rule().qualified();
            case REFUSED -> "rule " + broken.rule().qualified() + " cannot be applied: " + broken.rule().problem();
        };
    }

    /** The row {@code element} names, as {@link OldElement#display()} says it; a refusal naming the rows when none. */
    static Broken named(List<Broken> broken, String element) {
        String wanted = element == null ? "" : element.strip();
        List<Broken> found = broken.stream().filter(b -> b.element().display().equals(wanted)).toList();
        if (found.size() == 1) return found.getFirst();
        throw new IllegalArgumentException((found.isEmpty() ? "No old element the bot uses is " : "Two old elements "
                + "are both ") + "\"" + wanted + "\". " + (broken.isEmpty() ? "Nothing is broken."
                : "migration list names them: " + String.join(", ", broken.stream().limit(LISTED)
                .map(b -> b.element().display()).toList()) + "."));
    }

    /** Each use a plan rewrites, {@code Screens.java:12: before → after}. */
    static String changesText(List<Rewrite.Change> changes) {
        List<String> lines = new ArrayList<>();
        changes.stream().limit(LISTED).forEach(change -> lines.add("- " + change.site().file().getFileName() + ":"
                + change.site().line() + ": " + change.before() + " → "
                + (change.after().isEmpty() ? "(deleted)" : change.after())));
        if (changes.size() > LISTED) lines.add("… and " + (changes.size() - LISTED) + " more.");
        return String.join("\n", lines);
    }

    // --- reading ----------------------------------------------------------------------------------------------

    /** Reads the bot again, on a worker, and shows what it found. */
    private void reload() {
        Supplier<BotIndex> index = BotIndex.prepare(config, state);
        List<ManagedValue<?>> declared = PluginHost.managedValues();
        long asked = ++readAsked;
        background("Reading what the last plugin change broke…", () -> {
            BotIndex bot = index.get();
            Migration migration = Migration.sinceLastChange(bot, config.projectPath());
            List<Broken> broken = migration.broken();
            List<MigrationSteps.Pending> pending = MigrationSteps.anyRetired(declared)
                    ? MigrationSteps.pending(bot, PluginHost.grammar(), declared) : List.of();
            return new Read(bot, migration, broken, migration.done(broken), pending);
        }, found -> {
            // A Refresh clicked while one read was out: only the newest is shown.
            if (asked != readAsked) return;
            read = found;
            Broken was = rows.getSelectionModel().getSelectedItem();
            rows.getItems().setAll(found.broken());
            changes.getItems().clear();
            heading.setText(headingText(found.broken(), found.migration().unchanged()));
            removeDone.setText(found.done().isEmpty() ? "Remove finished rules…"
                    : "Remove " + found.done().size() + (found.done().size() == 1 ? " finished rule…"
                            : " finished rules…"));
            values.getChildren().setAll(found.pending().stream().map(this::valuesRow).toList());
            values.setVisible(!found.pending().isEmpty());
            String key = revealSeeded != null ? revealSeeded : was == null ? null : was.element().key();
            found.broken().stream().filter(b -> b.element().key().equals(key)).findFirst()
                    .ifPresent(b -> rows.getSelectionModel().select(b));
            if (revealSeeded != null) {
                found.broken().stream().filter(b -> b.element().key().equals(revealSeeded) && b.rule() != null)
                        .findFirst().ifPresent(b -> reveal(b.rule()));
                revealSeeded = null;
            }
            status.setText("");
            buttons();
        });
    }

    /** {@code 312 values of ImageTemplate, which its plugin retired}, and Copy values…. */
    private HBox valuesRow(MigrationSteps.Pending pending) {
        Label label = new Label(pending.values() + (pending.values() == 1 ? " value of " : " values of ")
                + pending.from().getSimpleName() + ", which its plugin retired, are copied rather than rewritten"
                + (pending.copied() == 0 ? "." : "; " + pending.copied() + " copied."));
        label.setWrapText(true);
        label.setMinWidth(0);
        HBox.setHgrow(label, Priority.ALWAYS);
        Button copy = new Button("Copy values…");
        copy.setMinWidth(Region.USE_PREF_SIZE);
        copy.setDisable(state.isReaderMode());
        copy.setOnAction(e -> copyValues.accept(pending.from()));
        HBox row = new HBox(8, label, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void selected(Broken broken) {
        uses.getItems().setAll(broken == null ? List.of() : broken.sites());
        buttons();
    }

    /** Each button for what is on screen: nothing while a worker is out, no write in a read-only project. */
    private void buttons() {
        Broken chosen = rows.getSelectionModel().getSelectedItem();
        boolean readOnly = state.isReaderMode();
        writeRule.setDisable(working || readOnly || chosen == null || chosen.state() != Broken.State.NONE);
        openRule.setDisable(working || chosen == null || chosen.rule() == null);
        boolean ready = read != null && read.migration().rules().stream().anyMatch(Rule::ready);
        preview.setDisable(working || !ready);
        apply.setDisable(working || readOnly || !ready);
        removeDone.setDisable(working || readOnly || read == null || read.done().isEmpty());
    }

    // --- acting -----------------------------------------------------------------------------------------------

    /** Seeds the chosen element's rule into the bot's rules file, then opens it in the editor. */
    private void writeRule() {
        Broken chosen = rows.getSelectionModel().getSelectedItem();
        if (chosen == null || read == null) return;
        Read basis = read;
        Path file = rulesFile(config, basis.migration());
        background("Writing the rule's signature…",
                () -> basis.migration().seed(chosen.element(), file, config.mainPackage()),
                outcome -> {
                    if (!(outcome instanceof Refactor.Planned plan)) {
                        status.setText(((Refactor.Refused) outcome).reason());
                        return;
                    }
                    if (!wrote(plan, basis)) return;
                    revealSeeded = chosen.element().key();
                    reload();
                });
    }

    /** Where a new rule goes: beside the bot's first rule, or {@value Migration#CLASS} in the main package. */
    static Path rulesFile(ProjectConfig config, Migration migration) {
        // A plugin's rule is in its sources jar: the bot's own rules are what a seed goes beside.
        return migration.rules().stream().filter(rule -> !rule.shipped()).findFirst().map(Rule::file)
                .orElseGet(() -> config.mainPackageDir().resolve(Migration.CLASS + ".java").toAbsolutePath()
                        .normalize());
    }

    /** Preview, or with {@code write} Apply: every use a written rule covers, rewritten. */
    private void plan(boolean write) {
        if (read == null) return;
        Read basis = read;
        boolean markable = ReviewMarker.available(state);
        background(write ? "Migrating…" : "Working out the preview…", () -> basis.migration().plan(markable),
                result -> {
                    changes.getItems().setAll(result.changes());
                    String left = leftText(result.left());
                    switch (result.outcome()) {
                        case Refactor.Refused refused -> status.setText(refused.reason() + left);
                        case Refactor.Planned plan when write -> {
                            if (!wrote(plan, basis)) return;
                            status.setText(plan.summary() + "." + left);
                            after.run();
                            reload();
                        }
                        case Refactor.Planned plan -> status.setText(result.applied().size()
                                + (result.applied().size() == 1 ? " use to rewrite." : " uses to rewrite.") + left);
                    }
                });
    }

    /** {@code  Left as written: Bot.java:12 (why)…}, or {@code ""}. */
    static String leftText(List<Rewrite.Left> left) {
        if (left.isEmpty()) return "";
        List<String> shown = left.stream().limit(4).map(each -> each.site().file().getFileName() + ":"
                + each.site().line() + " (" + each.why() + ")").toList();
        return " Left as written: " + String.join("; ", shown)
                + (left.size() > shown.size() ? " and " + (left.size() - shown.size()) + " more" : "") + ".";
    }

    /** Removes each rule whose element the bot no longer uses, once the author agrees. */
    private void removeDone() {
        if (read == null || read.done().isEmpty()) return;
        Read basis = read;
        background("Checking the bot compiles without them…", () -> basis.migration().remove(basis.done()),
                removal -> {
                    if (!(removal.outcome() instanceof Refactor.Planned plan)) {
                        status.setText(((Refactor.Refused) removal.outcome()).reason());
                        return;
                    }
                    String names = String.join(", ", basis.done().stream().map(Rule::qualified).limit(8).toList())
                            + (basis.done().size() > 8 ? " and " + (basis.done().size() - 8) + " more" : "");
                    Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION, "Remove " + names
                            + "? The bot uses none of the old elements they name any more."
                            + (removal.deleted().isEmpty() ? "" : "\n\nThis deletes " + String.join(", ",
                                    removal.deleted().stream().map(p -> p.getFileName().toString()).toList())
                                    + ", which holds nothing else.")
                            + "\n\nA version of the project is saved first.");
                    confirm.initOwner(stage);
                    if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                        status.setText("Nothing was changed.");
                        return;
                    }
                    MigrationActions actions = new MigrationActions(config, state, () -> stage, redraw, after);
                    Map<Path, String> sources = MigrationActions.basis(basis.index(), plan, removal.deleted());
                    MigrationActions.Wrote wrote = actions.write(plan, Set.copyOf(removal.deleted()), sources);
                    status.setText(wrote.text());
                    if (wrote.done()) {
                        after.run();
                        reload();
                    }
                });
    }

    /**
     * Writes {@code plan} as one version after a safety one — unless a file changed since {@code basis} was read,
     * which an edit made meanwhile would lose: then nothing is written and the window reads the bot again.
     */
    private boolean wrote(Refactor.Planned plan, Read basis) {
        List<String> changed = MigrationActions.changedSince(config, state, basis.index().sources());
        if (!changed.isEmpty()) {
            status.setText(String.join(", ", changed) + " changed while this was worked out, so nothing was"
                    + " changed. Try again.");
            reload();
            return false;
        }
        MigrationWindow.written(config, state, plan, basis.index(), redraw);
        return true;
    }

    /** Opens {@code rule} in the editor, the window staying open over it. */
    private void reveal(Rule rule) {
        // A plugin's rule is in its sources jar, which the editor does not open.
        if (rule.shipped()) return;
        goTo(rule.file(), rule.start());
    }

    /** Shows {@code offset} of {@code file} in the editor, the project's window brought in front of this one. */
    private void goTo(Path file, int offset) {
        bus.publish(new RevealRequestedEvent(file, offset));
        if (owner instanceof Stage main) {
            main.setIconified(false);
            main.toFront();
            main.requestFocus();
        }
    }

    /** {@code work} on a worker, saying {@code what} meanwhile; {@code then} on the FX thread with its answer. */
    private <T> void background(String what, Supplier<T> work, Consumer<T> then) {
        working = true;
        buttons();
        status.setText(what);
        Thread worker = new Thread(() -> {
            T answer;
            try {
                answer = work.get();
            } catch (RuntimeException | LinkageError e) {
                Platform.runLater(() -> {
                    working = false;
                    buttons();
                    status.setText("It could not be worked out: " + e);
                });
                return;
            }
            Platform.runLater(() -> {
                // Cleared first: an answer that reads the bot again sends a worker of its own out, which sets it.
                working = false;
                then.accept(answer);
                buttons();
            });
        }, "migrate");
        worker.setDaemon(true);
        worker.start();
    }

    /** {@code Screens.java:12 — Screens.HOME}. */
    private static final class SiteCell extends ListCell<Sites.Site> {
        @Override
        protected void updateItem(Sites.Site site, boolean empty) {
            super.updateItem(site, empty);
            setText(empty || site == null ? null : site.file().getFileName() + ":" + site.line() + " — " + site.where());
        }
    }
}
