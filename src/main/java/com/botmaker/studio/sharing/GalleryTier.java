package com.botmaker.studio.sharing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How much somebody vouches for a gallery listing.
 *
 * <p>The gallery's CI decides it and writes it into {@code catalog.json} as a wire id; Studio only reads it.
 * The same two tiers are {@code botmaker-cli}'s {@code com.botmaker.cli.gallery.Tier}, and the ids must stay
 * equal, but Studio does not depend on the CLI and must not, so this is a second declaration of a closed set
 * whose only shared fact is the id.
 *
 * <p>{@link #fromId} is total and falls to {@link #COMMUNITY}: an id a newer gallery invents must never read
 * as more trusted than the least trusted tier Studio knows.
 */
public enum GalleryTier {

    /** A maintainer looked at one release, {@code vettedVersion}, and chose to list it. */
    VETTED("vetted", "Vetted"),

    /** Listed automatically: well formed, owned by its author, and its release downloads. Nobody read the code. */
    COMMUNITY("community", "Community");

    private final String id;
    private final String displayName;

    GalleryTier(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    @JsonCreator
    public static GalleryTier fromId(String id) {
        for (GalleryTier tier : values()) {
            if (tier.id.equalsIgnoreCase(id == null ? "" : id.trim())) return tier;
        }
        return COMMUNITY;
    }
}
