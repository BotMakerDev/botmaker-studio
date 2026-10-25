package com.botmaker.studio.ui.app.loading;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.studio.project.PomInfo;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.studio.services.OpenStep;
import com.botmaker.studio.services.ProgressReporter;
import com.botmaker.studio.sharing.UnpackedBots;
import com.botmaker.studio.ui.app.botinfo.BotIconView;
import com.botmaker.studio.ui.app.home.MyBots;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The page a bot opens behind (2026-10-25, run-mode spec § 1b, layout B): the bot on the left, its open's steps as a
 * checklist on the right. A finished step has a tick and the time it took, the current one its own bar, a step not
 * reached yet is grey; a failed step says why in place, with <i>Try again</i>, <i>Back to My bots</i> and
 * <i>Details</i>. Every method but {@link #reporter} on the FX thread.
 */
public final class LoadingPage {

    /** What the page's left side says about the bot: "by <owner> · <version>", or "mine". */
    public record Bot(String name, String who, Optional<String> description, Optional<Path> dir) {

        /** A bot known by its name only, while its folder is read. */
        public static Bot named(String name, Optional<Path> dir) {
            return new Bot(name, "", Optional.empty(), dir);
        }

        /** What {@code dir}'s folder says. Blocking: a repository is opened, a pom or a {@code bot.json} read. */
        public static Bot of(Path dir) {
            MyBots.Facts facts = MyBots.read(dir, Map.of());
            String by = facts.originalUrl().flatMap(SyncModel::slug).map(s -> "by " + s.owner()).orElse("mine");
            return new Bot(facts.title().orElse(UnpackedBots.nameOf(dir)),
                    facts.version().map(v -> by + " · " + v).orElse(by),
                    facts.unpacked() ? Optional.empty() : PomInfo.read(dir).description(), Optional.of(dir));
        }
    }

    /**
     * A bot made on the page before it opens: ＋ New bot's copy ({@link OpenStep#COPYING}) or Get's download
     * ({@link OpenStep#DOWNLOADING}). {@code work} runs off the FX thread and gives the folder to open, in the Runner
     * when {@code run}, else in the editor as a bot just made.
     */
    public record Making(Bot bot, OpenStep first, Work work, boolean run) {}

    /** The making itself; it reports on the page's first step. */
    @FunctionalInterface
    public interface Work {
        Path make(ProgressReporter progress) throws Exception;
    }

    /** Where a step stands. */
    public enum State { WAITING, CURRENT, DONE, FAILED }

    private static final double ICON_SIZE = 96;

    private final Map<OpenStep, Row> rows = new EnumMap<>(OpenStep.class);
    private final List<OpenStep> steps;
    private final VBox botSide = new VBox(8);
    private final StackPane root;
    private OpenStep current;
    private long startedAt;

    public LoadingPage(Bot bot, List<OpenStep> steps) {
        this.steps = List.copyOf(steps);
        VBox stepSide = new VBox(10);
        for (OpenStep step : this.steps) {
            Row row = new Row(step);
            rows.put(step, row);
            stepSide.getChildren().add(row.box);
        }
        stepSide.setPrefWidth(340);
        botSide.setPrefWidth(240);
        botSide.setAlignment(Pos.TOP_LEFT);
        describe(bot);
        HBox page = new HBox(24, botSide, new Separator(Orientation.VERTICAL), stepSide);
        page.setMaxWidth(Region.USE_PREF_SIZE);
        page.setMaxHeight(Region.USE_PREF_SIZE);
        page.setPadding(new Insets(24));
        root = new StackPane(page);
        root.getStyleClass().add("loading-page");
    }

    /** The page as a node, for a test or a pane of its own. */
    public Parent root() {
        return root;
    }

    /**
     * A new scene over the page, unsized so it keeps the shell's geometry, for the shell to show: the shell moves its
     * root into the one scene it keeps ({@code StudioWindow.showOnShell}), so a page shown again needs a new one. The
     * page must not be the root of a scene shown now.
     */
    public Scene scene() {
        return ThemedWindows.scene(root);
    }

    /** Says {@code bot} on the left: its icon, name, who and which version, and its description. */
    public void describe(Bot bot) {
        Node icon = bot.dir().map(d -> BotIconView.local(bot.name(), d, ICON_SIZE))
                .orElseGet(() -> BotIconView.letter(bot.name(), ICON_SIZE));
        Label name = new Label(bot.name());
        name.getStyleClass().add("loading-name");
        name.setWrapText(true);
        botSide.getChildren().setAll(icon, name);
        if (!bot.who().isEmpty()) botSide.getChildren().add(note(bot.who()));
        bot.description().filter(d -> !d.isBlank()).ifPresent(d -> botSide.getChildren().add(note(d)));
    }

    /**
     * {@code step} starts: the one under way is done, with its time, and so is every step before {@code step} the
     * open passed over. A step not on the page is ignored.
     */
    public void step(OpenStep step) {
        if (!rows.containsKey(step) || step == current) return;
        long now = System.nanoTime();
        for (OpenStep s : steps) {
            if (s == step) break;
            Row row = rows.get(s);
            if (row.state == State.DONE) continue;
            row.done(s == current ? took(now - startedAt) : "");
        }
        current = step;
        startedAt = now;
        rows.get(step).start();
    }

    /** The current step's bar at {@code fraction} (negative: no percentage), and {@code detail} under it. */
    public void progress(double fraction, String detail) {
        if (current == null) return;
        rows.get(current).progress(fraction, detail);
    }

    /** Every step done: the page is about to give way to the bot's window. */
    public void finish() {
        long now = System.nanoTime();
        for (OpenStep s : steps) {
            Row row = rows.get(s);
            if (row.state != State.DONE) row.done(s == current ? took(now - startedAt) : "");
        }
        current = null;
    }

    /**
     * The step under way failed: {@code sentence} in its place, <i>Try again</i> ({@code retry}; none when null, for
     * a failure another try cannot mend), <i>Back to My bots</i> ({@code back}) and, when there is a {@code cause},
     * <i>Details</i> with its stack trace.
     */
    public void fail(String sentence, Throwable cause, Runnable retry, Runnable back) {
        OpenStep failed = current != null ? current
                : steps.stream().filter(s -> rows.get(s).state != State.DONE).findFirst().orElse(steps.getLast());
        rows.get(failed).fail(sentence, cause, retry, back);
        current = null;
    }

    /** Where {@code step} stands; {@link State#WAITING} for one not on the page. */
    public State state(OpenStep step) {
        Row row = rows.get(step);
        return row == null ? State.WAITING : row.state;
    }

    /**
     * A reporter for the open's thread: each step and percentage reaches the page on the FX thread, the percentages
     * coalesced so a download's thousands of reports cost one update per pulse. A report carries the step it was made
     * under, and waits for that step's own update when an earlier step's is still queued: it never lands on the step
     * before.
     */
    public ProgressReporter reporter() {
        AtomicReference<Pending> pending = new AtomicReference<>();
        // The step the open's thread last reported; null until it reports one (a making's first is set on FX).
        AtomicReference<OpenStep> threadStep = new AtomicReference<>();
        Runnable flush = () -> {
            Pending p = pending.get();
            // A step the page has no row for waits for nothing.
            if (p == null || p.step() != null && rows.containsKey(p.step()) && p.step() != current) return;
            if (pending.compareAndSet(p, null)) progress(p.fraction(), p.detail());
        };
        return new ProgressReporter() {
            @Override
            public void report(double fraction, String message) {
                if (pending.getAndSet(new Pending(threadStep.get(), fraction, message)) == null) {
                    Platform.runLater(flush);
                }
            }

            @Override
            public void step(OpenStep step) {
                threadStep.set(step);
                Platform.runLater(() -> {
                    LoadingPage.this.step(step);
                    flush.run();
                });
            }
        };
    }

    /** A report not shown yet, and the step it belongs to. */
    private record Pending(OpenStep step, double fraction, String detail) {}

    /** A step's time: "0.4 s", "12 s", "1 min 5 s". */
    static String took(long nanos) {
        double seconds = nanos / 1e9;
        if (seconds < 10) return String.format(Locale.ROOT, "%.1f s", seconds);
        long whole = Math.round(seconds);
        return whole < 60 ? whole + " s" : whole / 60 + " min " + whole % 60 + " s";
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("gallery-card-note");
        label.setWrapText(true);
        return label;
    }

    /** One step: its mark, name and time, then its bar and line while current, or its failure. */
    private static final class Row {
        final VBox box = new VBox(4);
        final Label mark = new Label("○");
        final Label time = new Label();
        final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        final Label detail = new Label();
        final VBox under = new VBox(4, bar, detail);
        State state = State.WAITING;

        Row(OpenStep step) {
            Label name = new Label(step.displayName());
            mark.getStyleClass().add("loading-mark");
            time.getStyleClass().add("gallery-card-note");
            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);
            HBox line = new HBox(8, mark, name, push, time);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("loading-step-line");
            bar.setMaxWidth(Double.MAX_VALUE);
            detail.getStyleClass().add("gallery-card-note");
            detail.setWrapText(true);
            under.setPadding(new Insets(0, 0, 0, 24));
            under.managedProperty().bind(under.visibleProperty());
            under.setVisible(false);
            box.getChildren().addAll(line, under);
            box.getStyleClass().addAll("loading-step", "loading-step-waiting");
        }

        void start() {
            state = State.CURRENT;
            mark.setText("●");
            restyle("loading-step-current");
            bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            detail.setText("");
            under.setVisible(true);
        }

        void progress(double fraction, String text) {
            bar.setProgress(fraction >= 0 ? fraction : ProgressBar.INDETERMINATE_PROGRESS);
            String line = text == null ? "" : text;
            detail.setText(fraction >= 0 && !line.isEmpty() ? line + " — " + Math.round(fraction * 100) + "%" : line);
        }

        void done(String took) {
            state = State.DONE;
            mark.setText("✓");
            time.setText(took);
            restyle("loading-step-done");
            under.setVisible(false);
        }

        void fail(String sentence, Throwable cause, Runnable retry, Runnable back) {
            state = State.FAILED;
            mark.setText("✕");
            restyle("loading-step-failed");
            Label why = new Label(sentence);
            why.getStyleClass().add("np-error");
            why.setWrapText(true);
            Button home = new Button("Back to My bots");
            home.setOnAction(e -> back.run());
            HBox buttons = new HBox(6, home);
            if (retry != null) {
                Button again = new Button("Try again");
                again.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
                again.setOnAction(e -> retry.run());
                buttons.getChildren().addFirst(again);
            }
            buttons.setAlignment(Pos.CENTER_LEFT);
            under.getChildren().setAll(why, buttons);
            if (cause != null) {
                TextArea trace = new TextArea(stackTrace(cause));
                trace.setEditable(false);
                trace.setPrefRowCount(10);
                trace.getStyleClass().add("loading-trace");
                trace.managedProperty().bind(trace.visibleProperty());
                trace.setVisible(false);
                Button details = new Button("Details");
                details.setOnAction(e -> trace.setVisible(!trace.isVisible()));
                buttons.getChildren().add(details);
                under.getChildren().add(trace);
            }
            under.setVisible(true);
        }

        private void restyle(String style) {
            box.getStyleClass().removeAll("loading-step-waiting", "loading-step-current", "loading-step-done",
                    "loading-step-failed");
            box.getStyleClass().add(style);
        }

        private static String stackTrace(Throwable cause) {
            StringWriter out = new StringWriter();
            cause.printStackTrace(new PrintWriter(out));
            return out.toString();
        }
    }
}
