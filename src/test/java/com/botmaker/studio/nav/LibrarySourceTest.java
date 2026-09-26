package com.botmaker.studio.nav;

import com.botmaker.studio.nav.LibrarySource.Coordinates;
import com.botmaker.studio.nav.LibrarySource.Origin;
import com.botmaker.studio.nav.LibrarySource.Target;
import com.botmaker.studio.nav.LibrarySource.View;
import io.github.classgraph.ClassGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Go to Definition for a library member: its sources when shipped, an outline otherwise (2026-09-26). */
class LibrarySourceTest {

    private static final String SOURCE = """
            package com.acme;

            public class Tool {
                public void run() {}

                public void run(int times) {}

                public static class Part {
                    public Part(String name) {}

                    public int size() { return 0; }
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void theOverloadWithTheSameParameterCountIsTheLineShown() {
        assertEquals(4, LibrarySource.lineOf(SOURCE, new Target("com.acme.Tool", "com.acme", "run", 0)));
        assertEquals(6, LibrarySource.lineOf(SOURCE, new Target("com.acme.Tool", "com.acme", "run", 1)));
    }

    @Test
    void aNestedTypesConstructorAndMethodAreFound() {
        assertEquals(9, LibrarySource.lineOf(SOURCE, new Target("com.acme.Tool$Part", "com.acme", "<init>", 1)));
        assertEquals(11, LibrarySource.lineOf(SOURCE, new Target("com.acme.Tool$Part", "com.acme", "size", 0)));
        assertEquals(8, LibrarySource.lineOf(SOURCE, new Target("com.acme.Tool$Part", "com.acme", null, 0)));
    }

    @Test
    void aJarIsReadBackAsItsCoordinates() {
        Path repo = dir.resolve("repository");
        Path jar = repo.resolve("com/acme/tools/lib/1.2/lib-1.2.jar");
        assertEquals(Optional.of(new Coordinates("com.acme.tools", "lib", "1.2")),
                LibrarySource.coordinatesOf(jar, repo));
        assertEquals(Optional.empty(), LibrarySource.coordinatesOf(dir.resolve("elsewhere/lib-1.2.jar"), repo));
    }

    @Test
    void theSourcesJarBesideTheJarIsRead() throws IOException {
        Path jar = zip(dir.resolve("lib-1.0.jar"), "com/acme/Tool.class", "");
        zip(dir.resolve("lib-1.0-sources.jar"), "com/acme/Tool.java", SOURCE);
        Target target = new Target("com.acme.Tool", "com.acme", "run", 1);

        View view = LibrarySource.locate(target, List.of(jar.toString()), null, j -> {
            throw new AssertionError("nothing to fetch: the sources are beside the jar");
        }).orElseThrow();

        assertEquals(Origin.SOURCES, view.origin());
        assertEquals(SOURCE, view.text());
        assertEquals(6, view.line());
        assertEquals("Tool", view.title());
    }

    @Test
    void aSourcesJarNotOnDiskIsAskedFor() throws IOException {
        Path jar = zip(dir.resolve("lib-1.0.jar"), "com/acme/Tool.class", "");
        Path fetched = zip(dir.resolve("downloaded.zip"), "com/acme/Tool.java", SOURCE);

        View view = LibrarySource.locate(new Target("com.acme.Tool", "com.acme", null, 0),
                List.of(jar.toString()), null, j -> Optional.of(fetched)).orElseThrow();

        assertEquals(Origin.SOURCES, view.origin());
        assertEquals(3, view.line());
    }

    /** ClassGraph's own jar ships no sources beside it in a test run's classpath entry, so it is outlined. */
    @Test
    void aJarWithNoSourcesIsOutlinedWithTheMemberMarked() throws Exception {
        Path jar = Path.of(ClassGraph.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Target target = new Target("io.github.classgraph.ClassGraph", "io.github.classgraph", "acceptClasses", 1);

        View view = LibrarySource.outline(target, jar).orElseThrow();

        assertEquals(Origin.OUTLINE, view.origin());
        String line = view.text().lines().toList().get(view.line() - 1);
        assertTrue(line.contains("acceptClasses("), line);
        assertTrue(line.trim().endsWith(";"), line);
        assertTrue(view.text().contains("package io.github.classgraph;"), view.text());
    }

    @Test
    void theJdksClassesAreOutlinedFromItsModulesWhenItShipsNoSources() {
        View view = LibrarySource.locate(new Target("java.util.List", "java.util", "size", 0),
                List.of(), dir, j -> Optional.empty()).orElseThrow();

        assertEquals(Origin.OUTLINE, view.origin());
        assertTrue(view.text().lines().toList().get(view.line() - 1).contains("size()"), view.text());
    }

    @Test
    void theJdksOwnSourcesAreReadWhenItHasThem() {
        Path home = Path.of(System.getProperty("java.home"));
        assumeTrue(Files.isRegularFile(home.resolve("lib/src.zip")), "this JDK ships no src.zip");

        View view = LibrarySource.locate(new Target("java.lang.String", "java.lang", "isBlank", 0),
                List.of(), home, j -> Optional.empty()).orElseThrow();

        assertEquals(Origin.SOURCES, view.origin());
        assertTrue(view.text().lines().toList().get(view.line() - 1).contains("isBlank()"));
    }

    private static Path zip(Path file, String entry, String text) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }
}
