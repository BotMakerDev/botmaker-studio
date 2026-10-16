package com.botmaker.studio.migrate;

import com.botmaker.studio.parser.guard.CompileGuard.Problem;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.Type;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One old element the bot still uses: the new jars break it, or a rule names it (2026-10-16). What the Migrate
 * window lists, one row each.
 *
 * @param element what the old jar declared
 * @param sites   each use of it in the bot
 * @param state   whether a rule covers it, and how far
 * @param rule    the rule that names it, or {@code null}
 */
public record Broken(OldElement element, List<Sites.Site> sites, State state, Rule rule) {

    public Broken {
        sites = List.copyOf(sites);
    }

    /** How far the bot's author has got with it. */
    public enum State {
        /** No rule names it. */
        NONE,
        /** "Write rule" wrote its skeleton, still marked to be written. */
        SEEDED,
        /** A rule names it and can be applied. */
        WRITTEN,
        /** A rule names it and cannot be applied: {@link Rule#problem()} says why. */
        REFUSED
    }

    /**
     * Every old element {@code now} no longer compiles a use of, by what {@code old} says that use bound to — an
     * error the old parse had too is not the new jars' — and every element a rule names that the bot still uses.
     * In the order the bot first uses each.
     */
    static List<Broken> in(BotIndex now, BotIndex old, Path probe, List<Rule> rules) {
        Map<String, OldElement> elements = new LinkedHashMap<>();
        Map<Path, List<Problem>> errors = now.errors();
        Map<Path, List<Problem>> before = old.errors();
        old.read(units -> {
            errors.forEach((file, problems) -> {
                CompilationUnit unit = units.get(file);
                if (unit == null || file.equals(probe)) return;
                Set<Integer> had = new HashSet<>();
                for (Problem problem : before.getOrDefault(file, List.of())) had.add(problem.offset());
                for (Problem problem : problems) {
                    if (had.contains(problem.offset())) continue;
                    used(unit, problem.offset()).ifPresent(element -> elements.putIfAbsent(element.key(), element));
                }
            });
            return null;
        });
        for (Rule rule : rules) {
            if (rule.element() != null) elements.putIfAbsent(rule.element().key(), rule.element());
        }
        Map<String, List<Sites.Site>> byKey = new LinkedHashMap<>();
        for (Sites.Site site : Sites.in(old, elements, probe)) {
            byKey.computeIfAbsent(site.element().key(), k -> new ArrayList<>()).add(site);
        }
        List<Broken> out = new ArrayList<>();
        byKey.forEach((key, sites) -> {
            Rule rule = rules.stream().filter(r -> r.element() != null && r.element().key().equals(key))
                    .findFirst().orElse(null);
            State state = rule == null ? State.NONE : rule.problem() != null ? State.REFUSED
                    : rule.seeded() ? State.SEEDED : State.WRITTEN;
            out.add(new Broken(elements.get(key), sites, state, rule));
        });
        return List.copyOf(out);
    }

    /**
     * The library element the use at {@code offset} bound to in the old parse: the innermost call, {@code new},
     * field or type around it, outside every {@code @Migrate} rule.
     */
    private static Optional<OldElement> used(CompilationUnit unit, int offset) {
        for (ASTNode at = NodeFinder.perform(unit, offset, 0); at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method && BotAnnotation.MIGRATE.on(method) != null) {
                return Optional.empty();
            }
            IBinding binding = switch (at) {
                case MethodInvocation call -> call.resolveMethodBinding();
                case SuperMethodInvocation call -> call.resolveMethodBinding();
                case ClassInstanceCreation creation -> creation.resolveConstructorBinding();
                case FieldAccess access -> access.resolveFieldBinding();
                case Name name -> name.resolveBinding();
                case Type type -> type.resolveBinding();
                case ImportDeclaration declaration -> declaration.resolveBinding();
                default -> null;
            };
            // The JDK does not move under a bot: a call of it that stopped compiling broke on what it was given.
            Optional<OldElement> element = binding == null ? Optional.empty()
                    : OldElement.of(binding).filter(found -> !found.jdk());
            if (element.isPresent()) return element;
            if (at instanceof MethodDeclaration) return Optional.empty();
        }
        return Optional.empty();
    }
}
