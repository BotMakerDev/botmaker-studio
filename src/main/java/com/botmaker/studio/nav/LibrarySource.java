package com.botmaker.studio.nav;

import com.botmaker.studio.parser.helpers.SourceParser;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Go to Definition for a member the bot does not declare (2026-09-26): the library's own source when the
 * library ships a {@code -sources.jar} (or, for the JDK, {@code lib/src.zip}), and otherwise an outline read off
 * its bytecode — every public signature, no bodies. Never a decompiler: what is shown is either what the
 * library's authors wrote or plainly marked as a listing.
 *
 * <p>No FX and no network here: finding a sources jar that is not on disk yet is the caller's
 * {@code fetchSources}, which may download.
 */
public final class LibrarySource {

    private LibrarySource() {}

    /**
     * What to look for, read off a binding on the FX thread: the declaring class's binary name
     * ({@code java.util.Map$Entry}), its package, and the member — a method name, {@code <init>} for a
     * constructor, or null for the type itself — with its parameter count to tell overloads apart.
     */
    public record Target(String binaryName, String packageName, String member, int parameters) {

        public static Optional<Target> of(IBinding binding) {
            return switch (binding) {
                case IMethodBinding m when m.getDeclaringClass() != null -> type(m.getDeclaringClass()).map(t ->
                        new Target(t.binaryName, t.packageName, m.isConstructor() ? "<init>" : m.getName(),
                                m.getParameterTypes().length));
                case ITypeBinding t -> type(t);
                case null, default -> Optional.empty();
            };
        }

        private static Optional<Target> type(ITypeBinding type) {
            ITypeBinding erased = type.getErasure();
            String binary = erased.getBinaryName();
            if (binary == null || erased.getPackage() == null) return Optional.empty();
            return Optional.of(new Target(binary, erased.getPackage().getName(), null, 0));
        }

        /** The top-level class the member is in: {@code java.util.Map} for {@code java.util.Map$Entry}. */
        public String topLevel() {
            int inner = binaryName.indexOf('$');
            return inner < 0 ? binaryName : binaryName.substring(0, inner);
        }

        /** The simple names from the top-level class down: {@code [Map, Entry]}. */
        List<String> typePath() {
            String local = packageName.isEmpty() ? binaryName : binaryName.substring(packageName.length() + 1);
            return List.of(local.split("\\$"));
        }

        String sourceEntry() {
            return topLevel().replace('.', '/') + ".java";
        }

        String classEntry() {
            return binaryName.replace('.', '/') + ".class";
        }
    }

    /** Where the text came from, and so what the window says above it. */
    public enum Origin {
        SOURCES("sources"), OUTLINE("outline");

        private final String id;

        Origin(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /**
     * What Go to Definition opens: a title, the text, the 1-based line of the declaration (0 when it could not
     * be found and the top is shown), where it came from, and which file that was.
     */
    public record View(String title, String text, int line, Origin origin, Path from) {}

    /** A jar's Maven coordinates, read back off its place in a local repository. */
    public record Coordinates(String groupId, String artifactId, String version) {}

    /**
     * Opens {@code target}: the jar (or classes folder) on {@code classpath} that holds its class, then that
     * jar's sources — beside it, or from {@code fetchSources} — and otherwise an outline of the jar. A class
     * on no classpath entry is the JDK's, read from {@code jdkHome}'s {@code lib/src.zip}, or outlined from
     * the running JDK's modules. Empty only when the class is nowhere.
     */
    public static Optional<View> locate(Target target, List<String> classpath, Path jdkHome,
                                        Function<Path, Optional<Path>> fetchSources) {
        Optional<Path> holder = holderOf(target, classpath);
        if (holder.isPresent()) {
            Path jar = holder.get();
            Optional<Path> sources = sourcesBeside(jar).or(() -> Files.isRegularFile(jar)
                    ? fetchSources.apply(jar) : Optional.empty());
            Optional<View> read = sources.flatMap(zip -> fromSources(target, zip, false));
            if (read.isPresent()) return read;
            return outline(target, jar);
        }
        Optional<View> jdk = Optional.ofNullable(jdkHome).map(home -> home.resolve("lib").resolve("src.zip"))
                .filter(Files::isRegularFile).flatMap(zip -> fromSources(target, zip, true));
        return jdk.isPresent() ? jdk : outline(target, null);
    }

    /** The classpath entry — a jar or a classes folder — that holds the target's class file. */
    static Optional<Path> holderOf(Target target, List<String> classpath) {
        for (String entry : classpath) {
            Path path = Path.of(entry);
            if (Files.isDirectory(path)) {
                if (Files.isRegularFile(path.resolve(target.classEntry()))) return Optional.of(path);
            } else if (Files.isRegularFile(path)) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    if (zip.getEntry(target.classEntry()) != null) return Optional.of(path);
                } catch (IOException ignored) {
                    // Not a readable jar: it holds nothing for us.
                }
            }
        }
        return Optional.empty();
    }

    /** {@code x-1.0-sources.jar} next to {@code x-1.0.jar}, as Maven puts it once it has been resolved. */
    static Optional<Path> sourcesBeside(Path jar) {
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) return Optional.empty();
        Path sibling = jar.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
        return Files.isRegularFile(sibling) ? Optional.of(sibling) : Optional.empty();
    }

    /**
     * {@code <repo>/com/acme/lib/1.0/lib-1.0.jar} read back as {@code com.acme:lib:1.0}; empty for a jar that is
     * not laid out in {@code repository} that way.
     */
    public static Optional<Coordinates> coordinatesOf(Path jar, Path repository) {
        Path absolute = jar.toAbsolutePath().normalize();
        Path repo = repository.toAbsolutePath().normalize();
        if (!absolute.startsWith(repo)) return Optional.empty();
        Path relative = repo.relativize(absolute);
        int n = relative.getNameCount();
        if (n < 4) return Optional.empty();
        String version = relative.getName(n - 2).toString();
        String artifact = relative.getName(n - 3).toString();
        if (!relative.getFileName().toString().startsWith(artifact + "-" + version)) return Optional.empty();
        String group = relative.subpath(0, n - 3).toString().replace(relative.getFileSystem().getSeparator(), ".");
        return Optional.of(new Coordinates(group, artifact, version));
    }

    /**
     * The target's source file out of {@code zip}. A JDK {@code src.zip} puts each file under its module
     * ({@code java.base/java/lang/String.java}), so there any entry ending in the path matches.
     */
    static Optional<View> fromSources(Target target, Path zip, boolean moduleFolders) {
        String wanted = target.sourceEntry();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry entry = file.getEntry(wanted);
            if (entry == null && moduleFolders) {
                entry = file.stream().filter(e -> e.getName().endsWith("/" + wanted)).findFirst().orElse(null);
            }
            if (entry == null) return Optional.empty();
            String text = new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            return Optional.of(new View(simpleName(target), text, lineOf(text, target), Origin.SOURCES, zip));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The 1-based line that declares the target in {@code source}: the member of that name with that many
     * parameters in the nested type the binary name walks to, else the first of that name, else the type,
     * else 0.
     */
    static int lineOf(String source, Target target) {
        CompilationUnit cu = SourceParser.parse(source);
        AbstractTypeDeclaration type = null;
        List<?> members = cu.types();
        for (String name : target.typePath()) {
            AbstractTypeDeclaration next = members.stream()
                    .filter(AbstractTypeDeclaration.class::isInstance).map(AbstractTypeDeclaration.class::cast)
                    .filter(t -> t.getName().getIdentifier().equals(name)).findFirst().orElse(null);
            if (next == null) break;
            type = next;
            members = type.bodyDeclarations();
        }
        if (type == null) return 0;
        ASTNode found = type.getName();
        if (target.member() != null) {
            List<MethodDeclaration> named = new ArrayList<>();
            for (Object d : type.bodyDeclarations()) {
                if (d instanceof MethodDeclaration m && (target.member().equals("<init>")
                        ? m.isConstructor() : m.getName().getIdentifier().equals(target.member()))) {
                    named.add(m);
                }
            }
            found = named.stream().filter(m -> m.parameters().size() == target.parameters()).findFirst()
                    .or(() -> named.stream().findFirst()).map(m -> (ASTNode) m.getName()).orElse(found);
        }
        return cu.getLineNumber(found.getStartPosition());
    }

    /**
     * Every public signature of the target's top-level class, and of the classes nested in it, read off the
     * class files in {@code jar} — or off the running JDK's modules when {@code jar} is null.
     */
    static Optional<View> outline(Target target, Path jar) {
        ClassGraph graph = new ClassGraph().enableClassInfo().enableMethodInfo().enableFieldInfo()
                .acceptPackagesNonRecursive(target.packageName());
        graph = jar == null ? graph.enableSystemJarsAndModules() : graph.overrideClasspath(jar.toString());
        try (ScanResult scan = graph.scan()) {
            ClassInfo top = scan.getClassInfo(target.topLevel());
            if (top == null) return Optional.empty();
            List<String> lines = new ArrayList<>();
            lines.add("// Outline of " + target.topLevel() + ": no sources were found for it, so only its");
            lines.add("// public signatures are listed, read from its compiled classes.");
            if (!target.packageName().isEmpty()) {
                lines.add("package " + target.packageName() + ";");
                lines.add("");
            }
            int[] line = {0};
            write(top, "", target, lines, line);
            return Optional.of(new View(simpleName(target), String.join("\n", lines) + "\n", line[0],
                    Origin.OUTLINE, jar));
        }
    }

    /** {@code ACC_SYNTHETIC}: a member the compiler made, which no one wrote and no one calls. */
    private static final int SYNTHETIC = 0x1000;

    private static void write(ClassInfo type, String indent, Target target, List<String> out, int[] line) {
        boolean isTarget = type.getName().equals(target.binaryName());
        if (isTarget && target.member() == null) line[0] = out.size() + 1;
        String simple = type.getName().substring(Math.max(type.getName().lastIndexOf('.'),
                type.getName().lastIndexOf('$')) + 1);
        out.add(indent + unqualified(type.toString()).replace(unqualified(type.getName()), simple) + " {");
        String inner = indent + "    ";
        for (FieldInfo field : type.getDeclaredFieldInfo()) {
            if ((field.getModifiers() & SYNTHETIC) == 0) out.add(inner + field.toStringWithSimpleNames() + ";");
        }
        List<MethodInfo> methods = new ArrayList<>();
        type.getDeclaredConstructorInfo().forEach(methods::add);
        type.getDeclaredMethodInfo().stream().filter(m -> !m.isSynthetic() && !m.isBridge())
                .sorted(Comparator.comparing(MethodInfo::getName)).forEach(methods::add);
        for (MethodInfo method : methods) {
            if (isTarget && line[0] == 0 && method.getName().equals(target.member())
                    && method.getParameterInfo().length == target.parameters()) {
                line[0] = out.size() + 1;
            }
            // A constructor's name in a class file is <init>; the outline writes it as Java does.
            out.add(inner + method.toStringWithSimpleNames().replace("<init>", simple) + ";");
        }
        for (ClassInfo nested : type.getInnerClasses()) {
            // getInnerClasses reaches every depth; each class writes only the ones declared directly in it.
            if (nested.getName().lastIndexOf('$') == type.getName().length() && !nested.isAnonymousInnerClass()) {
                out.add("");
                write(nested, inner, target, out, line);
            }
        }
        out.add(indent + "}");
    }

    /** {@code java.util.List<java.lang.String>} as {@code List<String>}: every package qualifier dropped. */
    static String unqualified(String text) {
        return text.replaceAll("\\b(?:[a-z_][\\w]*\\.)+(?=[A-Za-z_$])", "");
    }

    private static String simpleName(Target target) {
        List<String> path = target.typePath();
        return String.join(".", path);
    }
}
