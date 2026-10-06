package com.botmaker.studio.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plugin builds in this computer's {@code ~/.m2} that no repository serves: every {@code -SNAPSHOT} version
 * directory whose jar declares a {@code StudioPlugin} (2026-10-06, for dev mode).
 *
 * <p>That covers a plugin author's own {@code mvn install} at {@code 0.1.0-SNAPSHOT}, and the umbrella's
 * {@code mvn -pl botmaker-sdk -am install} — the SDK, plugin-basics and every plugin the toolkit's users build
 * at their {@code main} SNAPSHOT. <b>What makes a jar a plugin is its service file</b>
 * ({@link MavenService#declaresPlugin}), so no list or naming rule has to be kept in step with anything.
 *
 * <p>Offered only in a project in dev mode, which is what makes this different from the scans deleted on
 * 2026-10-01 ({@code localPluginBuilds}, {@code localSdkVersions}): those offered a SNAPSHOT in every project,
 * and a bot published from one named a version nobody else could resolve. Publish refuses such a pin now.
 *
 * <p>Best-effort throughout: an unreadable directory or jar is one fewer build, never an exception, because
 * the scan runs while a window opens.
 */
public final class LocalBuilds {

    /** One plugin build, its jar written at {@code built}. */
    public record Build(String groupId, String artifactId, String version, Instant built) {

        /** {@code group:artifact}, how a registry entry and a pom row both name a dependency. */
        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    private LocalBuilds() {}

    /** The builds in {@code ~/.m2/repository}, newest first. Opens jars: call it off the FX thread. */
    public static List<Build> scan() {
        return scan(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    /** The builds under {@code repositoryRoot}, newest first; one per coordinate, its newest version. */
    static List<Build> scan(Path repositoryRoot) {
        if (!Files.isDirectory(repositoryRoot)) return List.of();
        List<Path> versionDirs = new ArrayList<>();
        collectSnapshotDirs(repositoryRoot, versionDirs, 0);
        Map<String, Build> newest = new LinkedHashMap<>();
        for (Path versionDir : versionDirs) {
            String version = versionDir.getFileName().toString();
            Path artifactDir = versionDir.getParent();
            if (artifactDir == null || artifactDir.getParent() == null) continue;
            String artifactId = artifactDir.getFileName().toString();
            Path jar = versionDir.resolve(artifactId + "-" + version + ".jar");
            if (!Files.isRegularFile(jar) || downloaded(versionDir, jar) || !MavenService.declaresPlugin(jar)) continue;
            String groupId = repositoryRoot.relativize(artifactDir.getParent()).toString()
                    .replace('\\', '/').replace('/', '.');
            if (groupId.isBlank()) continue;
            Build build = new Build(groupId, artifactId, version, modified(jar));
            newest.merge(build.coordinate(), build, (a, b) -> a.built().isAfter(b.built()) ? a : b);
        }
        return newest.values().stream().sorted(Comparator.comparing(Build::built).reversed()).toList();
    }

    /** Every {@code *-SNAPSHOT} directory under {@code dir}, without descending into one. */
    private static void collectSnapshotDirs(Path dir, List<Path> out, int depth) {
        // A Maven coordinate is deep but not unbounded; the cap stops a symlink loop, which a real repository
        // never approaches.
        if (depth > 12) return;
        try (var children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                if (child.getFileName().toString().endsWith("-SNAPSHOT")) {
                    out.add(child);
                } else {
                    collectSnapshotDirs(child, out, depth + 1);
                }
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable directory is one fewer candidate, never a failed scan.
        }
    }

    /**
     * Whether {@code jar} came from a repository rather than {@code mvn install}: Maven records where each file
     * in a version directory came from in {@code _remote.repositories}, as {@code <file>><repository id>=}, and
     * a local install's repository id is empty. A JitPack {@code main-SNAPSHOT} is stored under the same file
     * name as a local one, so this is the only thing that tells them apart.
     */
    static boolean downloaded(Path versionDir, Path jar) {
        Path record = versionDir.resolve("_remote.repositories");
        if (!Files.isRegularFile(record)) return false;
        String prefix = jar.getFileName() + ">";
        try {
            for (String line : Files.readAllLines(record)) {
                if (!line.startsWith(prefix)) continue;
                int equals = line.indexOf('=', prefix.length());
                String repository = line.substring(prefix.length(), equals < 0 ? line.length() : equals);
                if (!repository.isBlank()) return true;
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: treated as a local build, the case this scan is for.
        }
        return false;
    }

    private static Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
