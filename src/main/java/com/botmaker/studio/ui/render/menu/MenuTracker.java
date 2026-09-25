package com.botmaker.studio.ui.render.menu;

import javafx.scene.control.ContextMenu;

import java.lang.ref.WeakReference;

/**
 * Keeps one canvas menu open at a time: showing a tracked menu hides the one shown before it.
 *
 * <p>Each opener builds a fresh {@link ContextMenu} and nothing knew about the others, so a click on a slot's
 * "+" while the insert menu was up left both open, each able to edit the file under the other. Tracking is
 * opt-in per menu ({@link #track}) and done where the canvas's menus are built, so a dialog's own menus are
 * untouched. FX thread only, like every menu.
 */
public final class MenuTracker {

    private static WeakReference<ContextMenu> current = new WeakReference<>(null);

    private MenuTracker() {}

    /** Registers {@code menu}: from now on, showing it closes whichever tracked menu is open. */
    public static ContextMenu track(ContextMenu menu) {
        // The press that closes this menu by clicking outside it also reaches what was clicked. JavaFX's
        // default swallows it, so a second "+" clicked while a menu was up closed the first menu and never
        // opened its own.
        menu.setConsumeAutoHidingEvents(false);
        menu.showingProperty().addListener((obs, was, showing) -> {
            if (!showing) return;
            ContextMenu previous = current.get();
            if (previous != null && previous != menu && previous.isShowing()) previous.hide();
            current = new WeakReference<>(menu);
        });
        return menu;
    }

    /** The tracked menu showing now, or {@code null}. */
    static ContextMenu showing() {
        ContextMenu menu = current.get();
        return menu != null && menu.isShowing() ? menu : null;
    }
}
