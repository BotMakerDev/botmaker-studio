package com.botmaker.studio.services;

import com.botmaker.studio.config.HostContract;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.UserLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    /** Whether any entry of {@code classpath} — a jar or a class directory — carries {@code @Param}. */
    public static boolean onClasspath(List<String> classpath) {
        if (classpath == null) return false;
        for (String entry : classpath) {
            if (carries(Path.of(entry))) return true;
        }
        return false;
    }

    private static boolean carries(Path entry) {
        if (Files.isDirectory(entry)) return Files.isRegularFile(entry.resolve(PARAM_CLASS));
        if (!Files.isRegularFile(entry)) return false;
        try (JarFile jar = new JarFile(entry.toFile())) {
            return jar.getEntry(PARAM_CLASS) != null;
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
        return MavenService.declareIfAbsent(projectDir, coordinate());
    }
}
