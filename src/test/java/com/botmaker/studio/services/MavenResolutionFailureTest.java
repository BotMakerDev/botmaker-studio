package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 8 — what a caller learns when classpath resolution fails.</b> Gates <b>SV13</b>
 * ("{@code MavenService} resolution failure surfaces as itself").
 *
 * <p>{@code resolveClasspath} returns {@code List<String>} and nothing else. Every way it can fail — no
 * {@code pom.xml}, an unreadable one, a dependency that does not resolve — produces the same value a
 * <em>successful</em> resolve of a dependency-free project produces: an empty or short list, plus a line on
 * {@code System.err} that in a packaged Studio goes nowhere.
 *
 * <p>The consequence is the item's own title. The empty classpath is handed to {@code javac}, which reports
 * every unresolved symbol in the bot — two hundred compile errors on the user's own code, none of them
 * mentioning the download that failed. The user is asked to debug a symptom.
 *
 * <p>So these tests characterise, deliberately: they pin <b>the failures are indistinguishable from success
 * at the call site</b>, which is the defect. SV13's fix changes what they assert — that is the point of
 * writing them now rather than after.
 */
class MavenResolutionFailureTest {

    /** Captures {@code System.err} for the duration of {@code work} — today the only failure channel. */
    private static String errDuring(Runnable work) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            work.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** Runs {@code work} with {@code System.err} swallowed and returns its value — the noise is not the point. */
    private static List<String> capture(java.util.function.Supplier<List<String>> work) {
        PrintStream original = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return work.get();
        } finally {
            System.setErr(original);
        }
    }

    private static Path projectWith(Path dir, String pomXml) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), pomXml);
        return dir;
    }

    /** A minimal, valid, dependency-free pom — the successful-resolve baseline every failure is compared to. */
    private static final String NO_DEPENDENCIES = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>bot</artifactId>
              <version>1.0.0</version>
            </project>
            """;

    // ---- The baseline ----

    @Test
    void aProjectWithNoDependenciesResolvesToAnEmptyClasspath(@TempDir Path dir) throws IOException {
        Path project = projectWith(dir.resolve("ok"), NO_DEPENDENCIES);

        assertEquals(List.of(), MavenService.resolveClasspath(project),
                "the success case: nothing to resolve, so nothing resolved");
    }

    /**
     * A pin written as {@code ${property}} resolves at the property's value (2026-10-01). The templates pin their
     * plugin that way, and the resolver was handed the placeholder text, which names no version at all. The
     * artifact is one already in {@code ~/.m2}: the JUnit running this test.
     */
    @Test
    void aPropertyPinResolvesAtThePropertysValue(@TempDir Path dir) throws Exception {
        Path jar = Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String version = jar.getParent().getFileName().toString();
        Path project = projectWith(dir.resolve("pinned"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>bot</artifactId>
                  <version>1.0.0</version>
                  <properties><junit.api.version>%s</junit.api.version></properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter-api</artifactId>
                      <version>${junit.api.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version));

        MavenService.Resolution resolution = MavenService.resolve(project, (fraction, message) -> { });

        assertEquals(List.of(), resolution.problems());
        assertTrue(resolution.jars().stream().anyMatch(p -> p.endsWith("junit-jupiter-api-" + version + ".jar")),
                resolution.jars().toString());

        // The pin as readDeclaredLibraries hands it back — the plugins window resolves each row's jar with it.
        var declared = MavenService.readDeclaredLibraries(project).getFirst();
        assertEquals("${junit.api.version}", declared.version(), "the pom as written, so a write keeps it");
        assertTrue(MavenService.resolveArtifact(project, declared.groupId(), declared.artifactId(), "",
                declared.version()).isPresent());
    }

    // ---- The failures, and what makes them invisible ----

    @Test
    void aMissingPomIsAnEmptyClasspathAndALineOnStderr(@TempDir Path dir) {
        Path project = dir.resolve("no-pom");

        String err = errDuring(() -> assertEquals(List.of(), MavenService.resolveClasspath(project)));

        assertTrue(err.contains("No pom.xml"),
                "the only trace of the failure is this line, on a stream a packaged Studio does not show: " + err);
    }

    @Test
    void anUnreadablePomIsAlsoAnEmptyClasspathAndALineOnStderr(@TempDir Path dir) throws IOException {
        Path project = projectWith(dir.resolve("bad-pom"), "<project> this is not xml");

        String err = errDuring(() -> assertEquals(List.of(), MavenService.resolveClasspath(project)));

        assertTrue(err.contains("Failed to read pom.xml"), err);
    }

    /**
     * <b>This is SV13.</b> Three different outcomes — success, no pom, corrupt pom — and the value the caller
     * receives is identical in all three. {@code LibraryService} takes that list straight to
     * {@code ProjectState.setResolvedClasspath}, so the project carries on with an empty classpath and the
     * next compile blames the user's source.
     */
    @Test
    void theCallerCannotTellSuccessFromFailure(@TempDir Path dir) throws IOException {
        Path ok = projectWith(dir.resolve("ok"), NO_DEPENDENCIES);
        Path missing = dir.resolve("no-pom");
        Path corrupt = projectWith(dir.resolve("bad-pom"), "<project> this is not xml");

        List<String> fromSuccess = MavenService.resolveClasspath(ok);
        List<String> fromMissing = capture(() -> MavenService.resolveClasspath(missing));
        List<String> fromCorrupt = capture(() -> MavenService.resolveClasspath(corrupt));

        assertEquals(fromSuccess, fromMissing,
                "a missing pom is reported to the caller exactly as a project with no dependencies");
        assertEquals(fromSuccess, fromCorrupt,
                "so is a pom that could not be parsed");
    }
}
