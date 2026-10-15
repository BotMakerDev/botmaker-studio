package com.botmaker.studio.plugin;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.services.BotSources;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A plugin's value writes made into undo steps: each write one step, or a plugin's batch of them one step under
 * its own label ({@code History.batch}).
 *
 * <p>The step hears the files through {@link BotSources#recording}, the walk every value write goes through, so
 * no write path has to say what it changed: what a file said before is what the walk read, and what it says after
 * is what the walk wrote. A file written twice in one step keeps its first <em>before</em> and its last
 * <em>after</em>. A file a write <em>creates</em> (a new class for a method) is not in the walk and not in the
 * step: ↶ takes back the method's uses and leaves the class.
 *
 * <p>Per thread, as the recorder is: a batch is the writes its {@code Runnable} makes, never a write another
 * thread made meanwhile.
 */
final class WriteSteps {

    private WriteSteps() {}

    private static final ThreadLocal<Step> OPEN = new ThreadLocal<>();

    /** One step being recorded: its label and the files it rewrote. */
    static final class Step implements BotSources.Recorder {

        private final String label;
        private final Map<Path, String> before = new LinkedHashMap<>();
        private final Map<Path, String> after = new LinkedHashMap<>();
        private boolean checkpointed;

        private Step(String label) {
            this.label = label;
        }

        @Override
        public void rewrote(Path file, String was, String now) {
            before.putIfAbsent(file, was);
            after.put(file, now);
        }

        /**
         * True the first time it is asked: the one Project History snapshot a step takes before its first write,
         * so a batch of twenty writes is one safety version, not twenty.
         */
        boolean firstCheckpoint() {
            if (checkpointed) return false;
            checkpointed = true;
            return true;
        }

        /** The files that changed, as the history records them; a file written back to what it was is not one. */
        List<CoreApplicationEvents.FileEdit> edits() {
            List<CoreApplicationEvents.FileEdit> edits = new ArrayList<>();
            before.forEach((file, was) -> {
                String now = after.get(file);
                if (!was.equals(now)) edits.add(new CoreApplicationEvents.FileEdit(file, was, now));
            });
            return edits;
        }
    }

    /** The step this thread is recording, or null when none is open. */
    static Step open() {
        return OPEN.get();
    }

    /**
     * Runs {@code writes} as one step named {@code label} and hands it to {@code recorded} once they end, unless
     * they changed nothing; inside a step already open on this thread, they are part of that one, under its
     * label.
     */
    static <T> T step(String label, Supplier<T> writes, Consumer<CoreApplicationEvents.FilesRewrittenEvent> recorded) {
        if (OPEN.get() != null) return writes.get();
        Step step = new Step(label == null || label.isBlank() ? "the last change" : label.strip());
        OPEN.set(step);
        try {
            return BotSources.recording(step, writes);
        } finally {
            // Also after a write that threw: what it wrote before is on disk, so it is undoable too.
            OPEN.remove();
            List<CoreApplicationEvents.FileEdit> edits = step.edits();
            if (!edits.isEmpty() && recorded != null) {
                try {
                    recorded.accept(new CoreApplicationEvents.FilesRewrittenEvent(step.label, edits));
                } catch (RuntimeException failed) {
                    // The files are written; a history that could not hear it must not turn the write into a failure.
                    System.err.println("Couldn't record \"" + step.label + "\" for undo: " + failed);
                }
            }
        }
    }
}
