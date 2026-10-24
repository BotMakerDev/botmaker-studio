package com.botmaker.studio.runtime;

import com.botmaker.studio.services.MavenService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code --release} every javac Studio runs on a project passes, from the pom's {@code maven.compiler.release}
 * (2026-10-24). Studio compiles with the {@code javac} of the JVM it runs on; without the flag the classes take that
 * JDK's version, so a dev Studio on a newer system JDK wrote classes an installed Studio's own runtime could not read
 * ({@code Unsupported class file version: 70} on Firestone). With it, Studio builds what {@code mvn} builds.
 */
public final class JavacRelease {

    /** A Java feature release, digits only: the pom is a cloned bot's too, and anything else would be a javac option. */
    private static final Pattern FEATURE = Pattern.compile("[1-9][0-9]{0,2}");

    private JavacRelease() {}

    /** {@code --release <n>} for the project in {@code projectDir}, or nothing when its pom names no release. */
    public static List<String> args(Path projectDir) {
        return args(MavenService.readProperties(projectDir));
    }

    static List<String> args(Map<String, String> properties) {
        String release = properties.getOrDefault("maven.compiler.release", "").strip();
        return FEATURE.matcher(release).matches() ? List.of("--release", release) : List.of();
    }
}
