package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * New Project's "Start from" rows.
 *
 * <p>Every published bot is a starting point (2026-10-23; only bots tagged {@code template} were until then), and
 * a bot row is cloned linked to its author, as Browse Bots installs it. Blank, Studio's own project, is the first
 * row and the default ({@link #rows}). Below it the dialog shows the bots of the catalog read last time, so it
 * starts where it will end up; the fresh list replaces them when it lands, and only a fresh row is created from.
 * New Project still works offline: Blank stays.
 */
final class StartingPoints {

    private StartingPoints() {}

    /** What a row of "Start from" is. */
    enum Kind {
        BOT("bot", null),
        /** A bot from the remembered list, shown until the gallery confirms it; never created from. */
        REMEMBERED("remembered", null),
        BLANK("blank", "Blank — a main() and nothing else"),
        LOADING("loading", "Loading published bots…");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        String id() {
            return id;
        }

        /** The row's text, or null for a bot, which is labelled by its entry. */
        String displayName() {
            return displayName;
        }
    }

    /**
     * One row of the "Start from" list: Studio's blank project, the loading placeholder, or a published bot —
     * one list to the user, so one type; the difference is a branch at creation time.
     */
    record StartChoice(Kind kind, GalleryEntry entry) {

        static final StartChoice BLANK = new StartChoice(Kind.BLANK, null);
        static final StartChoice LOADING = new StartChoice(Kind.LOADING, null);

        static StartChoice of(GalleryEntry entry) {
            return new StartChoice(Kind.BOT, entry);
        }

        boolean isBlank() {
            return kind == Kind.BLANK;
        }

        /**
         * Whether this row can be created from: a blank project or a bot the gallery listed just now. A
         * remembered entry may since have been delisted or lost its release, so it is never cloned.
         */
        boolean isCreatable() {
            return kind == Kind.BOT || kind == Kind.BLANK;
        }

        String label() {
            if (entry == null) return kind.displayName();
            String description = entry.description().isBlank() ? "" : " — " + entry.description();
            String basedOn = entry.basedOnLine().isBlank() ? "" : ", " + entry.basedOnLine();
            return displayName() + description + "  (" + entry.owner() + basedOn + ")";
        }

        /**
         * The entry's name with its first letter capitalized, for display only.
         *
         * <p>A gallery entry's name may come from its repository, lowercase. Nothing may capitalize it anywhere
         * but here: {@code entry.name()} is what {@code BotInstaller} resolves.
         */
        String displayName() {
            String name = entry.name();
            return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    /** Where the gallery fetch is. */
    enum Fetch {
        PENDING("pending", "Asking the gallery"),
        ANSWERED("answered", "The gallery listed bots"),
        FAILED("failed", "The gallery could not be reached");

        private final String id;
        private final String displayName;

        Fetch(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        String id() {
            return id;
        }

        String displayName() {
            return displayName;
        }
    }

    /**
     * The rows for {@code bots} (already ordered by {@link GalleryEntry#startingPoints}), Blank first in every
     * state: it is the one row that needs no network, and the default — starting from a bot runs somebody
     * else's code, so it is picked, never preselected. Pending: the remembered bots, shown and not creatable, or
     * the loading row. Answered: the fresh bots. Failed: Blank alone — nothing confirms a remembered entry, and
     * it clones at creation.
     */
    static List<StartChoice> rows(List<GalleryEntry> bots, Fetch fetch) {
        List<StartChoice> after = switch (fetch) {
            case PENDING -> bots.isEmpty() ? List.of(StartChoice.LOADING)
                    : bots.stream().map(e -> new StartChoice(Kind.REMEMBERED, e)).toList();
            case ANSWERED -> bots.stream().map(StartChoice::of).toList();
            case FAILED -> List.of();
        };
        return Stream.concat(Stream.of(StartChoice.BLANK), after.stream()).toList();
    }

    /**
     * The row in {@code rows} that is {@code choice}: the same repository — so a picked remembered row is
     * still picked once the gallery confirms it — or the same kind of row without an entry.
     */
    static Optional<StartChoice> same(List<StartChoice> rows, StartChoice choice) {
        if (choice == null) return Optional.empty();
        return rows.stream()
                .filter(r -> r.entry() == null || choice.entry() == null
                        ? r.kind() == choice.kind()
                        : r.entry().slug().equalsIgnoreCase(choice.entry().slug()))
                .findFirst();
    }

    // remembered()/remember(), a copy of the bot list in a preference, went on 2026-10-23: the catalog read last
    // time is kept on disk whole (CatalogClient.kept), and a list of catalog rows outgrew what a preference holds.
}
