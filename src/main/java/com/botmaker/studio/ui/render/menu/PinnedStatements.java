package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.project.ProjectPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The statement-menu entries the user pinned to the top, by palette id — kept in {@link ProjectPreferences}
 * so a pin survives a restart and follows the user from project to project.
 *
 * <p>An id is only a pin, never a block: the menu offers a pinned entry only when it would offer it anyway
 * (a plugin's call while that plugin is loaded, {@code yield} inside a switch). A pin nothing offers here is
 * kept, not deleted, so opening a project without that plugin does not lose it. FX thread only, like the menu.
 */
public final class PinnedStatements {

    /** Where the pins live. {@link #PREFERENCES} in the app; a test swaps in memory. */
    public interface Store {
        List<String> read();

        void write(List<String> ids);
    }

    public static final Store PREFERENCES = new Store() {
        @Override public List<String> read() {
            return ProjectPreferences.load().getPinnedStatements();
        }

        @Override public void write(List<String> ids) {
            ProjectPreferences prefs = ProjectPreferences.load();
            prefs.setPinnedStatements(ids);
            prefs.save();
        }
    };

    private static Store store = PREFERENCES;

    private PinnedStatements() {}

    /** Replaces where pins are kept; for tests. */
    public static void useStore(Store replacement) {
        store = replacement == null ? PREFERENCES : replacement;
    }

    /** The pinned ids, oldest pin first. */
    public static List<String> ids() {
        return List.copyOf(store.read());
    }

    public static boolean isPinned(String id) {
        return store.read().contains(id);
    }

    /** Pins {@code id}, or unpins it when it is pinned already. */
    public static void toggle(String id) {
        List<String> ids = new ArrayList<>(store.read());
        if (!ids.remove(id)) ids.add(id);
        store.write(ids);
    }
}
