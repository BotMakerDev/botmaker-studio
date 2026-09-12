package com.botmaker.studio.ui.app.overlay;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which window the overlay editor draws over, asked once per HUD.
 *
 * <h2>Nothing here is persisted, and that is the design</h2>
 *
 * <p>Studio used to read the project's configured capture target for this. That target describes what the
 * <b>bot</b> looks at, it lives in the owning plugin's {@code capture.json}, and Studio stopped reading it on
 * 2026-08-31 — so {@code ScreenCaptureService.defaultTarget()} has answered {@code null} for every caller
 * since, and the overlay could only open over a live private session. See
 * {@code docs/refactor/28-overlay-items.md}.
 *
 * <p>What the editor needs is a different fact with the same shape: <em>which window am I drawing on right
 * now</em>. It lasts as long as the HUD, so it is a local variable rather than a setting, and the duplicate
 * record Studio used to keep for it is deleted in the phase after this one.
 *
 * <p>A user who wants the <em>bot</em> pointed at this window presses the plugin's own item on the HUD's row,
 * and the plugin writes its own file in its own vocabulary. That is the only direction the fact travels, and
 * it is what makes one window two facts rather than one fact spelled twice.
 *
 * <p>{@code knownWindowTitles} is read only as an MRU — it already exists for exactly this, <i>"remembered so
 * a window can be picked as a target without the app being currently open"</i> — and a remembered title is
 * offered <em>after</em> every live one, because a list led by a window that is not open reads as a broken
 * picker.
 */
final class OverlayWindowPicker {

    private OverlayWindowPicker() {}

    /**
     * The titles to offer: every open window first, in the order the platform lists them, then every
     * remembered one not already there.
     *
     * <p>Pure, so the ordering rule is testable without a toolkit. The platform's order is kept within the
     * live windows rather than sorted — whatever the window manager lists first is the likeliest answer, and
     * re-sorting would be this picker having an opinion it has no basis for.
     */
    static List<String> candidates(List<String> live, List<String> known) {
        Set<String> seen = new LinkedHashSet<>();
        addAll(seen, live);
        addAll(seen, known);
        return List.copyOf(new ArrayList<>(seen));
    }

    private static void addAll(Set<String> into, List<String> titles) {
        if (titles == null) return;
        for (String title : titles) {
            if (title != null && !title.isBlank()) into.add(title.trim());
        }
    }

    /**
     * Asks which window to draw over, or {@code null} when the user cancels or there is nothing to offer.
     *
     * <p>A {@link ChoiceDialog} rather than a bespoke window: this is asked once, before the HUD exists, and
     * a list of titles is exactly what a choice dialog is for. Themed and owned like every other dialog here,
     * so it does not open behind the window that asked for it.
     */
    static String ask(Window owner, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;

        ChoiceDialog<String> dialog = new ChoiceDialog<>(candidates.getFirst(), candidates);
        dialog.setTitle("Overlay editor");
        dialog.setHeaderText("Which window should the overlay be drawn over?");
        dialog.setContentText("Window:");
        if (owner != null) dialog.initOwner(owner);
        ThemedWindows.apply(dialog);

        Optional<String> chosen = dialog.showAndWait();
        return chosen.orElse(null);
    }
}
