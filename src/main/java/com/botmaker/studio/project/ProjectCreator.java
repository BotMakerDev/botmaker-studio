package com.botmaker.studio.project;

import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.MavenService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.botmaker.studio.config.Constants.PROJECTS_ROOT;

/**
 * Creates a new user project, and since 2026-09-01 <b>writes every byte of it</b>.
 *
 * <p>It used to hand the job to {@code Authoring.createProject}, which owned five directories, an empty
 * {@code activities.json}, {@code botmaker-project.properties}, a placeholder PNG and the all-or-none
 * commit — and took Studio's own files as {@code callerFiles} so they landed in the same pass. Every one of
 * those five has since become something Studio either already owned or should not write at all:
 *
 * <ul>
 *   <li>the directories are a {@code mkdir} list and were never knowledge;
 *   <li><b>{@code activities.json} is nobody's here at all.</b> Studio wrote an empty stamped one for a game
 *       bot until 2026-09-11 — with its own mapper and its own schema stamp — and it is the SDK plugin's
 *       file: the plugin creates it on its first save, and a project with nothing stored is the state every
 *       reader of it already has to handle;
 *   <li>{@code botmaker-project.properties} carried only the capture resolution, which stopped being the
 *       editor's on 2026-09-01 — a fresh project has neither key, and the capturing plugin seeds them;
 *   <li>the placeholder picture belongs to whoever offers the type it stands for, so the plugin's own
 *       picture surfaces call {@code TemplateLibrary.ensurePlaceholder} when they first look at the folder.
 *       Creation writing it meant a project created without that plugin still got its file;
 *   <li>the pom and every {@code .java} were already composed here.
 * </ul>
 *
 * <p>The pom won that argument first, on 2026-08-26 after one day in the SDK. It is not a file about the
 * SDK, it is the file that declares <em>which</em> SDK the project has — and the SDK is the editor's default
 * plugin, not the editor. A second plugin would be invisible to it, so a pom it wrote would silently omit
 * that plugin's dependency. The entry point is the same argument one step on: it is where those plugins get
 * <em>installed</em>. And the argument for every other file is plainer still — a project's structure belongs
 * to the user, so it is written once ({@link StarterSources}) and never read, rewritten or restored.
 *
 * <p><b>All of it or none of it survives whole</b>, and it is the one thing that had to be carried across
 * rather than dropped: every file is rendered into a map before the first directory exists, anything that
 * can refuse refuses while there is nothing to clean up, and whole-file ownership is enforced by a
 * collision check rather than a merge. A half-created project is worse than no project — the editor lists
 * it, opening it fails in a different place each time, and the user has to find and delete it by hand.
 */
public class ProjectCreator {

    private final Path root;
    private final ShadowCheck shadows;

    public ProjectCreator() {
        this(PROJECTS_ROOT, MavenService::dropShadowedPlugins);
    }

    /** A creator over {@code root}; {@code shadows} stands in for the resolve, which reaches the network. */
    ProjectCreator(Path root, ShadowCheck shadows) {
        this.root = root;
        this.shadows = shadows;
    }

    /**
     * A plugin ticked in New Project (2026-10-01): its coordinate at the version to declare, and the
     * {@code provided} entries its registry entry says the editor needs beside it. The version is resolved by
     * the caller before anything is created, so a lookup that fails leaves nothing to delete.
     */
    public record PluginPick(UserLibrary plugin, List<UserLibrary> editorDependencies) {
        public PluginPick {
            editorDependencies = List.copyOf(editorDependencies);
        }
    }

    /** {@link MavenService#dropShadowedPlugins}, as a seam. */
    @FunctionalInterface
    interface ShadowCheck {
        List<MavenService.Shadowed> drop(Path projectDir) throws IOException;
    }

    public void createProject(String projectName) throws IOException {
        createProject(projectName, ProjectTemplate.EMPTY);
    }

    public void createProject(String projectName, ProjectTemplate template) throws IOException {
        createProject(projectName, template, List.of());
    }

    public void createFromTemplate(String projectName, TemplateUnpack unpack) throws IOException {
        createFromTemplate(projectName, unpack, List.of());
    }

    /**
     * Creates a new project. {@code template} records how it was started.
     *
     * <p><b>It names no plugin, and takes no SDK version (2026-09-04).</b> The pom is
     * {@link MavenService#blankPomXml}, whose whole dependency list is a test framework. Every project
     * created here used to pin {@code botmaker-sdk} plus eight entries serving its plugin half, which made
     * "blank" a bot project with a {@code System.out.println} in it and left a user who wanted a plain Java
     * project without one. The richer starting point is a published bot carrying the {@code template} tag —
     * {@link #createFromTemplate} — and the SDK is one install away in Manage Plugins.
     *
     * <p><b>No capture resolution is chosen here (2026-09-01).</b> The size templates are captured at is
     * the capturing plugin's, seeded by its own toolbar item the first time a picture is taken; a project
     * is created without one and {@code capture.width}/{@code capture.height} stay absent until then.
     *
     * <p><b>{@code plugins} are the ones the user ticked (2026-10-01)</b>, declared before the first commit
     * ({@link #installPlugins}). Their write is the one step that can fail after the directory exists, so a
     * failure now deletes it — unless it was there before this call, in which case it is not ours to delete.
     */
    public void createProject(String projectName, ProjectTemplate template, List<PluginPick> plugins)
            throws IOException {
        validateProjectName(projectName);

        ProjectConfig cfg = ProjectConfig.forProject(projectName, root);
        Path projectPath = cfg.projectPath();

        if (Files.exists(projectPath.resolve("pom.xml"))) {
            throw new IllegalArgumentException("Project '" + projectName + "' already exists");
        }
        boolean existed = Files.exists(projectPath);

        System.out.println("------------------------------------------------");
        System.out.println("Creating Project: " + projectName);
        System.out.println("Location: " + projectPath);
        System.out.println("------------------------------------------------");

        // There is no "can this SDK generate?" question to ask any more. It used to refuse an unrecognised
        // pin here, before anything existed, because the SDK was about to generate the project's files
        // against that version. It generates nothing now — the pin is a coordinate in a pom, and a pom
        // naming a version nobody can resolve fails where the user can read why.

        try {
            // 1. Everything the project is made of, in one pass: the src/ layout, the pom and every .java.
            //    Rendered first, committed second, so a refusal lands before a
            //    single directory exists and a project that cannot be created never has to be deleted by
            //    hand.
            System.out.println("1. Creating the project...");
            writeProject(cfg, template, Map.of("pom.xml", MavenService.blankPomXml(cfg)));
            installPlugins(projectPath, plugins, shadows);

            // 2. Seed settings.json (the chosen template). Studio's own file: no bot reads it, and it
            //    records what the editor chose rather than what the bot needs.
            System.out.println("2. Generating settings...");
            seedSettings(cfg, template);

            // 5b. The runtime tuning was seeded here into botmaker-project.properties until 2026-09-27. It is
            //     the SDK's @SdkValue(SdkValue.Id.SETTINGS) value now, in the bot's own Sdk.java, which the
            //     template ships and the host writes whole when a project has none; a project with no SDK has
            //     nothing to tune.

            // 6. Initialize local project history (linear VCS) with an initial commit.
            new ProjectVcs(projectPath).init();

            System.out.println("------------------------------------------------");
            System.out.println("SUCCESS: Project created at " + projectPath);
            System.out.println("------------------------------------------------");
        } catch (Exception e) {
            if (!existed) deleteRecursively(projectPath);
            System.err.println("!!! ERROR during project creation !!!");
            e.printStackTrace();
            throw new IOException("Failed to create project: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a project from a published template: download the release, rename it into the user's own
     * package and class, and record how it was made.
     *
     * <p><b>Nothing composed here reaches the result.</b> The pom is the template author's, versions and all,
     * and so are {@code activities.json}, the runtime settings and every {@code .java}. That is the point of
     * a template being a real published bot rather than a set of holes: what the user gets is a project that
     * demonstrably built for its author. Changing the SDK pin afterwards is <b>Project ▸ Manage Libraries</b>,
     * which is the same path any other version change takes.
     *
     * <p>The only thing written is the one Studio owns and the template cannot know: {@code settings.json},
     * whose {@code template} is {@link ProjectTemplate#FROM_TEMPLATE}. Whatever
     * {@code botmaker-project.properties} the template shipped is left exactly as its author wrote it.
     *
     * <p>All-or-none is kept the crude way rather than the {@code createProject} way: the unpack writes a
     * whole directory tree that {@code Authoring} never sees, so a failure anywhere in here deletes the
     * directory. A half-unpacked project the user has to remove by hand is exactly what the atomic pass on
     * the other path exists to prevent.
     *
     * @param projectName the user's name for it, which becomes the directory, the package and the class
     * @param unpack      downloads the release into the directory it is handed — {@code BotInstaller
     *                    ::unpackTemplate} bound to the chosen entry and tag, passed in so this class keeps
     *                    knowing nothing about GitHub
     * @param plugins     the ones the user ticked beside the template's own, declared before the first commit
     */
    public void createFromTemplate(String projectName, TemplateUnpack unpack, List<PluginPick> plugins)
            throws IOException {
        validateProjectName(projectName);

        ProjectConfig cfg = ProjectConfig.forProject(projectName, root);
        Path projectPath = cfg.projectPath();
        if (Files.exists(projectPath)) {
            throw new IllegalArgumentException("Project '" + projectName + "' already exists");
        }

        System.out.println("------------------------------------------------");
        System.out.println("Creating Project: " + projectName + " (from a template)");
        System.out.println("Location: " + projectPath);
        System.out.println("------------------------------------------------");

        try {
            System.out.println("1. Downloading the template...");
            unpack.into(projectPath);

            System.out.println("2. Making it yours...");
            TemplateProject.read(projectPath).renameInto(projectPath, "com." + cfg.packageName());
            installPlugins(projectPath, plugins, shadows);

            System.out.println("3. Generating settings...");
            seedSettings(cfg, ProjectTemplate.FROM_TEMPLATE);

            new ProjectVcs(projectPath).init();

            System.out.println("------------------------------------------------");
            System.out.println("SUCCESS: Project created at " + projectPath);
            System.out.println("------------------------------------------------");
        } catch (Exception e) {
            deleteRecursively(projectPath);
            System.err.println("!!! ERROR during project creation !!!");
            throw new IOException("Failed to create project from the template: " + e.getMessage(), e);
        }
    }

    /**
     * Declares each ticked plugin, then removes any declared plugin another one now brings — the same two steps
     * <i>Plugins &amp; Libraries ▸ Browse</i> takes, so a project made with the SDK ticked on Base has no direct
     * basics entry, exactly as one that installed it afterwards.
     *
     * <p>A plugin the pom already declares is skipped: it is the template's own, pinned the way its author pinned
     * it (often a {@code ${property}} its upgrade moves), and a pick has no business re-versioning it.
     */
    static List<MavenService.Shadowed> installPlugins(Path projectDir, List<PluginPick> plugins, ShadowCheck shadows)
            throws IOException {
        if (plugins.isEmpty()) return List.of();
        List<UserLibrary> declared = MavenService.readDeclaredLibraries(projectDir);
        boolean wrote = false;
        for (PluginPick pick : plugins) {
            UserLibrary plugin = pick.plugin();
            boolean own = declared.stream().anyMatch(d -> d.groupId().equals(plugin.groupId())
                    && d.artifactId().equals(plugin.artifactId()));
            if (own) continue;
            System.out.println("   + " + plugin.groupId() + ":" + plugin.artifactId() + ":" + plugin.version());
            MavenService.installPlugin(projectDir, plugin, pick.editorDependencies());
            wrote = true;
        }
        if (!wrote) return List.of();
        List<MavenService.Shadowed> dropped = shadows.drop(projectDir);
        if (!dropped.isEmpty()) System.out.println("   " + MavenService.Shadowed.sentence(dropped));
        return dropped;
    }

    /** Downloads a chosen template release into {@code dest}. */
    @FunctionalInterface
    public interface TemplateUnpack {
        void into(Path dest) throws IOException;
    }

    /** Removes a half-created project so a failure leaves nothing behind to delete by hand. */
    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best effort: the creation failure is what the user is told about, not this.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    /**
     * Writes {@code settings.json} — the originating {@code template}, which {@link FileRole} and
     * {@code ProjectRepair} read to tell scaffolding from user code. A project must record it or it is
     * indistinguishable from a legacy one.
     *
     * <p>The {@code capture.width}/{@code capture.height} mirror that used to go with it is gone
     * (2026-09-01), along with the resolution the editor used to pick. Those keys describe the size the
     * pictures were taken at, so the plugin that takes them writes them.
     */
    /**
     * The project's own files, rendered whole and then committed — the half that used to be
     * {@code Authoring.createProject}, absorbed on 2026-09-01.
     *
     * <p>Two rules travelled with it and neither is negotiable.
     *
     * <p><b>All of it or none of it.</b> Every file, ours and the caller's alike, is built in memory before
     * {@code src/main/java} exists. Anything that can refuse — a project already at this path, a game bot
     * whose model will not serialize — refuses while there is nothing to clean up.
     *
     * <p><b>Whole-file ownership, keyed by project-relative path.</b> A caller file colliding with one this
     * method writes is a hard error and never a merge. Two authors of one file is the mistake the scaffold
     * contract made and was deleted for, and the check outlives the arrangement that first needed it: it is
     * how a second plugin contributing files would be told it had claimed a path that is already taken.
     *
     * @param callerFiles project-relative path → content, committed in the same pass
     */
    static void writeProject(ProjectConfig cfg, ProjectTemplate template, Map<String, String> callerFiles)
            throws IOException {
        Path projectDir = cfg.projectPath();
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            throw new IOException("There is already a project at " + projectDir + ".");
        }

        // ---- render ----------------------------------------------------------------------------------
        // The starter source is what creation itself writes, and composing it here rather than taking it in
        // is what keeps the collision check below able to fire: it was checked against an empty map for as
        // long as every file arrived as a caller file, and a map cannot collide with itself.
        //
        // A game-bot project also got an empty, stamped activities.json until 2026-09-11. That file is the
        // SDK plugin's, and seeding a plugin's store means knowing its format; the plugin writes it on its
        // first save, and a project with nothing stored is the state every reader of it already handles.
        Map<String, String> files = new LinkedHashMap<>(StarterSources.of(cfg));
        for (Map.Entry<String, String> file : callerFiles.entrySet()) {
            if (files.containsKey(file.getKey())) {
                throw new IllegalArgumentException(
                        "Project creation already writes " + file.getKey() + "; a caller cannot also write it.");
            }
            files.put(file.getKey(), file.getValue());
        }

        // ---- commit ----------------------------------------------------------------------------------
        for (String dir : List.of("src/main/java", "src/main/resources", "src/test/java",
                "src/test/resources", "src/main/resources/images")) {
            Files.createDirectories(projectDir.resolve(dir));
        }
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = projectDir.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }

    static void seedSettings(ProjectConfig cfg, ProjectTemplate template) throws IOException {
        StudioProjectSettings.empty()
                .withTemplate(template)
                .write(cfg.studioRoot());
    }

    // Every read and write of botmaker-project.properties stood here until 2026-09-27 — writeLaunchTarget,
    // readCaptureSource, writeDebug, the session keys, launch.supported and the load-modify-store under them.
    // Nothing reads that file any more: a bot's tuning is its @SdkValue(SdkValue.Id.SETTINGS) value, what it
    // launches is this machine's run property (StudioProjectSettings.runProperties), and what it was tested on is the
    // gallery entry's. An old project keeps its file untouched.

    public boolean projectExists(String projectName) {
        Path projectPath = root.resolve(projectName);
        return Files.exists(projectPath.resolve("pom.xml"));
    }

    /**
     * The project name must be a single word of letters and digits, starting with a letter.
     *
     * <p>The first letter no longer has to be uppercase: the name is the user's, and the Java class name is
     * derived from it ({@link ProjectConfig#toClassName}) rather than being the same string. It still has to
     * start with a letter, because it also becomes the package name — {@code com.7bot} is not a package.
     */
    private void validateProjectName(String projectName) {
        if (projectName == null || projectName.trim().isEmpty() || !projectName.matches("^[A-Za-z][a-zA-Z0-9]*$")) {
            throw new IllegalArgumentException("Invalid project name: " + projectName);
        }
    }
}
