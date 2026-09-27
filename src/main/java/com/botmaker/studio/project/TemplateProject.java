package com.botmaker.studio.project;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A published bot used as a <b>starting template</b>: what it declares about itself, and the one thing that
 * changes when a copy of it becomes the user's own project.
 *
 * <h2>Why a template is a published bot</h2>
 *
 * <p>Studio composes exactly one starting point — a blank project, small enough to be honest about owning —
 * and every richer one is somebody's published project, downloaded from the gallery. A template therefore
 * needs no archetype, no bundled resource and no Studio release: it is a bot repo whose gallery entry carries
 * {@link com.botmaker.studio.sharing.GalleryEntry#TEMPLATE_TAG}, installed through the same path an ordinary
 * bot is.
 *
 * <h2>The project arrives as its author shipped it, except for its package</h2>
 *
 * <p>The template's package is the one holding its {@code main} — {@code com.botmaker.gamebot} — and that
 * prefix is replaced with the user's own — {@code com.myfarmer} — and the directories move with it.
 * <b>Nothing else is renamed.</b> A template's entry class stays {@code GameBot}, its helper classes keep
 * their names, and its javadoc keeps its wording: what the author shipped is what demonstrably built for
 * them, and a copy that quietly renames their types is a copy whose stack traces and README stop matching.
 * The package is the exception because it is the one name that must not be shared — two projects in one
 * package cannot sit on one classpath, and a package named after somebody else's bot is the first thing a
 * user reads in their own file.
 *
 * <p>A directory tree alone cannot say which of {@code com}, {@code com.botmaker} and
 * {@code com.botmaker.gamebot} was meant, but the Java can. A {@code botmaker-template.properties} declared it
 * until 2026-09-27; it is read no more, and a template that still ships one has it deleted from the copy.
 *
 * <p>Since the entry class keeps the author's name, nothing may assume it is named after the project — see
 * {@link ProjectConfig#entrySourceFile()}, which finds it rather than deriving it.
 */
public final class TemplateProject {

    /**
     * The declaration file templates carried until 2026-09-27. Nothing reads it; a copy made from an older
     * template release has it removed, since it only ever said how to unpack the template.
     */
    private static final String OLD_DECLARATION = "botmaker-template.properties";

    /** Files whose bytes are not text and must be copied through untouched. */
    private static final List<String> BINARY_SUFFIXES =
            List.of(".png", ".jpg", ".jpeg", ".gif", ".ico", ".zip", ".jar", ".class", ".pdf");

    private final String packageName;

    private TemplateProject(String packageName) {
        this.packageName = packageName;
    }

    public String packageName() {
        return packageName;
    }

    /**
     * The template's package: the package of the class holding {@code main} — an author should not have to
     * write down what their Java already says.
     *
     * @throws IOException if there is no {@code main}, or {@code main} sits in packages that share no root. A
     *                     template whose package is unknown cannot have it replaced, and a replacement that
     *                     silently matches nothing produces a project sitting in somebody else's package that
     *                     still compiles, which is the one failure worth refusing outright
     */
    public static TemplateProject read(Path projectDir) throws IOException {
        return new TemplateProject(derivePackage(projectDir));
    }

    private static final java.util.regex.Pattern PACKAGE =
            java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final java.util.regex.Pattern MAIN =
            java.util.regex.Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(");

    /**
     * The package of the class holding {@code public static void main}. When several classes do, the one
     * whose package every other lies under — a template's entry sits at its root, helpers below it.
     */
    static String derivePackage(Path projectDir) throws IOException {
        Path sources = projectDir.resolve("src/main/java");
        java.util.TreeSet<String> packages = new java.util.TreeSet<>(Comparator.comparingInt(String::length)
                .thenComparing(Comparator.naturalOrder()));
        if (Files.isDirectory(sources)) {
            try (var walk = Files.walk(sources)) {
                for (Path java : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(java);
                    if (!MAIN.matcher(text).find()) continue;
                    var pkg = PACKAGE.matcher(text);
                    if (pkg.find()) packages.add(pkg.group(1));
                }
            }
        }
        if (packages.isEmpty()) {
            throw new IOException("BotMaker can't tell this template's package: no class in a package has a "
                    + "main method. Add one to the class the template starts from.");
        }
        String root = packages.first();
        for (String other : packages) {
            if (!other.equals(root) && !other.startsWith(root + ".")) {
                throw new IOException("Classes with a main method sit in " + root + " and " + other
                        + ", so BotMaker can't tell which package is the template's. Keep every main method"
                        + " in the template's package or below it.");
            }
        }
        return root;
    }

    /**
     * True when there are sources in the package this template names. Checked before publishing, where the
     * author can still fix it.
     */
    public boolean matches(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("src/main/java").resolve(packageName.replace('.', '/')));
    }

    /**
     * Rewrites {@code projectDir} in place so it lives in {@code newPackage}, and removes an older template's
     * declaration file. The unpacked copy is the user's from here on.
     *
     * <p>The prefix is replaced everywhere in every text file, string literals included. That is right often
     * enough (a logger category, a resource path, a {@code Class.forName}) and harmless where it is not —
     * this is a prefix substitution and must not grow into a refactoring engine.
     */
    public void renameInto(Path projectDir, String newPackage) throws IOException {
        Path sources = projectDir.resolve("src/main/java").resolve(packageName.replace('.', '/'));
        if (!Files.isDirectory(sources)) {
            throw new IOException("This template's package is " + packageName
                    + ", but there are no sources in it. Ask its author to fix it.");
        }

        // Text first, then the moves. Rewriting after the move would mean walking a tree whose shape has
        // already changed, and a half-moved tree is the state that is hardest to recover from.
        rewriteText(projectDir, newPackage);
        movePackage(projectDir, newPackage);
        Files.deleteIfExists(projectDir.resolve(OLD_DECLARATION));
    }

    private void rewriteText(Path projectDir, String newPackage) throws IOException {
        for (Path file : textFilesUnder(projectDir)) {
            String before;
            try {
                before = Files.readString(file);
            } catch (MalformedInputException notText) {
                continue;   // a binary file with an unexpected extension: leave it exactly as it is
            }
            String after = before.replace(packageName, newPackage);
            if (!after.equals(before)) Files.writeString(file, after);
        }
    }

    /** Moves {@code src/**}{@code /<old package dirs>} to the new package's directories. */
    private void movePackage(Path projectDir, String newPackage) throws IOException {
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path from = projectDir.resolve(root).resolve(packageName.replace('.', '/'));
            if (!Files.isDirectory(from)) continue;
            Path to = projectDir.resolve(root).resolve(newPackage.replace('.', '/'));
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            pruneEmptyDirectories(projectDir.resolve(root), to);
        }
    }

    /** Removes the now-empty {@code com/botmaker/…} shells the move left behind. */
    private static void pruneEmptyDirectories(Path root, Path keep) throws IOException {
        if (!Files.isDirectory(root)) return;
        List<Path> directories;
        try (var walk = Files.walk(root)) {
            directories = walk.filter(Files::isDirectory)
                    .filter(p -> !p.equals(root) && !keep.startsWith(p))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path directory : directories) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isEmpty()) Files.delete(directory);
            }
        }
    }

    private static List<Path> textFilesUnder(Path projectDir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(projectDir)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase();
                if (BINARY_SUFFIXES.stream().noneMatch(name::endsWith)) out.add(path);
            }
        }
        return out;
    }
}
