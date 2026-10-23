package com.botmaker.studio.sharing;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * The bots this computer's user chose not to see, by {@code owner/repo} (2026-10-23): Browse Bots' <i>Hide this
 * bot</i>. Kept on this computer only, as one preference: nothing about it is published, and no one else's list
 * changes. New Project and the assistant's {@code list_bots} leave these out; Browse Bots shows them again under
 * <i>Show hidden</i>.
 *
 * <p>The rules are on an immutable value ({@link #of}, {@link #without}, {@link #with}) so they are tested without
 * the preference store; {@link #load} and {@link #save} are the edges.
 */
public record HiddenBots(Set<String> slugs) {

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "gallery.hidden";

    public HiddenBots {
        Set<String> normal = new LinkedHashSet<>();
        for (String slug : slugs) normal.add(normal(slug));
        slugs = Set.copyOf(normal);
    }

    /** The list a preference holds: one {@code owner/repo} per line. */
    public static HiddenBots of(String stored) {
        Set<String> slugs = new LinkedHashSet<>();
        for (String line : (stored == null ? "" : stored).split("\n")) {
            if (line.contains("/")) slugs.add(line.trim());
        }
        return new HiddenBots(slugs);
    }

    /** This computer's list; empty when the preference store cannot be read. */
    public static HiddenBots load() {
        try {
            return of(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, ""));
        } catch (Exception e) {
            return new HiddenBots(Set.of());
        }
    }

    /** Writes this list as this computer's. Best effort: a list that cannot be written stays as it was. */
    public void save() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, String.join("\n", slugs.stream().sorted().toList()));
            prefs.flush();
        } catch (Exception e) {
            System.err.println("Could not remember the hidden bots: " + e.getMessage());
        }
    }

    public boolean hides(GalleryEntry entry) {
        return slugs.contains(normal(entry.slug()));
    }

    /** This list with {@code entry} hidden ({@code hidden}) or shown again. */
    public HiddenBots with(GalleryEntry entry, boolean hidden) {
        Set<String> next = new LinkedHashSet<>(slugs);
        if (hidden) next.add(normal(entry.slug()));
        else next.remove(normal(entry.slug()));
        return new HiddenBots(next);
    }

    /** {@code entries} with the hidden ones left out, in their order. */
    public List<GalleryEntry> without(List<GalleryEntry> entries) {
        return entries.stream().filter(e -> !hides(e)).toList();
    }

    /** GitHub compares owners and names without case, and so does this. */
    private static String normal(String slug) {
        return slug.trim().toLowerCase(Locale.ROOT);
    }
}
