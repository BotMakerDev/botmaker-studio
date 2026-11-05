package com.botmaker.studio.services;

import com.botmaker.core.config.AppPaths;
import com.botmaker.core.config.AppVersion;
import com.botmaker.core.edit.CanvasEdit;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.events.FileEdit;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import com.botmaker.core.server.CoreClient;
import com.botmaker.core.server.CoreLauncher;
import com.botmaker.java.edit.palette.BlockType;
import com.botmaker.shared.config.CacheDirs;
import com.botmaker.studio.events.CoreApplicationEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Studio's editor over the core's process (plan phase 10a1): each edit is a request on a {@link CoreClient}, and what the
 * core tells comes back as the {@link CoreEvent}s Studio redraws from, Studio's {@link ProjectState} adopting each change
 * as a mirror of the core's.
 *
 * <p>The core tells a change before it answers the request that made it, on the channel's reader thread. Each told
 * event is queued, and the queue is drained in order by whichever comes first: the thread that asked, once its answer is
 * in (so an edit's caller sees its change adopted when the call returns, as it did in-process), or a delivery on the UI
 * thread, for a change another client made.
 */
public final class CoreEdits implements EditPort, AutoCloseable {

    private final ProjectState state;
    private final EventBus bus;
    private final EventBus.UiThread ui;
    private final Queue<CoreEvent> told = new ConcurrentLinkedQueue<>();
    private final Object draining = new Object();
    private volatile Consumer<CoreEvent> heard;
    private volatile boolean closing;
    private CoreClient client;

    private CoreEdits(ProjectState state, EventBus bus, EventBus.UiThread ui) {
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
        String version = AppVersion.label();
        // A sandboxed Studio has its own core, or it would share the user's and its one open project.
        Path dir = AppPaths.sandbox().map(home -> home.resolve("core")).orElseGet(CacheDirs::cacheRoot);
        CoreLauncher.Address at = CoreLauncher.connect(dir, version, CoreLauncher.process());
        CoreEdits edits = new CoreEdits(state, bus, ui);
        edits.client = CoreClient.connect(at, version, edits::arrived, edits::ended);
        try {
            if (edits.client.open(config.projectPath()) instanceof EditResult.Refused refused) {
                throw new IOException(refused.reason());
            }
        } catch (IOException e) {
            // Closed even when the answer never came: the core would keep this channel on the project it goes on opening.
            edits.client.close();
            throw e;
        }
        state.setSavedByCore(true);
        bus.subscribe(CoreApplicationEvents.LibrariesChangedEvent.class, event -> edits.librariesChanged());
        return edits;
    }

    /** The core resolves the changed libraries off the caller's thread: it may download. */
    private void librariesChanged() {
        if (closing) return;
        CompletableFuture.runAsync(() -> {
            try {
                if (client.reload() instanceof EditResult.Refused refused) {
                    System.err.println("BotMaker's core kept its libraries: " + refused.reason());
                }
            } catch (IOException e) {
                System.err.println("BotMaker's core did not answer: " + e.getMessage());
            }
        });
    }

    /** This port, telling {@code listener} what the core tells from now on: what {@code CodeEditorService} is given. */
    public EditPort heardBy(Consumer<CoreEvent> listener) {
        heard = listener;
        ui.later(this::drain);
        return this;
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
    public EditResult recordRewrite(String label, List<FileEdit> edits) {
        return ask(client -> client.recorded(label, edits));
    }

    @Override
    public EditResult rewritten(String file, String original, String source) {
        return ask(client -> client.rewritten(file, original, source));
    }

    @Override
    public EditResult commit(String file, String original, String source, String label) {
        EditResult result = ask(client -> client.commit(file, original, source, label));
        if (result instanceof EditResult.Accepted) readOthersFromDisk();
        return result;
    }

    @Override
    public EditResult show(String file) {
        return ask(client -> client.show(file));
    }

    private interface Ask {
        EditResult of(CoreClient client) throws IOException;
    }

    /** {@code ask} answered, with every change told before its answer adopted first. */
    private EditResult ask(Ask ask) {
        EditResult result;
        try {
            result = ask.of(client);
        } catch (IOException e) {
            result = new EditResult.Refused("BotMaker's core did not answer: " + e.getMessage());
            bus.publish(new CoreApplicationEvents.StatusMessageEvent(((EditResult.Refused) result).reason()));
        }
        drain();
        return result;
    }

    private void arrived(CoreEvent event) {
        told.add(event);
        try {
            ui.later(this::drain);
        } catch (RuntimeException gone) {
            // The UI thread is gone: Studio is closing, and nothing is drawn any more.
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

    private void ended() {
        if (closing) return;
        bus.publish(new CoreApplicationEvents.StatusMessageEvent(
                "BotMaker's core stopped: reopen the project to edit it again."));
    }

    /** Leaves the project, which the core closes when no other client is on it, and ends the channel. */
    @Override
    public void close() {
        if (client == null) return;
        closing = true;
        try {
            if (client.isOpen()) client.leave();
        } catch (IOException e) {
            System.err.println("BotMaker's core did not let the project go: " + e.getMessage());
        } finally {
            client.close();
        }
    }
}
