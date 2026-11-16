// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app;

import com.botmaker.core.events.ApplicationEvent;
import com.botmaker.studio.events.CoreApplicationEvents;

import java.util.function.Supplier;

/**
 * What the toolbar's Run split button does (2026-11-13, spec {@code 2026-11-13-studio-window-cleanup}): its menu
 * lists these, and its face repeats the last one chosen, kept per project in the workspace layout by {@link #id()}.
 */
public enum RunChoice {
    RUN("run", "▶ Run", "Run the bot", CoreApplicationEvents.ExecutionRequestedEvent::new),
    DEBUG("debug", "🐞 Debug", "Run the bot and pause at its breakpoints",
            CoreApplicationEvents.DebugStartRequestedEvent::new),
    FOLLOW("follow", "👁 Follow", "Run and highlight each executing block live (never pauses at breakpoints)",
            CoreApplicationEvents.FollowStartRequestedEvent::new),
    COMPILE("compile", "⚙ Compile", "Compile the bot without running it",
            CoreApplicationEvents.CompilationRequestedEvent::new);

    private final String id;
    private final String displayName;
    private final String tooltip;
    private final Supplier<ApplicationEvent> request;

    RunChoice(String id, String displayName, String tooltip, Supplier<ApplicationEvent> request) {
        this.id = id;
        this.displayName = displayName;
        this.tooltip = tooltip;
        this.request = request;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String tooltip() {
        return tooltip;
    }

    /** The request this choice publishes when pressed. */
    public ApplicationEvent request() {
        return request.get();
    }

    /** The choice saved as {@code id}; never chosen, or a key this Studio lacks, is {@link #RUN}. */
    public static RunChoice fromId(String id) {
        for (RunChoice c : values()) if (c.id.equals(id)) return c;
        return RUN;
    }
}
