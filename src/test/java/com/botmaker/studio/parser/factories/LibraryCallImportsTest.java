package com.botmaker.studio.parser.factories;

import com.botmaker.shared.config.CacheDirs;
import com.botmaker.studio.TestSupport;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.BlockType;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A plugin's call inserted from the statement menu imports what its seeded arguments name.
 *
 * <p>{@code Mouse ▸ click} and {@code Wait ▸ time} landed as {@code Mouse.click(new Point(0, 0))} and
 * {@code Wait.time(Duration.ofSeconds(1))} with only {@code Mouse}/{@code Wait} imported, so the fresh block
 * read "Point cannot be resolved" the moment it appeared (2026-09-30). The member menu's path
 * ({@code MethodHandler}) imported each parameter type; the palette's ({@code StatementFactory.buildLibraryCall})
 * did not. The fixture is a real jar scanned by the real index, because the parameter types reach the factory
 * from the index and nowhere else.
 */
class LibraryCallImportsTest {

    private static final String PKG = "com.example.lib";

    private static final String HOST = """
            package com.mybot;
            public class Subject {
                public void run() {
                }
            }
            """;

    @BeforeAll
    static void theCacheDirIsRedirectedIntoTheBuild() {
        assumeTrue(CacheDirs.cacheRoot().toString().contains("target"),
                "the BotMaker cache dir is not redirected into target/ (see the pom's environmentVariables)");
    }

    private static Path compile(Path dir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jar cannot be built");

        Path src = dir.resolve("src").resolve(PKG.replace('.', '/'));
        Files.createDirectories(src.resolve("geo"));
        Files.writeString(src.resolve("geo/Point.java"), """
                package %s.geo;
                public class Point {
                    public Point(int x, int y) {}
                }
                """.formatted(PKG));
        Files.writeString(src.resolve("Mouse.java"), """
                package %s;
                import %s.geo.Point;
                public class Mouse {
                    public static void click(Point p) {}
                    public static void click(int x, int y) {}
                }
                """.formatted(PKG, PKG));
        Files.writeString(src.resolve("Wait.java"), """
                package %s;
                public class Wait {
                    public static void time(java.time.Duration d) {}
                }
                """.formatted(PKG));

        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        int rc = compiler.run(null, null, null, "-d", classes.toString(),
                src.resolve("geo/Point.java").toString(), src.resolve("Mouse.java").toString(),
                src.resolve("Wait.java").toString());
        assertEquals(0, rc, "the fixture sources must compile");
        return classes;
    }

    private static Path jar(Path classes, Path dir) throws IOException {
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

    /** The source after inserting {@code facade.method(<defaults>)}, the menu's own block, into {@link #HOST}. */
    private static String insert(Path tmp, String facade, String method) throws Exception {
        Path classes = compile(tmp);
        Path jar = jar(classes, tmp);
        TypeSummaryManager index = new TypeSummaryManager(Set.of(PKG));
        index.refresh(List.of(jar.toString()));
        ProjectState state = new ProjectState();
        ProjectAnalyzer analyzer = new ProjectAnalyzer(index, state);

        CompilationUnit cu = ProjectAnalyzer.createCompilationUnit(
                TestSupport.runtimeClassPath(), HOST, TestSupport.SOURCE_ROOT);
        assertNotNull(cu, "host unit must parse");
        EditContext ctx = EditContext.of(cu, analyzer, state);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()})) {
            Class<?> type = loader.loadClass(PKG + "." + facade);
            BlockType block = new BlockType.LibraryCall("SDK_" + facade + "_" + method, facade + "." + method,
                    BlockCategory.INPUT, type, method, List.of());
            assertNotNull(StatementFactory.createStatement(ctx, block, null), "the factory built nothing");
        }
        return ctx.applyTo(HOST);
    }

    @Test
    void aSeededPluginTypeArgumentIsImported(@TempDir Path tmp) throws Exception {
        String source = insert(tmp, "Mouse", "click");
        assertTrue(source.contains("import " + PKG + ".geo.Point;"), source);
    }

    @Test
    void aSeededJdkTypeArgumentIsImported(@TempDir Path tmp) throws Exception {
        String source = insert(tmp, "Wait", "time");
        assertTrue(source.contains("import java.time.Duration;"), source);
    }
}
