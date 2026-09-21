package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.toolbar.ActionContext;
import com.botmaker.studio.project.ProjectConfig;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Studio's side of {@link ActionContext} for an item on the overlay editor's row.
 *
 * <p>It is {@link HostActionContext} plus the three facts only an open overlay can answer. A delegation
 * rather than a subclass because {@code HostActionContext} is final, and it should stay final: the base facts
 * have one answer, and a second implementation of them is a second answer that drifts.
 *
 * <p><b>Every added member is a supplier.</b> The overlay stays open for as long as a user works in the game,
 * during which the window it is drawn over is moved, resized and re-raised, and the insertion cursor moves on
 * every click in the HUD. A context holding values would answer with whatever was true when the row was
 * built — which for the cursor is the single most misleading answer available, since the whole point of
 * {@link ActionContext#insertAtCursor} is that a recorded action lands where the user is looking <em>now</em>.
 * It is the same rule {@link HostActionContext} keeps for the project and {@code HostSlotContext} keeps for
 * the slot, and here the fuse is the shortest of the three.
 */
public final class HostOverlayContext implements ActionContext {

    private final HostActionContext base;
    private final Supplier<String> windowTitle;
    private final Supplier<ActionContext.Area> bounds;
    private final Consumer<String[]> insert;

    /**
     * @param project     where the currently open project comes from; may answer null
     * @param pin         how the calling plugin's pinned version is read, as {@link HostActionContext} takes it
     * @param windowTitle the title of the window the HUD is currently drawn over; null or blank reads as none
     * @param bounds      where that window sits <em>right now</em>; null reads as none
     * @param insert      places whole statements at the editor's insertion cursor
     */
    public HostOverlayContext(Supplier<ProjectConfig> project, Supplier<String> pin,
                              Supplier<String> windowTitle, Supplier<ActionContext.Area> bounds,
                              Consumer<String[]> insert) {
        this.base = new HostActionContext(project, pin);
        this.windowTitle = windowTitle;
        this.bounds = bounds;
        this.insert = insert;
    }

    @Override
    public java.util.Optional<String> openProjectName() {
        return base.openProjectName();
    }

    @Override
    public String pinnedVersion() {
        return base.pinnedVersion();
    }

    @Override
    public StudioServices services() {
        return base.services();
    }

    @Override
    public Optional<String> overWindowTitle() {
        String title = windowTitle == null ? null : windowTitle.get();
        return title == null || title.isBlank() ? Optional.empty() : Optional.of(title);
    }

    @Override
    public Optional<ActionContext.Area> overBounds() {
        return Optional.ofNullable(bounds == null ? null : bounds.get());
    }

    @Override
    public void insertAtCursor(String... statements) {
        if (insert == null || statements == null || statements.length == 0) return;
        insert.accept(statements);
    }
}
