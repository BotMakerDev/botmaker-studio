package com.botmaker.studio.ui.app.trial;

import com.botmaker.studio.events.CoreApplicationEvents.DebugSessionFinishedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.DebugSessionStartedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.DebugSnapshotEvent;
import com.botmaker.studio.events.CoreApplicationEvents.MethodRunRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ProgramStartedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.ProgramStoppedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.StatusMessageEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TrialRunRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TryRequestedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.services.overlay.ProbeCalls;
import com.botmaker.studio.services.trial.LastRunValues;
import com.botmaker.studio.services.trial.TrialCaller;
import com.botmaker.studio.services.trial.TrialPlan;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Statement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ▶ Try and ▶ Run on its own, for both editors (2026-10-06): answers {@link TryRequestedEvent} and
 * {@link MethodRunRequestedEvent}, wherever they were asked from ({@link TrialMenu}).
 *
 * <p>A statement is planned ({@link TrialPlan}), its earlier locals asked about when it reads any
 * ({@link TryDialog}), its caller written ({@link TrialCaller}) with the bot's {@code @Managed} holders, and the run
 * requested as {@link TrialRunRequestedEvent}, which {@code CodeExecutionService} runs as it runs the bot. A refusal
 * is told in a dialog, with the plan's sentence. One project's; it keeps itself subscribed.
 */
public final class Trials {

    private final EventBus bus;
    private final ProjectState state;
    private final ProjectConfig config;
    private final LastRunValues lastRun = new LastRunValues();
    /** Whether the bot, or a try, runs now: one at a time, as runs are. */
    private volatile boolean running;
    /** Whether a debug session runs the bot now. */
    private volatile boolean debugging;

    public Trials(EventBus bus, ProjectState state, ProjectConfig config) {
        this.bus = bus;
        this.state = state;
        this.config = config;
        bus.subscribe(DebugSnapshotEvent.class, e -> lastRun.remember(e.snapshot()), false);
        bus.subscribe(ProgramStartedEvent.class, e -> running = true, false);
        bus.subscribe(ProgramStoppedEvent.class, e -> running = false, false);
        // A debug session is a bot on the game too, and the one whose pauses fill "From the last run".
        bus.subscribe(DebugSessionStartedEvent.class, e -> debugging = true, false);
        bus.subscribe(DebugSessionFinishedEvent.class, e -> debugging = false, false);
        bus.subscribe(TryRequestedEvent.class, e -> tryStatement(e.statement(), e.owner()), true);
        bus.subscribe(MethodRunRequestedEvent.class, e -> runMethod(e), true);
    }

    private void tryStatement(Statement statement, Window owner) {
        Optional<Method> entry = ready(owner);
        if (entry.isEmpty()) return;
        // The tree the editors show is the active file's; its text is what the tree was parsed from, unless
        // something was typed since, in which case the tree no longer says where anything is.
        ProjectFile file = state.getActiveFile();
        CompilationUnit unit = statement == null ? null : (CompilationUnit) statement.getRoot();
        if (file == null || unit == null || state.getCompilationUnit().orElse(null) != unit) {
            refuse(owner, "The file changed since this block was drawn. Try again once it has caught up.");
            return;
        }
        ValueGrammar grammar = PluginHost.grammar();
        List<ProbeCalls.Declared> probes = ProbeCalls.declared(PluginHost.overlayParts());
        TrialPlan.Result result = TrialPlan.plan(statement, file.getContent(),
                binding -> ProbeCalls.readsOnly(binding, probes),
                text -> grammar.valueOfAny(text).isPresent(), lastRun.reader(grammar));
        switch (result) {
            case TrialPlan.Refused refused -> refuse(owner, refused.reason());
            case TrialPlan.Planned planned -> {
                TrialPlan.Plan plan = planned.plan();
                if (plan.needed(Map.of()).isEmpty()) {
                    start(plan, Map.of(), entry.get());
                } else {
                    TryDialog.ask(owner, plan, config).ifPresent(values -> start(plan, values, entry.get()));
                }
            }
        }
    }

    private void runMethod(MethodRunRequestedEvent request) {
        Optional<Method> entry = ready(request.owner());
        if (entry.isEmpty()) return;
        start(TrialPlan.call(request.packageName(), request.className(), request.method(), request.valued()),
                Map.of(), entry.get());
    }

    /** The trial entry, when one is offered and nothing runs; else empty, the user told why. */
    private Optional<Method> ready(Window owner) {
        if (running || debugging) {
            refuse(owner, (debugging ? "The bot is being debugged." : "The bot is running.")
                    + " Stop it first, then try again.");
            return Optional.empty();
        }
        Optional<Method> entry = TrialCaller.entry();
        if (entry.isEmpty()) {
            refuse(owner, "None of this bot's plugins offers a way to try one statement, so it can only run whole.");
        }
        return entry;
    }

    /**
     * Writes the caller off the FX thread — finding the bot's holders parses its sources — then asks for the run on
     * the FX thread, where the run takes its snapshot of the project.
     */
    private void start(TrialPlan.Plan plan, Map<String, TrialCaller.Value> values, Method entry) {
        Supplier<BotIndex> index = BotIndex.prepare(config, state);
        bus.publish(new StatusMessageEvent("Preparing to try " + plan.label() + "…"));
        Thread.ofVirtual().name("trial-caller").start(() -> {
            TrialCaller.Source caller;
            try {
                caller = TrialCaller.write(plan, values, entry, TrialCaller.holders(index.get()));
            } catch (RuntimeException e) {
                Platform.runLater(() -> bus.publish(new StatusMessageEvent("Couldn't try " + plan.label() + ": "
                        + e.getMessage())));
                return;
            }
            Platform.runLater(() -> bus.publish(new TrialRunRequestedEvent(caller, plan.label())));
        });
    }

    private static void refuse(Window owner, String reason) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, reason);
        alert.setTitle("Try");
        alert.setHeaderText("Can't try this");
        if (owner != null) alert.initOwner(owner);
        ThemedWindows.apply(alert);
        alert.show();
    }
}
