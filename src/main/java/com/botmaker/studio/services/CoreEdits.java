package com.botmaker.studio.services;

import com.botmaker.core.config.AppPaths;
import com.botmaker.core.config.AppVersion;
import com.botmaker.core.edit.CanvasEdit;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.plugin.PluginWrite;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.server.CoreClient;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.core.server.CoreLink;
import com.botmaker.core.services.MavenService;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.shared.config.CacheDirs;
import com.botmaker.studio.events.CoreApplicationEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Studio's editor over the core's process (plan phase 10a1): each edit is a request on a {@link CoreClient}, and what the
 * core tells comes back as the {@link CoreEvent}s Studio redraws from, Studio's {@link ProjectState} adopting each change
 * as a mirror of the core's.
 *
 * <p>The core tells a change before it answers the request that made it, on the channel's reader thread. Each told
 * event is queued, and the queue is drained in order by whichever comes first: the thread that asked, once its answer is
 * in (so an edit's caller sees its change adopted when the call returns, as it did in-process), or a delivery on the UI
 * thread, for a change another client made. What is told of a run goes another way ({@link #heardByRuns}).
 */
public final class CoreEdits implements EditPort, AutoCloseable {

    private final ProjectState state;
    private final EventBus bus;
    private final EventBus.UiThread ui;
    private static final int MOST_FRAMES_WAITING = 1024;
    /** How long a plugin's write from a thread of its own waits for the UI thread to take it. */
    private static final Duration PLUGIN_WRITE_TAKEN = Duration.ofSeconds(10);
    /** What the libraries resolved to, as the core last told; nothing until it told. */
    private volatile MavenService.Resolution resolution = new MavenService.Resolution(List.of(), List.of());

    private final AtomicInteger framesWaiting = new AtomicInteger();
    private final Queue<CoreEvent> told = new ConcurrentLinkedQueue<>();
    private final Object draining = new Object();
    private volatile Consumer<CoreEvent> heard;
    private volatile boolean closing;
    /** The bot running now outlives the close: the core keeps it ({@link #keepRunning}). */
    private volatile boolean keepRun;
    private CoreLink link;
    private final Path sourceRoot;
    /** Whoever hears of runs; told through {@link #runEvents}, which waits for it. */
    private final CompletableFuture<Consumer<CoreEvent>> runListener = new CompletableFuture<>();
    private final ExecutorService runEvents = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "core-run-events");
        thread.setDaemon(true);
        return thread;
    });

    private CoreEdits(ProjectConfig config, ProjectState state, EventBus bus, EventBus.UiThread ui) {
        this.sourceRoot = config.sourceRoot();
        this.state = state;
        this.bus = bus;
        this.ui = ui;
    }

    /**
     * Finds or starts the user's core, Studio's own version, and opens {@code config}'s project in it: the core takes
     * the project's lock, makes its open-time writes and binds its plugins before this returns.
     *
     * @throws IOException when no core answers or the core refuses the project (another core holds it, another project
     *                     is open in this one)
     */
    public static CoreEdits open(ProjectConfig config, ProjectState state, EventBus bus, EventBus.UiThread ui)
            throws IOException {
        return open(coreDir(), AppVersion.label(), CoreLauncher.process(), config, state, bus, ui);
    }

    /** Where this Studio's core keeps its files: a sandboxed Studio has its own, or it would share the user's. */
    public static Path coreDir() {
        return AppPaths.sandbox().map(home -> home.resolve("core")).orElseGet(CacheDirs::cacheRoot);
    }

    /**
     * As {@link #open(ProjectConfig, ProjectState, EventBus, EventBus.UiThread)}, on the core for {@code dir}, started by
     * {@code starter} when none runs, and again by it when that core stops (plan phase 10d): the project is reopened
     * on the new one and the open file shown again.
     */
    static CoreEdits open(Path dir, String version, CoreLauncher.Starter starter, ProjectConfig config,
                          ProjectState state, EventBus bus, EventBus.UiThread ui) throws IOException {
        CoreEdits edits = new CoreEdits(config, state, bus, ui);
        edits.link = CoreLink.connect(dir, version, starter, edits::arrived, call -> { }, edits.new Restarts());
        // Before the first open: a core that dies during it is replaced with the project open again.
        edits.link.rejoin(client -> {
            if (client.open(config.projectPath()) instanceof EditResult.Refused refused) {
                throw new IOException(refused.reason());
            }
            String shown = state.activeFileName().orElse(null);
            if (shown != null && client.show(shown) instanceof EditResult.Refused refused) {
                throw new IOException(refused.reason());
            }
        });
        try {
            if (edits.link.client().open(config.projectPath()) instanceof EditResult.Refused refused) {
                throw new IOException(refused.reason());
            }
        } catch (IOException e) {
            // Closed even when the answer never came: the core would keep this channel on the project it goes on opening.
            edits.link.close();
            throw e;
        }
        state.setSavedByCore(true);
        return edits;
    }

    /**
     * What the project's libraries resolved to in the core as this client attached (plan phase 10c): Studio binds its
     * own copy of the plugins to it and resolves no pom itself.
     */
    public MavenService.Resolution resolution() {
        return resolution;
    }

    /**
     * The core resolves the project's libraries again, puts the contract's entry right and writes a holder a new plugin
     * brings; what they resolved to now. On the caller's thread, which waits: it may download.
     *
     * @throws IllegalStateException with the core's reason when it kept its libraries (a pom it cannot read)
     */
    public MavenService.Resolution reload() {
        if (request(CoreClient::reload) instanceof EditResult.Refused refused) {
            throw new IllegalStateException(refused.reason());
        }
        // Told before the answer, on the reader thread, so it is in by now.
        return resolution;
    }

    /**
     * A write of the bot's values a plugin of this Studio asked for, made by the core (plan phase 10c). On the UI thread
     * the mirror holds what it wrote when this returns; from another thread this waits, a while, for the UI thread to
     * have taken it, so a plugin that reads its value back after a write reads the new one either way.
     */
    public Optional<String> pluginWrite(PluginWrite write) {
        if (ui.isCurrent()) {
            EditResult result = ask(client -> client.pluginWrite(write));
            if (result instanceof EditResult.Refused refused) return Optional.of(refused.reason());
            wrote();
            return Optional.empty();
        }
        EditResult result = request(client -> client.pluginWrite(write));
        if (result instanceof EditResult.Refused refused) return Optional.of(refused.reason());
        CountDownLatch taken = new CountDownLatch(1);
        try {
            ui.later(() -> {
                try {
                    drain();
                    wrote();
                } finally {
                    taken.countDown();
                }
            });
            if (!taken.await(PLUGIN_WRITE_TAKEN.toMillis(), TimeUnit.MILLISECONDS)) {
                System.err.println("A plugin's write landed, but Studio had not drawn it yet.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException gone) {
            // The UI thread is gone: Studio is closing.
        }
        return Optional.empty();
    }

    /** A plugin's write landed: what it wrote beside the open file is read again, and the toolbar relabels. */
    private void wrote() {
        readOthersFromDisk();
        bus.publish(new CoreApplicationEvents.PluginWroteEvent());
    }

    /** Opens ({@code open}, under {@code label}) or ends a plugin's batch in the core; see {@code Request.Batch}. */
    public void batch(String label, boolean open) {
        if (request(client -> client.batch(label, open)) instanceof EditResult.Refused refused) {
            System.err.println("BotMaker's core did not take a plugin's batch: " + refused.reason());
        }
    }

    /** This port, telling {@code listener} what the core tells from now on: what {@code CodeEditorService} is given. */
    public EditPort heardBy(Consumer<CoreEvent> listener) {
        heard = listener;
        ui.later(this::drain);
        return this;
    }

    /**
     * Tells {@code listener}, in order and on a thread of its own, what the core tells of runs and debug sessions, from
     * the first the core told. Never on the channel's reader, whose thread a listener's own ask would wait on, and never
     * through the UI queue, which a bot's trace would flood.
     */
    public void heardByRuns(Consumer<CoreEvent> listener) {
        runListener.complete(listener);
    }

    /** A request to the core, on the caller's thread, with nothing told drained; for what the mirror does not wait on. */
    public EditResult request(Ask ask) {
        try {
            return ask.of(link.client());
        } catch (IOException e) {
            return new EditResult.Refused("BotMaker's core did not answer: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // A request refuses what it cannot carry, such as a run's class that is no class name.
            return new EditResult.Refused(e.getMessage());
        }
    }

    @Override
    public EditResult userEdit(Edit edit) {
        EditResult result = ask(client -> client.userEdit(edit));
        if (result instanceof EditResult.Accepted) {
            readOthersFromDisk();
            added(edit).ifPresent(type -> bus.publish(new CoreApplicationEvents.BlockAddedEvent(type)));
        }
        return result;
    }

    /** The palette's block a gesture added, for the palette's recent blocks: the core keeps no palette of a client's. */
    private static Optional<BlockType> added(Edit edit) {
        return switch (edit) {
            case CanvasEdit.InsertBlock insert -> Optional.of(insert.block());
            case CanvasEdit.FillSlotFromBlock fill -> Optional.of(fill.block());
            case CanvasEdit.FillEmptySlotFromBlock fill -> Optional.of(fill.block());
            default -> Optional.empty();
        };
    }

    /**
     * A step of the core's history. What it put back in a file nobody is looking at went to disk in the core, as a
     * signature edit's migrated callers do, so the mirror reads those files again; the open file's comes as
     * {@code MethodsChanged}.
     */
    @Override
    public EditResult step(HistoryStep step) {
        EditResult result = ask(client -> client.step(step));
        if (result instanceof EditResult.Accepted) readOthersFromDisk();
        return result;
    }

    @Override
    public EditResult show(String file) {
        return ask(client -> client.show(file));
    }

    /** One ask of the core. */
    public interface Ask {
        EditResult of(CoreClient client) throws IOException;
    }

    /** {@code ask} answered, with every change told before its answer adopted first. */
    private EditResult ask(Ask ask) {
        EditResult result;
        try {
            result = ask.of(link.client());
        } catch (IOException e) {
            result = new EditResult.Refused("BotMaker's core did not answer: " + e.getMessage());
            bus.publish(new CoreApplicationEvents.StatusMessageEvent(((EditResult.Refused) result).reason()));
        }
        drain();
        return result;
    }

    private void arrived(CoreEvent event) {
        if (event instanceof CoreEvent.LibrariesResolved resolved) {
            // Not queued: the asker that waits for it reads it as soon as its answer is in, on no UI thread.
            resolution = new MavenService.Resolution(resolved.jars(), resolved.problems());
            return;
        }
        if (event instanceof CoreEvent.Opened opened) {
            // Kept, not drawn: the status bar that shows it is built after the open answers.
            state.setOpenReport(opened.report());
            return;
        }
        if (ofARun(event)) {
            toRuns(event);
            return;
        }
        told.add(event);
        try {
            ui.later(this::drain);
        } catch (RuntimeException gone) {
            // The UI thread is gone: Studio is closing, and nothing is drawn any more.
        }
    }

    private static boolean ofARun(CoreEvent event) {
        return switch (event) {
            case CoreEvent.RunState _, CoreEvent.RunOutput _, CoreEvent.RunLogged _, CoreEvent.RunTraced _,
                 CoreEvent.RunFrame _, CoreEvent.RunProcess _, CoreEvent.InputAsked _, CoreEvent.DebugSession _,
                 CoreEvent.DebugPaused _, CoreEvent.DebugFollowed _ -> true;
            default -> false;
        };
    }

    private void toRuns(CoreEvent event) {
        boolean frame = event instanceof CoreEvent.RunFrame;
        // Behind a slow plugin frames pile up by the megabyte, and the next one says what they would have.
        if (frame && framesWaiting.get() >= MOST_FRAMES_WAITING) return;
        if (frame) framesWaiting.incrementAndGet();
        try {
            runEvents.execute(() -> {
                if (frame) framesWaiting.decrementAndGet();
                try {
                    runListener.get().accept(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | RuntimeException e) {
                    System.err.println("A listener of the core's run events failed: " + e);
                }
            });
        } catch (RejectedExecutionException closed) {
            // The project closed: nobody draws a run any more.
        }
    }

    /** Hands each told event on, in order, once a listener is there. */
    private void drain() {
        Consumer<CoreEvent> listener = heard;
        if (listener == null) return;
        synchronized (draining) {
            for (CoreEvent event = told.poll(); event != null; event = told.poll()) {
                if (event instanceof CoreEvent.Status status) {
                    bus.publish(new CoreApplicationEvents.StatusMessageEvent(status.message()));
                } else if (event instanceof CoreEvent.SourcesChanged changed) {
                    readFromDisk(changed.files());
                } else if (!alreadyHeld(event)) {
                    listener.accept(event);
                }
            }
        }
    }

    /** The core read from disk what Studio itself wrote, or showed a file the mirror holds as it is: nothing to redraw. */
    private boolean alreadyHeld(CoreEvent event) {
        return event instanceof CoreEvent.MethodsChanged changed
                && state.activeFileName().filter(changed.file()::equals).isPresent()
                && changed.source().equals(state.getCurrentCode());
    }

    /** Files beside the open one changed on disk: the mirror holds what is there now, a file gone not at all. */
    private void readFromDisk(List<String> files) {
        ProjectFile active = state.getActiveFile();
        for (String name : files) {
            Path path = sourceRoot.resolve(name).normalize();
            if (active != null && path.equals(active.getPath())) continue;
            if (!Files.isRegularFile(path)) {
                state.removeFile(path);
                continue;
            }
            try {
                String text = Files.readString(path);
                Optional<ProjectFile> known = state.getFile(path);
                if (known.isEmpty()) {
                    state.addFile(new ProjectFile(path, text));
                } else if (!text.equals(known.get().getContent())) {
                    known.get().setContent(text);
                    known.get().setAst(null);
                }
            } catch (IOException e) {
                System.err.println("Couldn't read " + path.getFileName() + " again: " + e.getMessage());
            }
        }
    }

    private void readOthersFromDisk() {
        ProjectFile active = state.getActiveFile();
        for (ProjectFile file : state.getAllFiles()) {
            if (file == active) continue;
            Path path = file.getPath();
            try {
                if (!Files.isRegularFile(path)) continue;
                String text = Files.readString(path);
                if (!text.equals(file.getContent())) {
                    file.setContent(text);
                    file.setAst(null);
                }
            } catch (IOException e) {
                System.err.println("Couldn't read " + path.getFileName() + " again: " + e.getMessage());
            }
        }
    }

    /** What Studio says and does as its core stops and a new one takes the project over (plan phase 10d). */
    private final class Restarts implements CoreLink.Listener {
        @Override
        public void stopped() {
            if (closing) return;
            status("The core stopped; BotMaker is starting it again.");
            // Nothing will tell what it ran has ended, and the toolbar would wait for that for ever.
            toRuns(new CoreEvent.RunState(CoreEvent.RunState.Phase.ENDED, "the core stopped"));
            toRuns(new CoreEvent.DebugSession(CoreEvent.DebugSession.Phase.FINISHED));
        }

        @Override
        public void restarted() {
            // Every accepted change was on disk before it was answered; one another client made just before may not be
            // in the mirror yet.
            readOthersFromDisk();
            status("BotMaker's core is back, with the project open again.");
        }

        @Override
        public void lost(IOException why) {
            if (closing) return;
            status("The core stopped and could not be started again (" + why.getMessage()
                    + "): reopen the project to edit it again.");
        }
    }

    private void status(String message) {
        bus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }

    /** The close leaves the bot running now to the core, which keeps the project open until it ends (plan phase 11). */
    public void keepRunning() {
        keepRun = true;
    }

    /** Leaves the project, which the core closes when no other client is on it, and ends the channel. */
    @Override
    public void close() {
        runEvents.shutdownNow();
        if (link == null) return;
        closing = true;
        CoreClient client = link.client();
        try {
            if (client.isOpen()) client.leave(keepRun);
        } catch (IOException e) {
            System.err.println("BotMaker's core did not let the project go: " + e.getMessage());
        } finally {
            link.close();
        }
    }
}
