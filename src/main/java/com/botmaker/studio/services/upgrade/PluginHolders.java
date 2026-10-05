package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.project.PluginFiles;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A plugin's files in a bot — the {@code plugins/<segment>/<Holder>.java} its {@code @Managed} values live in
 * ({@link PluginFiles}) — and every line of the bot's own code that names one: what removing the plugin offers
 * to delete (2026-10-05).
 *
 * <p>The user never edits such a file (the canvas locks it; only the plugin's windows write it), so once the
 * plugin is gone nothing else would ever take it away, and what it holds names the plugin's types. It goes
 * only when the user ticks it: a line elsewhere that names the class would stop compiling, so each is listed
 * first, and {@link #delete} drops the imports and marks the function each use is in rather than rewriting it.
 *
 * @param files      each holder file, with its class's qualified name, in source order
 * @param references one {@code "Base.java:12"} per code line outside {@link #files} that names one of the
 *                   classes — imports and comments excepted, since the first go by themselves
 */
public record PluginHolders(Map<Path, String> files, List<String> references) {

    public static final PluginHolders NONE = new PluginHolders(Map.of(), List.of());

    /**
     * What {@link #delete} did to the user's code.
     *
     * @param rewritten how many of the bot's other files it rewrote
     * @param left      one sentence per place it could only mark or report
     */
    public record Deleted(int rewritten, List<String> left) {
        public Deleted {
            left = List.copyOf(left);
        }
    }

    public PluginHolders {
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        references = List.copyOf(references);
    }

    /** The files {@code holders} name in the bot, and the lines that name their classes. */
    public static PluginHolders of(ProjectConfig config, ProjectState state, List<PluginFiles.Holder> holders) {
        if (config == null || holders.isEmpty()) return NONE;
        Map<Path, String> files = new LinkedHashMap<>();
        BotSources.scan(config, state, (file, source) -> PluginFiles.owner(file, holders).ifPresent(holder -> {
            String pkg = packageOf(source);
            files.put(file, pkg.isEmpty() ? holder.className() : pkg + "." + holder.className());
        }));
        if (files.isEmpty()) return NONE;
        Pattern named = named(simpleNames(files));
        List<String> references = new ArrayList<>();
        BotSources.scan(config, state, (file, source) -> {
            if (files.containsKey(file)) return;
            List<String> lines = source.lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("import ") || line.startsWith("//") || line.startsWith("*")
                        || line.startsWith("/*")) {
                    continue;
                }
                if (named.matcher(line).find()) references.add(file.getFileName() + ":" + (i + 1));
            }
        });
        return new PluginHolders(files, references);
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /** {@code "Sdk.java, Pictures.java"} — what the question names. */
    public String fileNames() {
        return String.join(", ", files.keySet().stream().map(f -> f.getFileName().toString()).toList());
    }

    /**
     * Deletes the files from disk (and their folder once empty), drops each import of their classes, and marks
     * every function that still names one. Blocking; the editor's buffers are {@link #forget}'s, on the FX
     * thread.
     *
     * @param plugin what the user calls the plugin that is going
     */
    public Deleted delete(ProjectConfig config, ProjectState state, String plugin) throws IOException {
        if (isEmpty()) return new Deleted(0, List.of());
        List<String> classes = simpleNames(files);
        Set<String> packages = emptiedPackages();
        Pattern named = named(classes);
        List<String> left = new ArrayList<>();
        int[] rewritten = {0};
        BotSources.forEach(config, state, (file, source) -> {
            if (files.containsKey(file) || !mentions(source, named, packages)) return null;
            String result = unhook(file, source, classes, packages, plugin, left);
            if (result == null || result.equals(source)) return null;
            rewritten[0]++;
            return result;
        });
        for (Path file : files.keySet()) {
            Files.deleteIfExists(file);
            Path folder = file.getParent();
            if (folder != null && Files.isDirectory(folder)) {
                try (Stream<Path> rest = Files.list(folder)) {
                    if (rest.findAny().isEmpty()) Files.delete(folder);
                }
            }
        }
        return new Deleted(rewritten[0], left);
    }

    /**
     * Drops the deleted files' buffers, and moves the editor off one of them to the bot's entry file. FX thread
     * only: the open-files map is the editor's.
     */
    public void forget(ProjectConfig config, ProjectState state) {
        ProjectFile active = state.getActiveFile();
        for (Path file : files.keySet()) state.removeFile(file);
        if (active != null && files.containsKey(active.getPath())) state.setActiveFile(config.entrySourceFile());
    }

    /** The holders' packages that the deletion leaves with no file at all — their on-demand imports go too. */
    private Set<String> emptiedPackages() throws IOException {
        Set<String> emptied = new LinkedHashSet<>();
        for (Map.Entry<Path, String> holder : files.entrySet()) {
            Path folder = holder.getKey().getParent();
            if (folder == null || !Files.isDirectory(folder)) continue;
            try (Stream<Path> each = Files.list(folder)) {
                if (each.allMatch(files::containsKey)) {
                    String qualified = holder.getValue();
                    int dot = qualified.lastIndexOf('.');
                    if (dot > 0) emptied.add(qualified.substring(0, dot));
                }
            }
        }
        return emptied;
    }

    private static boolean mentions(String source, Pattern named, Set<String> packages) {
        return named.matcher(source).find() || packages.stream().anyMatch(source::contains);
    }

    /**
     * {@code source} without imports of the deleted classes, each function naming one marked, and a sentence in
     * {@code left} for what could only be reported. {@code null} when the file does not parse.
     */
    private String unhook(Path file, String source, List<String> classes, Set<String> packages, String plugin,
                          List<String> left) {
        String name = file.getFileName().toString();
        CompilationUnit unit = BotParser.syntax(source);
        if (unit == null || SourceParser.hasSyntaxErrors(unit)) {
            left.add(name + " does not parse, so its use of " + String.join(", ", classes)
                    + " was left as written.");
            return null;
        }

        EditContext ctx = EditContext.of(unit, null, null);
        for (Object each : unit.imports()) {
            ImportDeclaration imported = (ImportDeclaration) each;
            String target = imported.getName().getFullyQualifiedName();
            boolean gone;
            if (imported.isOnDemand()) {
                gone = packages.contains(target) || (imported.isStatic() && files.containsValue(target));
            } else if (imported.isStatic()) {
                gone = files.values().stream().anyMatch(q -> target.startsWith(q + "."));
            } else {
                gone = files.containsValue(target);
            }
            if (!gone) continue;
            ctx.rewriter().remove(imported, null);
            if (imported.isStatic()) {
                left.add(name + " imported " + target + " statically; what it used from there is left as written.");
            }
        }
        Map<MethodDeclaration, Set<String>> naming = new LinkedHashMap<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName simple) {
                if (!classes.contains(simple.getIdentifier())) return false;
                for (ASTNode at = simple.getParent(); at != null; at = at.getParent()) {
                    if (at instanceof ImportDeclaration) return false;
                    if (at instanceof MethodDeclaration method) {
                        naming.computeIfAbsent(method, m -> new LinkedHashSet<>()).add(simple.getIdentifier());
                        return false;
                    }
                    if (at instanceof BodyDeclaration) {
                        // A field, an initializer, an enum constant: nothing to hang a mark on.
                        left.add(name + ":" + unit.getLineNumber(simple.getStartPosition()) + " names "
                                + simple.getIdentifier() + " outside a function; change it by hand.");
                        return false;
                    }
                }
                return false;
            }
        });
        naming.forEach((method, used) -> {
            String names = String.join(", ", used);
            ReviewMarks.mark(ctx, method,
                    List.of(names + " went with the " + plugin + " plugin: change what this used it for."));
            left.add(name + " · " + method.getName().getIdentifier() + "() still names " + names + ".");
        });
        String rewritten = ctx.applyTo(source);
        if (rewritten == null || SourceParser.hasSyntaxErrors(BotParser.syntax(rewritten))) {
            left.add(name + " could not be rewritten, so its use of " + String.join(", ", classes)
                    + " was left as written.");
            return null;
        }
        return rewritten;
    }

    private static List<String> simpleNames(Map<Path, String> files) {
        return files.values().stream().map(q -> q.substring(q.lastIndexOf('.') + 1)).distinct().toList();
    }

    /** The package line of {@code source}, {@code ""} for none. */
    private static String packageOf(String source) {
        CompilationUnit unit = BotParser.syntax(source);
        return unit == null || unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
    }

    private static Pattern named(List<String> classes) {
        return Pattern.compile("\\b(" + String.join("|", classes.stream().map(Pattern::quote).toList()) + ")\\b");
    }
}
