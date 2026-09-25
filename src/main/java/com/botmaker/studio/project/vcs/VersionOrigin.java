package com.botmaker.studio.project.vcs;

import java.util.Arrays;

/**
 * Who wrote a version, and why — read off the {@code BotMaker-Origin} trailer every commit Studio writes
 * carries ({@code docs/refactor/39-versions.md} §3). The timeline folds, badges and filters by it, so a user
 * who never pressed a commit button still reads a history of named milestones rather than forty snapshots.
 *
 * <p>The id is written into git and read back by every later Studio, so it never changes once shipped. A
 * commit without the trailer — the user's own from a terminal, or an author's history after a clone — is
 * {@link #UNKNOWN}, and so is an id a newer Studio wrote that this one does not know.
 */
public enum VersionOrigin {

    /** The user's own named version. */
    SAVE("save", "Saved"),
    /** Studio, before a Run or Debug, and when an AI tool session opens. */
    AUTO("auto", "Automatic"),
    /** Studio, when an AI tool session closes: what that session changed. */
    AI("ai", "AI session"),
    /** Studio, before it rewrites files the user is not looking at: a refactor, an upgrade, a host write. */
    SAFETY("safety", "Safety snapshot"),
    /** The merge of an author's release into the user's line. */
    UPDATE("update", "Update"),
    /** The commit a publish tags. */
    PUBLISH("publish", "Published"),
    /** A bot installed from the gallery. */
    INSTALL("install", "Installed"),
    /** A new project's first commit. */
    CREATE("create", "Created"),
    /** A whole-project restore to an earlier version. */
    RESTORE("restore", "Restored"),
    /** No trailer, or one this Studio does not know. */
    UNKNOWN("unknown", "Other");

    /** The git trailer key. */
    public static final String TRAILER = "BotMaker-Origin";

    private final String id;
    private final String displayName;

    VersionOrigin(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** True for the versions the Simple timeline folds together: nobody chose to make them. */
    public boolean automatic() {
        return this == AUTO || this == SAFETY;
    }

    public static VersionOrigin fromId(String id) {
        return Arrays.stream(values()).filter(o -> o.id.equals(id)).findFirst().orElse(UNKNOWN);
    }

    /** {@code label} as a commit message carrying this origin's trailer. */
    public String stamp(String label) {
        return label.strip() + "\n\n" + TRAILER + ": " + id + "\n";
    }

    /**
     * The origin a full commit message declares. Only the last paragraph is a trailer block, as git reads it,
     * so a label that happens to mention the key does not count.
     */
    public static VersionOrigin of(String fullMessage) {
        if (fullMessage == null) return UNKNOWN;
        String[] paragraphs = fullMessage.strip().split("\\n\\s*\\n");
        if (paragraphs.length < 2) return UNKNOWN;
        String prefix = TRAILER + ":";
        for (String line : paragraphs[paragraphs.length - 1].split("\\n")) {
            if (line.startsWith(prefix)) return fromId(line.substring(prefix.length()).strip());
        }
        return UNKNOWN;
    }
}
