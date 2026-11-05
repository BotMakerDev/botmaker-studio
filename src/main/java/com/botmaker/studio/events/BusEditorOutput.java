package com.botmaker.studio.events;

import com.botmaker.core.events.EventBus;
import com.botmaker.core.parser.EditorOutput;
import com.botmaker.java.edit.palette.BlockType;

/** The core editor's output as Studio's events, published on {@code bus}: what the window and history listen to. */
public record BusEditorOutput(EventBus bus) implements EditorOutput {

    @Override
    public void status(String message) {
        bus.publish(new CoreApplicationEvents.StatusMessageEvent(message));
    }

    @Override
    public void written(Write write) {
        bus.publish(new CoreApplicationEvents.CodeUpdatedEvent(write.newCode(), write.previousCode(),
                write.markNewIdentifiersAsUnedited(), write.label(), write.alsoChanged()));
    }

    @Override
    public void blockAdded(BlockType type) {
        bus.publish(new CoreApplicationEvents.BlockAddedEvent(type));
    }
}
