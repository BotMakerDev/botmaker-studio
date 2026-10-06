package com.botmaker.studio.services;

import com.botmaker.studio.config.HostContract;
import com.botmaker.studio.plugin.ReleasedPlugins;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.project.UserLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Whether a project can compile the contract's annotations, and the one write that makes it able to.
 *
 * <p><b>Why Studio writes this at all.</b> A blank project names no plugin, so nothing brings
 * {@code botmaker-studio-api} — and the Parameters window writes {@code @Param}, which lives there. Before
 * 2026-09-24 the first parameter of a blank project was a compile error:
 * {@code package com.botmaker.plugin.api.params does not exist}. The pom is Studio's to write (only the host
 * knows the whole plugin set), so the host that writes the annotation supplies the jar it needs.
 *
 * <p><b>Present is decided by the class, on the resolved classpath, and never by a name.</b> The SDK brings the
 * contract at {@code compile}; a direct entry beside it would win by nearest-wins and pin the bot to a
 * contract its SDK was not built against — the trap {@code MavenService.BOT_DEPENDENCIES}' javadoc records
 * for the toolkit. So a classpath that already carries {@code Param}, however it got there, is left alone,
 * and so is a pom that declares the coordinate but has not resolved yet.
 */
public final class ContractDependency {

    /** The class whose presence is the question — the annotation the Parameters window writes. */
    static final String PARAM_CLASS = "com/botmaker/plugin/api/params/Param.class";

    /** The package prefix a bot's own source imports the contract's annotations from. */
    private static final String CONTRACT_IMPORT = "import com.botmaker.plugin.api.";

    private ContractDependency() {}

    /** The coordinate a project is given: the contract tag this Studio was built against. */
    public static UserLibrary coordinate() {
        return new UserLibrary(HostContract.GROUP_ID, HostContract.ARTIFACT_ID, HostContract.version());
    }

    /**
     * The coordinate the project in {@code projectDir} is given: in dev mode, the contract this Studio runs
     * ({@link HostContract#devVersion()}, a dev build's {@code -SNAPSHOT}); otherwise {@link #coordinate()}.
     */
    static UserLibrary coordinate(Path projectDir) {
        if (!StudioProjectSettings.devModeIn(projectDir)) return coordinate();
        return new UserLibrary(HostContract.GROUP_ID, HostContract.ARTIFACT_ID, HostContract.devVersion());
    }

    /** Whether any entry of {@code classpath} — a jar or a class directory — carries {@code @Param}. */
    public static boolean onClasspath(List<String> classpath) {
        return carries(classpath, PARAM_CLASS);
    }

    /**
     * Whether any entry of {@code classpath} carries the contract class {@code type} — asked for one the
     * contract gained later than {@code @Param}, which a bot on an older contract has the jar but not the class
     * of ({@code @Refactor}, 2026-09-27).
     */
    public static boolean onClasspath(List<String> classpath, Class<?> type) {
        return carries(classpath, type.getName().replace('.', '/') + ".class");
    }

    private static boolean carries(List<String> classpath, String classFile) {
        if (classpath == null) return false;
        for (String entry : classpath) {
            if (carries(Path.of(entry), classFile)) return true;
        }
        return false;
    }

    private static boolean carries(Path entry, String classFile) {
        if (Files.isDirectory(entry)) return Files.isRegularFile(entry.resolve(classFile));
        if (!Files.isRegularFile(entry)) return false;
        try (JarFile jar = new JarFile(entry.toFile())) {
            return jar.getEntry(classFile) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether any of the bot's sources imports from the contract — a project that needs the jar to compile. */
    public static boolean usedBy(ProjectConfig config, ProjectState state) {
        return BotSources.firstMatch(config, state, (file, source) -> source.contains(CONTRACT_IMPORT)) != null;
    }

    /**
     * Declares the contract in {@code projectDir}'s pom when {@code classpath} lacks it, and answers whether
     * the pom was written — the caller re-resolves when it was.
     */
    public static boolean ensure(Path projectDir, List<String> classpath) throws IOException {
        if (onClasspath(classpath)) return false;
        return MavenService.declareIfAbsent(projectDir, coordinate(projectDir));
    }

    /**
     * The pom's contract entry, put right after a complete resolve: declared when the bot's source imports the
     * contract ({@code used}) and {@code classpath} lacks it, removed once a plugin brings the contract again at
     * that version or newer ({@link MavenService#dropRedundantContract}) — so it never sits beside a plugin's
     * and pins it. An entry nothing imports any more is left: harmless alone, and gone when a plugin arrives.
     * Outside dev mode an entry at the {@code -SNAPSHOT} dev mode writes ({@link HostContract#devVersion()}) is
     * moved back to the released contract (2026-10-06); any other SNAPSHOT pin is the author's and stays. Answers whether the pom changed; blocking.
     */
    public static boolean reconcile(Path projectDir, boolean used, List<String> classpath) throws IOException {
        UserLibrary wanted = coordinate(projectDir);
        if (used && !onClasspath(classpath) && MavenService.declareIfAbsent(projectDir, wanted)) return true;
        if (MavenService.dropRedundantContract(projectDir, wanted)) return true;
        // Only the exact SNAPSHOT dev mode would write here: one pinned by hand is the author's, and a released
        // Studio's dev mode writes no SNAPSHOT at all.
        String devWritten = HostContract.devVersion();
        if (ReleasedPlugins.isDevVersion(wanted.version()) || !ReleasedPlugins.isDevVersion(devWritten)) return false;
        boolean devPin = MavenService.readDependencyVersion(projectDir, wanted.groupId(), wanted.artifactId())
                .filter(devWritten::equals).isPresent();
        if (!devPin) return false;
        MavenService.setDependencyVersions(projectDir,
                Map.of(wanted.groupId() + ":" + wanted.artifactId(), wanted.version()));
        return true;
    }

    /**
     * {@link #ensure}, asked only of a bot whose own source imports the contract — on open; a rebind asks
     * {@link #reconcile} in {@code LibraryService.bind}. Removing the plugin that brought the contract leaves the
     * {@code @Refactor} its repair wrote with nothing to compile against (2026-10-05: the SDK removed from a
     * blank-template bot).
     */
    public static boolean ensureFor(ProjectConfig config, ProjectState state, List<String> classpath)
            throws IOException {
        return usedBy(config, state) && ensure(config.projectPath(), classpath);
    }
}
