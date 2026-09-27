package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.toolbar.ActionContext;
import com.botmaker.studio.project.ProjectConfig;

/**
 * Studio's side of {@link ActionContext} — the facts a toolbar item's click is handed.
 *
 * <p><b>Everything is read at call time, nothing is captured.</b> The same rule {@link HostSlotContext}
 * keeps, for the same reason and with a longer fuse here: a toolbar button is built once when a project's
 * plugins are bound and outlives every project opened after it, so a context holding the project it was
 * created with would answer for a project the user closed an hour ago.
 */
public final class HostActionContext implements ActionContext {

    /** Where the currently open project comes from; may answer null, which is an ordinary state. */
    private final java.util.function.Supplier<ProjectConfig> project;

    public HostActionContext(java.util.function.Supplier<ProjectConfig> project) {
        this.project = project == null ? () -> null : project;
    }

    @Override
    public StudioServices services() {
        return HostServices.forProject(project.get());
    }
}
