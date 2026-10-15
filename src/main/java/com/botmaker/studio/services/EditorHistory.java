package com.botmaker.studio.services;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.state.HistoryManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The project's one undo history: every edit made on the canvas and every file a plugin's value writes
 * rewrote, in the order they happened, so ↶ — the menu's, the toolbar's, a plugin window's, the assistant's —
 * takes back the last of them whoever made it.
 *
 * <p>Split out of {@link CodeEditorService} (2026-10-15) when plugin writes joined it: those arrive as a
 * {@link CoreApplicationEvents.FilesRewrittenEvent} rather than through the canvas, and the contract's
 * {@code History} reads this state back through {@link CoreApplicationEvents.HistoryStateChangedEvent}.
 *
 * <p>Synchronized because two threads feed it: the canvas's edits arrive on the JavaFX thread, and a plugin
 * tool the assistant calls writes on the endpoint's. A restore never waits on another thread — the canvas is
 * redrawn later, through {@code Platform.runLater} — so holding the lock across one cannot deadlock.
 */
public final class EditorHistory {

    private final ProjectState state;
    private final EventBus eventBus;
    private final HistoryManager history;

    public EditorHistory(ProjectState state, EventBus eventBus) {
        this.state = state;
        this.eventBus = eventBus;
        this.history = new HistoryManager(this::restoreFiles);
        eventBus.subscribe(CoreApplicationEvents.FilesRewrittenEvent.class, this::recordRewrite, false);
        eventBus.subscribe(CoreApplicationEvents.UndoRequestedEvent.class, e -> undo(), false);
        eventBus.subscribe(CoreApplicationEvents.RedoRequestedEvent.class, e -> redo(), false);
    }

    /**
     * Turns one canvas write into one history step: the active file, plus every other file the same edit
     * rewrote.
     *
     * <p>The second half is why this is a map rather than a string. A signature migration writes the
     * declaration here and the calls in the files that use it, and it is one change to the user — so it has to
     * be one ↶.
     */
    public synchronized void recordEdit(CoreApplicationEvents.CodeUpdatedEvent event) {
        Map<Path, String> before = new LinkedHashMap<>();
        Map<Path, String> after = new LinkedHashMap<>();

        Path active = activePath();
        String previousCode = event.previousCode();
        if (active != null && previousCode != null && !previousCode.isEmpty()) {
            before.put(active, previousCode);
            after.put(active, event.newCode());
        }
        for (CoreApplicationEvents.FileEdit edit : event.alsoChanged()) {
            before.put(normal(edit.path()), edit.previousContent());
            after.put(normal(edit.path()), edit.newContent());
        }

        history.record(event.label(), before, after);
        broadcast();
    }

    /** One key per file, whichever spelling of its path a write was announced with. */
    private static Path normal(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** A plugin's writes, already on buffer and disk: recorded, never applied. */
    private synchronized void recordRewrite(CoreApplicationEvents.FilesRewrittenEvent event) {
        Map<Path, String> before = new LinkedHashMap<>();
        Map<Path, String> after = new LinkedHashMap<>();
        for (CoreApplicationEvents.FileEdit edit : event.edits()) {
            before.put(normal(edit.path()), edit.previousContent());
            after.put(normal(edit.path()), edit.newContent());
        }
        history.record(event.label(), before, after);
        broadcast();
    }

    private synchronized void undo() {
        if (!history.canUndo()) return;
        history.undo();
        broadcast();
    }

    private synchronized void redo() {
        if (!history.canRedo()) return;
        history.redo();
        broadcast();
    }

    /**
     * Puts one step's files back: the ones nobody is looking at straight to disk, the open one through the
     * refresh.
     *
     * <p>Restoring without recording is the point of {@code UIRefreshRequestedEvent} — it updates
     * {@link ProjectState} and the canvas, and publishes no {@code CodeUpdatedEvent}, so an undo does not
     * become a step of its own.
     *
     * <p>The other files are written to disk here because that is where the change put them:
     * {@code CallMigrator.commit} and a plugin's value writes wrote them immediately rather than leaving them for
     * a save. Their cached AST goes with the text, or every suggestion in Studio keeps reading the version that
     * was just taken back.
     */
    private void restoreFiles(Map<Path, String> files) {
        Path active = activePath();
        for (Map.Entry<Path, String> each : files.entrySet()) {
            if (each.getKey().equals(active)) continue;
            writeBack(each.getKey(), each.getValue());
        }
        String activeText = active == null ? null : files.get(active);
        if (activeText != null) {
            eventBus.publish(new CoreApplicationEvents.UIRefreshRequestedEvent(activeText));
        }
    }

    private void writeBack(Path path, String text) {
        // Matched by the normal path, as BotSources finds a buffer: the state keys a file as it was opened.
        for (ProjectFile file : state.getAllFiles()) {
            if (file.getPath() != null && normal(file.getPath()).equals(path)) {
                file.setContent(text);
                file.setAst(null);
            }
        }
        try {
            Files.writeString(path, text);
        } catch (IOException e) {
            eventBus.publish(new CoreApplicationEvents.StatusMessageEvent(
                    "Couldn't put " + path.getFileName() + " back: " + e.getMessage()));
        }
    }

    private Path activePath() {
        ProjectFile file = state.getActiveFile();
        return file == null || file.getPath() == null ? null : normal(file.getPath());
    }

    private void broadcast() {
        eventBus.publish(new CoreApplicationEvents.HistoryStateChangedEvent(
                history.canUndo(), history.canRedo(), history.undoLabel(), history.redoLabel()));
    }
}
