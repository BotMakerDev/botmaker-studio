package com.botmaker.studio.ui.app;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.GitHubGallery;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * New Project's "Start from" rows, and the template list it remembers between opens.
 *
 * <p>The dialog opens on the templates the gallery listed last time, so it starts where it will end up; the
 * fresh list replaces them when it lands, and only a fresh row is created from. With nothing remembered it
 * says it is loading rather than offering Blank, which a user would otherwise pick before the templates
 * arrive. Blank is the row once the fetch failed or answered with no template — New Project still works
 * offline.
 */
final class StartingPoints {

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "newProject.templates";

    private StartingPoints() {}

    /** What a row of "Start from" is. */
    enum Kind {
        TEMPLATE("template", null),
        /** A template from the remembered list, shown until the gallery confirms it; never created from. */
        REMEMBERED("remembered", null),
        BLANK("blank", "Blank — a main() and nothing else"),
        LOADING("loading", "Loading templates…");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        String id() {
            return id;
        }

        /** The row's text, or null for a template, which is labelled by its entry. */
        String displayName() {
            return displayName;
        }
    }

    /**
     * One row of the "Start from" list: Studio's blank project, the loading placeholder, or a published
     * template — one list to the user, so one type; the difference is a branch at creation time.
     */
    record TemplateChoice(Kind kind, GalleryEntry entry) {

        static final TemplateChoice BLANK = new TemplateChoice(Kind.BLANK, null);
        static final TemplateChoice LOADING = new TemplateChoice(Kind.LOADING, null);

        static TemplateChoice of(GalleryEntry entry) {
            return new TemplateChoice(Kind.TEMPLATE, entry);
        }

        boolean isBlank() {
            return kind == Kind.BLANK;
        }

        /**
         * Whether this row can be created from: a blank project or a template the gallery listed just now. A
         * remembered entry may since have been delisted or lost its vetted release, so it is never installed.
         */
        boolean isCreatable() {
            return kind == Kind.TEMPLATE || kind == Kind.BLANK;
        }

        String label() {
            if (entry == null) return kind.displayName();
            String description = entry.description().isBlank() ? "" : " — " + entry.description();
            String tier = entry.isVetted() ? "" : " · Community";
            return displayName() + description + "  (" + entry.owner() + tier + ")";
        }

        /**
         * The entry's name with its first letter capitalized, for display only.
         *
         * <p>A gallery entry's name comes from its repository — {@code botmaker-base} publishes as
         * {@code base} — so it is lowercase. Nothing may capitalize it anywhere but here: {@code entry.name()}
         * is what {@code BotInstaller} resolves and what {@code GitHubConfig.entryPath} keys on.
         */
        String displayName() {
            String name = entry.name();
            return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    /** Where the gallery fetch is. */
    enum Fetch {
        PENDING("pending", "Asking the gallery"),
        ANSWERED("answered", "The gallery listed templates"),
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
     * The rows for {@code templates} (already ordered by {@link GalleryEntry#templates}). Pending: the
     * remembered templates, shown and not creatable, or the loading row. Answered: the fresh templates, or
     * Blank with none. Failed: Blank alone — nothing confirms a remembered entry, and it downloads at creation.
     */
    static List<TemplateChoice> rows(List<GalleryEntry> templates, Fetch fetch) {
        return switch (fetch) {
            case PENDING -> templates.isEmpty() ? List.of(TemplateChoice.LOADING)
                    : templates.stream().map(e -> new TemplateChoice(Kind.REMEMBERED, e)).toList();
            case ANSWERED -> templates.isEmpty() ? List.of(TemplateChoice.BLANK)
                    : templates.stream().map(TemplateChoice::of).toList();
            case FAILED -> List.of(TemplateChoice.BLANK);
        };
    }

    /**
     * The row in {@code rows} that is {@code choice}: the same repository — so a picked remembered row is
     * still picked once the gallery confirms it — or the same kind of row without an entry.
     */
    static Optional<TemplateChoice> same(List<TemplateChoice> rows, TemplateChoice choice) {
        if (choice == null) return Optional.empty();
        return rows.stream()
                .filter(r -> r.entry() == null || choice.entry() == null
                        ? r.kind() == choice.kind()
                        : r.entry().slug().equalsIgnoreCase(choice.entry().slug()))
                .findFirst();
    }

    /** The remembered entries, or empty when nothing was remembered or it no longer reads. */
    static List<GalleryEntry> remembered(ObjectMapper mapper) {
        try {
            return fromJson(mapper, Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, null));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Remembers {@code entries}' templates for the next open. Nothing is written when there are none: a fetch
     * that failed must not forget the list that worked.
     */
    static void remember(ObjectMapper mapper, List<GalleryEntry> entries) {
        toJson(mapper, entries).ifPresent(json -> {
            try {
                Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
                prefs.put(PREFS_KEY, json);
                prefs.flush();
            } catch (Exception e) {
                // Not remembered: the next open shows the loading row, then the same list.
            }
        });
    }

    /**
     * {@code entries}' templates in the catalog's own shape, read back by {@link GitHubGallery#parseCatalog};
     * empty when there are none or they outgrow what a preference holds.
     */
    static Optional<String> toJson(ObjectMapper mapper, List<GalleryEntry> entries) {
        List<GalleryEntry> templates = entries.stream().filter(GalleryEntry::isTemplate).toList();
        if (templates.isEmpty()) return Optional.empty();
        try {
            String json = mapper.writeValueAsString(Map.of("bots", templates));
            return json.length() <= Preferences.MAX_VALUE_LENGTH ? Optional.of(json) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static List<GalleryEntry> fromJson(ObjectMapper mapper, String json) {
        return GitHubGallery.parseCatalog(mapper, json).orElse(List.of());
    }
}
