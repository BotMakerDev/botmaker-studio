package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.ApplicationEvent;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The Run tab: what the running (or debugged) bot prints, and the two things one does to it — stop it, clear
 * the text.
 *
 * <p>It used to be the "Terminal" tab, a read-only {@code TextArea} built inline in {@link UIManager}. It was
 * never a terminal: the bot runs on pipes because the {@code BM-INPUT} stdin protocol
 * ({@link CoreApplicationEvents.InputRequestedEvent}) reads them line by line, so what arrives here is lines,
 * not a screen. The shell is a separate tab. Colour escapes a bot's logger writes are stripped, since a text
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

    private final EventBus eventBus;
    private final TextArea output = new TextArea();
    private final Button stop = new Button("■ Stop");
    private final Label state = new Label("Not running");
    private final BorderPane view = new BorderPane();
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>();
    private Running running = Running.NONE;

    RunConsole(EventBus eventBus, Runnable raise) {
        this.eventBus = eventBus;

        output.setEditable(false);
        output.getStyleClass().add("console-area");
        output.setContextMenu(contextMenu());

        stop.setDisable(true);
        stop.setOnAction(e -> requestStop());
        Button clear = new Button("Clear");
        clear.setOnAction(e -> output.clear());
        state.getStyleClass().add("run-console-state");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(6, state, gap, stop, clear);
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
        clear.setOnAction(e -> output.clear());
        return new ContextMenu(copy, new SeparatorMenuItem(), clear);
    }
}
