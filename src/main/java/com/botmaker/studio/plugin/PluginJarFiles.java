package com.botmaker.studio.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The two files a plugin's author writes beside its pom and {@code botmaker-plugin-parent} copies into the jar:
 * {@code CHANGELOG.md} as {@value #CHANGELOG} and {@code icon.png} as {@value #ICON}. Either may be missing, and
 * every reader degrades: no changelog shows no highlights, no icon shows the default one.
 */
public final class PluginJarFiles {

    /** The plugin's {@code CHANGELOG.md}, whole: the upgrade dialog reads the span it crosses. */
    public static final String CHANGELOG = "META-INF/botmaker/changelog.md";

    /** The plugin's {@code icon.png}, shown beside its name. */
    public static final String ICON = "META-INF/botmaker/icon.png";

    private PluginJarFiles() {}

    /** {@code entry}'s bytes in {@code jar}, or empty for every reason: no such entry, not a jar, gone. */
    public static Optional<byte[]> read(Path jar, String entry) {
        if (jar == null) return Optional.empty();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry found = jarFile.getJarEntry(entry);
            if (found == null) return Optional.empty();
            try (InputStream in = jarFile.getInputStream(found)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
