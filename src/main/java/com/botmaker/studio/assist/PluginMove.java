package com.botmaker.studio.assist;

/**
 * The assistant's plugin version move, from the question on screen to its end (2026-10-13). A confirmed
 * {@code plugins set_version} answers once the user is asked, since the user's answer and the pass after it outlast
 * an MCP client's call; {@code plugins list} says where the move is with {@link #line()}. At most one runs.
 */
public record PluginMove(String plugin, String from, String to, Stage stage, String detail) {

    /** Where a move is. */
    public enum Stage {
        /** The question is on screen. */
        ASKED,
        /** The user agreed; the bot is being repaired and the pom written. */
        MOVING,
        /** Done; {@code detail} is the pass's summary. */
        MOVED,
        /** The user said no, or did not answer; {@code detail} says which. */
        DECLINED,
        /** The pass stopped; {@code detail} is why. */
        FAILED
    }

    public PluginMove {
        detail = detail == null ? "" : detail.strip();
    }

    /** A move whose question was just put on screen. */
    public static PluginMove asked(String plugin, String from, String to) {
        return new PluginMove(plugin, from, to, Stage.ASKED, "");
    }

    public PluginMove moving() {
        return new PluginMove(plugin, from, to, Stage.MOVING, "");
    }

    public PluginMove moved(String summary) {
        return new PluginMove(plugin, from, to, Stage.MOVED, summary);
    }

    public PluginMove declined(String why) {
        return new PluginMove(plugin, from, to, Stage.DECLINED, why);
    }

    public PluginMove failed(String why) {
        return new PluginMove(plugin, from, to, Stage.FAILED, why);
    }

    /** Whether it has not ended: another move waits for it. */
    public boolean busy() {
        return stage == Stage.ASKED || stage == Stage.MOVING;
    }

    /** What the confirmed call answers. */
    public String started() {
        return "The user is being asked on screen to move " + plugin + " from " + from + " to " + to + ". It moves "
                + "once they agree; plugins list says when it is done, and the plugin's tools are served anew then.";
    }

    /** Where the move is, as plugins list says it. */
    public String line() {
        String move = plugin + " from " + from + " to " + to;
        return switch (stage) {
            case ASKED -> "Moving " + move + ": the user is asked on screen.";
            case MOVING -> "Moving " + move + ": the user agreed, and the bot is being repaired.";
            case MOVED -> "Last move: " + detail;
            case DECLINED -> "Not moved " + move + ": " + detail;
            case FAILED -> "Moving " + move + " failed: " + detail + " It may have moved part way; the version saved "
                    + "before it is in versions list, for versions revert.";
        };
    }
}
