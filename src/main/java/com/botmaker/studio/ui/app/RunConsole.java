package com.botmaker.studio.ui.app;

import com.botmaker.core.events.ApplicationEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.core.events.EventBus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The Run tab: what the running (or debugged) bot prints, or its trace by the chips ({@link View}), and the two
 * things one does to it — stop it, clear the text.
 *
 * <p>It used to be the "Terminal" tab, a read-only {@code TextArea} built inline in {@link UIManager}. It was
 * never a terminal: the bot runs on pipes and asks its questions as dialogs
 * ({@link CoreApplicationEvents.InputRequestedEvent}), so what arrives here is lines, not a screen. The shell is a separate tab. Colour escapes a bot's logger writes are stripped, since a text
 * area would print them as {@code [32m}.
 */
final class RunConsole {

    /** Past this many characters the console keeps only its last {@link #KEEP} — a long run must not grow forever. */
    static final int LIMIT = 10_000;
    static final int KEEP = 5_000;
    static final String TRIMMED = "[...Trimmed...]\n";

    /** CSI sequences (colours, cursor moves) and OSC titles: what a terminal consumes and a text area would print. */
    private static final Pattern ESCAPES = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)");

    private enum Running { NONE, RUN, DEBUG }

    /**
     * The chips: what the tab lists. The Trace tab was a tab of its own until 2026-11-13; it is this console's
     * second and third views now, so one tab holds everything a run says.
     */
    enum View {
        /** Everything the bot prints, its own {@code System.out} included. */
        OUTPUT("Output", "Everything the bot prints"),
        /** Its trace lines, each linked to the block that wrote it — see {@link TracePanel}. */
        TRACE("Trace", "The bot's trace: each line says what wrote it, and a click shows that block"),
        /** The trace's warnings and errors only. */
        ONLY_WARNINGS("Only warnings", "The trace's warnings and errors only");

        private final String displayName;
        private final String tooltip;

        View(String displayName, String tooltip) {
            this.displayName = displayName;
            this.tooltip = tooltip;
        }

        String displayName() {
            return displayName;
        }

        String tooltip() {
            return tooltip;
        }
    }

    private final EventBus eventBus;
    private final TextArea output = new TextArea();
    private final Button stop = new Button("■ Stop");
    private final Label state = new Label("Not running");
    private final BorderPane view = new BorderPane();
    private final Map<View, ToggleButton> chips = new EnumMap<>(View.class);
    private final ToggleGroup group = new ToggleGroup();
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    private Running running = Running.NONE;
    /** The project's trace, once {@link #trace} has been given it; {@code null} before. */
    private TracePanel trace;

    RunConsole(EventBus eventBus, Runnable raise) {
        this.eventBus = eventBus;

        output.setEditable(false);
        output.getStyleClass().add("console-area");
        output.setContextMenu(contextMenu());

        stop.setDisable(true);
        stop.setOnAction(e -> requestStop());
        Button clear = new Button("Clear");
        clear.setOnAction(e -> clear());
        state.getStyleClass().add("run-console-state");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox chipRow = new HBox(4);
        for (View v : View.values()) {
            ToggleButton chip = new ToggleButton(v.displayName());
            chip.setTooltip(new Tooltip(v.tooltip()));
            chip.setToggleGroup(group);
            chip.getStyleClass().add("run-console-chip");
            chip.setUserData(v);
            // No trace until a project hands one in (trace(...)): a chip that cannot show its lines is off.
            chip.setDisable(v != View.OUTPUT);
            chips.put(v, chip);
            chipRow.getChildren().add(chip);
        }
        // A chip group always has one chip on: pressing the one that is on keeps it on.
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                group.selectToggle(was);
                return;
            }
            showView((View) now.getUserData());
        });
        chips.get(View.OUTPUT).setSelected(true);
        HBox bar = new HBox(6, state, chipRow, gap, stop, clear);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 6, 2, 6));
        bar.getStyleClass().add("run-console-bar");

        view.setTop(bar);
        view.setCenter(output);

        on(CoreApplicationEvents.OutputAppendedEvent.class, e -> append(e.text()));
        on(CoreApplicationEvents.OutputClearedEvent.class, e -> output.clear());
        on(CoreApplicationEvents.ProgramStartedEvent.class, e -> {
            running(Running.RUN);
            raise.run();
        });
        on(CoreApplicationEvents.ProgramStoppedEvent.class, e -> running(Running.NONE));
        on(CoreApplicationEvents.DebugSessionStartedEvent.class, e -> {
            running(Running.DEBUG);
            raise.run();
        });
        on(CoreApplicationEvents.DebugSessionFinishedEvent.class, e -> running(Running.NONE));
    }

    Node node() {
        return view;
    }

    /**
     * The project's trace, shown by the Trace and Only warnings chips. The console outlives a project's panels
     * (it is built with the window), so each project hands its own in.
     */
    void trace(TracePanel panel) {
        trace = panel;
        chips.forEach((v, chip) -> chip.setDisable(v != View.OUTPUT && panel == null));
        if (panel == null) show(View.OUTPUT);
        showView(shown());
    }

    /** The chip that is on; the group always has one (see the constructor). */
    View shown() {
        return (View) group.getSelectedToggle().getUserData();
    }

    /** Turns {@code chip} on, as a click on it would. */
    void show(View chip) {
        chips.get(chip).setSelected(true);
    }

    private void showView(View chosen) {
        if (chosen == View.OUTPUT || trace == null) {
            view.setCenter(output);
            return;
        }
        trace.onlyWarnings(chosen == View.ONLY_WARNINGS);
        view.setCenter(trace.node());
    }

    /** <i>Clear</i>: what is shown — the output, or the trace's list as its own Clear did. */
    private void clear() {
        if (shown() == View.OUTPUT || trace == null) output.clear();
        else trace.clear();
    }

    /** Drops this console's handlers from the project's bus: the next window builds its own. */
    void dispose() {
        subscriptions.forEach(EventBus.Subscription::close);
        subscriptions.clear();
    }

    String text() {
        return output.getText();
    }

    boolean canStop() {
        return !stop.isDisable();
    }

    void append(String text) {
        String clean = stripEscapes(text);
        // getLength(), not getText().length(): the latter copies the whole buffer to measure it, once per line.
        if (output.getLength() > LIMIT) {
            String current = output.getText();
            output.setText(TRIMMED + current.substring(current.length() - KEEP) + clean);
            output.positionCaret(output.getLength());
        } else {
            output.appendText(clean);
        }
    }

    static String stripEscapes(String text) {
        return text.indexOf('\u001B') < 0 ? text : ESCAPES.matcher(text).replaceAll("");
    }

    private void requestStop() {
        switch (running) {
            case RUN -> eventBus.publish(new CoreApplicationEvents.StopRunRequestedEvent());
            case DEBUG -> eventBus.publish(new CoreApplicationEvents.DebugStopRequestedEvent());
            case NONE -> { }
        }
    }

    private void running(Running now) {
        running = now;
        stop.setDisable(now == Running.NONE);
        state.setText(switch (now) {
            case RUN -> "Running";
            case DEBUG -> "Debugging";
            case NONE -> "Not running";
        });
    }

    private <T extends ApplicationEvent> void on(Class<T> type, Consumer<T> handler) {
        subscriptions.add(eventBus.subscribe(type, handler, true));
    }

    private ContextMenu contextMenu() {
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> output.copy());
        MenuItem clear = new MenuItem("Clear");
        clear.setOnAction(e -> clear());
        return new ContextMenu(copy, new SeparatorMenuItem(), clear);
    }
}
