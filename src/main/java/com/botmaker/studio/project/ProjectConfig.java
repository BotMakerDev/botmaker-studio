package com.botmaker.studio.project;


import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Immutable configuration for a single project.
 *
 * <p>Three names, derived once here and never re-derived by callers:
 * <ul>
 *   <li>{@code projectName} — <b>what the user typed</b>. Names the project directory and the Maven artifactId,
 *       and is what the project list shows. It is theirs, so it is kept verbatim.</li>
 *   <li>{@code packageName} — {@code projectName} lowercased, because package names are lowercase.</li>
 *   <li>{@code className} — {@code projectName} with its first letter capitalized, because Java classes are
 *       capitalized. This used to be {@code projectName} itself, which is why the New Project dialog had to
 *       refuse a lowercase first letter: the name doubled as a class name, so {@code myBot} would have
 *       produced {@code class myBot}. Deriving the class name instead lets the user call their project
 *       whatever they like.</li>
 * </ul>
 */
public record ProjectConfig(
        String projectName,
        String packageName,
        String className,
        Path projectPath,
        Path sourceRoot,
        Path mainSourceFile,
        Path compiledOutputPath,
        String mainClassName,
        String javaHome,
        String javaExecutable,
        String javacExecutable
) {

    public static ProjectConfig forProject(String projectName, Path projectsRoot) {
        return of(projectName, projectName.toLowerCase(), projectsRoot.resolve(projectName));
    }

    /**
     * The project <b>in</b> {@code projectDir}, wherever that is — the way to open one Studio did not create
     * under {@code ~/BotMakerProjects}, such as a template kept in its own repository.
     *
     * <p>The name is still the folder's name. The package is not always derivable from it: a folder called
     * {@code botmaker-gamebot} holds {@code com.botmaker.gamebot}, and no rule gets from one to the other. A
     * template says which package it is in ({@link TemplateProject#FILE_NAME}), or since 2026-09-26 its
     * {@code main} does ({@link TemplateProject#read}), so that answer wins when there is one and it names a
     * {@code com.} package; otherwise the package is derived exactly as
     * {@link #forProject} derives it, which keeps a project under the default root reading the same through
     * either door.
     */
    public static ProjectConfig forDirectory(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize();
        String projectName = dir.getFileName().toString();
        String packageName = projectName.toLowerCase();
        try {
            String declared = TemplateProject.read(dir).packageName();
            if (declared.startsWith("com.") && declared.length() > "com.".length()) {
                packageName = declared.substring("com.".length());
            }
        } catch (java.io.IOException notATemplate) {
            // An ordinary project: the derived package is the answer.
        }
        return of(projectName, packageName, dir);
    }

    private static ProjectConfig of(String projectName, String packageName, Path projectPath) {
        String javaHome = System.getProperty("java.home");
        String className = toClassName(projectName);

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String javaBin = isWindows ? "java.exe" : "java";
        String javacBin = isWindows ? "javac.exe" : "javac";

        return new ProjectConfig(
                projectName,
                packageName,
                className,
                projectPath,
                projectPath.resolve("src").resolve("main").resolve("java"),
                packageDir(projectPath.resolve("src").resolve("main").resolve("java"), packageName)
                        .resolve(className + ".java"),
                // Maven standard output directory
                projectPath.resolve("target").resolve("classes"),
                "com." + packageName + "." + className,
                javaHome,
                Paths.get(javaHome, "bin", javaBin).toString(),
                Paths.get(javaHome, "bin", javacBin).toString()
        );
    }

    /**
     * The project directory as a window names it — {@code ~/IdeaProjects/gamebot}.
     *
     * <p>Shown because two checkouts of one template is the ordinary case here (the umbrella's
     * {@code botmaker-gamebot/} and a clone beside it), and a window naming only the project could be editing
     * either: an edit made in Studio then looks lost from the IDE open on the other copy.
     */
    public String displayDirectory() {
        return displayDirectory(projectPath);
    }

    /** The same, for a directory no config has been built for yet — a project still opening. */
    public static String displayDirectory(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        return absolute.startsWith(home) && !absolute.equals(home)
                ? "~/" + home.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString();
    }

    /**
     * The file holding the bot's {@code main} — <b>found, not assumed</b>.
     *
     * <p>{@link #mainSourceFile()} is the file a project Studio created <em>starts</em> with, named after the
     * project. That is a fact about creation and not about the project, and two things break it: a user who
     * renames or splits their entry class (their file, their call), and a project made from a published
     * template, which arrives exactly as its author shipped it and whose entry class is called whatever they
     * called it.
     *
     * <p>So: the derived path when it is there, otherwise the one source in the bot's package that declares a
     * {@code main}. Falls back to the derived path when there is no such file, because every caller wants a
     * path to complain about rather than a null.
     */
    public Path entrySourceFile() {
        if (java.nio.file.Files.isRegularFile(mainSourceFile)) return mainSourceFile;
        Path packageDir = mainSourceFile.getParent();
        if (packageDir == null || !java.nio.file.Files.isDirectory(packageDir)) return mainSourceFile;
        try (var files = java.nio.file.Files.list(packageDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(ProjectConfig::declaresMain)
                    .sorted()
                    .findFirst()
                    .orElse(mainSourceFile);
        } catch (java.io.IOException unreadable) {
            return mainSourceFile;
        }
    }

    /**
     * The fully-qualified class {@code java -cp …} is given — {@link #entrySourceFile()}'s class, so a
     * renamed entry point or a template's own is launched rather than a name that no longer exists.
     */
    public String entryClassName() {
        String file = entrySourceFile().getFileName().toString();
        return "com." + packageName + "." + file.substring(0, file.length() - ".java".length());
    }

    /**
     * A cheap textual test for {@code public static void main(String[])}. Deliberately not a parse: this runs
     * on a directory listing, before anything is compiled, and the cost of a wrong answer is that Run names
     * the wrong class in an error the user can read.
     */
    private static boolean declaresMain(Path javaFile) {
        try {
            String source = java.nio.file.Files.readString(javaFile);
            return source.contains("static void main(") || source.contains("static void main (");
        } catch (java.io.IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * {@code <sourceRoot>/com/<packageName>}, one directory per segment. A derived package has one segment;
     * a declared one ({@link #forDirectory}) may have several, and resolving {@code botmaker.gamebot} whole
     * would name a directory with a dot in it.
     */
    private static Path packageDir(Path sourceRoot, String packageName) {
        Path dir = sourceRoot.resolve("com");
        for (String segment : packageName.split("\\.")) dir = dir.resolve(segment);
        return dir;
    }

    /** {@code projectName} as a Java class name: the same word, capitalized. */
    public static String toClassName(String projectName) {
        if (projectName == null || projectName.isEmpty()) return projectName;
        return Character.toUpperCase(projectName.charAt(0)) + projectName.substring(1);
    }

    /**
     * {@code src/main/resources} — image templates, and one folder per plugin holding that plugin's own
     * data ({@code plugins/<id prefix>/<last segment>/}). Nothing here is the host's to read: a file under
     * {@code plugins/} belongs to whichever plugin's id names the folder.
     */
    public Path resourcesRoot() {
        return projectPath.resolve("src").resolve("main").resolve("resources");
    }

    /**
     * {@code .botmaker} at the project root — Studio's own editor state for this checkout ({@code settings.json}).
     * Outside {@code src} so none of it is packaged into the bot's jar, and ignored by git (the project's
     * {@code .gitignore} for a new project, {@code .git/info/exclude} for every one — {@code ProjectVcs}).
     */
    public Path studioRoot() {
        return projectPath.resolve(STUDIO_DIR);
    }

    /** The name of {@link #studioRoot()}'s directory. */
    public static final String STUDIO_DIR = ".botmaker";

    /** {@code src/main/resources/images} — the saved image-template directory. */
    public Path imagesRoot() {
        return resourcesRoot().resolve("images");
    }

    // activitiesSourceFile() went on 2026-09-21 with no caller. It named the generated Activities.java,
    // which held one activity's enable flag per field; a flow's activity carries its own `enabled` now
    // (Flow.activity(…)), inside the value the SDK plugin's window writes.

    /**
     * The generated {@code Parameters.java} sidecar: every configured value the bot reads.
     *
     * <p>Its own file since 2026-08-25 — the flags and the values used to be one class, which is why a project
     * created before then has none until it is opened and migrated.
     */
    public Path parametersSourceFile() {
        return mainPackageDir().resolve("Parameters.java");
    }

    // templatesSourceFile() went on 2026-09-01 with no caller and none since the SDK stopped writing source
    // into a project (2026-08-29). It named the generated Templates.java, which is the picture library's
    // file: the plugin that writes it is the one entitled to say where it goes.

    // activityRegistrySourceFile() and flowDriverSourceFile() went on 2026-09-21, both with no caller in
    // main. They named ActivityRegistry.java and FlowDriver.java, the two files the old generator wrote to
    // hold the flow: a bot runs from the flow value in its own plugins/sdk/Sdk.java now, and nothing
    // generates either file. needsReviewSourceFile() went with them, also uncalled — parser.refactor
    // .ReviewMarker resolves that file itself, on demand, being the only thing that writes it.

    /** {@code src/main/java/com/<pkg>} — the package the bot's own classes are written into. */
    public Path mainPackageDir() {
        return packageDir(sourceRoot, packageName);
    }

    /** {@code com.<pkg>} — the bot's own package, as an import would spell it. */
    public String mainPackage() {
        return "com." + packageName;
    }

    /** {@code src/main/java/com/<pkg>/activities} — where per-activity subclass stubs live. */
    public Path activitiesPackageDir() {
        return mainPackageDir().resolve("activities");
    }

    /**
     * {@code .botmaker/archived-activities} — the attic the retired "archive activity" feature parked a
     * retired activity's source in, outside {@code src} so it would not be compiled.
     *
     * <p><b>Legacy.</b> Nothing writes here any more. The one reader left is
     * {@link ProjectOpenMigrations}, which empties it back into the source tree when an older project is
     * opened; see {@link com.botmaker.studio.project.activity.ActivityDefinition} for why archiving is gone.
     */
    public Path archivedActivitiesDir() {
        return projectPath.resolve(".botmaker").resolve("archived-activities");
    }
}
