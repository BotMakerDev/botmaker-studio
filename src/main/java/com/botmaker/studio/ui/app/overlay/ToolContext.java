package com.botmaker.studio.ui.app.overlay;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.overlay.Marks;
import com.botmaker.plugin.api.overlay.OverlayToolContext;
import com.botmaker.plugin.api.overlay.Pixel;
import com.botmaker.plugin.api.toolbar.ActionContext.Area;
import com.botmaker.studio.services.capture.ScreenOverlay;
import com.botmaker.studio.services.overlay.WatchedScreen;
import javafx.stage.Window;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One plugin's {@link OverlayToolContext} for one opening of the panel: the watched screen, picks drawn over
 * it, boxes on the desktop layer, and inserts at the panel's caret through {@link Inserter}. FX thread, except
 * {@link #frame()} and {@link #marks()}.
 */
final class ToolContext implements OverlayToolContext {

    /** Inserts a call at the caret: empty when inserted, else why not. */
    @FunctionalInterface
    interface Inserter {
        Optional<String> insert(Executable call, List<Object> arguments);
    }

    /**
     * A pick's surface is owned by no window. Not the panel: the panel hides while a capture surface is up,
     * and hiding an owner closes what it owns, so the pick would cancel as it opened.
     */
    private static final Window PICK_OWNER = null;

    private final StudioServices services;
    private final Supplier<WatchedScreen> screen;
    private final Inserter inserter;
    private final Consumer<String> status;
    private final Marks marks;
    private final List<Runnable> onClosed = new ArrayList<>();
    private boolean closed;

    /**
     * @param status the panel's status line, where a pick's prompt is shown while the screen is picked on
     * @param marks  this tool's boxes, in the bot's pixels; cleared when the panel closes
     */
    ToolContext(StudioServices services, Supplier<WatchedScreen> screen, Inserter inserter, Consumer<String> status,
                Marks marks) {
        this.services = services;
        this.screen = screen;
        this.inserter = inserter;
        this.status = status;
        this.marks = marks;
    }

    @Override
    public Marks marks() {
        return marks;
    }

    @Override
    public StudioServices services() {
        return services;
    }

    @Override
    public Optional<BufferedImage> frame() {
        WatchedScreen watched = screen.get();
        return watched == null ? Optional.empty() : watched.frame();
    }

    @Override
    public Optional<Area> watchedArea() {
        WatchedScreen watched = screen.get();
        return watched == null ? Optional.empty() : watched.area();
    }

    @Override
    public CompletionStage<Optional<Area>> pickRegion(String prompt) {
        CompletableFuture<Optional<Area>> picked = new CompletableFuture<>();
        WatchedShots shots = new WatchedShots(screen);
        status.accept(prompt);
        new ScreenOverlay(shots).selectRegion(PICK_OWNER, r -> {
            Rectangle at = shots.origin();
            picked.complete(Optional.of(new Area(at.x + r[0], at.y + r[1], r[2], r[3])));
        }, () -> picked.complete(Optional.empty()));
        return picked;
    }

    @Override
    public CompletionStage<Optional<Pixel>> pickPoint(String prompt) {
        CompletableFuture<Optional<Pixel>> picked = new CompletableFuture<>();
        WatchedShots shots = new WatchedShots(screen);
        status.accept(prompt);
        new ScreenOverlay(shots).pickPoint(PICK_OWNER, p -> {
            Rectangle at = shots.origin();
            picked.complete(Optional.of(new Pixel(at.x + p[0], at.y + p[1])));
        }, () -> picked.complete(Optional.empty()));
        return picked;
    }

    @Override
    public void onClosed(Runnable action) {
        if (action == null) return;
        if (closed) run(action);
        else onClosed.add(action);
    }

    @Override
    public Optional<String> insertMember(Executable call, Object... arguments) {
        // Arrays.asList, not List.of: a null argument is the plugin's mistake to be told about, not an NPE here.
        return inserter.insert(call, arguments == null ? List.of() : Arrays.asList(arguments));
    }

    /** Runs every close action once; one that throws costs only itself. */
    void close() {
        if (closed) return;
        closed = true;
        for (Runnable action : onClosed) run(action);
        onClosed.clear();
        marks.clear();
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error e) {
            System.err.println("Warning: an overlay tool's close action threw: " + e);
        }
    }
}
