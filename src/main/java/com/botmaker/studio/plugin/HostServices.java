package com.botmaker.studio.plugin;

import com.botmaker.core.plugin.PluginOwners;
import com.botmaker.core.plugin.PluginLogs;
import com.botmaker.core.plugin.PluginHost;
import com.botmaker.core.plugin.UiPort;
import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.History;
import com.botmaker.plugin.api.PluginLog;
import com.botmaker.plugin.api.Runs;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;
import com.botmaker.plugin.api.source.PluginValues;
import com.botmaker.core.project.ProjectConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The host's side of {@link StudioServices} — the host capabilities a plugin's editor may reach.
 *
 * <p><b>Everything here already existed; none of it is new capability.</b> The paths are the open project's
 * {@link ProjectConfig}; the look and the choosers are a {@link UiPort}, which Studio fills ({@code FxUi}) and which
 * is {@link UiPort#NONE} with no user interface attached (2026-11-05), when {@link #hasUi()} says false. What the
 * class adds is the *shape* — a set of interfaces a plugin can compile against without seeing a Studio type, which is
 * the only way an editor written outside this repository can look and behave like one written inside it.
 *
 * <p><b>It fails soft, on purpose.</b> A plugin has no way to know whether a project is open, so a missing project
 * answers with the working directory rather than null.
 */
public final class HostServices implements StudioServices {

    /** The user interface attached now, which {@link #forProject(ProjectConfig)} hands out; none until one attaches. */
    private static volatile UiPort attached = UiPort.NONE;

    private final ProjectConfig project;
    /** Asked on every call, so services held for a whole project follow a UI that attaches or detaches meanwhile. */
    private final Supplier<UiPort> ui;

    public HostServices(ProjectConfig project, UiPort ui) {
        this(project, () -> ui);
    }

    private HostServices(ProjectConfig project, Supplier<UiPort> ui) {
        this.project = project;
        this.ui = () -> Optional.ofNullable(ui.get()).orElse(UiPort.NONE);
    }

    /** The services for the project {@code config} describes, its dialogs and look through {@code ui}. */
    public static HostServices forProject(ProjectConfig config, UiPort ui) {
        return new HostServices(config, ui);
    }

    /** The same, through whichever user interface is attached when a plugin asks ({@link #attach}), or none. */
    public static HostServices forProject(ProjectConfig config) {
        return new HostServices(config, () -> attached);
    }

    /** Makes {@code ui} the one {@link #forProject(ProjectConfig)} hands out; null detaches it. */
    public static void attach(UiPort ui) {
        attached = ui == null ? UiPort.NONE : ui;
    }

    @Override
    public Path projectDir() {
        return project == null ? Path.of("") : project.projectPath();
    }

    /**
     * The calling plugin's own folder of the resources, {@code plugins/<group>/<artifact>/} ({@link PluginOwners#folder},
     * 2026-10-30): two plugins writing {@code images/} at the root wrote over each other's files. One instance serves
     * every plugin, so the plugin is the one calling ({@link PluginHost#calling}); a call of the host's own answers
     * the resources' root.
     */
    @Override
    public Path resourcesDir() {
        if (project == null) return Path.of("");
        Path root = project.resourcesRoot();
        return PluginHost.calling().map(plugin -> PluginOwners.folder(root, plugin.coordinate())).orElse(root);
    }

    /**
     * The calling plugin's state folder of the open bot ({@link PluginOwners#stateFolder}), created here; empty between
     * projects, for a call of the host's own, or when the folder cannot be made.
     */
    @Override
    public Optional<Path> stateDir() {
        if (project == null) return Optional.empty();
        return PluginHost.calling().flatMap(plugin -> {
            Path dir = PluginOwners.stateFolder(project.projectPath(), plugin.coordinate());
            try {
                return Optional.of(Files.createDirectories(dir));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    /** Whether the {@link UiPort} these services were made with shows anything: false for {@link UiPort#NONE}. */
    @Override
    public boolean hasUi() {
        return ui.get().present();
    }

    @Override
    public Theme theme() {
        return ui.get().theme();
    }

    @Override
    public Dialogs dialogs() {
        return ui.get().dialogs();
    }

    /**
     * The open project's bot, or {@link Runs#NONE} between projects.
     *
     * <p>Read from {@link HostRuns} rather than held, and that is the one thing about it worth knowing: this
     * class is built ad hoc from a {@code ProjectConfig} at several call sites with no event bus in scope, and
     * an instance can outlive the project it was made for. Asking the live channel each time is what stops a
     * plugin's editor, built for a project the user has since left, from starting that project's bot.
     */
    @Override
    public Runs runs() {
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
    //
    // The theme and the dialogs were implemented here until 2026-11-05; they are Studio's FxUi now, behind the
    // core's UiPort, so this class names no JavaFX type.
}
