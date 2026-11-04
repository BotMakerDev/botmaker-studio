package com.botmaker.studio.migrate;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.studio.parser.guard.CompileGuard.Problem;
import com.botmaker.plugin.host.grammar.BotAnnotation;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
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
import java.util.function.Function;

/**
 * One old element the bot still uses: the new jars break it, or a rule names it (2026-10-16). What the Migrate
 * window lists, one row each.
 *
 * @param element what the old jar declared
 * @param sites   each use of it in the bot
 * @param state   whether a rule covers it, and how far
 * @param rule    the rule that names it, or {@code null}
 * @param dropped what its plugin says of it with {@code @Migrate.Dropped}, read off the jar the bot moves from; or
 *                {@code null}
 */
public record Broken(OldElement element, List<Sites.Site> sites, State state, Rule rule, Dropped dropped) {

    public Broken {
        sites = List.copyOf(sites);
    }

    /** One no plugin marked dropped. */
    public Broken(OldElement element, List<Sites.Site> sites, State state, Rule rule) {
        this(element, sites, state, rule, null);
    }

    /**
     * An element its plugin removes on purpose, with nothing in its place (2026-11-01, {@link Migrate.Dropped}).
     *
     * @param plugin the name of the plugin whose jar declares it, or {@code null} when no plugin jar on the old
     *               classpath does (a class folder)
     * @param reason why nothing replaces it, as the plugin says it
     */
    public record Dropped(String plugin, String reason) {

        /** {@code dropped by BotMaker SDK: popups are handled by the run itself now}. */
        public String text() {
            return "dropped" + (plugin == null ? "" : " by " + plugin) + ": " + reason;
        }
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
     * In the order the bot first uses each. {@code pluginOf} names the plugin whose jar declares a binary name, or
     * answers {@code null}.
     */
    static List<Broken> in(BotIndex now, BotIndex old, Path probe, List<Rule> rules,
                           Function<String, String> pluginOf) {
        Map<String, OldElement> elements = new LinkedHashMap<>();
        Map<Path, List<Problem>> errors = now.errors();
        Map<Path, List<Problem>> before = old.errors();
        old.read(units -> {
            errors.forEach((file, problems) -> {
                CompilationUnit unit = units.get(file);
                if (unit == null || Rules.added(file, probe)) return;
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
        membersOfGoneTypes(old, probe, rules, elements);
        Map<String, List<Sites.Site>> byKey = new LinkedHashMap<>();
        for (Sites.Site site : Sites.in(old, elements, probe)) {
            byKey.computeIfAbsent(site.element().key(), k -> new ArrayList<>()).add(site);
        }
        Map<String, Dropped> dropped = dropped(old, byKey, pluginOf);
        List<Broken> out = new ArrayList<>();
        byKey.forEach((key, sites) -> {
            Rule rule = rules.stream().filter(r -> r.element() != null && r.element().key().equals(key))
                    .findFirst().orElse(null);
            State state = rule == null ? State.NONE : rule.problem() != null ? State.REFUSED
                    : rule.seeded() ? State.SEEDED : State.WRITTEN;
            out.add(new Broken(elements.get(key), sites, state, rule, dropped.get(key)));
        });
        return List.copyOf(out);
    }

    /**
     * What each element's plugin says of it with {@code @Migrate.Dropped}, by key: read off the binding at its first
     * use in the old parse, whose jar still declares it. A member of a type marked dropped is dropped with it.
     */
    private static Map<String, Dropped> dropped(BotIndex old, Map<String, List<Sites.Site>> byKey,
                                                Function<String, String> pluginOf) {
        Map<String, Dropped> out = new LinkedHashMap<>();
        old.read(units -> {
            byKey.forEach((key, sites) -> {
                Sites.Site first = sites.getFirst();
                CompilationUnit unit = units.get(first.file());
                if (unit == null) return;
                for (ASTNode at = NodeFinder.perform(unit, first.start(), first.length()); at != null;
                     at = at.getParent()) {
                    IBinding binding = bindingOf(at);
                    if (binding == null || !OldElement.of(binding).map(e -> e.key().equals(key)).orElse(false)) {
                        if (at instanceof MethodDeclaration) return;
                        continue;
                    }
                    ITypeBinding owner = ownerOf(binding);
                    String reason = droppedReason(binding);
                    // A member of a dropped type, or of a type nested in one, goes with it.
                    for (ITypeBinding around = owner == binding ? owner.getDeclaringClass() : owner;
                         reason == null && around != null; around = around.getDeclaringClass()) {
                        reason = droppedReason(around);
                    }
                    if (reason != null) {
                        String binary = owner == null ? null : owner.getErasure().getBinaryName();
                        out.put(key, new Dropped(binary == null ? null : pluginOf.apply(binary), reason));
                    }
                    return;
                }
            });
            return null;
        });
        return out;
    }

    /** The type that declares {@code binding}: a member's owner, or a type itself. */
    private static ITypeBinding ownerOf(IBinding binding) {
        return switch (binding) {
            case IMethodBinding method -> method.getMethodDeclaration().getDeclaringClass();
            case IVariableBinding variable -> variable.getVariableDeclaration().getDeclaringClass();
            case ITypeBinding type -> type.getErasure();
            default -> null;
        };
    }

    /** {@code @Migrate.Dropped}'s reason on {@code binding}'s declaration, or {@code null}. */
    private static String droppedReason(IBinding binding) {
        IBinding declared = switch (binding) {
            case IMethodBinding method -> method.getMethodDeclaration();
            case IVariableBinding variable -> variable.getVariableDeclaration();
            case ITypeBinding type -> type.getErasure();
            default -> binding;
        };
        for (IAnnotationBinding mark : declared.getAnnotations()) {
            ITypeBinding type = mark.getAnnotationType();
            if (type == null || !DROPPED.equals(type.getBinaryName())) continue;
            for (IMemberValuePairBinding pair : mark.getDeclaredMemberValuePairs()) {
                if (pair.getName().equals("value") && pair.getValue() instanceof String reason) return reason;
            }
        }
        return null;
    }

    private static final String DROPPED = Migrate.Dropped.class.getName();

    /** The binding of the call, {@code new}, field, name, type or import {@code at} is. */
    private static IBinding bindingOf(ASTNode at) {
        return switch (at) {
            case MethodInvocation call -> call.resolveMethodBinding();
            case SuperMethodInvocation call -> call.resolveMethodBinding();
            case ClassInstanceCreation creation -> creation.resolveConstructorBinding();
            case FieldAccess access -> access.resolveFieldBinding();
            case Name name -> name.resolveBinding();
            case Type type -> type.resolveBinding();
            case ImportDeclaration declaration -> declaration.resolveBinding();
            default -> null;
        };
    }

    /**
     * Adds to {@code elements} every member the bot uses of a broken type no rule names. javac reports a type the
     * new jars lack at the type's name, so {@code Finder.untilFind(…)} breaks as {@code Finder}; each member is still
     * an element of its own, which a rule — or the last resort's default — can stand for. A type a rule names is
     * left whole: its rule stands for each {@code new} of it.
     */
    private static void membersOfGoneTypes(BotIndex old, Path probe, List<Rule> rules,
                                           Map<String, OldElement> elements) {
        Set<String> ruled = new HashSet<>();
        for (Rule rule : rules) if (rule.element() != null) ruled.add(rule.element().key());
        Set<String> gone = new HashSet<>();
        for (OldElement element : elements.values()) {
            if (element.kind() == Migrate.Kind.TYPE && !ruled.contains(element.key())) gone.add(element.owner());
        }
        if (gone.isEmpty()) return;
        old.read(units -> {
            units.forEach((file, unit) -> {
                if (Rules.added(file, probe)) return;
                unit.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodDeclaration method) {
                        return BotAnnotation.MIGRATE.on(method) == null;
                    }

                    @Override
                    public boolean visit(ClassInstanceCreation creation) {
                        add(creation.resolveConstructorBinding());
                        return true;
                    }

                    @Override
                    public boolean visit(SimpleName name) {
                        if (!name.isDeclaration()) add(name.resolveBinding());
                        return false;
                    }

                    private void add(IBinding binding) {
                        if (binding == null) return;
                        OldElement.of(binding)
                                .filter(element -> element.kind() != Migrate.Kind.TYPE && gone.contains(element.owner()))
                                .ifPresent(element -> elements.putIfAbsent(element.key(), element));
                    }
                });
            });
            return null;
        });
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
            IBinding binding = bindingOf(at);
            // The JDK does not move under a bot: a call of it that stopped compiling broke on what it was given.
            Optional<OldElement> element = binding == null ? Optional.empty()
                    : OldElement.of(binding).filter(found -> !found.jdk());
            if (element.isPresent()) return element;
            if (at instanceof MethodDeclaration) return Optional.empty();
        }
        return Optional.empty();
    }
}
