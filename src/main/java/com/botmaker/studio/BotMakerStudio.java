package com.botmaker.studio;

import com.botmaker.cli.bmk.BotJson;
import com.botmaker.session.remote.DisplayAgent;
import com.botmaker.shared.capture.linux.X11ErrorTrap;
import com.botmaker.session.Sessions;
import com.botmaker.studio.assist.BotGuard;
import com.botmaker.studio.assist.HomeEndpoint;
import com.botmaker.studio.config.AppPaths;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.plugin.PluginGate;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.BotProject;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.shared.platform.Os;
import com.botmaker.studio.services.OpenStep;
import com.botmaker.studio.services.ProgressReporter;
import com.botmaker.studio.services.StudioChannel;
import com.botmaker.studio.sharing.BmkOpen;
import com.botmaker.studio.sharing.BotLink;
import com.botmaker.studio.sharing.LinkScheme;
import com.botmaker.studio.sharing.ProgramEntries;
import com.botmaker.studio.sharing.UnpackedBots;
import com.botmaker.studio.project.migration.ProjectSchema;
import com.botmaker.studio.project.migration.ProjectSchemaTooNew;
import com.botmaker.studio.ui.app.CommunityPluginPrompt;
import com.botmaker.studio.ui.app.ForceX11Notice;
import com.botmaker.studio.ui.app.ProjectWindow;
import com.botmaker.studio.ui.app.home.BmkFlow;
import com.botmaker.studio.ui.app.home.MyBots;
import com.botmaker.studio.ui.app.home.RunHome;
import com.botmaker.studio.ui.app.loading.LoadingPage;
import com.botmaker.studio.ui.app.loading.ReloadBox;
import com.botmaker.studio.ui.app.StudioWindow;
import com.botmaker.studio.ui.app.UIManager;
import com.botmaker.studio.ui.app.runner.RunnerWindow;
import com.botmaker.studio.ui.render.theme.BlockFont;
import com.botmaker.studio.ui.render.theme.BlockTheme;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public class BotMakerStudio extends Application {

    static {
        // Before any folder or preference is read: a sandbox home (BOTMAKER_HOME) moves them all.
        AppPaths.apply();
    }

    /** Narrowest the shell may be dragged — see {@code configureWindow}. */
    private static final double MIN_WINDOW_WIDTH = 900;
    /** Shortest the shell may be dragged: menu bar, toolbar, a usable canvas and the status line. */
    private static final double MIN_WINDOW_HEIGHT = 560;

    /** The currently open project (null when on project selection screen). */
    private BotProject currentProject;
    /** The window built for {@link #currentProject}, held only so it can be disposed when that project ends. */
    private ProjectWindow currentWindow;

    /**
     * Which window the open project gets, when the user has said. {@code null} means "derive it" — an
     * installed bot opens in the Runner, your own opens in the editor.
     *
     * <p>Session-only and deliberately not on disk: previewing what a user sees, or taking a read-only look at
     * an installed bot's code, must not change what the project <em>is</em>. Only "Improve this bot" does that,
     * and it does it through {@link com.botmaker.studio.project.ProjectMode}'s marker. Cleared whenever a
     * different project is opened, so the choice never outlives the bot it was made about.
     */
    private Boolean showAsUser;
    /** The project {@link #showAsUser} was chosen for. */
    private Path openProjectDir;
    /** Bumped by every open and every making: a failure of an earlier one is not this one's. FX thread only. */
    private int opens;

    /** The primary window, kept for owning dialogs. */
    private Stage primaryStage;

    /** Guards the one-time-per-session Wayland → X11 notice (across project switches). */
    private boolean waylandNoticeChecked;

    /** Where a second launch hands this Studio a clicked link, when it could listen ({@link StudioChannel}). */
    private Optional<StudioChannel> channel = Optional.empty();

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // Before any window is shown: from here on every window gets the stylesheet and the theme class as it
        // appears, including the popups (context menus, tooltips, dropdowns) that no call site can reach.
        ThemedWindows.install();
        // The saved theme before the first window too (2026-10-25): it loaded with the editor or the Runner, so the
        // home and the loading page were always light, and a dark user's window flashed light on every open.
        BlockTheme.initialize();
        // The fonts Studio ships and the ones the user imported, before a stylesheet names one: the Run
        // console asks for JetBrains Mono before any canvas would have loaded it.
        BlockFont.loadBundled();
        // Collect what a previous run (or a killed bot JVM) left behind, before anything asks the process table
        // whether a launcher is open: a leftover session reads as one, and a launch is then refused on its account.
        // Off the FX thread — it shells out to systemctl.
        //
        // It also warms the `systemd-run --user --scope` probe every SessionReaper needs, which is cached per JVM
        // but costs a spawn and a waitFor the first time. Paying it here, while the user is still opening a
        // project, keeps it off the first session launch — which is why this thread starts before the UI is built.
        Thread sweep = new Thread(Sessions::reapOrphans, "session-orphan-sweep");
        sweep.setDaemon(true);
        sweep.start();
        applyAppIcons(primaryStage);
        // Opt-in record of every move and resize of the shell and who made it (BOTMAKER_TRACE_GEOMETRY=1).
        com.botmaker.studio.ui.app.GeometryTrace.install(primaryStage, "main");
        configureWindow(primaryStage);
        // Before the first project binds: a community plugin is asked about before it loads, and a catalog
        // plugin binds only under its entry's id (2026-10-23).
        PluginHost.guard(PluginGate.studio(CommunityPluginPrompt::agrees));
        // BotMaker opens on the bot list (2026-10-25, spec § 1a), not on the project last opened: only a project
        // named on the command line opens straight away.
        Path requested = requestedProject();
        List<String> unnamed = getParameters() == null ? List.of() : getParameters().getUnnamed();
        Optional<Path> bmk = BmkOpen.requested(named("open"), unnamed);
        Optional<String> link = BotLink.asked(unnamed);
        Optional<String> run = named("run");
        listenForOpenedFiles(primaryStage);
        // A link clicked while this Studio runs comes here, not to a second Studio (main hands it over).
        channel = StudioChannel.listen(StudioChannel.socket(), text -> Platform.runLater(() -> openLink(text)));
        LinkScheme.registerOnWindows();
        // A dev Studio's ui tool, from the first window on (BOTMAKER_MCP_UI); nothing otherwise.
        HomeEndpoint.mainWindow(primaryStage);
        HomeEndpoint.serve();
        if (bmk.isPresent()) {
            // A second launch is a Studio of its own (2026-10-25): it opens the file on the loading page (N12).
            make(primaryStage, new BmkFlow(primaryStage).opening(bmk.get()));
        } else if (link.isPresent()) {
            showProjectSelection(primaryStage);
            openLink(link.get());
        } else if (run.isPresent()) {
            Optional<Path> dir = botByRepo(run.get());
            if (dir.isPresent()) {
                openAs(primaryStage, dir.get(), true, false);
            } else {
                System.err.println("No bot " + run.get() + " in My bots — showing the bot list instead.");
                showProjectSelection(primaryStage);
            }
        } else if (requested != null && projectExists(requested)) {
            openProject(primaryStage, requested, false);
        } else {
            if (requested != null) {
                // Named but absent: say so rather than silently showing the list, because the caller was
                // a program (`botmaker run --project`) and a list is not an answer a program can read.
                System.err.println("No project at " + requested + " — showing the bot list instead.");
            }
            showProjectSelection(primaryStage);
        }
    }

    /**
     * macOS hands a double-clicked {@code .bmk} to the running app as an event, never on the command line (the
     * dmg's file type, spec § 5). It opens on the home; with a project open, the user is told where to go instead
     * of the project being closed under them.
     */
    private void listenForOpenedFiles(Stage primaryStage) {
        if (!Os.current().isMac()) return;
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) return;
            Desktop.getDesktop().setOpenFileHandler(event -> {
                Optional<Path> bmk = BmkOpen.requested(Optional.empty(),
                        event.getFiles().stream().map(File::getPath).toList());
                bmk.ifPresent(file -> Platform.runLater(() -> {
                    if (currentWindow instanceof RunHome home) {
                        home.openBmk(file);
                    } else {
                        Alert alert = ThemedWindows.alert(Alert.AlertType.INFORMATION);
                        alert.initOwner(primaryStage);
                        alert.setHeaderText("Open " + file.getFileName() + " from My bots");
                        alert.setContentText("Go back to My bots (File ▸ My Bots), then open the file again.");
                        alert.show();
                    }
                }));
            });
        } catch (RuntimeException | LinkageError e) {
            System.err.println("A double-clicked .bmk will not reach this Studio: " + e.getMessage());
        }
        // A clicked botmaker:// link arrives the same way, launched or running (the bundle's CFBundleURLTypes).
        try {
            if (!Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) return;
            Desktop.getDesktop().setOpenURIHandler(event ->
                    Platform.runLater(() -> openLink(event.getURI().toString())));
        } catch (RuntimeException | LinkageError e) {
            System.err.println("A clicked botmaker:// link will not reach this Studio: " + e.getMessage());
        }
    }

    /**
     * A clicked {@code botmaker://run/<owner>/<repo>} link ({@link BotLink}), on the FX thread: the bot already in My
     * bots by that repository opens in its runner window, as {@code --run} does; any other is got as its card's
     * <i>Get</i> would ({@link RunHome#openLink}). With a bot open, the user is asked before it is closed. A link
     * Studio does not open is said, and nothing changes.
     */
    private void openLink(String text) {
        primaryStage.setIconified(false);
        primaryStage.toFront();
        Optional<BotLink> link = BotLink.parse(text);
        if (link.isEmpty()) {
            Alert alert = ThemedWindows.alert(Alert.AlertType.WARNING);
            alert.initOwner(primaryStage);
            alert.setHeaderText("Not a bot link");
            alert.setContentText(text + " is not a link Studio opens. A bot's link is botmaker://run/<owner>/<repo>.");
            alert.show();
            return;
        }
        if (!(currentWindow instanceof RunHome)) {
            Alert confirm = ThemedWindows.alert(Alert.AlertType.CONFIRMATION,
                    "Studio closes what is open, then opens " + link.get().slug() + " to run.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(primaryStage);
            confirm.setHeaderText("Open " + link.get().slug() + "?");
            if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;
        }
        // My bots is read off the FX thread: it opens each bot's repository.
        CompletableFuture.supplyAsync(() -> botByRepo(link.get().slug())).whenComplete((mine, failed) ->
                Platform.runLater(() -> {
                    if (mine != null && mine.isPresent()) {
                        openAs(primaryStage, mine.get(), true, false);
                    } else {
                        RunHome home = currentWindow instanceof RunHome shown ? shown
                                : showProjectSelection(primaryStage);
                        home.openLink(link.get());
                    }
                }));
    }

    // =========================================================================
    // HOME
    // =========================================================================

    /** The home: My bots over Get more bots ({@link RunHome}); it replaced the project selection screen. */
    private RunHome showProjectSelection(Stage primaryStage) {
        // The home becomes the current window, so whatever window and project an error path leaves open are let
        // go here: nothing else would dispose them, and the project's bus would still reopen it.
        disposeUi();
        if (currentProject != null) {
            currentProject.close();
            currentProject = null;
        }
        RunHome home = RunHome.studio(primaryStage, new RunHome.Actions() {
            @Override
            public void run(Path dir) {
                openAs(primaryStage, dir, true, false);
            }

            @Override
            public void edit(Path dir, boolean freshlyCreated) {
                editProject(primaryStage, dir, freshlyCreated);
            }

            @Override
            public void make(LoadingPage.Making making) {
                BotMakerStudio.this.make(primaryStage, making);
            }
        });
        this.currentWindow = home;
        setScenePreservingGeometry(primaryStage, home.createScene());
        primaryStage.setTitle("BotMaker");
        primaryStage.show();
        requestSceneLayout(primaryStage);
        return home;
    }

    /**
     * Opens the editor on {@code projectDir}; a bot opened from a {@code .bmk} is cloned from its author first, on
     * the loading page's Copying step ({@link BmkFlow#editing}), and the editor opens on the clone (spec § 1a, N13).
     */
    private void editProject(Stage primaryStage, Path projectDir, boolean freshlyCreated) {
        Optional<BotJson> unpacked = UnpackedBots.at(projectDir);
        if (unpacked.isEmpty()) {
            openAs(primaryStage, projectDir, false, freshlyCreated);
            return;
        }
        make(primaryStage, new BmkFlow(primaryStage).editing(projectDir, unpacked.get()));
    }

    /** Opens {@code projectDir} in the Runner ({@code asUser}) or the editor, as a home card's button asked. */
    private void openAs(Stage primaryStage, Path projectDir, boolean asUser, boolean freshlyCreated) {
        openProjectDir = projectDir;
        showAsUser = asUser;
        openProject(primaryStage, projectDir, freshlyCreated);
    }

    /**
     * Forces a layout pass once the real scene is shown, so the content fills the stage. The stage is given
     * its geometry in {@link #configureWindow} before {@code show()} and keeps it across every scene swap
     * ({@link #setScenePreservingGeometry}), so this is a belt-and-suspenders relayout rather than a resize.
     */
    private void requestSceneLayout(Stage stage) {
        Platform.runLater(() -> {
            if (stage.getScene() != null && stage.getScene().getRoot() != null) {
                stage.getScene().getRoot().requestLayout();
            }
        });
    }

    // =========================================================================
    // PROJECT LIFECYCLE
    // =========================================================================

    private void openProject(Stage primaryStage, Path projectDir, boolean freshlyCreated) {
        openProject(primaryStage, projectDir, freshlyCreated, null, Optional.empty());
    }

    /**
     * Opens {@code projectDir} behind the loading page (2026-10-25, spec § 1b): {@code page} when a bot made on it
     * opens there next, a new one otherwise. A branch switch that changed files ({@code filesOnly}) keeps the editor
     * in view under the reload box instead.
     */
    private void openProject(Stage primaryStage, Path projectDir, boolean freshlyCreated, LoadingPage page,
                             Optional<CoreApplicationEvents.BranchSwitch> filesOnly) {
        // A run copy by its bot.json name, never its tag folder (2026-10-25).
        String projectName = UnpackedBots.nameOf(projectDir);
        int generation = ++opens;
        // 1. Close previous project — its window first. This is also the reload path (a VCS rollback publishes
        //    ProjectReloadRequestedEvent), so it runs far more often than "the user picked another project";
        //    without releasing the shell the Remote Pilot port and its nested display would survive every one.
        disposeUi();
        if (currentProject != null) {
            currentProject.close();
            currentProject = null;
        }

        // A window choice belongs to the project it was made about. Reloading the same one (the audience
        // toggle, a VCS rollback) keeps it; moving to another drops it.
        if (!projectDir.equals(openProjectDir)) showAsUser = null;
        openProjectDir = projectDir;

        // 2. Save preference
        ProjectPreferences.updateLastOpened(projectDir);

        // 3. Show the loading page immediately so the window is never blank/frozen while the (slow, possibly
        //    download-heavy) open runs off the FX thread: each step ticked as it ends, the current one's bar a
        //    real percentage while dependencies download. A branch switch's reload keeps the editor in view.
        LoadingPage shown;
        ProgressReporter progress;
        if (filesOnly.isPresent() && page == null && primaryStage.getScene() != null) {
            shown = null;
            progress = ReloadBox.over(primaryStage.getScene(), filesOnly.get().branch(),
                    filesOnly.get().changedFiles()).reporter();
        } else {
            shown = page != null ? page
                    : new LoadingPage(LoadingPage.Bot.named(projectName, Optional.of(projectDir)), OpenStep.OPEN);
            if (page == null) describeLater(shown, projectDir);
            showPage(primaryStage, shown);
            primaryStage.setTitle("BotMaker - Opening " + projectName + "…");
            progress = shown.reporter();
        }

        // 4. Run BotProject.open() on a background thread; it reports each step to the page.
        Task<OpenedProject> openTask = new Task<>() {
            @Override
            protected OpenedProject call() {
                BotProject project = BotProject.open(projectDir, false, progress);
                try {
                    // Reading the sources belongs here rather than in finishOpen: it is a disk walk plus one
                    // readString per file, and on FX it ran with the main window already shown — a project with a
                    // few dozen activities froze it for as long as the disk took.
                    // A Claude Code session started in the bot's folder edits it only through Studio's tools.
                    BotGuard.ensure(projectDir);
                    progress.step(OpenStep.FILES);
                    return new OpenedProject(project, project.getCodeEditorService().readProjectSources());
                } catch (RuntimeException e) {
                    // Nothing else holds it yet: a failure here is the project's last chance to be closed.
                    project.close();
                    throw e;
                }
            }
        };

        openTask.setOnSucceeded(e -> {
            OpenedProject opened = openTask.getValue();
            currentProject = opened.project();
            if (shown == null) {
                finishOpen(primaryStage, projectDir, freshlyCreated, opened.sources(), null, generation);
                return;
            }
            // One frame with the last step showing, so the checklist never jumps from Files to the window.
            shown.step(OpenStep.EDITOR);
            afterFirstPaint(() -> {
                if (currentProject != opened.project()) return;
                finishOpen(primaryStage, projectDir, freshlyCreated, opened.sources(), shown, generation);
            });
        });

        openTask.setOnFailed(e -> failOpen(primaryStage, projectDir, freshlyCreated, shown, openTask.getException(),
                generation));

        Thread t = new Thread(openTask, "project-open");
        t.setDaemon(true);
        t.start();
    }

    /** Fills in {@code page}'s left side — who made the bot, its version and description — off the FX thread. */
    private static void describeLater(LoadingPage page, Path projectDir) {
        CompletableFuture.supplyAsync(() -> LoadingPage.Bot.of(projectDir)).thenAccept(bot ->
                Platform.runLater(() -> page.describe(bot)));
    }

    /** Puts {@code page} on the shell, unless it is what the shell shows already. */
    private void showPage(Stage primaryStage, LoadingPage page) {
        if (primaryStage.getScene() == null || primaryStage.getScene().getRoot() != page.root()) {
            setScenePreservingGeometry(primaryStage, page.scene());
        }
        primaryStage.show();
        requestSceneLayout(primaryStage);
    }

    /**
     * An open that failed says so on its page (2026-10-25, spec § 1b: no error alert): the sentence in the failed
     * step's place, <i>Try again</i>, <i>Back to My bots</i> and <i>Details</i>. Whatever the open left is let go —
     * unless another open started since ({@code generation}), whose project and page this failure must not touch.
     */
    private void failOpen(Stage primaryStage, Path projectDir, boolean freshlyCreated, LoadingPage page,
                          Throwable ex, int generation) {
        // A too-new project is a refusal, not a failure: its sentence says it all.
        if (ex != null && !(ex instanceof ProjectSchemaTooNew)) ex.printStackTrace();
        if (generation != opens) return;
        disposeUi();
        if (currentProject != null) {
            currentProject.close();
            currentProject = null;
        }
        String projectName = UnpackedBots.nameOf(projectDir);
        LoadingPage failed = page != null ? page
                : new LoadingPage(LoadingPage.Bot.named(projectName, Optional.of(projectDir)), OpenStep.OPEN);
        showPage(primaryStage, failed);
        primaryStage.setTitle("BotMaker - " + projectName + " did not open");
        // A project saved by a newer Studio opens no better a second time: no Try again.
        failed.fail(openFailure(ex), ex, ex instanceof ProjectSchemaTooNew ? null
                        : () -> openProject(primaryStage, projectDir, freshlyCreated),
                () -> showProjectSelection(primaryStage));
    }

    /** The failed step's sentence: a too-new project's own explanation, else what went wrong. */
    static String openFailure(Throwable ex) {
        // Not dressed as a failure: the message is a whole explanation with the way out in it.
        if (ex instanceof ProjectSchemaTooNew) return ex.getMessage();
        String why = ex == null ? null : ex.getMessage();
        return "Could not open this bot: " + (why == null || why.isBlank() ? "an unknown error" : why);
    }

    /**
     * A bot made on the loading page — ＋ New bot's or Edit in Studio's copy, Get's download, a {@code .bmk} file's
     * unpack — then opened on the same page (2026-10-25, spec § 1b, P1; N12, N13). A failure says so there, and
     * <i>Try again</i> makes it again; a making the user called off goes back to My bots.
     */
    private void make(Stage primaryStage, LoadingPage.Making making) {
        disposeUi();
        int generation = ++opens;
        LoadingPage page = new LoadingPage(making.bot(), OpenStep.after(making.first()));
        showPage(primaryStage, page);
        primaryStage.setTitle("BotMaker - Opening " + making.bot().name() + "…");
        page.step(making.first());
        ProgressReporter progress = page.reporter();
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return making.work().make(progress);
            }
        };
        task.setOnSucceeded(e -> {
            if (generation != opens) return;
            Path dir = task.getValue();
            openProjectDir = dir;
            showAsUser = making.opens() == LoadingPage.Opens.RUNNER;
            // A .bmk file is known by its file name until it is unpacked: its bot.json says the rest.
            if (making.bot().who().isEmpty()) describeLater(page, dir);
            openProject(primaryStage, dir, making.opens() == LoadingPage.Opens.NEW_BOT, page, Optional.empty());
        });
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            if (generation != opens) return;
            if (ex instanceof LoadingPage.Cancelled) {
                showProjectSelection(primaryStage);
                return;
            }
            if (ex != null) ex.printStackTrace();
            String why = ex == null || ex.getMessage() == null ? "an unknown error" : ex.getMessage();
            page.fail(why, ex, () -> make(primaryStage, making), () -> showProjectSelection(primaryStage));
        });
        Thread t = new Thread(task, "bot-make");
        t.setDaemon(true);
        t.start();
    }

    /** What the background open produced: the project, and the sources read for it off the FX thread. */
    private record OpenedProject(BotProject project, java.util.List<ProjectFile> sources) {}

    /**
     * Post-open UI wiring that must run on the FX thread once {@link BotProject#open} has completed.
     *
     * <p>The order here is the point. Parsing the entry point with bindings and building its blocks is the one
     * genuinely slow step left, and it used to run <em>between</em> setting the scene and painting it — the
     * window was up, sized and completely white for the whole of it, which is indistinguishable from a hang.
     * So the shell is shown first with the canvas in its loading state, and the parse is handed to
     * {@link #afterFirstPaint}. Everything after the parse (the reload subscription, the Wayland notice, the
     * new-project setup dialog) goes with it, since all of it either assumes a rendered program or opens a
     * dialog over one.
     */
    private void finishOpen(Stage primaryStage, Path projectDir, boolean freshlyCreated,
                            java.util.List<ProjectFile> sources, LoadingPage page, int generation) {
        String projectName = UnpackedBots.nameOf(projectDir);
        try {
            // A project saved by a newer Studio is refused here and not in ProjectOpenMigrations, because it
            // has to be refused for *both* audiences: a file shape this build cannot read is no more readable
            // to the Runner than to the editor, and the Runner never reaches the migrations at all. Before the
            // close hook and the reload subscription, so a refusal leaves nothing wired to a project we are
            // about to abandon.
            ProjectSchema.check(currentProject.getConfig());

            primaryStage.setOnCloseRequest(e -> {
                e.consume();
                shutdown();
            });

            // A VCS rollback rewrites the working tree on disk, and "Improve this bot" changes which window
            // this project gets; both ask for a reload. Subscribed before either window is built, because
            // both of them can raise it.
            currentProject.getEventBus().subscribe(
                    CoreApplicationEvents.ProjectReloadRequestedEvent.class,
                    e -> openProject(primaryStage, projectDir, false, null, e.filesOnly()), true);
            currentProject.getEventBus().subscribe(
                    CoreApplicationEvents.OpenProjectRequestedEvent.class,
                    e -> openProject(primaryStage, e.projectDir(), true), true);

            // The audience decides the whole window, not a set of hidden controls — so the branch is here,
            // before anything editor-shaped is constructed. The Runner needs no parsed source at all, which
            // is why it returns before the block-building work below.
            if (openAsUser()) {
                openRunner(primaryStage, projectDir);
                return;
            }

            UIManager uiManager = getUiManager(primaryStage, projectDir);

            setScenePreservingGeometry(primaryStage, uiManager.createScene());
            primaryStage.setTitle("BotMaker Blocks - " + projectName
                    + " — " + ProjectConfig.displayDirectory(projectDir));
            uiManager.showEditorLoading();

            primaryStage.show();
            requestSceneLayout(primaryStage);

            // The project this deferred work belongs to. A reload (or a fast switch to another project) can
            // land between the frame and the tick, and the sources read above are this project's, not its
            // successor's.
            BotProject opened = currentProject;
            afterFirstPaint(() -> {
                if (currentProject != opened) return;
                try {
                    currentProject.getCodeEditorService().openInitialFile(sources);

                    // One-time-per-session: on Wayland, guide the user to switch to X11 (and offer install).
                    if (!waylandNoticeChecked) {
                        waylandNoticeChecked = true;
                        ForceX11Notice.maybeShow(primaryStage);
                    }

                    // A brand-new project used to auto-open the Project Setup checklist here. That checklist
                    // is the SDK plugin's 📋 Project Setup toolbar item since 2026-08-31, and the shell has
                    // no handle on another plugin's item — the same precedent the Capture Templates and
                    // Capture Targets steps set. The button is on the bar the moment the project opens.
                } catch (Exception e) {
                    failOpen(primaryStage, projectDir, freshlyCreated, page, e, generation);
                }
            });
        } catch (Exception e) {
            // A too-new project's refusal too: openFailure keeps its own explanation.
            failOpen(primaryStage, projectDir, freshlyCreated, page, e, generation);
        }
    }

    /**
     * Runs {@code work} once the window has actually been drawn.
     *
     * <p>Not {@code Platform.runLater}: the runLater queue is drained at the <em>start</em> of a pulse, before
     * that pulse lays out and paints, so a long task posted there still delays the first frame — the blank
     * window would just be blank for a pulse longer. An {@link AnimationTimer} ticks once per pulse, so
     * skipping one tick puts the work after a completed frame.
     */
    private static void afterFirstPaint(Runnable work) {
        new AnimationTimer() {
            private int pulses;

            @Override
            public void handle(long now) {
                if (pulses++ < 2) return;
                stop();
                work.run();
            }
        }.start();
    }

    // createLoadingScene (a title, a bar and one grey line) became ui/app/loading/LoadingPage on 2026-10-25.

    /** True when this project should open in the Runner: the user said so, or it is somebody else's bot. */
    private boolean openAsUser() {
        return showAsUser != null ? showAsUser : currentProject.context().state().isReaderMode();
    }

    /**
     * Builds the Runner — the window for using a bot rather than writing one. Its way out is a reload rather
     * than a scene swap: the project's services and its event bus are rebuilt with it, so nothing from the
     * window being left behind can still be listening.
     */
    private void openRunner(Stage primaryStage, Path projectDir) {
        // By what the project is, not by who asked (2026-10-25): the home's ▶ Run asks for the Runner on any bot,
        // and somebody else's bot keeps "Improve this bot" while the user's own offers the way back to editing.
        RunnerWindow.Origin origin = currentProject.context().state().isReaderMode()
                ? RunnerWindow.Origin.INSTALLED
                : RunnerWindow.Origin.PREVIEW;
        RunnerWindow runner = new RunnerWindow(currentProject.context(), primaryStage, origin, () -> {
            if (UnpackedBots.isUnpacked(projectDir)) {
                editProject(primaryStage, projectDir, false);
                return;
            }
            showAsUser = Boolean.FALSE;
            openProject(primaryStage, projectDir, false);
        }, () -> switchToProjectSelector(primaryStage));
        this.currentWindow = runner;

        setScenePreservingGeometry(primaryStage, runner.createScene());
        primaryStage.setTitle("BotMaker - " + UnpackedBots.nameOf(projectDir)
                + " — " + ProjectConfig.displayDirectory(projectDir));
        primaryStage.show();
        requestSceneLayout(primaryStage);
    }

    private UIManager getUiManager(Stage primaryStage, Path projectDir) {
        UIManager uiManager = new UIManager(currentProject.context(), primaryStage);
        uiManager.setOnSelectProject(v -> switchToProjectSelector(primaryStage));
        uiManager.setOnGetMoreBots(() -> showProjectSelection(primaryStage).scrollToGetBots());
        uiManager.setOnPreviewAsUser(() -> {
            showAsUser = Boolean.TRUE;
            openProject(primaryStage, projectDir, false);
        });
        this.currentWindow = uiManager;
        return uiManager;
    }

    /**
     * Releases the current window's OS resources (the Remote Pilot port, its nested display, the theme
     * listeners) before the project behind it goes away. FX-thread only, and idempotent — every path that ends
     * a project calls it.
     */
    private void disposeUi() {
        if (currentWindow != null) {
            currentWindow.dispose();
            currentWindow = null;
        }
    }

    /** The toolkit is ending: the UI tools' endpoint goes too, or its server threads keep the JVM alive. */
    @Override
    public void stop() {
        HomeEndpoint.yieldPort();
        channel.ifPresent(StudioChannel::close);
    }

    private void switchToProjectSelector(Stage primaryStage) {
        showProjectSelection(primaryStage);
    }

    private void shutdown() {
        disposeUi(); // on the FX thread, before the background close below
        new Thread(() -> {
            try {
                if (currentProject != null) currentProject.close();
            } catch (Exception ex) {
                System.err.println("Error during shutdown: " + ex.getMessage());
            } finally {
                Platform.runLater(() -> {
                    Platform.exit();
                    System.exit(0);
                });
            }
        }).start();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Load the window/taskbar icon from the bundled PNG rasters (generated from {@code icons/icon.svg};
     * JavaFX's {@link Image} can't read SVG). Multiple sizes let the OS pick the sharpest per context.
     * Missing rasters are skipped so a fresh checkout without generated PNGs still launches.
     */
    /**
     * Sizes the window at startup and keeps its geometry synced with {@link ProjectPreferences}.
     *
     * <p>A session that ended maximized reopens maximized, through {@code setMaximized(true)}; a session that
     * ended at a size the user chose reopens at that size. A fresh install gets a large inset window. The
     * saved restored geometry is kept alongside the maximized flag, so un-maximizing lands on the size the
     * user last picked rather than on whatever the WM decides.
     *
     * <p><b>Only a real user resize is recorded.</b> This used to track every {@code x/y/width/height} change
     * and clear the maximized flag on each, then flush on focus loss — so opening a dialog (which takes focus)
     * wrote back whatever geometry the WM had transiently reported, and the main window drifted a little every
     * time a dialog opened or closed. Now the write is debounced, and both the write and the tracking are
     * skipped unless the stage is focused and not maximized: while a dialog is up, the shell's own geometry is
     * not the user talking.
     */
    private void configureWindow(Stage stage) {
        javafx.geometry.Rectangle2D vb = javafx.stage.Screen.getPrimary().getVisualBounds();
        ProjectPreferences.WindowState saved = ProjectPreferences.loadWindowState();

        // The restored (non-maximized) geometry we persist and fall back to; a large inset window by default.
        ProjectPreferences.WindowState state = (saved != null && saved.isUsable())
                ? saved
                : new ProjectPreferences.WindowState(
                        vb.getMinX() + vb.getWidth() * 0.05, vb.getMinY() + vb.getHeight() * 0.05,
                        vb.getWidth() * 0.9, vb.getHeight() * 0.9, true);

        // The floor goes on the Stage, not on the scene root: root.setMinWidth(0) (UIManager.createScene) is
        // what stops a growing toolbar pushing the window outwards and must stay 0, so the root's computed
        // minimum — which is what JavaFX would otherwise derive the window's from — says nothing. Below this
        // the editor is not merely untidy: the canvas has no room for a block's own width and the toolbar is
        // entirely in its overflow menu. Clamped to the screen, since a minimum larger than the display is a
        // window that cannot be positioned at all.
        stage.setMinWidth(Math.min(MIN_WINDOW_WIDTH, vb.getWidth()));
        stage.setMinHeight(Math.min(MIN_WINDOW_HEIGHT, vb.getHeight()));

        stage.setX(state.getX());
        stage.setY(state.getY());
        stage.setWidth(state.getWidth());
        stage.setHeight(state.getHeight());
        if (state.isMaximized()) stage.setMaximized(true);

        // One pending write at a time, a second after the last change: a drag-resize is hundreds of property
        // events, and the file is rewritten wholesale.
        javafx.animation.PauseTransition flush = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));
        flush.setOnFinished(e -> ProjectPreferences.saveWindowState(state));

        javafx.beans.value.ChangeListener<Number> geom = (obs, o, n) -> {
            if (StudioWindow.fillsScreen(stage) || !stage.isFocused() || !stage.isShowing()) return;
            state.setX(stage.getX());
            state.setY(stage.getY());
            state.setWidth(stage.getWidth());
            state.setHeight(stage.getHeight());
            state.setMaximized(false);
            flush.playFromStart();
        };
        stage.xProperty().addListener(geom);
        stage.yProperty().addListener(geom);
        stage.widthProperty().addListener(geom);
        stage.heightProperty().addListener(geom);

        // The maximize toggle is a user act whatever the focus state, and cheap enough to write straight out.
        stage.maximizedProperty().addListener((obs, was, isMax) -> {
            // Going fullscreen drops the maximized flag on some window managers. That is the WM answering, not
            // the user un-maximizing, and persisting it would reopen a session that ended full-screen as a
            // small window.
            if (stage.isFullScreen()) return;
            state.setMaximized(isMax);
            ProjectPreferences.saveWindowState(state);
        });
        stage.setOnHidden(e -> {
            flush.stop();
            ProjectPreferences.saveWindowState(state);
        });
    }

    /** Shows a screen on the shell without moving or resizing it — see {@link StudioWindow#showOnShell}. */
    private void setScenePreservingGeometry(Stage stage, Scene built) {
        StudioWindow.showOnShell(stage, built);
    }

    private void applyAppIcons(Stage stage) {
        for (int size : new int[] {16, 32, 64, 128, 256, 512}) {
            InputStream in = getClass().getResourceAsStream("/icons/icon-" + size + ".png");
            if (in != null) stage.getIcons().add(new Image(in));
        }
    }

    /**
     * The project named on the command line as {@code --project=<name or path>}, as a directory, or
     * {@code null}.
     *
     * <p>Added 2026-08-28 for {@code botmaker run}, whose whole job is to build a plugin and put the user in
     * front of a project that uses it. Without it the CLI would have to write Studio's own preferences file
     * from outside to choose what opens, which is a program reaching into another program's state.
     *
     * <p>A bare <em>name</em> is a project under {@link Constants#PROJECTS_ROOT}, which is what every caller
     * written before 2026-09-18 passes and what {@code botmaker run} still passes. A value containing a path
     * separator is a directory, because since that date a project is identified by its directory and may
     * live anywhere — a template kept in its own repository is the case that forced it. Nothing is guessed
     * between the two: a name cannot contain a separator, so the separator is the whole rule.
     *
     * <p>It does not become the remembered project by itself — {@link #openProject} does that, exactly as it
     * would for a click. Opening one project by request and then having Studio reopen it next time is what a
     * user expects; suppressing that would be a special case with no reason behind it.
     */
    private Path requestedProject() {
        Parameters parameters = getParameters();
        if (parameters == null) {
            return null;
        }
        String named = parameters.getNamed().get("project");
        if (named == null || named.isBlank()) return null;
        boolean isPath = named.contains("/") || named.contains(java.io.File.separator);
        Path dir = isPath ? Path.of(named) : Constants.PROJECTS_ROOT.resolve(named);
        return dir.toAbsolutePath().normalize();
    }

    /** The command line's {@code --<name>=<value>}, when it is there and not blank. */
    private Optional<String> named(String name) {
        Parameters parameters = getParameters();
        String value = parameters == null ? null : parameters.getNamed().get(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * {@code --run=<owner>/<repo>}: the My bots folder of that bot — the one the author's repository was cloned into,
     * else its newest tag opened from a file — as My bots shows it. {@code --run=<absolute folder>}: that bot of the
     * user's own, the way a programs-menu entry names one ({@link ProgramEntries}).
     */
    private static Optional<Path> botByRepo(String slug) {
        Optional<Path> byFolder;
        try {
            Path folder = Path.of(slug);
            byFolder = folder.isAbsolute() ? Optional.of(folder.normalize()) : Optional.empty();
        } catch (InvalidPathException e) {
            byFolder = Optional.empty();   // not a path on this system: an owner/repo, or nothing
        }
        Optional<Path> asked = byFolder;
        int slash = slug.indexOf('/');
        if (byFolder.isEmpty() && slash <= 0) return Optional.empty();
        String key = byFolder.isPresent() ? "" : MyBots.key(slug.substring(0, slash), slug.substring(slash + 1));
        var opened = ProjectPreferences.recentOpenTimes();
        return MyBots.scan(MyBots.folders(new ProjectManager(), ProjectPreferences.recentDirectories(),
                        UnpackedBots.newest(UnpackedBots.root())), dir -> MyBots.read(dir, opened), Map.of())
                .stream()
                .filter(b -> b.state() == MyBots.State.READY && b.runnable())
                .filter(b -> asked.map(f -> f.equals(b.dir().toAbsolutePath().normalize()))
                        .orElseGet(() -> b.original().map(s -> MyBots.key(s.owner(), s.repo()).equals(key))
                                .orElse(false)))
                .map(MyBots.MyBot::dir).findFirst();
    }

    private static boolean projectExists(Path projectDir) {
        return Files.exists(projectDir) && Files.exists(projectDir.resolve("pom.xml"));
    }

    /**
     * Every word Studio shows is English, JavaFX's own too (2026-10-25, the user's call: "Annuler" and
     * "Avertissement" beside English text): its buttons and dialog titles follow the default locale, so that is
     * English, before any control loads its words. Numbers and dates keep the computer's format.
     */
    static void speakEnglish() {
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.ENGLISH);
        Locale.setDefault(Locale.Category.FORMAT, format);
    }

    public static void main(String[] args) {
        // Before anything else — certainly before JavaFX: this same program is what a session re-execs to get a
        // process that can hold a :N connection safely (see DisplayAgent). Booting a whole IDE to answer window
        // queries over a pipe would be absurd, and the agent's stdout is a binary protocol a UI would corrupt.
        if (DisplayAgent.isAgentInvocation(args)) {
            DisplayAgent.run(args);
            return;
        }
        // Swallow benign Xlib protocol errors (BadMatch from window capture, etc.) at their source. Must run
        // BEFORE launch(): installing an Xlib error handler after JavaFX's GTK backend is up triggers GDK's
        // own "XSetErrorHandler() called with a GDK error trap pushed" warning. No-op off Linux.
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            X11ErrorTrap.install();
        }
        // A clicked botmaker:// link goes to the Studio already running, if one listens; this launch ends there.
        Optional<String> link = BotLink.asked(List.of(args));
        if (link.isPresent() && StudioChannel.handOver(StudioChannel.socket(), link.get())) return;
        speakEnglish();
        launch(args);
    }
}
