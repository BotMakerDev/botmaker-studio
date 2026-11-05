package com.botmaker.studio.runtime;

import com.botmaker.java.parse.JavaLevel;
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
 *
 * <p>The same property is the {@link JavaLevel} the bot's source is parsed and written at (2026-11-04), read through
 * {@link #release(Map)} here too, so the compile and the parse cannot disagree on what the pom says.
 */
public final class JavacRelease {

    /** A Java feature release, digits only: the pom is a cloned bot's too, and anything else would be a javac option. */
    private static final Pattern FEATURE = Pattern.compile("[1-9][0-9]{0,2}");

    /**
     * The release a new project's pom names (2026-11-03): BotMaker's Java, as {@code botmaker-plugin-parent} pins it,
     * never the JDK that happens to run Studio. A pom that named Studio's own JDK (27 under a newer system JDK) could
     * not be compiled by a Studio on 25: {@code release version 27 not supported} on Sword x Staff.
     */
    public static final int NEW_PROJECT = 25;

    private JavacRelease() {}

    /**
     * {@code --release <n>} for the project in {@code projectDir}, or nothing when its pom names no release; a release
     * above the JDK Studio runs on is that JDK's, which javac can build, and code needing more says so in its errors.
     */
    public static List<String> args(Path projectDir) {
        return args(MavenService.readProperties(projectDir), Runtime.version().feature());
    }

    /** The Java the bot in {@code projectDir} is written in, as its source is parsed and written. */
    public static JavaLevel level(Path projectDir) {
        return JavaLevel.of(release(MavenService.readProperties(projectDir)));
    }

    static List<String> args(Map<String, String> properties, int running) {
        String release = release(properties);
        if (release == null) return List.of();
        return List.of("--release", Integer.toString(Math.min(Integer.parseInt(release), running)));
    }

    /** The pom's {@code maven.compiler.release} when it is a feature release, else {@code null}: the one read of it. */
    static String release(Map<String, String> properties) {
        String release = properties.getOrDefault("maven.compiler.release", "").strip();
        return FEATURE.matcher(release).matches() ? release : null;
    }
}
