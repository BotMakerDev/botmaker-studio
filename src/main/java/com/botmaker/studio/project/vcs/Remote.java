package com.botmaker.studio.project.vcs;

/**
 * The two places besides this computer a bot lives ({@code docs/refactor/39-versions.md} §2), each a git
 * remote of that name. Anything else a user adds by hand is theirs and Studio never reads it.
 *
 * <p>Since 2026-10-25 they carry git's usual names, {@code origin} and {@code upstream};
 * {@link ProjectVcs#adoptRemoteNames} renames a project's {@link #oldId} ones on open.
 */
public enum Remote {

    /** The user's own repository — the one they made, or their fork of someone else's bot. */
    MINE("origin", "mine", "My copy"),
    /** The author's repository, for a bot installed from someone else; its tags are its releases. */
    ORIGINAL("upstream", "original", "Original");

    private final String id;
    private final String oldId;
    private final String displayName;

    Remote(String id, String oldId, String displayName) {
        this.id = id;
        this.oldId = oldId;
        this.displayName = displayName;
    }

    /** The git remote's name. */
    public String id() {
        return id;
    }

    /** The name Studio gave this remote until 2026-10-25. */
    public String oldId() {
        return oldId;
    }

    public String displayName() {
        return displayName;
    }
}
