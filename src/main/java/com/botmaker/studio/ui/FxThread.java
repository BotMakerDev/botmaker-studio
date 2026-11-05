package com.botmaker.studio.ui;

import com.botmaker.studio.events.EventBus;
import javafx.application.Platform;

/** The JavaFX application thread, as the {@link EventBus} a Studio window listens on delivers to it. */
public final class FxThread implements EventBus.UiThread {

    public static final FxThread INSTANCE = new FxThread();

    private FxThread() {}

    @Override
    public boolean isCurrent() {
        return Platform.isFxApplicationThread();
    }

    @Override
    public void later(Runnable delivery) {
        Platform.runLater(delivery);
    }
}
