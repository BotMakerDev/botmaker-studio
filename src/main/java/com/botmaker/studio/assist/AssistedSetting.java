package com.botmaker.studio.assist;

/**
 * The project settings an assistant may read and set over MCP (2026-10-06): the two that change what a run says,
 * and, since 2026-10-13, dev mode, which changes which plugin builds load. {@code set_setting} takes one by its
 * constant's name in lower case, which is its {@link #id()}; the tool refuses any other.
 * The rest of {@code settings.json} is the windows' own — layouts, remembered titles, favourites — and no tool
 * reaches it.
 */
public enum AssistedSetting {

    /** The toolbar's 🐞 Debug choice: this checkout's {@code Runs.DEBUG}. */
    DEBUG_OUTPUT("debug_output", "Debug output",
            "bot, on or off — whether every run here prints and traces its debug lines; bot lets the bot's own "
                    + "setting decide"),

    /** What the Trace tab hides, by writer. */
    HIDDEN_TRACE("hidden_trace", "Hidden trace writers",
            "the writers whose lines the Trace tab hides, comma-separated, each a class's binary name or "
                    + "class#method; empty for none"),

    /** *Plugins & Libraries*' Dev mode (2026-10-13): try a plugin's local build before its release. */
    DEV_MODE("dev_mode", "Dev mode",
            "on or off — whether this project, on this computer, loads its plugins' local -SNAPSHOT builds (what "
                    + "mvn install put in ~/.m2); the user is asked on screen before it turns on; the plugins "
                    + "reload at once, so their tools may change");

    private final String id;
    private final String displayName;
    private final String takes;

    AssistedSetting(String id, String displayName, String takes) {
        this.id = id;
        this.displayName = displayName;
        this.takes = takes;
    }

    /** How the assistant names it: its constant's name in lower case, which is what {@code set_setting} matches. */
    public String id() {
        return id;
    }

    /** Its name as Studio's windows show it. */
    public String displayName() {
        return displayName;
    }

    /** What a value of it is, in a sentence a model reads. */
    public String takes() {
        return takes;
    }
}
