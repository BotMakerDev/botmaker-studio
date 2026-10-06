package com.botmaker.studio.ui.app;

import com.botmaker.studio.assist.AssistTurn;
import com.botmaker.studio.assist.AssistWorkspace;
import com.botmaker.studio.assist.LiveBot;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.BotSources;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The bot's files for the MCP endpoint, whose calls arrive on Jetty's threads. Both halves run on the FX thread —
 * {@code ProjectState} is confined to it — and the turn's own work runs on the caller's thread in between, so a
 * slow compile never blocks the window.
 *
 * <p>Was {@code LiveEditorFile}, the active file only, until 2026-10-06. A file the editor does not show is read
 * from its buffer or the disk ({@link BotSources}); an edit to it opens it first, so it lands where the user sees
 * it, as one undo step of that file.
 */
final class LiveEditorBot implements LiveBot {

    private static final long WAIT_SECONDS = 30;

    private final StudioContext ctx;

    LiveEditorBot(StudioContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Optional<AssistTurn> begin(String file) {
        return onFx(() -> {
            if (ctx.config() == null) return Optional.<AssistTurn>empty();
            Map<Path, String> sources = sources();
            Path path;
            if (file == null || file.isBlank()) {
                ProjectFile active = ctx.state().getActiveFile();
                if (active == null) {
                    throw new IllegalArgumentException("No file is open in Studio. Give `file`; list_files names them.");
                }
                path = active.getPath();
            } else {
                path = LiveBot.resolve(sources.keySet(), ctx.config().sourceRoot(), file);
            }
            ProjectFile active = ctx.state().getActiveFile();
            String source = active != null && active.getPath().equals(path) ? ctx.state().getCurrentCode()
                    : sources.get(path);
            if (source == null) throw new IllegalArgumentException("Studio cannot read " + file + ".");
            AssistWorkspace workspace = AssistWorkspace.of(ctx.config(), ctx.state(),
                    ctx.projectAnalyzer() == null ? null : ctx.projectAnalyzer().libraryIndex(), path);
            return Optional.of(new AssistTurn(workspace, source));
        });
    }

    @Override
    public String commit(AssistTurn turn, String label) {
        return onFx(() -> {
            ProjectFile active = ctx.state().getActiveFile();
            boolean opened = active == null || !active.getPath().equals(turn.file());
            // What the turn must have started from: the open file's text, or — for a file opened now — its text
            // before opening it, since opening may tidy its switches and that is not the user changing it.
            String live = opened ? sources().get(turn.file()) : ctx.state().getCurrentCode();
            // The edit is made where the user sees it: the editor shows the file, then takes the change.
            if (opened) ctx.codeEditorService().switchToFile(turn.file());
            active = ctx.state().getActiveFile();
            if (active == null || !active.getPath().equals(turn.file())) {
                return "Not applied: Studio could not open " + turn.file().getFileName() + ".";
            }
            Optional<CoreApplicationEvents.CodeUpdatedEvent> event = turn.commit(live, label);
            if (event.isEmpty()) return "Not applied: the file changed in Studio meanwhile. Read the tree again.";
            ctx.eventBus().publish(event.get());
            ctx.eventBus().publish(new CoreApplicationEvents.StatusMessageEvent("An MCP client changed "
                    + turn.file().getFileName() + "."));
            return "Applied to " + turn.file().getFileName() + (opened ? ", now open" : "")
                    + " in Studio; Undo takes it back.";
        });
    }

    @Override
    public List<String> files() {
        return onFx(() -> {
            if (ctx.config() == null) return List.<String>of();
            Path root = ctx.config().sourceRoot();
            return sources().keySet().stream().filter(LiveBot::isJava).map(p -> LiveBot.relative(root, p)).toList();
        });
    }

    /** Every source of the bot, buffer first, by path. FX thread. */
    private Map<Path, String> sources() {
        Map<Path, String> sources = new LinkedHashMap<>();
        BotSources.scan(ctx.config(), ctx.state(), sources::put);
        return sources;
    }

    /**
     * {@code work}'s answer, computed on the FX thread. An {@link IllegalArgumentException} it throws — a
     * refusal — reaches the caller as it was thrown.
     */
    static <T> T onFx(Supplier<T> work) {
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
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException thrown) throw thrown;
            throw new IllegalStateException("the editor did not answer: " + e.getMessage(), e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("the editor did not answer in " + WAIT_SECONDS + "s", e);
        }
    }
}
