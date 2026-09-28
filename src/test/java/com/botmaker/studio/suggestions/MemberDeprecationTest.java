package com.botmaker.studio.suggestions;

import com.botmaker.shared.config.CacheDirs;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ProjectAnalyzer#isMemberDeprecated}: the strike-through on a block and the Errors panel's deprecation
 * line.
 *
 * <p>The fixture is a real jar built by the platform compiler and scanned by the real ClassGraph, because what
 * breaks silently is exactly what a mock would paper over: that {@code @Deprecated} reaches the index at all
 * (only if {@code TypeSummaryManager} asks ClassGraph for annotation info). Moved from
 * {@code SdkSurfaceServiceTest} on 2026-09-28.
 */
class MemberDeprecationTest {

    private static final String PKG = "com.example.fixture";

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(CacheDirs.cacheRoot().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables); "
                        + "refusing to write jar caches into the developer's real cache dir");
    }

    /** One method deprecated beside a live one, a name with one deprecated overload of two, a deprecated class. */
    private static Path fixtureJar(Path dir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jar cannot be built");

        Path pkgDir = dir.resolve("src").resolve(PKG.replace('.', '/'));
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Wait.java"), """
                package %s;
                public class Wait {
                    public static void time(long ms) {}
                    /** @deprecated use {@link #time(long)} instead */
                    @Deprecated(since = "1.1.0", forRemoval = true)
                    public static void seconds(int s) {}
                    public static void mixed(int a) {}
                    @Deprecated
                    public static void mixed(String a) {}
                }
                """.formatted(PKG));
        Files.writeString(pkgDir.resolve("Legacy.java"), """
                package %s;
                @Deprecated
                public class Legacy {
                    public static void anything() {}
                }
                """.formatted(PKG));

        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        int rc = compiler.run(null, null, null, "-d", classes.toString(),
                pkgDir.resolve("Wait.java").toString(), pkgDir.resolve("Legacy.java").toString());
        assertEquals(0, rc, "the fixture sources must compile");

        Path jar = dir.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static ProjectAnalyzer analyzerOver(Path jar) {
        TypeSummaryManager index = new TypeSummaryManager(Set.of(PKG));
        if (jar != null) index.refresh(List.of(jar.toString()));
        return new ProjectAnalyzer(index, new ProjectState());
    }

    @Test
    void deprecationIsReadFromBytecode(@TempDir Path tmp) throws IOException {
        ProjectAnalyzer analyzer = analyzerOver(fixtureJar(tmp));

        assertTrue(analyzer.isMemberDeprecated("Wait", "seconds"));
        assertFalse(analyzer.isMemberDeprecated("Wait", "time"));
    }

    /** The menus collapse overloads to one entry, so one live overload keeps the name live. */
    @Test
    void aNameIsOnlyDeprecatedWhenEveryOverloadIs(@TempDir Path tmp) throws IOException {
        assertFalse(analyzerOver(fixtureJar(tmp)).isMemberDeprecated("Wait", "mixed"));
    }

    @Test
    void aDeprecatedClassDeprecatesEverythingOnIt(@TempDir Path tmp) throws IOException {
        assertTrue(analyzerOver(fixtureJar(tmp)).isMemberDeprecated("Legacy", "anything"));
    }

    /** With no index, strike nothing through rather than everything. */
    @Test
    void anUnknownMemberIsNeverReportedDeprecated() {
        ProjectAnalyzer analyzer = analyzerOver(null);

        assertFalse(analyzer.isMemberDeprecated("Wait", "seconds"));
        assertFalse(analyzer.isMemberDeprecated("Legacy", "anything"));
        assertFalse(new ProjectAnalyzer(null, new ProjectState()).isMemberDeprecated("Wait", "seconds"));
    }
}
