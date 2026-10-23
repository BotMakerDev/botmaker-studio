package com.botmaker.studio.sharing;

import java.util.Arrays;
import java.util.Locale;

/**
 * Who published a catalog row, by its repository owner's account (2026-10-23): the catalog's
 * {@code publishers.json} lists the admin and trusted accounts, and everyone else is community. A rank says who
 * published, never that anybody read the code.
 */
public enum PublisherRank {
    ADMIN("admin", "Official"),
    TRUSTED("trusted", "Trusted"),
    COMMUNITY("community", "Community");

    private final String id;
    private final String displayName;

    PublisherRank(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The id {@code catalog.json} writes. */
    public String id() {
        return id;
    }

    /** What a badge says. */
    public String displayName() {
        return displayName;
    }

    /** The less trusted of {@code a} and {@code b}; the constants are declared most trusted first. */
    public static PublisherRank lower(PublisherRank a, PublisherRank b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * The rank {@code id} names. An id this Studio does not know is {@link #COMMUNITY}: a reader that lags must
     * never rank a row above what it can vouch for.
     */
    public static PublisherRank fromId(String id) {
        String wanted = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(rank -> rank.id.equals(wanted)).findFirst().orElse(COMMUNITY);
    }
}
