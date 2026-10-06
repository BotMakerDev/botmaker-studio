package com.botmaker.studio.ui.app;

import java.util.Arrays;
import java.util.List;

/**
 * The project settings an assistant may read and set over MCP (2026-10-06): the two that change what a run says.
 * The rest of {@code settings.json} is the windows' own — layouts, remembered titles, favourites — and no tool
 * reaches it.
 */
enum AssistedSetting {

    /** The toolbar's 🐞 Debug choice: this checkout's {@code Runs.DEBUG_PROPERTY}. */
    DEBUG_OUTPUT("debug_output", "Debug output",
            "bot, on or off — whether every run here prints and traces its debug lines; bot lets the bot's own "
                    + "setting decide"),

    /** What the Trace tab hides, by writer. */
    HIDDEN_TRACE("hidden_trace", "Hidden trace writers",
            "the writers whose lines the Trace tab hides, comma-separated, each a class's binary name or "
                    + "class#method; empty for none"),

    UNKNOWN("", "", "");

    private final String id;
    private final String displayName;
    private final String takes;

    AssistedSetting(String id, String displayName, String takes) {
        this.id = id;
        this.displayName = displayName;
        this.takes = takes;
    }

    String id() {
        return id;
    }

    String displayName() {
        return displayName;
    }

    /** What a value of it is, in a sentence a model reads. */
    String takes() {
        return takes;
    }

    /** Every setting but {@link #UNKNOWN}. */
    static List<AssistedSetting> offered() {
        return Arrays.stream(values()).filter(s -> s != UNKNOWN).toList();
    }

    /** The setting {@code id} names; {@link #UNKNOWN} for anything else. */
    static AssistedSetting fromId(String id) {
        return offered().stream().filter(s -> s.id.equals(id)).findFirst().orElse(UNKNOWN);
    }
}
