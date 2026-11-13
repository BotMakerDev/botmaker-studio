package com.botmaker.studio.ui.app;

/**
 * The tabs of the bottom tool window, in the order they are added.
 *
 * <p>A closed set rather than an {@code int}: the shell used to say {@code selectBottomTab(0)} for Terminal,
 * {@code selectBottomTab(1)} for Errors and carry a computed {@code vcsTabIndex} field for the third — three
 * spellings of one fixed list, none of which the compiler could check. The title lives here too, so the tab
 * and the thing that raises it can't drift apart. Which are there when is {@link BottomTabs}' (2026-11-13).
 */
enum BottomTab {
    /**
     * What the running bot prints — see {@link RunConsole}. It was titled "Terminal" and keyed {@code TERMINAL}
     * until 2026-09-25, which it never was.
     */
    RUN("Run", true, false),
    // TRACE("Trace") stood here from 2026-09-29 until 2026-11-13 and is the Run tab's Trace chip now (RunConsole.View);
    // a layout saved on it opens on the default tab, Run.
    // REPORTS("Reports") stood here from 2026-11-03 until 2026-11-13 and is the Reports window now (ToolWindow); a
    // layout saved on it opens on the default tab.
    /**
     * Shells in the project directory on a real PTY — see {@link com.botmaker.studio.ui.app.terminal.TerminalPane}.
     * From View ▸ Terminal, and always in dev mode.
     */
    TERMINAL("Terminal", false, true),
    // ERRORS("Errors") and REVIEW("Review") stood here until 2026-11-13 and are the status bar's list now
    // (ProjectStatusBar); a layout saved on either opens on the default tab.
    /** Where the selected block's name is used, across the bot — see {@link UsagesPanel} (2026-09-26). From Find Usages. */
    USAGES("Usages", false, true),
    /** The paused bot's call stack and variables — see {@link DebugPanel} (2026-09-26). While a debug session lasts. */
    DEBUG("Debug", false, false);
    // VERSIONS("Versions"), keyed VCS until 2026-09-25, stood here until 2026-11-13 and is the Versions window now
    // (ToolWindow); a layout saved on it opens on the default tab.
    // ASSISTANT("Assistant") stood here until 2026-11-11 and is the Assistant window now (AssistantWindow); a layout
    // saved on it opens on the default tab.

    private final String title;
    private final boolean always;
    private final boolean closable;

    BottomTab(String title, boolean always, boolean closable) {
        this.title = title;
        this.always = always;
        this.closable = closable;
    }

    String title() {
        return title;
    }

    /** Whether the tab is there from the start and never goes; the others come with what they show. */
    boolean always() {
        return always;
    }

    /** Whether the tab has its ✕. */
    boolean closable() {
        return closable;
    }

    /** The stable key this tab is persisted under. The title is a label and may be reworded; this may not. */
    String key() {
        return name();
    }

    /**
     * The tab {@code key} names, or {@code null} for an unknown or absent one — a total parse, so a layout
     * saved by a newer Studio (or hand-edited) opens on the default tab instead of failing to open.
     */
    static BottomTab named(String key) {
        if (key == null) return null;
        for (BottomTab tab : values()) {
            if (tab.key().equals(key)) return tab;
        }
        return null;
    }
}
