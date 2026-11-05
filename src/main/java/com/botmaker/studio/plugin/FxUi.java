package com.botmaker.studio.plugin;

import com.botmaker.core.plugin.UiPort;
import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.Theme;
import com.botmaker.studio.ui.Choosers;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import com.botmaker.studio.util.NativeFileDialog;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Studio's {@link UiPort}: the theme is {@link ThemedWindows}, the file choosers are {@link Choosers} (the save
 * chooser added 2026-10-28, so a plugin's export goes through them like every other chooser).
 *
 * <p><b>The window owner is asked for, never held.</b> A plugin's editor is built inside some dialog and may
 * outlive it; a captured {@code Window} would be a stale parent the moment that dialog closes, which is how
 * a modal ends up behind the window it is supposed to block. So the owner arrives as a {@link Supplier}
 * evaluated at the moment a dialog is actually shown.
 *
 * <p><b>It fails soft, on purpose.</b> A plugin has no way to know whether this platform has a native file dialog,
 * and an editor is drawn while the user is looking at it, so every callback that cannot be satisfied is simply not
 * invoked — which the contract documents as the cancel case, and which is what a plugin already has to handle.
 */
public final class FxUi implements UiPort {

    /** Parented on whichever window has focus when a dialog is actually opened. */
    public static final FxUi FOCUSED = new FxUi(FxUi::focusedWindow);

    private final Supplier<Window> owner;

    private FxUi(Supplier<Window> owner) {
        this.owner = owner == null ? () -> null : owner;
    }

    /** The port whose dialogs are owned by {@code owner}, asked each time one is shown. */
    public static FxUi ownedBy(Supplier<Window> owner) {
        return new FxUi(owner);
    }

    /**
     * The focused window, or null.
     *
     * <p>The right default owner for an editor that does not know which dialog it was built into: it is
     * evaluated at the moment a dialog is shown, so it names the window the user is actually looking at
     * rather than one that may since have closed.
     */
    public static Window focusedWindow() {
        return Window.getWindows().stream().filter(Window::isFocused).findFirst().orElse(null);
    }

    @Override
    public Theme theme() {
        return THEME;
    }

    @Override
    public Dialogs dialogs() {
        return new DialogsAdapter();
    }

    /**
     * Stateless, so one instance serves every plugin: {@link ThemedWindows} is entirely static, and the
     * current theme is a property of the application rather than of whoever is asking.
     */
    private static final Theme THEME = new Theme() {
        @Override public void apply(Scene scene) { ThemedWindows.apply(scene); }

        @Override public void apply(Stage stage) { ThemedWindows.apply(stage); }

        @Override public void apply(Dialog<?> dialog) { ThemedWindows.apply(dialog); }

        @Override public void apply(DialogPane pane) { ThemedWindows.apply(pane); }

        @Override public Scene scene(Parent root) { return ThemedWindows.scene(root); }

        @Override public Scene scene(Parent root, double width, double height) {
            return ThemedWindows.scene(root, width, height);
        }

        @Override public Alert alert(Alert.AlertType type) { return ThemedWindows.alert(type); }

        @Override public Alert alert(Alert.AlertType type, String message, ButtonType... buttons) {
            return ThemedWindows.alert(type, message, buttons);
        }

        @Override public void applyThemeClass(Parent root) { ThemedWindows.applyThemeClass(root); }
    };

    private final class DialogsAdapter implements Dialogs {

        @Override
        public Optional<Window> ownerWindow() {
            return Optional.ofNullable(owner.get());
        }

        @Override
        public Choice chooseProgram(Path initialDir) {
            NativeFileDialog.Choice choice = Choosers.program(initialDir);
            // nativeDialogShown is passed through rather than recomputed: a plugin uses it to decide whether
            // to offer its own fallback, and only the dialog itself knows whether one appeared.
            return new Choice(choice.nativeDialogShown(), choice.path().map(Path::of));
        }

        @Override
        public Choice chooseFile(String title, Path initialDir, String... extensions) {
            return chosen(Choosers.open(chooserOwner(), titled(title, "Choose a file"), initialDir, filter(extensions)));
        }

        @Override
        public Choice chooseDirectory(String title, Path initialDir) {
            return chosen(Choosers.folder(chooserOwner(), titled(title, "Choose a folder"), initialDir));
        }

        @Override
        public Choice saveFile(String title, Path initialDir, String fileName, String... extensions) {
            return chosen(Choosers.save(chooserOwner(), titled(title, "Save a file"), initialDir, fileName,
                    filter(extensions)));
        }

        /**
         * The window the user is in, so a chooser opened from a plugin's own window shows over it; with none focused
         * (another application has the focus), the newest window shown, which is the one that asked when a plugin's
         * dialog is open; else the editor's.
         */
        private Window chooserOwner() {
            List<Window> shown = Window.getWindows().stream().filter(w -> w instanceof Stage && w.isShowing()).toList();
            return shown.stream().filter(Window::isFocused).findFirst()
                    .or(() -> shown.isEmpty() ? Optional.empty() : Optional.of(shown.getLast()))
                    .orElseGet(owner);
        }

        private static String titled(String title, String otherwise) {
            return title == null || title.isBlank() ? otherwise : title;
        }

        private static Choosers.Filter filter(String[] extensions) {
            if (extensions == null || extensions.length == 0) return null;
            return Choosers.Filter.of(String.join(", ", extensions), extensions);
        }

        private static Choice chosen(Optional<Path> path) {
            return new Choice(true, path);
        }
    }
}
