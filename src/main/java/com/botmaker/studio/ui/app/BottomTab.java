package com.botmaker.studio.ui.app;

/**
 * The tabs of the bottom tool window, in the order they are added.
 *
 * <p>A closed set rather than an {@code int}: the shell used to say {@code selectBottomTab(0)} for Terminal,
 * {@code selectBottomTab(1)} for Errors and carry a computed {@code vcsTabIndex} field for the third — three
 * spellings of one fixed list, none of which the compiler could check. The title lives here too, so the tab
 * and the thing that raises it can't drift apart.
 */
enum BottomTab {
    /**
     * What the running bot prints — see {@link RunConsole}. It was titled "Terminal" and keyed {@code TERMINAL}
     * until 2026-09-25, which it never was.
     */
    RUN("Run"),
    /** Shells in the project directory on a real PTY — see {@link com.botmaker.studio.ui.app.terminal.TerminalPane}. */
    TERMINAL("Terminal"),
    ERRORS("Errors"),
    /** What a refactor changed and could not finish — see {@link ReviewPanel}. */
    REVIEW("Review"),
    VCS("VCS"),
    /** A model changing the open file's blocks through the host's own edit checks — see {@link AssistantPane}. */
    ASSISTANT("Assistant");

    private final String title;

    BottomTab(String title) {
        this.title = title;
    }

    String title() {
        return title;
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
