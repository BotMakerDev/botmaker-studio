package com.botmaker.studio.sharing;

import java.util.List;
import java.util.prefs.Preferences;

/**
 * Whether this computer's lists show community bots and plugins (2026-10-23): <i>Show community bots and
 * plugins</i>, off until the user turns it on. Off, Browse Bots, New Project, <i>Plugins &amp; Libraries ▸
 * Browse</i> and the assistant's {@code list_bots} show only official and trusted rows; a bot that uses a
 * community plugin counts as community ({@link GalleryEntry#trust}). Kept on this computer only, like
 * {@link HiddenBots}.
 *
 * <p>The rules are on the value ({@link #bots}, {@link #plugins}) so they are tested without the preference store;
 * {@link #load} and {@link #save} are the edges.
 */
public record ShowCommunity(boolean shown) {

    /** The toggle's words, the same in every window that has it. */
    public static final String LABEL = "Show community bots and plugins";

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "catalog.showCommunity";

    /** This computer's choice; off when the preference store cannot be read. */
    public static ShowCommunity load() {
        try {
            return new ShowCommunity(Preferences.userRoot().node(PREFS_NODE).getBoolean(PREFS_KEY, false));
        } catch (Exception e) {
            return new ShowCommunity(false);
        }
    }

    /** Writes this choice as this computer's. Best effort: one that cannot be written stays as it was. */
    public void save() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.putBoolean(PREFS_KEY, shown);
            prefs.flush();
        } catch (Exception e) {
            System.err.println("Could not remember whether to show community listings: " + e.getMessage());
        }
    }

    /** {@code entries} this choice shows, {@link GalleryEntry#RANKED}. */
    public List<GalleryEntry> bots(List<GalleryEntry> entries) {
        return entries.stream().filter(e -> shown || e.trust() != PublisherRank.COMMUNITY)
                .sorted(GalleryEntry.RANKED).toList();
    }

    /** {@code rows} this choice shows, {@link PluginRegistry.Plugin#RANKED}. */
    public List<PluginRegistry.Plugin> plugins(List<PluginRegistry.Plugin> rows) {
        return rows.stream().filter(p -> shown || p.rank() != PublisherRank.COMMUNITY)
                .sorted(PluginRegistry.Plugin.RANKED).toList();
    }
}
