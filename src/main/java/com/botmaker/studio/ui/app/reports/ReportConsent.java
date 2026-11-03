package com.botmaker.studio.ui.app.reports;

import com.botmaker.studio.project.StudioProjectSettings;

/**
 * Whether this machine sends a bot's problems to its author (run reports spec §3): a run property of the bot,
 * {@value #PROPERTY}, so a fact about this computer, never committed or published.
 */
public enum ReportConsent {
    ASK("ask", "Ask each time"),
    ALWAYS("always", "Always send"),
    NEVER("never", "Never");

    /** The run property that holds it. */
    public static final String PROPERTY = "botmaker.reports";

    private final String id;
    private final String displayName;

    ReportConsent(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * The consent {@code id} names; {@link #ASK} for anything else. Ask, not an {@code UNKNOWN}: a value this Studio
     * cannot read must neither send without asking nor stay silent forever.
     */
    public static ReportConsent fromId(String id) {
        for (ReportConsent c : values()) {
            if (c.id.equals(id == null ? null : id.strip())) return c;
        }
        return ASK;
    }

    /** {@code settings}'s consent, {@link #ASK} when it holds none. */
    public static ReportConsent of(StudioProjectSettings settings) {
        return settings == null ? ASK : fromId(settings.runProperties().get(PROPERTY));
    }
}
