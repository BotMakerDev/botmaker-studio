package com.botmaker.studio.services;

import com.botmaker.shared.ipc.IpcEnv;
import com.botmaker.shared.ipc.TelemetryServer;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.runtime.BotJvm;
import com.botmaker.studio.runtime.CodeExecutionService;
import com.botmaker.studio.runtime.ConsoleBatcher;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.vcs.Checkpoints;
import com.botmaker.studio.project.vcs.VersionOrigin;
import com.botmaker.studio.services.debug.DebugSnapshot;
import com.botmaker.studio.services.debug.DebugTargets;
import com.botmaker.studio.services.debug.FollowPacer;
import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import javafx.application.Platform;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the entire debugging lifecycle:
 * 1. Mapping every source file's statements to line numbers ({@link DebugTargets}).
 * 2. Launching the JVM in debug mode.
 * 3. Attaching via JDI (Java Debug Interface).
 * 4. Managing Breakpoints, Stepping, and Resuming.
 *
 * <p>A stop reaches the editor as a <em>file and a line</em>, never as a block: the block is looked up on the FX
 * thread, in whatever the canvas shows at that moment. Blocks resolved once at the start went stale the first
 * time the file was re-drawn, and a highlight on a detached block is a highlight nobody sees.
 */
public class DebuggingService {

    // Console Coloring for internal logs
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BLUE = "\u001B[34m";

    /**
     * Follow switches the canvas to another file only once the bot has spent a whole window there, and not
     * more often than this: a bot bouncing between two files must not make the whole canvas flash.
     */
    private static final long FILE_SWITCH_GAP_MS = 1_500;

    private final ProjectState state;
    private final EventBus eventBus;
    private final CodeExecutionService codeExecutionService;
    private final ProjectConfig config;

    // Debug Session State
    private volatile Process currentProcess;
    private volatile VirtualMachine vm;
    private volatile ThreadReference currentDebugThread;
    private volatile DebugTargets targets;
    private volatile TelemetryServer telemetryServer;
    private volatile ConsoleBatcher console;

    // "Follow" (trace) mode: attach like debug but auto-resume past every block, highlighting each live.
    private volatile boolean traceMode;
    private final FollowPacer pacer = new FollowPacer();
    private ScheduledExecutorService followTicker;

    /**
     * Which session is live. Anything queued for the FX thread carries the number it was queued under and is
     * dropped when that session has ended, so a highlight posted a moment before Stop cannot land after it.
     */
    private final AtomicInteger session = new AtomicInteger();
    private final AtomicBoolean finished = new AtomicBoolean(true);

    // FX-confined: the last file Follow switched to, and when.
    private long lastFileSwitch;
    // FX-confined: the line index of the map last looked up, rebuilt when the canvas is re-drawn.
    private Map<ASTNode, CodeBlock> indexedMap;
    private Map<Integer, CodeBlock> lineIndex = Map.of();

    public DebuggingService(
            ProjectState state,
            EventBus eventBus,
            CodeExecutionService codeExecutionService,
            ProjectConfig config) {
        this.state = state;
        this.eventBus = eventBus;
        this.codeExecutionService = codeExecutionService;
        this.config = config;

        setupEventHandlers();
    }

    private void setupEventHandlers() {
        eventBus.subscribe(CoreApplicationEvents.DebugControlRequest.class, e -> {
            switch (e) {
                case CoreApplicationEvents.DebugStartRequestedEvent ignored -> startDebugging(false);
                case CoreApplicationEvents.FollowStartRequestedEvent ignored -> startDebugging(true);
                case CoreApplicationEvents.DebugStepOverRequestedEvent ignored -> stepOver();
                case CoreApplicationEvents.DebugContinueRequestedEvent ignored -> continueExecution();
                case CoreApplicationEvents.DebugStopRequestedEvent ignored -> stopDebugging();
            }
        }, false);
        eventBus.subscribe(CoreApplicationEvents.SendInputEvent.class, e -> sendInput(e.text()), false);
    }

    /** Writes a line to the debuggee's stdin (used by the input popup) and echoes it to the console. */
    public void sendInput(String line) {
        Process process = currentProcess;
        if (process == null || !process.isAlive()) return;
        try {
            java.io.OutputStream stdin = process.getOutputStream();
            stdin.write((line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
            Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(line + "\n")));
        } catch (IOException ignored) {}
    }

    /**
     * Kicks off a debug ({@code trace=false}) or follow/trace ({@code trace=true}) session on a
     * separate thread. In trace mode user breakpoints are ignored and every statement line is observed
     * and immediately resumed, so execution is followed live without ever pausing.
     */
    public void startDebugging(boolean trace) {
        if (!finished.get()) {
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("A debug session is already running. Stop it first."));
            return;
        }
        this.traceMode = trace;
        finished.set(false);
        int id = session.incrementAndGet();
        // Read the project as one value, here, on the FX thread — the debug thread must not touch ProjectState
        // (see its javadoc, and bugs.md B10). Everything below maps breakpoints from this revision's sources
        // onto the classes this revision compiled to, which is only true if they are the same revision.
        ProjectState.Snapshot snapshot = state.snapshot();
        Map<Path, Set<String>> breakpoints = state.breakpoints();
        new Thread(() -> {
            try {
                // 1. Compile
                if (!codeExecutionService.compileAndWait(snapshot, config.compiledOutputPath())) {
                    eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debug aborted due to compilation failure."));
                    finished.set(true);
                    return;
                }
                Checkpoints.take(config.projectPath(), VersionOrigin.AUTO, trace ? "Trace" : "Debug");

                // 2. Where the session can stop, file by file.
                DebugTargets found = DebugTargets.of(config.sourceRoot(), snapshot.files(), breakpoints);
                this.targets = found;
                Path entry = config.entrySourceFile();
                Integer pauseAtStart = null;
                if (!trace && !found.anyBreakpoint()) {
                    // No breakpoints: pause at the start, so the session does not just run to the end.
                    pauseAtStart = found.firstLine(entry).orElse(null);
                    if (pauseAtStart != null) {
                        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                                "No breakpoints set. Pausing at start (Line " + pauseAtStart + ")."));
                    }
                }

                // 3. Find Free Port
                int freePort;
                try (ServerSocket socket = new ServerSocket(0)) {
                    freePort = socket.getLocalPort();
                }

                // 4. Launch Target Process
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Starting debugger on port " + freePort + "..."));
                eventBus.publish(new CoreApplicationEvents.DebugSessionStartedEvent());
                Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputClearedEvent()));

                // The bot's entry class — found rather than derived, so a renamed one or a template's own is
                // debugged rather than a name that no longer exists. See ProjectConfig.entrySourceFile().
                String className = config.entryClassName();
                String javaExecutable = config.javaExecutable();

                // Suspend=y waits for us to attach before running main()
                String debugAgent = String.format("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=%d", freePort);
                StringBuilder fullClassPath = new StringBuilder();
                fullClassPath.append(config.compiledOutputPath().toString());

                // src/main/resources on the classpath, as CodeExecutionService does and for the same reason:
                // a bot reads its own resources — a plugin's data, its image templates — from there.
                fullClassPath.append(java.io.File.pathSeparator).append(config.resourcesRoot().toString());

                // Add all resolved dependency JARs — from the same snapshot the code was compiled from
                for (String jarPath : snapshot.resolvedClasspath()) {
                    fullClassPath.append(java.io.File.pathSeparator).append(jarPath);
                }

                // Use the full classpath here. Run from the project root (like CodeExecutionService) so the
                // bot's relative resource paths — e.g. src/main/resources/images/*.png passed to OpenCV
                // imread — resolve against the bot project, not Studio's working directory.
                List<String> command = new ArrayList<>(List.of(javaExecutable));
                command.addAll(BotJvm.OPTIONS);
                command.addAll(List.of(debugAgent, "-cp", fullClassPath.toString(), className));
                ProcessBuilder pb = new ProcessBuilder(command).directory(config.projectPath().toFile());
                startTelemetry(pb);
                this.currentProcess = pb.start();

                // Output reaches the Run tab through the same batcher a run uses: one flush per 100 ms however
                // fast the bot prints. One runLater per line is what froze the window under Follow.
                ConsoleBatcher batcher = codeExecutionService.console();
                this.console = batcher;
                batcher.pump(currentProcess.getInputStream(), true, "debuggee-stdout");
                batcher.pump(currentProcess.getErrorStream(), false, "debuggee-stderr");

                if (trace) startFollowTicker(id);

                // 5. Attach JDI
                attachJdi(freePort, found, entry, pauseAtStart);

            } catch (Exception e) {
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debugger Error: " + e.getMessage()));
                e.printStackTrace();
                stopDebugging(); // Cleanup if fail
            }
        }, "debug-launch").start();
    }

    /**
     * Connects the JDI VirtualMachine to the running process and asks for a stop on every line wanted in every
     * class of the bot: the ones already loaded now, and the rest as each is prepared.
     */
    private void attachJdi(int port, DebugTargets found, Path entry, Integer pauseAtStart) throws Exception {
        VirtualMachineManager vmMgr = Bootstrap.virtualMachineManager();
        AttachingConnector connector = vmMgr.attachingConnectors().stream()
                .filter(c -> c.transport().name().equals("dt_socket"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Socket attaching connector not found"));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("port").setValue(String.valueOf(port));
        arguments.get("hostname").setValue("localhost");

        VirtualMachine attached = null;
        int maxRetries = Constants.DEBUGGER_MAX_CONNECT_RETRIES;
        for (int i = 0; i < maxRetries; i++) {
            try {
                attached = connector.attach(arguments);
                System.out.println(ANSI_BLUE + "Attached to VM: " + attached.name() + ANSI_RESET);
                break;
            } catch (IOException e) {
                if (i == maxRetries - 1) throw e;
                Thread.sleep(Constants.DEBUGGER_RETRY_DELAY_MS);
            }
        }
        vm = attached;

        EventRequestManager erm = attached.eventRequestManager();
        for (String name : found.classNames()) {
            for (String filter : List.of(name, name + "$*")) {
                ClassPrepareRequest prepare = erm.createClassPrepareRequest();
                prepare.addClassFilter(filter);
                prepare.enable();
            }
        }
        for (ReferenceType loaded : attached.allClasses()) {
            requestStops(loaded, found, entry, pauseAtStart);
        }

        CountDownLatch listenerReadyLatch = new CountDownLatch(1);
        new Thread(() -> jdiEventLoop(listenerReadyLatch, found, entry, pauseAtStart), "jdi-events").start();

        listenerReadyLatch.await();
        // No attached.resume() here (2026-09-26). The debuggee waits (suspend=y) with its VMStartEvent queued,
        // and the event loop resumes that set, which is what starts the bot. Resuming here as well let the bot
        // run and suspend again at a class's ClassPrepareEvent before the loop had taken the VMStart set — whose
        // resume() then released that class before its stops were requested, so the first lines of main ran
        // past their breakpoints. DebugSnapshotTest hit it about one launch in six.
    }

    private void jdiEventLoop(CountDownLatch listenerReadyLatch, DebugTargets found, Path entry, Integer pauseAtStart) {
        EventQueue eventQueue = vm.eventQueue();
        listenerReadyLatch.countDown();

        while (true) {
            try {
                EventSet eventSet = eventQueue.remove();
                boolean shouldResume = true;

                for (Event event : eventSet) {
                    if (event instanceof VMDisconnectEvent) {
                        handleDisconnect();
                        return;
                    }
                    if (event instanceof ClassPrepareEvent cpe) {
                        requestStops(cpe.referenceType(), found, entry, pauseAtStart);
                    } else if (event instanceof LocatableEvent locatable) {
                        handleLocatableEvent(locatable);
                        // Trace mode never pauses: fall through to eventSet.resume() and keep following.
                        if (!traceMode) shouldResume = false;
                    }
                }

                if (shouldResume) {
                    eventSet.resume();
                }
            } catch (InterruptedException | VMDisconnectedException e) {
                handleDisconnect();
                return;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Asks for a stop on each line this class's source file wants: every statement under Follow, else the breakpoints. */
    private void requestStops(ReferenceType type, DebugTargets found, Path entry, Integer pauseAtStart) {
        Optional<DebugTargets.FileTargets> file = found.forClass(type.name());
        if (file.isEmpty()) return;
        Set<Integer> lines;
        if (traceMode) {
            lines = file.get().lines();
        } else if (pauseAtStart != null && file.get().file().equals(entry)) {
            lines = Set.of(pauseAtStart);
        } else {
            lines = file.get().breakpointLines();
        }
        EventRequestManager erm = vm.eventRequestManager();
        for (int line : lines) {
            try {
                for (Location location : type.locationsOfLine(line)) {
                    // A line can hold code of several methods (a lambda on it); ask only in the type that owns it.
                    if (!location.declaringType().equals(type)) continue;
                    BreakpointRequest request = erm.createBreakpointRequest(location);
                    request.enable();
                    break;
                }
            } catch (AbsentInformationException e) {
                System.err.println("No debug info for " + type.name() + " (compiled without -g?).");
                return;
            }
        }
    }

    private void handleLocatableEvent(LocatableEvent event) {
        this.currentDebugThread = event.thread();

        if (event instanceof StepEvent) {
            vm.eventRequestManager().deleteEventRequest(event.request());
        }

        Location location = event.location();
        Optional<DebugTargets.FileTargets> file = targets == null ? Optional.empty()
                : targets.forClass(location.declaringType().name());
        FollowPacer.Hit hit = file.map(f -> new FollowPacer.Hit(f.file(), location.lineNumber())).orElse(null);

        if (traceMode) {
            // Follow mode: note where the bot is; the ticker decides what is shown. The event loop resumes.
            if (hit != null) pacer.hit(hit);
            return;
        }
        int id = session.get();
        // Read now, on this thread, while the VM is suspended: a frame is gone the moment it resumes.
        DebugSnapshot snapshot = DebugSnapshot.of(event.thread(), targets);
        Platform.runLater(() -> {
            if (id != session.get() || finished.get()) return;
            eventBus.publish(new CoreApplicationEvents.DebugSnapshotEvent(snapshot));
            showPaused(id, hit, location.lineNumber());
        });
    }

    // --- what the canvas shows (FX thread) -----------------------------------------------------------------

    private void showPaused(int id, FollowPacer.Hit hit, int line) {
        if (id != session.get() || finished.get()) return;
        if (hit == null) {
            // A step landed outside the bot's own code (a library frame): say where, highlight nothing.
            eventBus.publish(new CoreApplicationEvents.DebugSessionPausedEvent(line, null));
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Paused at line: " + line));
            return;
        }
        onFile(hit.file(), true, () -> {
            if (id != session.get() || finished.get()) return;
            CodeBlock target = blockAt(hit.line());
            eventBus.publish(new CoreApplicationEvents.DebugSessionPausedEvent(hit.line(), target));
            eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
            eventBus.publish(new CoreApplicationEvents.ExecutionFollowedEvent(target));
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                    "Paused at " + hit.file().getFileName() + ", line " + hit.line()));
        });
    }

    private void startFollowTicker(int id) {
        synchronized (this) {
            if (followTicker != null) followTicker.shutdownNow();
            followTicker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "follow-ticker");
                t.setDaemon(true);
                return t;
            });
            pacer.take(); // start from an empty window
            followTicker.scheduleAtFixedRate(() -> {
                FollowPacer.Frame frame = pacer.take();
                if (frame != null) Platform.runLater(() -> showFollowed(id, frame));
            }, FollowPacer.DWELL_MS, FollowPacer.DWELL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * One window of Follow. A frame in the open file highlights its latest block, or the loop enclosing all of
     * them when the bot came round within the window. A frame spent wholly in another file switches to it, at
     * most once per {@link #FILE_SWITCH_GAP_MS}; a frame that crossed files leaves the highlight where it is.
     */
    private void showFollowed(int id, FollowPacer.Frame frame) {
        if (id != session.get() || finished.get()) return;
        Optional<Path> single = frame.singleFile();
        Path file = frame.latest().file();
        boolean onScreen = file.equals(activeFile());
        if (!onScreen) {
            long now = System.currentTimeMillis();
            if (single.isEmpty() || now - lastFileSwitch < FILE_SWITCH_GAP_MS) return;
            lastFileSwitch = now;
        }
        onFile(file, true, () -> {
            if (id != session.get() || finished.get()) return;
            CodeBlock target = null;
            if (frame.looping() && single.isPresent()) {
                List<ASTNode> nodes = frame.hits().stream()
                        .map(h -> blockAt(h.line()))
                        .filter(Objects::nonNull)
                        .map(CodeBlock::getAstNode)
                        .toList();
                target = FollowPacer.enclosingLoop(nodes)
                        .flatMap(state::getBlockForNode)
                        .map(CodeBlock::getHighlightTarget)
                        .orElse(null);
            }
            if (target == null) target = blockAt(frame.latest().line());
            if (target == null) return;
            eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(target));
            eventBus.publish(new CoreApplicationEvents.ExecutionFollowedEvent(target));
        });
    }

    /** Runs {@code then} once {@code file} is the one on screen, asking the editor to switch to it first if needed. */
    private void onFile(Path file, boolean mayOpen, Runnable then) {
        if (file.equals(activeFile())) {
            then.run();
        } else if (mayOpen) {
            eventBus.publish(new CoreApplicationEvents.FileOpenRequestedEvent(file));
            // The switch is delivered through runLater; this is queued behind it, so it runs on the new file.
            Platform.runLater(then);
        }
    }

    private Path activeFile() {
        ProjectFile active = state.getActiveFile();
        return active == null ? null : active.getPath();
    }

    /** The block the canvas shows for {@code line} of the open file, preferring a statement to what shares its line. */
    private CodeBlock blockAt(int line) {
        Map<ASTNode, CodeBlock> map = state.getNodeToBlockMap();
        if (map != indexedMap) {
            CompilationUnit cu = state.getCompilationUnit().orElse(null);
            Map<Integer, CodeBlock> index = new HashMap<>();
            for (CodeBlock block : map.values()) {
                if (block == null) continue;
                int at = block.getBreakpointLine(cu);
                if (at > 0 && (!index.containsKey(at) || block instanceof StatementBlock)) index.put(at, block);
            }
            indexedMap = map;
            lineIndex = index;
        }
        CodeBlock block = lineIndex.get(line);
        return block == null ? null : block.getHighlightTarget();
    }

    // --- process plumbing ------------------------------------------------------------------------------------

    /** Starts the loopback telemetry server for the preview panel and passes port+token to the debuggee. */
    private void startTelemetry(ProcessBuilder pb) {
        stopTelemetry();
        try {
            String token = java.util.UUID.randomUUID().toString();
            TelemetryServer server = new TelemetryServer(token,
                    feedback -> Platform.runLater(() ->
                            eventBus.publish(new CoreApplicationEvents.ViewFeedbackEvent(feedback))),
                    reason -> Platform.runLater(() -> eventBus.publish(new CoreApplicationEvents.OutputAppendedEvent(
                            "⚠ Live preview/overlays unavailable: the bot's SDK sends telemetry this Studio can't "
                            + "read (" + reason + "). Pick a current SDK build in Project ▸ Manage Libraries and re-run.\n"))));
            this.telemetryServer = server;
            pb.environment().put(IpcEnv.PORT, String.valueOf(server.port()));
            pb.environment().put(IpcEnv.TOKEN, token);
        } catch (IOException e) {
            System.err.println("Telemetry server failed to start: " + e.getMessage());
        }
    }

    private void stopTelemetry() {
        TelemetryServer server = telemetryServer;
        if (server != null) {
            server.close();
            telemetryServer = null;
        }
    }

    /**
     * The one way a session ends, however many paths reach it: the VM disconnecting, the event loop failing,
     * and Stop all call this, and only the first does anything.
     */
    private void handleDisconnect() {
        if (!finished.compareAndSet(false, true)) return;
        session.incrementAndGet();
        this.currentDebugThread = null;
        this.vm = null;
        this.currentProcess = null;
        this.targets = null;

        synchronized (this) {
            if (followTicker != null) {
                followTicker.shutdownNow();
                followTicker = null;
            }
        }
        pacer.take();
        stopTelemetry();
        ConsoleBatcher batcher = console;
        if (batcher != null) {
            // Hand over what the bot printed last, then stop the timer. The pumps end with the process.
            new Thread(() -> {
                try {
                    batcher.finish();
                } catch (InterruptedException ignored) {
                    batcher.close();
                }
            }, "debuggee-output-drain").start();
            console = null;
        }

        eventBus.publish(new CoreApplicationEvents.DebugSessionFinishedEvent());
        eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debug session finished."));
        eventBus.publish(new CoreApplicationEvents.BlockHighlightEvent(null));
    }

    public void stepOver() {
        VirtualMachine machine = vm;
        ThreadReference thread = currentDebugThread;
        if (machine == null || thread == null) return;
        try {
            eventBus.publish(new CoreApplicationEvents.DebugSessionResumedEvent());
            EventRequestManager erm = machine.eventRequestManager();

            erm.stepRequests().stream()
                    .filter(r -> r.thread().equals(thread))
                    .forEach(erm::deleteEventRequest);

            StepRequest request = erm.createStepRequest(thread, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
            request.addCountFilter(1);
            request.enable();

            machine.resume();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void continueExecution() {
        VirtualMachine machine = vm;
        if (machine != null) {
            eventBus.publish(new CoreApplicationEvents.DebugSessionResumedEvent());
            machine.resume();
        }
    }

    public void stopDebugging() {
        VirtualMachine machine = vm;
        if (machine != null) {
            try {
                machine.dispose();
            } catch (VMDisconnectedException ignored) {
            } catch (Exception e) { e.printStackTrace(); }
        }

        Process process = currentProcess;
        if (process != null && process.isAlive()) {
            try {
                // Collect the debuggee's children before killing it: afterwards they are reparented to init
                // and no longer reachable here, which is how a bot-launched game outlived a stopped session.
                var descendants = process.descendants().toList();
                process.destroyForcibly();
                descendants.forEach(ProcessHandle::destroyForcibly);
                eventBus.publish(new CoreApplicationEvents.StatusMessageEvent("Debug process terminated."));
            } catch (Exception e) { e.printStackTrace(); }
        }

        handleDisconnect();
    }
}
