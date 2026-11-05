package com.botmaker.studio.services;

import com.botmaker.core.edit.CoreEditor;
import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.event.CoreEvent;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.events.FileEdit;
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
        return told -> new LocalEdits(new CoreEditor(config, state, new BusEditorOutput(bus), analyzer, told));
    }

    @Override
    public EditResult userEdit(Edit edit) {
        return core.userEdit(edit);
    }

    @Override
    public EditResult step(HistoryStep step) {
        return core.step(step) ? new EditResult.Accepted(List.of()) : new EditResult.Refused("No step that way.");
    }

    @Override
    public EditResult recordRewrite(String label, List<FileEdit> edits) {
        core.recordRewrite(label, edits);
        return new EditResult.Accepted(List.of());
    }

    @Override
    public EditResult rewritten(String file, String original, String source) {
        core.rewritten(new EditorOutput.Write(source, original, false, null, List.of()));
        return new EditResult.Accepted(List.of());
    }

    @Override
    public EditResult commit(String file, String original, String source, String label) {
        return core.commit(file, original, source, label);
    }

    @Override
    public EditResult show(String file) {
        return new EditResult.Accepted(List.of());
    }
}
