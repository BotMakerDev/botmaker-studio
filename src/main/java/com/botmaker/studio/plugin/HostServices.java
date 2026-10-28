package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.History;
import com.botmaker.plugin.api.PluginLog;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.studio.project.ProjectConfig;
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
 * Studio's own side of {@link StudioServices} — the host capabilities a plugin's editor may reach.
 *
 * <p><b>Everything here already existed; none of it is new capability</b> — but the save chooser, added
 * 2026-10-28 so a plugin's export goes through {@link Choosers} like every other chooser. The theme is
 * {@link ThemedWindows}, the file choosers are {@link Choosers}, the paths are the
 * open project's {@link ProjectConfig}. What the class adds is the *shape* — a set of interfaces a plugin
 * can compile against without seeing a Studio type, which is the only way an editor written outside this
 * repository can look and behave like one written inside it.
 *
 * <p><b>The window owner is asked for, never held.</b> A plugin's editor is built inside some dialog and may
 * outlive it; a captured {@code Window} would be a stale parent the moment that dialog closes, which is how
 * a modal ends up behind the window it is supposed to block. So the owner arrives as a {@link Supplier}
 * evaluated at the moment a dialog is actually shown.
 *
 * <p><b>It fails soft, on purpose.</b> A plugin has no way to know whether a project is open or whether this
 * platform has a native file dialog, and an editor is drawn while the user is looking at it. So a missing
 * project answers with the working directory rather than null, and every callback that cannot be satisfied is
 * simply not invoked — which the contract documents as the cancel case, and which is what a plugin already
 * has to handle.
 */
public final class HostServices implements StudioServices {

    private final ProjectConfig project;
    private final Supplier<Window> owner;

    public HostServices(ProjectConfig project, Supplier<Window> owner) {
        this.project = project;
        this.owner = owner == null ? () -> null : owner;
    }

    /** The services for the project {@code config} describes, with {@code owner} as the dialog parent. */
    public static HostServices forProject(ProjectConfig config, Supplier<Window> owner) {
        return new HostServices(config, owner);
    }

    /** The same, parented on whichever window has focus when a dialog is actually opened. */
    public static HostServices forProject(ProjectConfig config) {
        return forProject(config, HostServices::focusedWindow);
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
    public Path projectDir() {
        return project == null ? Path.of("") : project.projectPath();
    }

    @Override
    public Path resourcesDir() {
        return project == null ? Path.of("") : project.resourcesRoot();
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
     * The open project's bot, or {@link com.botmaker.plugin.api.Runs#NONE} between projects.
     *
     * <p>Read from {@link HostRuns} rather than held, and that is the one thing about it worth knowing: this
     * class is built ad hoc from a {@code ProjectConfig} at three call sites with no event bus in scope, and
     * an instance can outlive the project it was made for. Asking the live channel each time is what stops a
     * plugin's editor, built for a project the user has since left, from starting that project's bot.
     */
    @Override
    public com.botmaker.plugin.api.Runs runs() {
        return HostRuns.live();
    }

    /**
     * The open project's managed values, or {@link PluginValues#NONE}
     * between projects.
     *
     * <p>Read from {@link HostPluginValues} rather than held, for the reason {@link #runs()} is: this class
     * is built ad hoc from a {@code ProjectConfig} and an instance can outlive the project it was made for,
     * so asking the live channel each time is what stops a plugin's window writing into a file the user has
     * left behind.
     */
    @Override
    public PluginValues pluginValues() {
        return HostPluginValues.live();
    }

    /**
     * The open project's undo history, or {@link History#NONE} between projects — read from {@link HostHistory}
     * each time, for the reason {@link #runs()} is.
     */
    @Override
    public History history() {
        return HostHistory.live();
    }

    @Override
    public void status(String message) {
        HostRuns.status(message);
    }

    /** Each line attributed to the plugin whose class wrote it ({@link PluginLogs}), shown in the Plugins window. */
    @Override
    public PluginLog log() {
        return PluginLogs.CALLER;
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

    // CaptureAdapter stood here from the day this class was written until 2026-08-31, implementing the
    // contract's Capture: selectRegion, pickPoint, sampleColor, grabFrame and toFxImage, over Studio's own
    // ScreenCaptureService. All of it is gone with that interface.
    //
    // Two deletions in one, and they are separate arguments. grabFrame answered "one frame of what the host
    // looks at" out of a default capture target — capture-target vocabulary written so as not to say the
    // word — and the host holds no targets any more. The other four went because the maintainer's ruling is
    // that the overlay and the desktop grab do not belong to the editor either: putting a full-screen surface
    // over a running game and asking the user to point at something in it is about what a bot sees from end
    // to end. So the overlay itself moved to the SDK plugin with them, and the toolkit's screen-pick widgets
    // take theirs from a ScreenPicks the plugin registers.
    //
    // grabTargetFrame, sampleFromTarget, chooseSource and defaultSource were implemented here from phase 12a
    // until 2026-08-27 and went the same way, for the reason recorded on StudioServices: what they described
    // was the SDK's own vocabulary, which no second plugin could have contributed.

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
