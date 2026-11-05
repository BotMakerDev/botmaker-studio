package com.botmaker.studio.runtime;

import com.botmaker.java.grammar.ParamValues;
import com.botmaker.java.grammar.ParamsFitter;
import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.java.index.BotIndex;
import com.botmaker.studio.runtime.tryhost.TryHost;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.services.trial.TrialCaller;
import com.botmaker.studio.ui.app.reports.RunProblems;
import com.botmaker.session.launch.BackgroundLauncher;
import com.botmaker.studio.util.ClassPathManager;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.application.Platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class CodeExecutionService {

    private final DiagnosticsManager diagnosticsManager;
    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;

    private volatile Process currentRunningProcess;
    private volatile ConsoleBatcher activeConsole;
    private volatile TelemetryServer telemetryServer;
    /** From the request to the process's end, the compile included. */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    /** Stop was pressed while the run was still compiling: it ends before starting the bot. */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    /** A try runs in {@link #tries}: a stop interrupts it there rather than killing a process. */
    private volatile boolean trying;
    private WarmTries tries;
    private ConsoleBatcher tryConsole;

    /**
     * Kills the bot when this JVM exits. The bot runs as its own OS process, so it outlives the Studio unless
     * something explicitly ends it — and {@code System.exit(0)} does not: closing the window left a bot still
     * clicking away with no UI to stop it from.
     *
     * <p>{@link #close()} is the ordinary path and unregisters this. The hook exists for the paths that skip
     * it — {@code File ▸ Exit}, a crash, a kill signal — where there is no orderly close to hang the cleanup on.
     */
    private final Thread shutdownHook = new Thread(this::killAll, "bot-process-reaper");

    /**
     * What a run's JVM starts: the bot's {@code main} (no {@code title}), or a plugin's declared run
     * ({@link CoreApplicationEvents.EntryRunRequestedEvent}) with the bot's value holders as {@code args}.
     */
    public record Launch(String title, String mainClass, List<String> args) {

        public Launch {
            args = List.copyOf(args);
        }

        /** The command line's end: {@code -cp <classpath> <mainClass> <args…>}. */
        public List<String> tail(String classpath) {
            List<String> tail = new ArrayList<>(List.of("-cp", classpath, mainClass));
            tail.addAll(args);
            return tail;
        }
    }

    public CodeExecutionService(
            DiagnosticsManager diagnosticsManager,
            ProjectConfig config,
            ProjectState state,
            EventBus eventBus) {
        this.diagnosticsManager = diagnosticsManager;
        this.config = config;
        this.state = state;
        this.eventBus = eventBus;
        setupEventHandlers();
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.CompilationRequestedEvent.class,
                e -> compileCode(state.snapshot()), false);
        eventBus.subscribe(CoreApplicationEvents.ExecutionRequestedEvent.class,
                e -> runCode(state.snapshot()), false);
        eventBus.subscribe(CoreApplicationEvents.EntryRunRequestedEvent.class,
                e -> runEntry(state.snapshot(), e.title(), e.mainClass()), false);
        eventBus.subscribe(CoreApplicationEvents.TrialRunRequestedEvent.class,
                e -> tryCode(state.snapshot(), e.caller(), e.label()), false);
        eventBus.subscribe(CoreApplicationEvents.StopRunRequestedEvent.class,
                e -> stopRunningProgram(), false);
        eventBus.subscribe(CoreApplicationEvents.InputAnsweredEvent.class,
                e -> answer(e.id(), e.value()), false);
    }

    /**
     * Sends the user's answer to the running bot's question {@code id} on the run's telemetry socket; false when no
     * bot is connected. A debug session answers its own ({@code DebuggingService}), so at most one of the two holds
     * a server.
     */
    public boolean answer(long id, String value) {
        return RunTelemetry.answer(trying ? tries().telemetry() : telemetryServer, id, value);
    }

    private void status(String message) {
        Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(message)));
    }

    /**
     * Compiles and runs the project as {@code snapshot} describes it.
     *
     * <p>Takes a {@link ProjectState.Snapshot} rather than reading {@link ProjectState} itself, because
     * everything below the {@code new Thread} runs off the FX thread: see that class's note, and bugs.md B10.
     * The caller takes the snapshot on the FX thread; the run is then pinned to one revision of the project
     * for its whole life, which is also what makes "the console shows what this run compiled" true.
     */
    public void runCode(ProjectState.Snapshot snapshot) {
        // entryClassName(), not mainClassName(): the entry class is named after the project only in a
        // project Studio created and the user has not renamed. One made from a published template
        // keeps its author's class name, which is the whole point of a template arriving as shipped.
        launch(snapshot, null, null, () -> new Launch(null, config.entryClassName(), List.of()));
    }

    /**
     * Runs a plugin's declared run {@code title} as {@link #runCode} runs the bot — compile, version, run folder,
     * telemetry, the end-of-run dialog — with {@code mainClass} as the JVM's main and the bot's value holders
     * ({@link TrialCaller#holderBinaryNames}) as its arguments. Call it on the FX thread: the sources are read now.
     */
    public void runEntry(ProjectState.Snapshot snapshot, String title, String mainClass) {
        Supplier<BotIndex> index = BotSources.prepareIndex(config, state);
        launch(snapshot, null, null, () -> new Launch(title, mainClass, TrialCaller.holderBinaryNames(index.get())));
    }

    /**
     * ▶ Try: compiles the project as {@link #runCode} does, then {@code caller} beside it in
     * {@code target/botmaker-trial/} — never in the project's sources — and runs the caller as a run, with its
     * console, its trace, Stop and the run bar. No version is taken: a try is not the bot running.
     *
     * @param label what is tried, for the status line
     */
    public void tryCode(ProjectState.Snapshot snapshot, TrialCaller.Source caller, String label) {
        launch(snapshot, caller, label, null);
    }

    /** {@code run} is what a run (not a try) starts, asked for off the FX thread once the compile passed. */
    private void launch(ProjectState.Snapshot snapshot, TrialCaller.Source trial, String label,
                        Supplier<Launch> run) {
        // Pre-compile block validation: a slot still waiting on one of the user's variables (a red "Choose a
        // variable…" chip) would only surface as a raw javac error. Detect it via BlockValidator, surface it in
        // the Errors panel, and abort before compiling. Always publish (an empty list clears any previously
        // shown slot errors). Every *value* slot now seeds a compiling default, so this fires only for the
        // positions that need a name — see UnfilledSlot.
        List<org.eclipse.lsp4j.Diagnostic> emptySlotIssues = diagnosticsManager.validateBlocks();
        // Deprecated SDK calls ride along in the same panel but never gate the run: they compile and work
        // today, and the warning IS the deprecation cycle (see DiagnosticsManager.deprecationWarnings).
        List<org.eclipse.lsp4j.Diagnostic> published = new java.util.ArrayList<>(emptySlotIssues);
        published.addAll(diagnosticsManager.deprecationWarnings());
        published.addAll(diagnosticsManager.entryPointHints());
        eventBus.publish(new CoreApplicationEvents.DiagnosticsUpdatedEvent(published));
        if (!emptySlotIssues.isEmpty()) {
            status("Run aborted: choose a variable for the highlighted slot(s) — see the Errors tab.");
            return;
        }
        if (diagnosticsManager.hasErrors()) {
            status("Run aborted due to errors.");
            return;
        }
        // Claimed before the compile, not after it: a second request during the compile (a double click, an
        // assistant's second call) would otherwise compile beside the first and put two bots on the game.
        if (!isRunning.compareAndSet(false, true)) {
            status("Program is already running. Stop it first.");
            return;
        }
        cancelled.set(false);

        new Thread(() -> {
            boolean ran = false;
            Map<Path, String> paramsAtStart = Map.of();
            try {
                status("Compiling...");
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputClearedEvent()));

                if (!compileAndWait(snapshot, config.compiledOutputPath())) {
                    status((trial == null ? "Run" : "Try") + " aborted due to build failure.");
                    return;
                }
                if (cancelled.get()) {
                    status("Stopped before it started.");
                    return;
                }
                if (trial != null) {
                    Path classes = compileTrial(snapshot, trial);
                    if (classes == null) {
                        status("Try aborted: Studio's caller for it did not compile — see the Run tab.");
                        return;
                    }
                    if (cancelled.get()) {
                        status("Stopped before it started.");
                        return;
                    }
                    status("Trying " + label + "… (Press Stop to end it)");
                    trying = true;
                    eventBus.publish(new CoreApplicationEvents.ProgramStartedEvent());
                    // The bot's classes load per try, beside the caller; the warm JVM holds the dependencies.
                    int code = tries().run(tryHostCommand(snapshot), tracePlanText(), config.projectPath(),
                            List.of(classes, config.compiledOutputPath()), trial.className());
                    status(switch (code) {
                        case 0 -> "Tried " + label + ".";
                        case 130, -1 -> "Try stopped.";
                        default -> "Try failed — see the Run tab.";
                    });
                    return;
                }
                String classpath = buildRuntimeClasspath(snapshot);
                // Before the version is taken: a launch that cannot be read is a run that never started.
                Launch launch = run.get();
                // The version that ran, so "go back to when it worked" has somewhere to go.
                Checkpoints.take(config.projectPath(), VersionOrigin.AUTO, "Run");
                status((launch.title() == null ? "Running" : "Running " + launch.title())
                        + "... (Press Stop to terminate)");
                if (cancelled.get()) {
                    status("Stopped before it started.");
                    return;
                }

                // Tell UI the program has started so the Stop button becomes clickable.
                eventBus.publish(new CoreApplicationEvents.ProgramStartedEvent());

                // Run the compiled main class directly:
                // java [bot options] [session hand-off] -cp <classes:deps> <mainClass> [args]
                List<String> command = new ArrayList<>(List.of(config.javaExecutable()));
                command.addAll(BotJvm.options(state.getSettings()));
                command.addAll(BotJvm.runAgent(config, state.getSettings()));
                command.addAll(sessionHandoffArguments());
                command.addAll(launch.tail(classpath));
                ProcessBuilder pb = new ProcessBuilder(command)
                        .directory(config.projectPath().toFile());

                startTelemetry(pb);
                paramsAtStart = paramSources();
                currentRunningProcess = pb.start();
                ran = true;
                // A stop that landed between the last check and the start found no process to kill.
                if (cancelled.get()) killRunningProcess();

                ConsoleBatcher console = console();
                activeConsole = console;
                console.pump(currentRunningProcess.getInputStream(), "bot-stdout");
                console.pump(currentRunningProcess.getErrorStream(), "bot-stderr");

                int exitCode = currentRunningProcess.waitFor();

                // Drain the readers and flush anything still buffered, otherwise short-lived programs lose all
                // output that arrived in the last <100ms.
                console.finish();

                if (exitCode == 0) status("Program completed successfully.");
                else if (exitCode == 143 || exitCode == 130 || exitCode == 1 || exitCode == -1)
                    status("Program stopped.");
                else status("Program exited with code: " + exitCode);

            } catch (InterruptedException e) {
                status("Program stopped by user.");
            } catch (Exception e) {
                e.printStackTrace();
                status("Error: " + e.getMessage());
            } finally {
                trying = false;
                // This run's folder, read while no next run can have made its own.
                Path endedRun = ran ? RunProblems.latest(config.projectPath()).orElse(null) : null;
                // While this still counts as running: a next run compiles the editor's copies, values merged.
                if (ran) takeRunsValues(paramsAtStart);
                isRunning.set(false);
                currentRunningProcess = null;
                closeConsole();
                stopTelemetry();
                // Tell UI the program has finished so the Stop button disables.
                eventBus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
                if (ran) eventBus.publish(new CoreApplicationEvents.BotRunEndedEvent(endedRun));
            }
        }, "CodeRunner").start();
    }

    /**
     * Brings the during-a-run parameters the run wrote to disk into the editor's copies, whose text the next compile
     * writes back ({@code ProjectState.Snapshot.writeSources}), so that compile keeps them. A value the run did not
     * change stays as edited, in the window or the file's tab.
     */
    private void takeRunsValues(Map<Path, String> atStart) {
        Map<Path, String> written = paramSources();
        Platform.runLater(() -> written.forEach((file, text) -> state.getFile(file).ifPresent(open -> {
            String merged = ParamValues.withRunsValues(state.level(), open.getContent(), atStart.getOrDefault(file, ""), text);
            if (!merged.equals(open.getContent())) open.setContent(merged);
        })));
    }

    /** Every group file under {@code params/} as it is on disk now; empty when they cannot be read. */
    private Map<Path, String> paramSources() {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        if (!Files.isDirectory(params)) return Map.of();
        Map<Path, String> sources = new LinkedHashMap<>();
        try (var walk = Files.walk(params)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.put(file, Files.readString(file));
            }
        } catch (IOException | UncheckedIOException e) {
            warn("WARN what the run wrote to its parameters could not be read: " + e.getMessage());
            return Map.of();
        }
        return sources;
    }

    /** The project's warm try JVM, made at the first try. */
    private synchronized WarmTries tries() {
        if (tries == null) {
            // One console for the JVM's life: its pipes stay open between tries.
            ConsoleBatcher console = console();
            tryConsole = console;
            tries = new WarmTries(console::append, console::flushNow, pb -> RunTelemetry.start(eventBus, pb));
        }
        return tries;
    }

    /**
     * What starts the warm try JVM: a run's options, trace agent and session hand-off, with the dependencies and
     * the resources on its classpath but not the bot's classes, which each try loads afresh.
     */
    private List<String> tryHostCommand(ProjectState.Snapshot snapshot) throws IOException {
        StringBuilder cp = new StringBuilder(TryHostJar.path().toString());
        cp.append(java.io.File.pathSeparator).append(config.resourcesRoot().toString());
        for (String jar : snapshot.resolvedClasspath()) cp.append(java.io.File.pathSeparator).append(jar);
        List<String> command = new ArrayList<>(List.of(config.javaExecutable()));
        command.addAll(BotJvm.options(state.getSettings()));
        command.addAll(BotJvm.runAgent(config, state.getSettings()));
        command.addAll(sessionHandoffArguments());
        command.addAll(List.of("-cp", cp.toString(), TryHost.class.getName()));
        return command;
    }

    /** The run plan the agent read at the JVM's start: a JVM started with another one is started again. */
    private String tracePlanText() {
        try {
            Path plan = BotJvm.runPlan(config);
            return Files.isRegularFile(plan) ? Files.readString(plan) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The {@code -D} arguments that offer this project's live background session to the bot, or empty when none is
     * running.
     *
     * <p>Why the bot needs telling at all: it decides on its own whether to isolate, and left to itself it would
     * bring up a *second* private display and launch the game into it. Every store launcher is single-instance, so
     * that launch gets handed to the copy already running in the session Studio is showing, and the game ends up on
     * a display nobody is watching. Offered rather than imposed — the bot still declines if its own isolation
     * setting says {@code :0}, and {@code Sessions.offered()} declines if the display has gone since.
     */
    private List<String> sessionHandoffArguments() {
        try {
            return BackgroundLauncher.forProject(config.resourcesRoot()).handoffArguments();
        } catch (Exception e) {
            // Never block a run over the hand-off: without it the bot just brings up its own session, which is the
            // behaviour that existed before this existed.
            return List.of();
        }
    }

    /** Builds {@code <compiledOutput><sep><resources><sep><dep jars...>} for launching/compiling the project. */
    private String buildRuntimeClasspath(ProjectState.Snapshot snapshot) {
        StringBuilder cp = new StringBuilder(config.compiledOutputPath().toString());
        // Put src/main/resources on the classpath, mirroring Maven's resource-on-classpath semantics: it is
        // where a plugin's own data and the bot's image templates live, and a bot reading one of its own
        // resources at runtime must find it the same way it will once it is built by Maven.
        cp.append(java.io.File.pathSeparator).append(config.resourcesRoot().toString());
        for (String jar : snapshot.resolvedClasspath()) {
            cp.append(java.io.File.pathSeparator).append(jar);
        }
        return cp.toString();
    }

    /**
     * Writes {@code trial}'s caller under {@code target/botmaker-trial/src} and compiles it against the bot into
     * {@code target/botmaker-trial/classes}, javac's output in the Run tab; the classes folder, or null when it did
     * not compile. Both folders are emptied first, so nothing from an earlier try is left to run.
     */
    private Path compileTrial(ProjectState.Snapshot snapshot, TrialCaller.Source trial)
            throws IOException, InterruptedException {
        Path root = config.compiledOutputPath().resolveSibling("botmaker-trial");
        deleteTree(root);
        Path source = root.resolve("src").resolve(trial.path());
        Path classes = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, trial.text());
        List<String> command = new ArrayList<>(List.of(config.javacExecutable(), "-encoding", "UTF-8",
                "-cp", buildRuntimeClasspath(snapshot), "-d", classes.toString()));
        command.addAll(JavacRelease.args(config.projectPath()));
        command.add(source.toString());
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(config.projectPath().toFile())
                .redirectErrorStream(true);
        Process process = pb.start();
        try (ConsoleBatcher console = console()) {
            console.pump(process.getInputStream(), "javac-trial-output");
            int exitCode = process.waitFor();
            console.finish();
            return exitCode == 0 ? classes : null;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** As {@link #runCode}, minus the run: the snapshot is taken by the caller, on the FX thread. */
    public void compileCode(ProjectState.Snapshot snapshot) {
        new Thread(() -> {
            try {
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputClearedEvent()));
                status("Compiling...");
                if (compileAndWait(snapshot, config.compiledOutputPath())) {
                    status("Compilation successful.");
                }
            } catch (IOException | InterruptedException e) {
                status("Compilation Error: " + e.getMessage());
            }
        }).start();
    }

    public boolean compileAndWait(ProjectState.Snapshot snapshot, Path compiledOutputPath)
            throws IOException, InterruptedException {
        // Edited source reaches disk here and when a version is taken (Checkpoints) — the editor keeps every
        // change in memory (ProjectFile.setContent) and never writes as you type.
        //
        // It used to compare each file's locked parts against what was on disk and refuse a write that
        // changed one. Nothing generates a project's Java any more, so a project file has no locked parts:
        // every one of them is the user's, and what they typed is what gets written.
        snapshot.writeSources();
        // A private parameter file never stops a build (2026-11-04): fitted to its starting copy first, reset from it
        // when javac still refuses the file alone.
        fitParams();

        Files.createDirectories(compiledOutputPath);
        StringBuilder output = new StringBuilder();
        boolean compiled = compileSources(snapshot, compiledOutputPath, output);
        List<Path> refused = compiled ? List.of() : refusedParams(output.toString());
        if (!refused.isEmpty()) {
            Path pass = config.projectPath().resolve(VALUES_BACKUPS).resolve("refused-" + System.currentTimeMillis());
            Path params = config.parametersPackageDir().toAbsolutePath().normalize();
            for (Path file : refused) {
                Path relative = params.relativize(file);
                Path starting = config.projectPath().resolve(ParamsFitter.STARTING).resolve(relative);
                Files.createDirectories(pass.resolve(relative).getParent());
                Files.copy(file, pass.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                String reset = Files.readString(starting);
                Files.writeString(file, reset);
                Platform.runLater(() -> state.getFile(file).ifPresent(open -> open.setContent(reset)));
                warn("WARN " + config.projectPath().relativize(file) + " did not compile and was reset to its"
                        + " starting copy. A copy is in " + config.projectPath().relativize(pass) + ".");
            }
            compiled = compileSources(snapshot, compiledOutputPath, new StringBuilder());
        }
        // Only if the disk still holds what javac read: a write since would make a state nobody compiled the good one.
        if (compiled && snapshot.onDisk()) Checkpoints.recordCompiled(config.projectPath());
        return compiled;
    }

    /** Where a parameter file is copied before Studio fits or resets it: in no version, beside the plugins' state. */
    private static final String VALUES_BACKUPS = ".botmaker/values-removed";

    /**
     * Fits {@code params/} to {@code starting-params/} ({@link ParamsFitter#onDisk}), one WARN per file changed, and
     * makes the editor match: run before each compile and on open, which fills a fresh copy. Never throws: a fit
     * that fails leaves the files as they were.
     */
    public void fitParams() {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        List<ParamsFitter.Fitted> fitted;
        try {
            fitted = ParamsFitter.onDisk(state.level(), params, config.projectPath().resolve(ParamsFitter.STARTING),
                    config.projectPath().resolve(VALUES_BACKUPS));
        } catch (IOException | UncheckedIOException e) {
            warn("WARN parameters could not be fitted to their starting copy: " + e.getMessage());
            return;
        }
        if (fitted.isEmpty()) return;
        fitted.forEach(each -> warn("WARN params/" + each.sentence() + " A copy is in " + VALUES_BACKUPS + "."));
        Platform.runLater(() -> fitted.forEach(each -> {
            Path file = params.resolve(each.file());
            each.source().ifPresentOrElse(text -> state.getFile(file).ifPresentOrElse(open -> open.setContent(text),
                            () -> state.addFile(new ProjectFile(file, text))),
                    () -> state.removeFile(file));
        }));
    }

    /**
     * The params files to reset when javac's {@code output} names errors in params files alone, each with a starting
     * copy. An error anywhere else (a class mid-edit, a dependency that did not resolve) may be what the file trips
     * on, so then none is reset.
     */
    private List<Path> refusedParams(String output) {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        Path starting = config.projectPath().resolve(ParamsFitter.STARTING);
        List<Path> files = TreeCompile.errorFiles(output).stream()
                .map(file -> (file.isAbsolute() ? file : config.projectPath().resolve(file)).toAbsolutePath().normalize())
                .distinct().toList();
        boolean all = !files.isEmpty() && files.stream().allMatch(file -> file.startsWith(params)
                && Files.isRegularFile(starting.resolve(params.relativize(file))));
        return all ? files : List.of();
    }

    private void warn(String line) {
        Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(line + "\n")));
    }

    private boolean compileSources(ProjectState.Snapshot snapshot, Path compiledOutputPath, StringBuilder output)
            throws IOException, InterruptedException {

        List<String> sourceFiles = ClassPathManager.findJavaFiles(config.sourceRoot());
        if (sourceFiles.isEmpty()) {
            Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent("No source files to compile.\n")));
            return false;
        }

        List<String> command = new ArrayList<>(List.of(
                config.javacExecutable(),
                "-cp", buildRuntimeClasspath(snapshot),
                "-d", compiledOutputPath.toString()));
        command.addAll(JavacRelease.args(config.projectPath()));
        command.addAll(sourceFiles);

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(config.projectPath().toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (ConsoleBatcher console = new ConsoleBatcher(text -> {
            synchronized (output) {
                output.append(text);
            }
            eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(text));
        })) {
            console.pump(process.getInputStream(), "javac-output");
            int exitCode = process.waitFor();
            console.finish();
            return exitCode == 0;
        }
    }

    /**
     * A batcher that hands its text to the Run tab — shared by a run, a compile and a debug session, so all
     * three reach the console the same way.
     */
    public ConsoleBatcher console() {
        return new ConsoleBatcher(text -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(text)));
    }

    public void stopRunningProgram() {
        if (trying) {
            // The try's thread ends it, its events with it; the warm JVM stays unless the try ignores the stop.
            tries().stop();
            status("Stopping the try…");
            return;
        }
        // While it still compiles there is no process to kill: the run ends itself before starting one, and its
        // own finally says it stopped — resetting the claim here would let a second run start beside it.
        if (isRunning.get() && currentRunningProcess == null) {
            cancelled.set(true);
            status("Stopping before it starts…");
            return;
        }
        killRunningProcess();
        closeConsole();
        stopTelemetry();
        // Force state update immediately on hard kill
        isRunning.set(false);
        eventBus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
    }

    /**
     * Force-kills the bot process and everything it spawned. Deliberately process-only — no event publishing,
     * no UI teardown — so it is also safe to call from {@link #shutdownHook}, where the FX toolkit may already
     * be gone.
     *
     * <p>Descendants are collected <em>before</em> the parent dies: once it does, its children are reparented
     * to init and are no longer reachable through {@code descendants()}. The old order (parent first, then
     * descendants) therefore missed anything the bot had launched.
     */
    private void killRunningProcess() {
        Process process = currentRunningProcess;
        if (process == null || !process.isAlive()) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroyForcibly();
        descendants.forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * Releases the shutdown hook and kills the bot. Called by {@code BotProject.close()} when the project is
     * closed or the Studio quits — see {@link #shutdownHook} for why both paths exist.
     */
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // The hook is running (or about to); it does the same work, so there is nothing left to do here.
        }
        killAll();
        synchronized (this) {
            if (tryConsole != null) tryConsole.close();
        }
    }

    /** The bot's process and the warm try JVM: what a closing project or a quitting Studio leaves nothing of. */
    private void killAll() {
        WarmTries warm;
        synchronized (this) {
            warm = tries;
        }
        if (warm != null) warm.close();
        killRunningProcess();
    }

    /**
     * Starts the loopback telemetry server (the Trace tab, and plugins' {@code Runs} listeners) and passes its
     * port and a random token to the bot. Best-effort: if it fails to bind, the bot still runs, untraced.
     */
    private void startTelemetry(ProcessBuilder pb) {
        stopTelemetry();
        this.telemetryServer = RunTelemetry.start(eventBus, pb);
    }

    private void stopTelemetry() {
        TelemetryServer server = telemetryServer;
        if (server != null) {
            server.close();
            telemetryServer = null;
        }
    }

    private void closeConsole() {
        ConsoleBatcher console = activeConsole;
        if (console != null) {
            console.close();
            activeConsole = null;
        }
    }

    public boolean isRunning() { return isRunning.get(); }

    /**
     * The OS pid of the running bot JVM, if one is alive. The bot is launched directly as
     * {@code java -cp … <mainClass>} (no wrapper process), so the launched process <em>is</em> the bot —
     * usable for out-of-band control such as the pilot's {@code SIGSTOP}/{@code SIGCONT} pause/resume.
     */
    public java.util.OptionalLong runningBotPid() {
        if (trying) return tries().runningPid();
        Process p = currentRunningProcess;
        return (p != null && p.isAlive()) ? java.util.OptionalLong.of(p.pid()) : java.util.OptionalLong.empty();
    }
}
