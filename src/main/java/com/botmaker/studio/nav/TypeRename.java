package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import javax.lang.model.SourceVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renaming a file from the explorer (2026-09-26): a {@code .java} file is its top-level class, so the class is
 * renamed with it, and every name in the bot bound to that class — an import, a field's type, a {@code new},
 * a static call's qualifier, the constructors — is rewritten with it. Pure: sources in, rewritten sources out.
 *
 * <p>Names are found through bindings ({@link Usages}), never by text, so a class of the same simple name in
 * another package, a local variable spelled the same and the word inside a string are all left alone. What
 * is <em>not</em> rewritten is what has no binding: a comment, a Javadoc {@code {@link}}, a string holding
 * the name. A rename that finds no binding for the class refuses rather than guess.
 */
public final class TypeRename {

    private TypeRename() {}

    /** What a rename would do, or why it will not. */
    public sealed interface Result {

        /**
         * The rename, planned.
         *
         * @param from     the file renamed
         * @param to       its new path, beside it
         * @param rewrites every changed source, by its <em>current</em> path — {@code from}'s text included
         */
        record Plan(Path from, Path to, Map<Path, String> rewrites) implements Result {}

        record Refused(String reason) implements Result {}
    }

    /**
     * Plans renaming {@code file}'s class to {@code newName}.
     *
     * @param sources  every bot source, by path — the open buffer where there is one
     * @param existing the paths that exist on disk, so the new name cannot land on one
     * @param parser   a binding parser for the open project
     */
    public static Result plan(Path file, String newName, Map<Path, String> sources, Set<Path> existing,
                              BotParser parser) {
        String name = newName == null ? "" : newName.trim();
        if (name.endsWith(".java")) name = name.substring(0, name.length() - ".java".length());
        String oldName = file.getFileName().toString().replaceFirst("\\.java$", "");
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            return new Result.Refused("\"" + name + "\" is not a Java class name.");
        }
        if (name.equals(oldName)) return new Result.Refused("That is already its name.");
        Path to = file.resolveSibling(name + ".java");
        if (sources.containsKey(to) || existing.contains(to)) {
            return new Result.Refused(to.getFileName() + " already exists.");
        }
        String source = sources.get(file);
        if (source == null) return new Result.Refused(file.getFileName() + " could not be read.");
        if (!parser.binds()) return new Result.Refused("The project's classes are not resolved yet — try again in a moment.");

        CompilationUnit unit = parser.parse(file, source);
        AbstractTypeDeclaration type = topLevel(unit, oldName);
        if (type == null) return new Result.Refused(file.getFileName() + " declares no class named " + oldName + ".");
        ITypeBinding binding = type.resolveBinding();
        String key = binding == null ? null : Usages.keyOf(binding);
        if (key == null) return new Result.Refused(oldName + " could not be resolved, so its uses cannot be found.");

        Map<Path, String> rewrites = new LinkedHashMap<>();
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            String text = entry.getValue();
            if (!text.contains(oldName)) continue;
            CompilationUnit cu = entry.getKey().equals(file) ? unit : parser.parse(entry.getKey(), text);
            List<Integer> starts = new ArrayList<>();
            for (Usages.Usage use : Usages.in(entry.getKey(), text, cu, key)) starts.add(use.start());
            starts.addAll(constructorNames(cu, key));
            String rewritten = replace(text, starts, oldName, name);
            if (!rewritten.equals(text)) rewrites.put(entry.getKey(), rewritten);
        }
        if (!rewrites.containsKey(file)) return new Result.Refused(oldName + "'s declaration could not be found.");
        return new Result.Plan(file, to, rewrites);
    }

    private static AbstractTypeDeclaration topLevel(CompilationUnit unit, String name) {
        for (Object type : unit.types()) {
            if (type instanceof AbstractTypeDeclaration t && t.getName().getIdentifier().equals(name)) return t;
        }
        return null;
    }

    /** A constructor's name binds to the constructor, not the class, so {@link Usages} does not see it. */
    private static List<Integer> constructorNames(CompilationUnit cu, String key) {
        List<Integer> out = new ArrayList<>();
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (!method.isConstructor()) return true;
                var binding = method.resolveBinding();
                if (binding != null && key.equals(Usages.keyOf(binding.getDeclaringClass()))) {
                    out.add(method.getName().getStartPosition());
                }
                return true;
            }
        });
        return out;
    }

    /** {@code text} with {@code oldName} at each start replaced, back to front so the offsets hold. */
    private static String replace(String text, List<Integer> starts, String oldName, String newName) {
        StringBuilder out = new StringBuilder(text);
        starts.stream().distinct().sorted(Comparator.reverseOrder()).forEach(start -> {
            if (text.startsWith(oldName, start)) out.replace(start, start + oldName.length(), newName);
        });
        return out.toString();
    }
}
