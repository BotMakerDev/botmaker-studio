package com.botmaker.studio.events;

import com.botmaker.core.events.EventBus;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.java.edit.palette.BlockType;

/**
 * The core editor's output as Studio's events, published on {@code bus}: its sentences for the status bar and the blocks
 * it added. A write is not one of them: Studio draws it from the core's {@code MethodsChanged} (plan phase 6e), which
 * carries the file's new text, and the core records it in its history.
 */
public record BusEditorOutput(EventBus bus) implements EditorOutput {

    @Override
    public void status(String message) {
        bus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }

    @Override
    public void written(Write write) {
        // Announced by the core as MethodsChanged, which CodeEditorService redraws from.
    }

    @Override
    public void blockAdded(BlockType type) {
        bus.publish(new CoreApplicationEvents.BlockAddedEvent(type));
    }
}
