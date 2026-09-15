package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two real SDK jars and a project to point them at — the fixture every upgrade test is built on.
 *
 * <p>The jars are <b>compiled here rather than mocked</b>, for the reason the whole feature exists: the
 * question is about bytecode, and the interesting cases — a method gone, an overload's arity changed, a
 * pointer split across two survivors — are exactly the ones no pair of published SDK versions exhibits yet.
 * A fixture that only ever encoded what already shipped would pass forever without proving the diff works.
 *
 * <p>Shared rather than copied because two harnesses that build jars slightly differently is a way to get
 * two tests disagreeing about a jar neither of them is really testing.
 */
final class SdkFixtures {

    /**
     * The package a bare class key lands in — one plugin's API package, and the SDK's because that is the
     * plugin these tests were written against. It is a {@link #jarOf} <em>parameter</em> since 2026-09-15,
     * so a test may build a second plugin's jar beside this one; this constant is what the overload that
     * does not care passes.
     */
    static final String PKG = "com.botmaker.sdk.api";

    /** Where the pointer vocabulary really lives — the plugin contract's, not any one plugin's. */
    static final String META = "com.botmaker.plugin.api.meta";

    /**
     * The jars get unique names: {@link TypeSummaryManager}'s disk cache is keyed by jar <em>filename</em>
     * and settles ties by mtime, so two same-named fixtures written in the same millisecond would have the
     * second one read the first one's scan.
     */
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private SdkFixtures() {
    }

    /** Compiles {@code classes} into a jar, optionally carrying {@code resources} under {@code META-INF}. */
    static Path jarOf(Path dir, String label, Map<String, String> classes,
                      Map<String, String> resources) throws IOException {
        return jarOf(dir, PKG, label, classes, resources);
    }

    /**
     * The same, for a jar whose bare class keys belong to {@code pkg} rather than to {@link #PKG} — which is
     * how a test builds a <em>second</em> plugin's jar and asks what happens when two of them declare a type
     * of the same simple name.
     */
    static Path jarOf(Path dir, String pkg, String label, Map<String, String> classes,
                      Map<String, String> resources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jars cannot be built");

        Path root = dir.resolve("src-" + label + "-" + UNIQUE.get());
        Path src = root.resolve(pkg.replace('.', '/'));
        Files.createDirectories(src);
        List<String> paths = new ArrayList<>();
        for (Map.Entry<String, String> e : classes.entrySet()) {
            // A bare key is a class of `pkg`, which is every class fixture. A dotted key is a fully-qualified
            // name, which is how the pointer annotations land in the contract's package rather than this
            // plugin's — the vocabulary is every plugin's, and a fixture that put it under `pkg` would be
            // testing a jar shape no plugin produces.
            String key = e.getKey();
            int dot = key.lastIndexOf('.');
            String owner = dot < 0 ? pkg : key.substring(0, dot);
            String simple = key.substring(dot + 1);
            Path pkgDir = root.resolve(owner.replace('.', '/'));
            Files.createDirectories(pkgDir);
            Path file = pkgDir.resolve(simple + ".java");
            Files.writeString(file, withMetaImport(e.getValue()));
            paths.add(file.toString());
        }

        Path out = dir.resolve("classes-" + label + "-" + UNIQUE.get());
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-d", out.toString()));
        args.addAll(paths);
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "the " + label + " fixture sources must compile");

        for (Map.Entry<String, String> e : resources.entrySet()) {
            Path file = out.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
        }

        Path jar = dir.resolve("botmaker-sdk-" + label + "-" + UNIQUE.incrementAndGet() + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(out)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }
        return jar;
    }

    /**
     * {@code source} with {@code import com.botmaker.plugin.api.meta.*;} after its package line.
     *
     * <p>A fixture class writes {@code @ReplacedBy(…)} unqualified, which worked while the annotations were
     * declared in the same package it is. They are the contract's now, so every fixture would otherwise have
     * to carry the import — one line repeated across three test classes, forgotten in exactly the test that
     * adds the next pointer case. A real plugin imports the contract the same way.
     *
     * <p>A source already in that package is left alone, and an unused import of a package the jar declares
     * is not a compile error, so this is applied to every fixture rather than to the ones that need it.
     */
    private static String withMetaImport(String source) {
        if (source.contains("package " + META + ";")) return source;
        int end = source.indexOf(';', source.indexOf("package "));
        return end < 0 ? source
                : source.substring(0, end + 1) + "\nimport " + META + ".*;" + source.substring(end + 1);
    }

    /** A service over a throwaway project holding {@code sources} as {@code Subject.java}, {@code Subject1}… */
    static PluginUpgradeService serviceOver(Path tmp, String... sources) throws IOException {
        Path project = tmp.resolve("project");
        Files.createDirectories(project.resolve("src/main/java/com/mybot"));

        ProjectState state = new ProjectState();
        int n = 0;
        for (String source : sources) {
            Path file = project.resolve("src/main/java/com/mybot/Subject" + (n == 0 ? "" : n) + ".java");
            Files.writeString(file, source);
            state.addFile(new ProjectFile(file, source));
            n++;
        }

        ProjectConfig config = ProjectConfig.forProject("fixture", project);
        EventBus bus = new EventBus(false);
        LibraryService libraries = new LibraryService(config, state, new TypeSummaryManager(), bus);
        return new PluginUpgradeService(config, state, libraries, new JitPackSearch(),
                PluginUpgradeService.SDK);
    }

    /**
     * The meta annotations, compiled into whichever fixture jar wants them. CLASS retention is the default,
     * which is exactly what the real ones declare — and what makes them readable off a jar.
     *
     * <p><b>They are declared in {@link #META}, the plugin contract's own package, because that is the only
     * spelling {@code ApiModel} reads.</b> They were declared under {@code com.botmaker.sdk.api} until
     * 2026-09-15, which was where a pre-1.1.0 SDK jar kept them — and the reader stopped accepting that
     * spelling on 2026-09-02, when the four historical package names were dropped. Nothing failed loudly:
     * the fixture went on compiling, the jars went on carrying annotations, and every pointer test simply
     * asserted against a jar whose pointers production could no longer see.
     *
     * <p>{@code Since} lives there too. There is no {@code Replaces}: the back edge is not read any more, and
     * a fixture that can still express one would be testing a jar shape no plugin can produce.
     */
    static Map<String, String> withPointers(Map<String, String> base) {
        Map<String, String> out = new HashMap<>(base);
        out.put(META + ".ReplacedBy", """
                package %s;
                public @interface ReplacedBy {
                    String[] value() default {};
                    String[] whens() default {};
                    String note() default "";
                    boolean behaviourChanged() default false;
                }
                """.formatted(META));
        out.put(META + ".Since", """
                package %s;
                public @interface Since { String value(); }
                """.formatted(META));
        return out;
    }
}
