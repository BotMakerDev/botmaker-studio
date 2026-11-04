package com.botmaker.studio.runtime;

import com.botmaker.plugin.host.grammar.ParamsFitter;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.ProgressReporter;
import com.botmaker.studio.util.ClassPathManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether the project <em>on disk</em> compiles, read apart from the editor: an update's merged tree is checked
 * before it is committed ({@code docs/refactor/39-versions.md} §7, 2026-10-23). The editor still holds the sources
 * from before the merge, so this compiles the files themselves, against the classpath of the pom on disk, into a
 * folder of its own that is deleted afterwards. Nothing reaches the Run tab.
 *
 * <p>Blocking — a resolve and a javac — so off the FX thread.
 */
public final class TreeCompile {

    /** {@code /abs/Path.java:12: error: cannot find symbol}, as javac prints it. */
    private static final Pattern ERROR = Pattern.compile("^(.+\\.java):(\\d+): error: (.*)$");

    /** How a dependency that would not resolve starts its line in {@link #errors}: no fault of the code. */
    public static final String UNRESOLVED = "Dependency ";

    private TreeCompile() {}

    /**
     * The compile errors of the project at {@code config}, each {@code path:line: message} with the path
     * relative to the project; empty when it compiles. A dependency that would not resolve is one more line.
     */
    public static List<String> errors(ProjectConfig config) throws IOException, InterruptedException {
        if (sources(config).isEmpty()) return List.of();
        MavenService.Resolution resolved = MavenService.resolve(config.projectPath(), ProgressReporter.NONE);
        Path out = Files.createTempDirectory("botmaker-update-check");
        try {
            String output = javac(config, resolved, out);
            if (output == null) return List.of();
            List<String> errors = new ArrayList<>(errorLines(output, config.projectPath()));
            resolved.problems().forEach(p -> errors.add(UNRESOLVED + p));
            if (errors.isEmpty()) errors.add(output.isBlank() ? "javac failed and said nothing." : output.strip());
            return List.copyOf(errors);
        } finally {
            deleteTree(out);
        }
    }

    /** javac's output over the project's sources into {@code out}, or null when it compiled. */
    private static String javac(ProjectConfig config, MavenService.Resolution resolved, Path out)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(config.javacExecutable(),
                "-cp", String.join(File.pathSeparator, classpath(config, resolved.jars())),
                "-d", out.toString(), "-encoding", "UTF-8", "-nowarn",
                // a check, not a build: no annotation processor an update's new dependency brings runs here
                "-proc:none"));
        command.addAll(JavacRelease.args(config.projectPath()));
        command.addAll(sources(config));
        Process javac = new ProcessBuilder(command).directory(config.projectPath().toFile())
                .redirectErrorStream(true).start();
        String output = new String(javac.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return javac.waitFor() == 0 ? null : output;
    }

    /**
     * The bot's sources with each private parameter file that has a starting copy swapped for that copy (2026-11-04):
     * the bot compiles against the parameters it ships, and a params file an update no longer fits is fitted at the
     * next build, never a reason to refuse the update. A starting copy with no params file yet is compiled too.
     */
    private static List<String> sources(ProjectConfig config) {
        Path params = config.parametersPackageDir().toAbsolutePath().normalize();
        Path starting = config.projectPath().resolve(ParamsFitter.STARTING).toAbsolutePath().normalize();
        List<String> starts = Files.isDirectory(starting) ? ClassPathManager.findJavaFiles(starting) : List.of();
        List<String> out = new ArrayList<>(starts);
        for (String file : ClassPathManager.findJavaFiles(config.sourceRoot())) {
            Path at = Path.of(file).toAbsolutePath().normalize();
            boolean swapped = at.startsWith(params) && Files.isRegularFile(starting.resolve(params.relativize(at)));
            if (!swapped) out.add(file);
        }
        return out;
    }

    /** Each file javac's {@code output} names an error in, as javac wrote its path. */
    public static List<Path> errorFiles(String output) {
        List<Path> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher m = ERROR.matcher(line);
            if (m.matches()) out.add(Path.of(m.group(1).strip()));
        }
        return out;
    }

    private static List<String> classpath(ProjectConfig config, List<String> jars) {
        List<String> cp = new ArrayList<>();
        cp.add(config.resourcesRoot().toString());
        cp.addAll(jars);
        return cp;
    }

    /** javac's {@code error:} lines in {@code output}, each as {@code path:line: message}, the path relative to {@code root}. */
    static List<String> errorLines(String output, Path root) {
        List<String> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher m = ERROR.matcher(line);
            if (!m.matches()) continue;
            Path file = Path.of(m.group(1));
            String shown = file.isAbsolute() && file.startsWith(root)
                    ? root.relativize(file).toString().replace('\\', '/') : m.group(1);
            out.add(shown + ":" + m.group(2) + ": " + m.group(3));
        }
        return out;
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // A temp folder left behind is the OS's to clear.
        }
    }
}
