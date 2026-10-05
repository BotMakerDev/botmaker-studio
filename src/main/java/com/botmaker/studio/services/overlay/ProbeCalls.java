package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.managed.ManagedConstants;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which statement a declared {@link com.botmaker.plugin.api.overlay.Probe} answers for, and what it is given —
 * read off the editor's tree on the FX thread, so the {@link ProbeEngine} probes off it without touching the
 * tree again.
 *
 * <p>A statement's probe is the first call, in source order, of the statement's own expression — a call
 * statement, a local's initializer, an {@code if}'s or a loop's condition, a {@code return} — whose binding is a
 * declared probe's call. Not a call inside a lambda, a nested block or a branch: those are rows of their own.
 * The call is matched as the contract matches it, by declaring class, name and parameter types by name, never
 * by {@code Class} identity.
 */
public final class ProbeCalls {

    private ProbeCalls() {}

    /** A declared probe, with the plugin that declared it. */
    public record Declared(String pluginId, OverlayPart.ProbedCall probed) {}

    /**
     * One argument as the bot writes it, and the initializer of the {@code @Managed} constant it names when it
     * names one: {@code Pictures.ORE} reads as what {@code Pictures.java} says it holds.
     *
     * @param text     the argument's Java
     * @param constant the named constant's initializer, or null
     */
    public record Argument(String text, String constant) {}

    /**
     * One statement's probe, ready to run off the FX thread.
     *
     * @param key       what the answer is for, the caller's: a row of the tree it was read from
     * @param declared  the probe
     * @param arguments the call's arguments, in order, as written
     */
    public record Job(Object key, Declared declared, List<Argument> arguments) {

        public Job {
            arguments = List.copyOf(arguments);
        }

        /** Whether the call acts — a click — so its probe says what it would do, not a value. */
        public boolean acting() {
            return !declared.probed().probe().readsOnly();
        }
    }

    /** Every probe the loaded plugins declare, in plugin order. */
    public static List<Declared> declared(List<PluginHost.OwnedOverlay> parts) {
        List<Declared> out = new ArrayList<>();
        for (PluginHost.OwnedOverlay owned : parts) {
            for (OverlayPart.ProbedCall probed : owned.part().probes()) out.add(new Declared(owned.pluginId(), probed));
        }
        return out;
    }

    /** The probe for the call {@code statement} makes, keyed {@code key}; empty when no declared probe matches. */
    public static Optional<Job> job(Object key, Statement statement, List<Declared> declared,
                                    ManagedConstants.Lookup constants) {
        if (statement == null || declared.isEmpty()) return Optional.empty();
        for (Expression root : roots(statement)) {
            Optional<Job> found = first(key, root, declared, constants);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** The statement's own expressions, the ones a row shows; never a body. */
    private static List<Expression> roots(Statement statement) {
        List<Expression> roots = new ArrayList<>();
        switch (statement) {
            case ExpressionStatement s -> roots.add(s.getExpression());
            case VariableDeclarationStatement s -> {
                for (Object each : s.fragments()) {
                    Expression init = ((VariableDeclarationFragment) each).getInitializer();
                    if (init != null) roots.add(init);
                }
            }
            case IfStatement s -> roots.add(s.getExpression());
            case WhileStatement s -> roots.add(s.getExpression());
            case DoStatement s -> roots.add(s.getExpression());
            case ReturnStatement s -> {
                if (s.getExpression() != null) roots.add(s.getExpression());
            }
            default -> {
            }
        }
        return roots;
    }

    private static Optional<Job> first(Object key, Expression root, List<Declared> declared,
                                       ManagedConstants.Lookup constants) {
        Job[] found = {null};
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation call) {
                if (found[0] != null) return false;
                IMethodBinding binding = call.resolveMethodBinding();
                for (Declared d : declared) {
                    if (binding != null && sameCall(binding, d.probed().call())) {
                        found[0] = new Job(key, d, arguments(call, constants));
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean visit(LambdaExpression lambda) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration body) {
                return false;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    private static List<Argument> arguments(MethodInvocation call, ManagedConstants.Lookup constants) {
        List<Argument> out = new ArrayList<>();
        for (Object each : call.arguments()) {
            Expression argument = (Expression) each;
            String constant = argument instanceof QualifiedName name && constants != null
                    ? constants.constant(name).map(ManagedConstants.Constant::initializer).orElse(null)
                    : null;
            out.add(new Argument(argument.toString(), constant));
        }
        return out;
    }

    /** Whether {@code binding} is {@code call}: declaring class, name and parameter types, all by name. */
    static boolean sameCall(IMethodBinding binding, Executable call) {
        IMethodBinding declaration = binding.getMethodDeclaration();
        ITypeBinding owner = declaration.getDeclaringClass();
        if (owner == null || !call.getDeclaringClass().getName().equals(owner.getErasure().getBinaryName())) {
            return false;
        }
        if (!call.getName().equals(declaration.getName())) return false;
        ITypeBinding[] params = declaration.getParameterTypes();
        Class<?>[] expected = call.getParameterTypes();
        if (params.length != expected.length) return false;
        for (int i = 0; i < params.length; i++) {
            if (!expected[i].getName().equals(typeName(params[i]))) return false;
        }
        return true;
    }

    /** A parameter type's name as {@link Class#getName} spells it — a primitive's keyword, else its binary name. */
    private static String typeName(ITypeBinding type) {
        ITypeBinding erased = type.getErasure();
        if (erased.isPrimitive()) return erased.getName();
        return erased.getBinaryName();
    }
}
