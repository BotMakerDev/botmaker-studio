package com.botmaker.studio.project.vcs;

/**
 * The two places besides this computer a bot lives ({@code docs/refactor/39-versions.md} §2), each a git
 * remote of that name. Anything else a user adds by hand is theirs and Studio never reads it.
 */
public enum Remote {

    /** The user's own repository — the one they made, or their fork of someone else's bot. */
    MINE("mine", "My copy"),
    /** The author's repository, for a bot installed from someone else; its tags are its releases. */
    ORIGINAL("original", "Original");

    private final String id;
    private final String displayName;

    Remote(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The git remote's name. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }
}
