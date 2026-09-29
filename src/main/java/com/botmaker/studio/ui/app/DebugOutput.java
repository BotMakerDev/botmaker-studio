package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.Runs;

/**
 * What the toolbar's 🐞 Debug output button says about {@link Runs#DEBUG_PROPERTY}, the one run property the host
 * names ({@code docs/refactor/40-run-trace.md}).
 *
 * <p>Three states, because the property has three: {@code true}, {@code false}, and unset, where the bot's own
 * default decides (the SDK's {@code BotSettings.debug}). A press goes to the next one, so the choice the user
 * makes for this machine is always one they can take back to "whatever the bot says".
 */
enum DebugOutput {
    /** Unset: the bot's own default decides. */
    BOT(null, "🐞 Debug: bot", "Debug output follows the bot's own setting. Press to turn it on for every run here."),
    ON("true", "🐞 Debug: on", "Every run here prints and traces its debug lines. Press to turn them off."),
    OFF("false", "🐞 Debug: off", "Every run here keeps its debug lines quiet. Press to let the bot's own setting decide.");

    private final String propertyValue;
    private final String label;
    private final String explanation;

    DebugOutput(String propertyValue, String label, String explanation) {
        this.propertyValue = propertyValue;
        this.label = label;
        this.explanation = explanation;
    }

    /** What the run property is set to; null clears it. */
    String propertyValue() {
        return propertyValue;
    }

    String label() {
        return label;
    }

    String explanation() {
        return explanation;
    }

    /** The one a press moves to: bot, then on, then off, then bot again. */
    DebugOutput next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * The state {@code value} stands for, read the way the SDK reads the property (trimmed, any case); anything
     * else, or nothing, is {@link #BOT}, since the bot ignores a value it cannot read.
     */
    static DebugOutput fromProperty(String value) {
        if (value == null) return BOT;
        String v = value.trim();
        if (v.equalsIgnoreCase("true")) return ON;
        if (v.equalsIgnoreCase("false")) return OFF;
        return BOT;
    }
}
