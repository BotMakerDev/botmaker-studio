package com.botmaker.studio.services;

import com.botmaker.core.edit.CoreEditor;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectState;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.studio.events.BusEditorOutput;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The core's editor in the test's own process, over the state the test holds: what Studio's tests edit through, where
 * Studio itself has {@link CoreEdits}.
 */
public final class LocalEdits implements EditPort {

    private final CoreEditor core;

    private LocalEdits(CoreEditor core) {
        this.core = core;
    }

    /** What {@link CodeEditorService}'s constructor takes. */
    public static Function<Consumer<CoreEvent>, EditPort> over(ProjectConfig config, ProjectState state, EventBus bus,
                                                               ProjectAnalyzer analyzer) {
        return told -> of(config, state, bus, analyzer, told);
    }

    /** A core over the test's state, telling {@code told} what lands. */
    public static LocalEdits of(ProjectConfig config, ProjectState state, EventBus bus, ProjectAnalyzer analyzer,
                                Consumer<CoreEvent> told) {
        return new LocalEdits(new CoreEditor(config, state, new BusEditorOutput(bus), analyzer, told));
    }

    @Override
    public EditResult userEdit(Edit edit) {
        return core.userEdit(edit);
    }

    @Override
    public EditResult step(HistoryStep step) {
        return core.step(step) ? new EditResult.Accepted(List.of()) : new EditResult.Refused("No step that way.");
    }

    /**
     * A write of the open file the test made outside an edit, recorded as one step and announced as an edit is: what a
     * test redraws through. Studio has no such path; the core writes its own files.
     */
    public void rewritten(String source, String original) {
        core.rewritten(new EditorOutput.Write(source, original, false, null, List.of()));
    }

    @Override
    public EditResult show(String file) {
        return new EditResult.Accepted(List.of());
    }
}
