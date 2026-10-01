package com.botmaker.studio.runtime;

import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.session.launch.BackgroundLauncher;
import com.botmaker.studio.util.ClassPathManager;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class CodeExecutionService {

    private final DiagnosticsManager diagnosticsManager;
    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;

    private volatile Process currentRunningProcess;
    private volatile ConsoleBatcher activeConsole;
    private volatile TelemetryServer telemetryServer;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Kills the bot when this JVM exits. The bot runs as its own OS process, so it outlives the Studio unless
     * something explicitly ends it — and {@code System.exit(0)} does not: closing the window left a bot still
     * clicking away with no UI to stop it from.
     *
     * <p>{@link #close()} is the ordinary path and unregisters this. The hook exists for the paths that skip
     * it — {@code File ▸ Exit}, a crash, a kill signal — where there is no orderly close to hang the cleanup on.
     */
    private final Thread shutdownHook = new Thread(this::killRunningProcess, "bot-process-reaper");

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
        return RunTelemetry.answer(telemetryServer, id, value);
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
        eventBus.publish(new CoreApplicationEvents.DiagnosticsUpdatedEvent(published));
        if (!emptySlotIssues.isEmpty()) {
            status("Run aborted: choose a variable for the highlighted slot(s) — see the Errors tab.");
            return;
        }
        if (diagnosticsManager.hasErrors()) {
            status("Run aborted due to errors.");
            return;
        }
        if (isRunning.get()) {
            status("Program is already running. Stop it first.");
            return;
        }

        new Thread(() -> {
            try {
                status("Compiling...");
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputClearedEvent()));

                if (!compileAndWait(snapshot, config.compiledOutputPath())) {
                    status("Run aborted due to build failure.");
                    return;
                }
                // The version that ran, so "go back to when it worked" has somewhere to go.
                Checkpoints.take(config.projectPath(), VersionOrigin.AUTO, "Run");

                status("Running... (Press Stop to terminate)");
                isRunning.set(true);

                // Tell UI the program has started so the Stop button becomes clickable.
                eventBus.publish(new CoreApplicationEvents.ProgramStartedEvent());

                // Run the compiled main class directly:
                // java [bot options] [session hand-off] -cp <classes:deps> <mainClass>
                List<String> command = new ArrayList<>(List.of(config.javaExecutable()));
                command.addAll(BotJvm.options(state.getSettings()));
                command.addAll(BotJvm.traceAgent(config, state.getSettings()));
                command.addAll(sessionHandoffArguments());
                // entryClassName(), not mainClassName(): the entry class is named after the project only in a
                // project Studio created and the user has not renamed. One made from a published template
                // keeps its author's class name, which is the whole point of a template arriving as shipped.
                command.addAll(List.of("-cp", buildRuntimeClasspath(snapshot), config.entryClassName()));
                ProcessBuilder pb = new ProcessBuilder(command)
                        .directory(config.projectPath().toFile());

                startTelemetry(pb);
                currentRunningProcess = pb.start();

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
                isRunning.set(false);
                currentRunningProcess = null;
                closeConsole();
                stopTelemetry();
                // Tell UI the program has finished so the Stop button disables.
                eventBus.publish(new CoreApplicationEvents.ProgramStoppedEvent());
            }
        }, "CodeRunner").start();
    }

    /**
     * The {@code -D} arguments that offer this project's live background session to the bot, or empty when none is
     * running.
     *
     * <p>Why the bot needs telling at all: it decides on its own whether to isolate, and left to itself it would
     * bring up a *second* private display and launch the game into it. Every store launcher is single-instance, so
     * that launch gets handed to the copy already running in the session Studio is showing, and the game ends up on
     * a display nobody is watching. Offered rather than imposed — the bot still declines if its own isolation
     * setting says {@code :0}, and {@code AdoptedSession} declines if the display has gone since.
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

        Files.createDirectories(compiledOutputPath);
        return compileSources(snapshot, compiledOutputPath);
    }

    private boolean compileSources(ProjectState.Snapshot snapshot, Path compiledOutputPath)
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
        command.addAll(sourceFiles);

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(config.projectPath().toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (ConsoleBatcher console = console()) {
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
        Process p = currentRunningProcess;
        return (p != null && p.isAlive()) ? java.util.OptionalLong.of(p.pid()) : java.util.OptionalLong.empty();
    }
}
