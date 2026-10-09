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
import com.botmaker.studio.plugin.grammar.JavaValue;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * ▶ Try and ▶ Run on its own, for both editors (2026-10-06): answers {@link TryRequestedEvent} and
 * {@link MethodRunRequestedEvent}, wherever they were asked from ({@link TrialMenu}).
 *
 * <p>A statement is planned ({@link TrialPlan}), its earlier locals asked about when it reads any
 * ({@link TryDialog}), its caller written ({@link TrialCaller}) with the bot's managed holders, and the run
 * requested as {@link TrialRunRequestedEvent}, which {@code CodeExecutionService} runs as it runs the bot. A refusal
 * is told in a dialog, with the plan's sentence. The assistant asks through {@link #tryWith} and {@link #runOnItsOwn},
 * which take the values the dialog would ask for and throw the refusal instead. One project's; it keeps itself
 * subscribed.
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
        try {
            Method entry = ready();
            TrialPlan.Plan plan = plan(statement);
            if (plan.needed(Map.of()).isEmpty()) {
                start(plan, Map.of(), entry);
            } else {
                TryDialog.ask(owner, plan, config).ifPresent(values -> start(plan, values, entry));
            }
        } catch (IllegalArgumentException refused) {
            refuse(owner, refused.getMessage());
        }
    }

    private void runMethod(MethodRunRequestedEvent request) {
        try {
            runOnItsOwn(request.packageName(), request.className(), request.method(), request.valued());
        } catch (IllegalArgumentException refused) {
            refuse(request.owner(), refused.getMessage());
        }
    }

    /**
     * ▶ Try {@code statement} with no dialog, for the assistant: an earlier local it reads takes its value from
     * {@code given}, by name, as text its type's grammar reads; one not given takes the source the dialog would
     * default it to. FX thread.
     *
     * @return what was started
     * @throws IllegalArgumentException with the sentence why not: something runs, the plan's refusal, a value
     *                                  that does not read, a local that can be neither found nor computed
     */
    public String tryWith(Statement statement, Map<String, String> given) {
        Method entry = ready();
        TrialPlan.Plan plan = plan(statement);
        Map<String, TrialPlan.Source> chosen = new LinkedHashMap<>();
        given.keySet().forEach(name -> chosen.put(name, TrialPlan.Source.ASK));
        List<TrialPlan.Local> needed = plan.needed(chosen);
        List<String> reads = plan.locals().stream().map(TrialPlan.Local::name).toList();
        for (String name : given.keySet()) {
            if (!reads.contains(name)) {
                throw new IllegalArgumentException(name + " is not an earlier value this statement reads"
                        + (reads.isEmpty() ? "; it reads none." : "; it reads " + reads + "."));
            }
        }
        ValueGrammar grammar = PluginHost.grammar();
        Map<String, TrialCaller.Value> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (TrialPlan.Local local : needed) {
            switch (chosen.getOrDefault(local.name(), local.defaultSource())) {
                case LAST_RUN -> values.put(local.name(), new TrialCaller.Given(local.lastRun()));
                case COMPUTE -> values.put(local.name(), new TrialCaller.Computed());
                case ASK -> {
                    String text = given.get(local.name());
                    if (text == null) {
                        missing.add(local.name() + " (" + local.type() + ")");
                        continue;
                    }
                    // Through the grammar, as a slot is set: text is read as a value, never pasted in as Java.
                    Optional<JavaValue> value = grammar.named(local.typeName())
                            .flatMap(type -> grammar.valueOf(type, text).flatMap(v -> grammar.spell(type, v)));
                    if (value.isEmpty()) {
                        throw new IllegalArgumentException("`" + text + "` is not a " + local.type()
                                + " value Studio can write, for " + local.name() + ".");
                    }
                    values.put(local.name(), new TrialCaller.Given(value.get()));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("This statement reads earlier values the last run did not leave and "
                    + "Studio cannot compute: give them in values — " + String.join(", ", missing) + ".");
        }
        start(plan, values, entry);
        return "Trying " + plan.label() + ".";
    }

    /**
     * ▶ Run {@code className.method()} on its own. FX thread.
     *
     * @throws IllegalArgumentException with the sentence why not
     */
    public String runOnItsOwn(String packageName, String className, String method, boolean valued) {
        Method entry = ready();
        TrialPlan.Plan plan = TrialPlan.call(packageName, className, method, valued);
        start(plan, Map.of(), entry);
        return "Running " + plan.label() + " on its own.";
    }

    /**
     * {@code statement}'s plan, from the tree the editors show.
     *
     * @throws IllegalArgumentException when the tree is stale, or the plan refuses
     */
    private TrialPlan.Plan plan(Statement statement) {
        // The tree the editors show is the active file's; its text is what the tree was parsed from, unless
        // something was typed since, in which case the tree no longer says where anything is.
        ProjectFile file = state.getActiveFile();
        CompilationUnit unit = statement == null ? null : (CompilationUnit) statement.getRoot();
        if (file == null || unit == null || state.getCompilationUnit().orElse(null) != unit) {
            throw new IllegalArgumentException("The file changed since this block was drawn. Try again once it has "
                    + "caught up.");
        }
        ValueGrammar grammar = PluginHost.grammar();
        List<ProbeCalls.Declared> probes = ProbeCalls.declared(PluginHost.overlayParts());
        TrialPlan.Result result = TrialPlan.plan(statement, file.getContent(),
                binding -> ProbeCalls.readsOnly(binding, probes),
                text -> grammar.valueOfAny(text).isPresent(), lastRun.reader(grammar));
        return switch (result) {
            case TrialPlan.Refused refused -> throw new IllegalArgumentException(refused.reason());
            case TrialPlan.Planned planned -> planned.plan();
        };
    }

    /**
     * The trial entry, when one is offered and nothing runs.
     *
     * @throws IllegalArgumentException saying why not
     */
    private Method ready() {
        if (running || debugging) {
            throw new IllegalArgumentException((debugging ? "The bot is being debugged." : "The bot is running.")
                    + " Stop it first, then try again.");
        }
        return TrialCaller.entry().orElseThrow(() -> new IllegalArgumentException(
                "None of this bot's plugins offers a way to try one statement, so it can only run whole."));
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
