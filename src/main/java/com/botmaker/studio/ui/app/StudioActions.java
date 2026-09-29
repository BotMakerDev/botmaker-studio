package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.docs.StudioAction;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenCentralSearch;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.services.ScreenCaptureService;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.app.dev.PickerGalleryWindow;
import com.botmaker.studio.ui.app.overlay.ProgramShapeOverlay;
import com.botmaker.studio.ui.app.params.ParametersDialog;
import com.botmaker.session.launch.BackgroundLauncher;
import javafx.stage.Stage;

/**
 * Every top-level action the shell offers, built once and wired into the menu bar and the toolbar.
 *
 * <p>This used to be ~120 lines of {@code setOnX} calls interleaved with service construction in
 * {@code UIManager}'s constructor, which made "which button opens what" unreadable and hid the ordering
 * constraints between them. Here the actions are named methods and {@link #wire} is the one table.
 *
 * <p>It also owns the GitHub/sharing services, because they exist only to back these actions — the scene
 * reaches them through {@link #gitHubAuth()} and friends rather than through fields of its own.
 */
final class StudioActions {

    private final Stage primaryStage;
    private final ProjectConfig config;
    private final ProjectState state;
    private final EventBus eventBus;
    private final CodeEditorService codeEditorService;
    private final ProjectSettingsService projectSettingsService;
    private final ScreenCaptureService screenCaptureService;
    private final ProjectAnalyzer projectAnalyzer;
    private final LibraryService libraryService;
    private final MenuBarManager menuBar;
    private final ToolbarManager toolbar;
    private final Runnable recoverProjectFiles;

    private final MavenCentralSearch mavenCentralSearch = new MavenCentralSearch();
    private final JitPackSearch jitPackSearch = new JitPackSearch();

    private final GitHubClient gitHubClient = new GitHubClient();
    private final GitHubAuth gitHubAuth = new GitHubAuth();
    private final GitHubGallery gallery = new GitHubGallery(gitHubClient, gitHubAuth);
    private final BotInstaller botInstaller = new BotInstaller(gitHubClient, gallery);
    private Runnable onPublish = () -> { };
    /** Opens the block a use is in — the canvas's, so {@link UIManager} sets it once the canvas exists. */
    private java.util.function.Consumer<com.botmaker.studio.nav.Usages.Usage> onReveal = null;
    // Reads the plugin index off the same raw CDN the gallery uses, with the same client and no account.
    private final PluginRegistry pluginRegistry = new PluginRegistry(gitHubClient);

    /**
     * Six of the thirteen parameters this took were the project's own services, re-listed here after
     * {@code UIManager} had already listed them; they arrive as one {@link StudioContext} now. What is left is
     * genuinely the shell's: the window, the one thing only the shell builds
     * ({@link ScreenCaptureService}) and the two surfaces these actions are wired onto.
     */
    StudioActions(StudioContext ctx,
                  Stage primaryStage,
                  ScreenCaptureService screenCaptureService,
                  MenuBarManager menuBar,
                  ToolbarManager toolbar,
                  Runnable recoverProjectFiles) {
        this.primaryStage = primaryStage;
        this.config = ctx.config();
        this.state = ctx.state();
        this.eventBus = ctx.eventBus();
        this.codeEditorService = ctx.codeEditorService();
        this.projectSettingsService = ctx.projectSettingsService();
        this.screenCaptureService = screenCaptureService;
        this.projectAnalyzer = ctx.projectAnalyzer();
        this.libraryService = ctx.libraryService();
        this.menuBar = menuBar;
        this.toolbar = toolbar;
        this.recoverProjectFiles = recoverProjectFiles;
    }

    /** Installs every action on the menu bar and the toolbar. Called once, from the shell's constructor. */
    void wire() {
        menuBar.setEventBus(eventBus);
        menuBar.setProjectPath(config.projectPath());

        // --- Project ---
        // Manage Libraries, Manage Plugins, Reload Plugins, Upgrade... and Manage Imports were five entries
        // until 2026-09-29; the first four are the tabs and the header of one window now, and Manage Imports
        // is deleted (every edit imports what it writes).
        menuBar.setOnPlugins(() -> openPlugins(PluginsWindow.Section.INSTALLED));
        // Nothing to wire for Project Setup: that checklist is the SDK plugin's 📋 Project Setup item since
        // 2026-08-31. Every row of it reads a file the plugin owns, so the shell could only ever have shown
        // it by asking the plugin for the answers.
        // Nothing to wire for the Activity Flow: the graph editor is the SDK plugin's 🔀 Activity Flow item
        // since 2026-09-11. A flow's nodes, edges and outcomes are that plugin's vocabulary, so the shell
        // could only ever have drawn it by learning what a branch is — and the file it writes is the
        // plugin's too, which is why its host original was deleted rather than kept as a second door.
        menuBar.setOnParameters(this::openParameters);
        toolbar.setOnParameters(this::openParameters);
        menuBar.setOnRecoverProjectFiles(recoverProjectFiles);
        // Nothing to wire for the Resource Manager: the picture library is the SDK plugin's, and the manager
        // went to it on 2026-09-01 with the gallery, the archive and the tag rules it is built on. Leaving
        // the menu entry and the toolbar button unwired keeps them visible and dead, so both are gone; the
        // way in is the plugin's own toolbar item.
        menuBar.setOnProjectSettings(this::openProjectSettings);
        toolbar.setOnProjectSettings(this::openProjectSettings);

        // --- Capture / launch / input ---
        // Nothing to wire for the capture targets: that button is the SDK plugin's item, and its dialog is
        // the plugin's too. The shell supplies the bar it is placed on and nothing else.
        //
        // Nor for the launch target, since 2026-09-01: the 🚀 button and its dialog went together. What this
        // machine launches is a run property now (StudioProjectSettings.runProperties), set by the SDK
        // plugin's emulator picker through Runs.setProperty.
        //
        // The Debug output toggle and 🖱 Input & Clicks stood here until 2026-09-27. Both edited
        // botmaker-project.properties; the bot's settings are the SDK's @Managed("settings") value now, and
        // ⚙ Bot Settings, the SDK plugin's item, edits them — debug output included.
        // ✂ Capture Templates stood here until 2026-08-31 and is the SDK plugin's item now, placed by the
        // same merge as the pilot's. Everything behind it — the capture target, the size to snap to, the
        // picture folder it writes into — is that plugin's, so there is nothing left for the shell to wire.
        toolbar.setOnOverlayEditor(this::openOverlayEditor);
        // ⏺ Record stood beside it and opened the same overlay straight into recording. The recorder is the
        // SDK plugin's since 2026-09-02 — what a recorded click is written down as is that plugin's sentence —
        // so it reaches the bar as a ToolbarItem and there is nothing left for the shell to wire.

        // The Remote Pilot used to be wired here. It is the SDK plugin's feature since 2026-08-30 and reaches
        // the bar as a ToolbarItem like any other plugin's, so there is nothing for the shell to wire.

        // --- Sharing / VCS ---
        menuBar.setOnBrowseGallery(this::openGallery);
        menuBar.setOnPublishGallery(this::openPublish);
        // Project History selects the Versions tab; UIManager wires it, since the tab is the window's.
        menuBar.setProjectRepoUrl(BotSource.read(config.projectPath())
                .map(s -> "https://github.com/" + s.slug()).orElse(null));

        // --- Help ---
        menuBar.setOnGettingStarted(this::openGettingStarted);
        menuBar.setOnPickerGallery(this::openPickerGallery);
    }

    GitHubAuth gitHubAuth() { return gitHubAuth; }

    GitHubClient gitHubClient() { return gitHubClient; }

    /**
     * Where <i>Project ▸ Publish…</i> goes: the Versions tab's publish sheet, which is the window's, so
     * {@link UIManager} sets it once the tab exists.
     */
    void setOnPublish(Runnable onPublish) {
        this.onPublish = onPublish;
    }

    /** Where the Parameters window sends a person to a use of the parameter they tried to remove. */
    void setOnReveal(java.util.function.Consumer<com.botmaker.studio.nav.Usages.Usage> onReveal) {
        this.onReveal = onReveal;
    }

    void openPublish() {
        onPublish.run();
    }

    private void openGallery() {
        new GalleryDialog(primaryStage, gallery, botInstaller, gitHubAuth, gitHubClient).show();
    }

    /**
     * <b>Project ▸ Plugins &amp; Libraries…</b>, on {@code section}. The canvas banners open it too — a plugin
     * missing goes to Browse, a plugin that did not load to Installed — so it takes the tab rather than each
     * door having a method of its own.
     */
    void openPlugins(PluginsWindow.Section section) {
        new PluginsWindow(primaryStage, config, state, libraryService, pluginRegistry, jitPackSearch,
                mavenCentralSearch, menuBar::reviewChanges).show(section);
    }

    /**
     * Opens the dev-only picker gallery, seeded with this project so the template and colour editors have
     * something real to resolve. Only reachable from a dev build's Help menu.
     */
    private void openPickerGallery() {
        new PickerGalleryWindow(primaryStage, config).show();
    }

    /** The one editor for every value the bot reads. */
    private void openParameters() {
        new ParametersDialog(primaryStage, config, state, libraryService, onReveal).show();
    }

    private void openProjectSettings() {
        new ProjectSettingsDialog(primaryStage, projectSettingsService, projectAnalyzer).show();
    }

    // openLaunchTarget stood here until 2026-09-01, showing the Launch Target dialog and — when the overlay
    // editor had nothing to draw over — running a callback once it closed. The dialog went with the launch
    // rows, so there was no recovery to offer and this class passed null for that callback for eleven days,
    // leaving the overlay's fallback a dead-end warning.
    //
    // Restored 2026-09-12, and not by the route recorded here (the launcher becoming a plugin's toolbar
    // item): the overlay asks which window to draw over. That is a better answer than the dialog it lost,
    // because the dialog arranged a *launch* while the question is which of the windows already open the
    // user means — and a window opened by hand was never reachable through it at all.

    /**
     * Opens the program-shape overlay authoring editor (compact clickable block tree + insertion cursor).
     */
    private void openOverlayEditor() {
        ProgramShapeOverlay.open(primaryStage, codeEditorService, projectSettingsService, screenCaptureService,
                this::liveSessionWindow);
    }

    /**
     * The live private session's host window for the overlay to draw over, or {@code 0} when none is running —
     * revealed first, since a session is brought up minimized and an overlay over a minimized window shows
     * nothing.
     *
     * <p>Asked of the project's one {@link BackgroundLauncher} rather than of the pilot. It used to come from
     * {@code RemotePilotUi}, which was the only thing holding a launcher; the pilot is a plugin now, and a
     * host may not reach into one. The launcher is the right source anyway — it is per project and holds the
     * session whether the pilot, the ▶ Launch button, or nothing at all started it.
     */
    private long liveSessionWindow() {
        return BackgroundLauncher.forProject(config.resourcesRoot()).revealHostWindow();
    }

    /**
     * Opens Help ▸ Getting Started. The dialog owns none of the prose — it renders
     * {@link com.botmaker.studio.docs.Workflow}, the same source {@code WORKFLOW.md} is generated from — so all
     * this has to supply is the way to actually open each destination a step names.
     */
    private void openGettingStarted() {
        GettingStartedDialog.Actions actions = GettingStartedDialog.Actions.builder()
                // No PROJECT_SETUP entry, and no CAPTURE_TARGETS one, for the same reason as CAPTURE_TEMPLATES
                // below: all three are the SDK plugin's toolbar items since 2026-08-31.
                // No LAUNCH_TARGET entry either, since 2026-09-01: the 🚀 dialog is gone and the shell has
                // nothing to open. The step still reads, without an Open button.
                // No CAPTURE_TEMPLATES entry: the tool is the SDK plugin's toolbar item since 2026-08-31 and
                // the shell has no handle on it. The step still reads, without an Open button — the action's
                // own text already says where it lives, which is what that field is for.
                // No RESOURCES entry, for the same reason as CAPTURE_TEMPLATES: the Resource Manager is the
                // SDK plugin's since 2026-09-01 and the shell has no handle on it.
                // No ACTIVITY_FLOW entry since 2026-09-11, for the same reason as CAPTURE_TEMPLATES: the
                // graph editor is the SDK plugin's toolbar item and the shell has no handle on it. The step
                // still reads, without an Open button.
                .on(StudioAction.PARAMETERS, this::openParameters)
                .on(StudioAction.OVERLAY_EDITOR, this::openOverlayEditor)
                .on(StudioAction.PUBLISH, this::openPublish)
                .on(StudioAction.GALLERY, this::openGallery)
                .build();
        new GettingStartedDialog(primaryStage, actions).show();
    }
}
