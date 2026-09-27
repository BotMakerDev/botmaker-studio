package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;

import javax.lang.model.SourceVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>The one way a name changes</b> (2026-09-27) — a field, a parameter, a function, a type, an enum or one of
 * its constants, a local variable. Pure: a {@link BotIndex} in, a {@link Planned plan} or a {@link Refused
 * refusal} out, and nothing written. Whoever asked writes the plan through its own undo path — the canvas as
 * one ↶ step across every file, the Parameters window with its own history, the explorer with the file moved.
 *
 * <p><b>What a rename touches</b> is exactly what {@link Usages} binds to the declaration: the declaration,
 * every use in every file, a static import, a constructor's name for a type. Nothing is matched by spelling,
 * so a local of the same name, a same-named member of another class and the word in a string all stay.
 *
 * <p><b>What it must never do is stop the bot compiling.</b> The plan is parsed as the whole bot before it is
 * offered ({@link BotIndex#with}), and a plan that adds an error anywhere — a name already taken, a shadowed
 * field, an {@code @Override} that no longer overrides — is refused with javac's sentence, and with a free
 * name offered in its place as a {@link Fix}. An error the bot already had never refuses.
 *
 * <p><b>Refusals are one shape</b> ({@link Refused}): why, where the declaration is still used, and the
 * fixes the refactor itself can vouch for, each a whole plan that was checked the same way. Every window that
 * says no — a function still called, a parameter still read, a rename that would break the build — shows that
 * shape through one dialog ({@code ui/app/RefusalDialog}).
 */
public final class Refactor {

    private Refactor() {}

    /** What a refactor would do, or why it will not. */
    public sealed interface Outcome permits Planned, Refused {}

    /**
     * The files a refactor rewrites, none of them written yet.
     *
     * @param summary  what it does, in the words a status line and an undo step can show
     * @param rewrites every changed source, by its <em>current</em> absolute path
     * @param moves    files that move, from their current path to the new one ({@code Foo.java} → {@code Bar.java}
     *                 when public class {@code Foo} becomes {@code Bar}); their text is in {@code rewrites}
     */
    public record Planned(String summary, Map<Path, String> rewrites, Map<Path, Path> moves) implements Outcome {
        public Planned {
            rewrites = Map.copyOf(rewrites);
            moves = Map.copyOf(moves);
        }

        public Planned(String summary, Map<Path, String> rewrites) {
            this(summary, rewrites, Map.of());
        }
    }

    /**
     * Nothing was planned.
     *
     * @param reason the sentence that says why, naming the file where there is one
     * @param uses   where the declaration is still used, when that is the reason — each one a place to go
     * @param fixes  what could be done instead, each already planned and checked
     */
    public record Refused(String reason, List<Usages.Usage> uses, List<Fix> fixes) implements Outcome {
        public Refused {
            uses = List.copyOf(uses);
            fixes = List.copyOf(fixes);
        }

        public Refused(String reason) {
            this(reason, List.of(), List.of());
        }
    }

    /** Another way through a refusal: a label for its button and the plan it applies. */
    public record Fix(String label, Planned plan) {}

    /** How many alternative names a refused rename tries before offering none. */
    private static final int SUGGESTIONS = 3;

    /**
     * Renames the declaration whose name starts at {@code start} in {@code file}, and every use of it.
     *
     * @param start the offset of the declaration's name, or of any use of it — both bind to the same thing
     */
    public static Outcome rename(BotIndex index, Path file, int start, String newName) {
        String name = newName == null ? "" : newName.strip();
        Path at = file == null ? null : file.toAbsolutePath().normalize();
        Target target = index.read(units -> Target.at(units.get(at), start));
        if (target == null) return new Refused("That name could not be resolved, so the places that use it "
                + "cannot be found. Nothing was renamed.");
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            return new Refused("“" + name + "” is not a name Java accepts.");
        }
        if (name.equals(target.name())) return new Refused("That is already its name.");

        Planned plan = plan(index, at, target, name);
        if (plan == null) return new Refused(target.name() + "'s declaration could not be found. Nothing was renamed.");
        Optional<String> broken = index.firstNewError(index.with(plan.rewrites(), plan.moves()), plan.moves());
        if (broken.isEmpty()) return plan;
        return new Refused("Renaming " + target.name() + " to " + name + " would stop the bot compiling ("
                + broken.get() + "), so nothing was changed.", List.of(), suggestion(index, at, target, name));
    }

    /**
     * Every place the declaration named at {@code start} in {@code file} is used, the declaration left out —
     * empty when it cannot be resolved, which is not the same answer as "nothing uses it".
     */
    public static Optional<List<Usages.Usage>> uses(BotIndex index, Path file, int start) {
        Path at = file == null ? null : file.toAbsolutePath().normalize();
        Target target = index.read(units -> Target.at(units.get(at), start));
        if (target == null) return Optional.empty();
        List<Usages.Usage> found = target.local() ? index.read(units -> Usages.in(at, index.sources().get(at),
                units.get(at), target.key())) : Usages.across(index, target.key(), target.name());
        return Optional.of(found.stream().filter(use -> !use.declaration()).toList());
    }

    /** Checks {@code plan} against the bot: the plan itself, or a refusal naming the first error it adds. */
    public static Outcome checked(BotIndex index, Planned plan, String what) {
        Optional<String> broken = index.firstNewError(index.with(plan.rewrites(), plan.moves()), plan.moves());
        return broken.<Outcome>map(error -> new Refused(what + " would stop the bot compiling (" + error
                + "), so nothing was changed.")).orElse(plan);
    }

    // --- planning -------------------------------------------------------------------------------------------

    /** The declaration a rename starts from: its key, its current name, and whether it is a local. */
    private record Target(String key, String name, boolean local, boolean type) {

        static Target at(CompilationUnit unit, int start) {
            IBinding binding = Usages.bindingAt(unit, start);
            String key = binding == null ? null : Usages.keyOf(binding);
            if (key == null) return null;
            boolean local = binding instanceof IVariableBinding variable && !variable.isField();
            return new Target(key, binding.getName(), local, binding instanceof ITypeBinding);
        }
    }

    /** The rename as rewrites, or null when the declaration itself was not among the names found. */
    private static Planned plan(BotIndex index, Path file, Target target, String newName) {
        Map<Path, String> rewrites = new LinkedHashMap<>();
        boolean[] declared = {false};
        index.read(units -> {
            units.forEach((path, unit) -> {
                if (target.local() && !path.equals(file)) return;
                String text = index.sources().get(path);
                if (!text.contains(target.name())) return;
                List<Integer> starts = new ArrayList<>();
                for (Usages.Usage use : Usages.in(path, text, unit, target.key())) {
                    starts.add(use.start());
                    declared[0] |= use.declaration();
                }
                if (target.type()) starts.addAll(constructorNames(unit, target.key()));
                String rewritten = Usages.renamed(text, starts, target.name(), newName);
                if (!rewritten.equals(text)) rewrites.put(path, rewritten);
            });
            return null;
        });
        if (!declared[0]) return null;
        Map<Path, Path> moves = target.type() ? move(index, target, newName) : Map.of();
        int files = rewrites.size();
        return new Planned("Renamed " + target.name() + " to " + newName
                + (files > 1 ? ", in " + files + " files" : ""), rewrites, moves);
    }

    /**
     * The file that moves with a top-level public type — {@code Foo.java} holds {@code public class Foo}, and
     * javac refuses it under any other name. Empty for every other type.
     */
    private static Map<Path, Path> move(BotIndex index, Target target, String newName) {
        return index.read(units -> {
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                for (Object each : entry.getValue().types()) {
                    if (!(each instanceof AbstractTypeDeclaration type)) continue;
                    ITypeBinding binding = type.resolveBinding();
                    if (binding == null || !target.key().equals(Usages.keyOf(binding))) continue;
                    String fileName = entry.getKey().getFileName().toString();
                    if (!Modifier.isPublic(type.getModifiers()) || !fileName.equals(target.name() + ".java")) {
                        return Map.of();
                    }
                    return Map.of(entry.getKey(), entry.getKey().resolveSibling(newName + ".java"));
                }
            }
            return Map.<Path, Path>of();
        });
    }

    /** A constructor's name binds to the constructor, not the class, so {@link Usages} does not list it. */
    private static List<Integer> constructorNames(CompilationUnit unit, String key) {
        List<Integer> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (!method.isConstructor()) return true;
                IMethodBinding binding = method.resolveBinding();
                if (binding != null && key.equals(Usages.keyOf(binding.getDeclaringClass()))) {
                    out.add(method.getName().getStartPosition());
                }
                return true;
            }
        });
        return out;
    }

    /**
     * The first of {@code newName2}, {@code newName3}, … whose rename compiles, as the fix a refused rename
     * offers — or none, when {@link #SUGGESTIONS} tries all break something too (the name was never the
     * problem).
     */
    private static List<Fix> suggestion(BotIndex index, Path file, Target target, String newName) {
        String stem = newName.replaceFirst("\\d+$", "");
        for (int n = 2; n < 2 + SUGGESTIONS; n++) {
            String candidate = stem + n;
            if (candidate.equals(target.name())) continue;
            Planned plan = plan(index, file, target, candidate);
            if (plan == null) return List.of();
            if (checked(index, plan, "") instanceof Planned) {
                return List.of(new Fix("Rename to " + candidate + " instead", plan));
            }
        }
        return List.of();
    }
}
