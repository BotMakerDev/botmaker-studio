package com.botmaker.studio.events;

import com.botmaker.core.events.EventBus;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.java.edit.palette.BlockType;

import java.util.function.Consumer;

/**
 * A {@code CodeEditor}'s output for a test that drives the editor itself: its sentences and added blocks on the bus, as
 * Studio's {@link BusEditorOutput} publishes them, and each write handed to {@code written}. Studio draws a write from
 * the core's {@code MethodsChanged} since plan phase 6e, which a bare editor never sends.
 *
 * @see com.botmaker.core.parser.CodeEditor
 */
public record TestOutput(EventBus bus, Consumer<EditorOutput.Write> written) implements EditorOutput {

    public static EditorOutput of(EventBus bus, Consumer<EditorOutput.Write> written) {
        return new TestOutput(bus, written);
    }

    @Override
    public void status(String message) {
        new BusEditorOutput(bus).status(message);
    }

    @Override
    public void written(Write write) {
        written.accept(write);
    }

    @Override
    public void blockAdded(BlockType type) {
        new BusEditorOutput(bus).blockAdded(type);
    }
}
