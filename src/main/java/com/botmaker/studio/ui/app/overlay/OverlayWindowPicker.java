package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which window the overlay editor docks beside, asked only when no plugin says what the bot watches — and by
 * ⇄ Change, when no plugin offers a picker of its own.
 *
 * <h2>Nothing here is persisted, and that is the design</h2>
 *
 * <p>What the <b>bot</b> watches is its plugin's to say ({@code OverlayPart.watched}) and to change
 * ({@code OverlayPart.changeWatched}); this answers only <em>which window am I drawing beside right now</em>,
 * for a project whose plugins say nothing. It lasts as long as the panel. See
 * {@code docs/refactor/42-overlay-editor.md}.
 *
 * <p><b>Only windows that are open</b> (2026-10-06). Remembered titles ({@code knownWindowTitles}) were offered
 * after the open ones, and picking one opened a panel beside nothing.
 */
final class OverlayWindowPicker {

    /** The prefix {@link OverlayToolbars} titles Studio's own borderless windows with. */
    private static final String OWN_WINDOW = "__bm_overlay_";

    private OverlayWindowPicker() {}

    /**
     * The titles to offer: every open window, in the order the platform lists them, each once, trimmed, and
     * none of Studio's own overlays.
     *
     * <p>Pure, so the rule is testable without a toolkit. The platform's order is kept rather than sorted —
     * whatever the window manager lists first is the likeliest answer.
     */
    static List<String> candidates(List<String> live) {
        Set<String> seen = new LinkedHashSet<>();
        if (live != null) {
            for (String title : live) {
                if (title == null || title.isBlank() || title.startsWith(OWN_WINDOW)) continue;
                seen.add(title.trim());
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Asks which window to dock beside, or {@code null} when the user cancels or there is nothing to offer.
     * Themed and owned like every other dialog here, so it does not open behind the window that asked.
     */
    static String ask(Window owner, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        ChoiceDialog<String> dialog = new ChoiceDialog<>(candidates.getFirst(), candidates);
        dialog.setTitle("Overlay editor");
        dialog.setHeaderText("Which window does the bot watch?");
        dialog.setContentText("Window:");
        if (owner != null) dialog.initOwner(owner);
        ThemedWindows.apply(dialog);

        Optional<String> chosen = dialog.showAndWait();
        return chosen.orElse(null);
    }
}
