package com.botmaker.studio.ui.app;

import com.botmaker.studio.assist.AssistTurn;
import com.botmaker.studio.assist.AssistWorkspace;
import com.botmaker.studio.assist.LiveFile;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.StudioContext;
import javafx.application.Platform;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The editor's active file for the MCP endpoint, whose calls arrive on Jetty's threads. Both halves run on the
 * FX thread — {@code ProjectState} is confined to it — and the turn's own work runs on the caller's thread in
 * between, so a slow compile never blocks the window.
 */
final class LiveEditorFile implements LiveFile {

    private static final long WAIT_SECONDS = 30;

    private final StudioContext ctx;

    LiveEditorFile(StudioContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Optional<AssistTurn> begin() {
        return onFx(() -> {
            if (ctx.state().getActiveFile() == null) return Optional.<AssistTurn>empty();
            AssistWorkspace workspace = AssistWorkspace.of(ctx.config(), ctx.state(),
                    ctx.projectAnalyzer() == null ? null : ctx.projectAnalyzer().libraryIndex(),
                    ctx.sdkSurfaceService());
            return Optional.of(new AssistTurn(workspace, ctx.state().getCurrentCode()));
        });
    }

    @Override
    public String commit(AssistTurn turn, String label) {
        return onFx(() -> {
            Optional<CoreApplicationEvents.CodeUpdatedEvent> event = turn.commit(ctx.state().getCurrentCode(), label);
            if (event.isEmpty()) return "Not applied: the file changed in Studio meanwhile. Read the tree again.";
            ctx.eventBus().publish(event.get());
            ctx.eventBus().publish(new CoreApplicationEvents.StatusMessageEvent("An MCP client changed this file."));
            return "Applied to the open file in Studio; Undo takes it back.";
        });
    }

    private static <T> T onFx(Supplier<T> work) {
        if (Platform.isFxApplicationThread()) return work.get();
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the editor", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("the editor did not answer: " + e.getMessage(), e);
        }
    }
}
