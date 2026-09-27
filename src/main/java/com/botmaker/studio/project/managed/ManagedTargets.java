package com.botmaker.studio.project.managed;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeMethodReference;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bot's own methods that its plugins' values point at — {@code Collect::body} inside the flow — and the
 * file each one is written in.
 *
 * <p><b>This is where the HUD's activity list comes from</b> (2026-09-23). It came from
 * {@code Activities.define("Mining", ctx -> …)} calls until SDK 2.0 deleted {@code define}: an activity's work
 * is now a method reference in the flow's {@code @Managed} value, and a reference is the one thing a host can
 * follow without knowing whose value it is in. No plugin's name is spelled here, so any plugin whose value
 * holds references gets the same navigation.
 *
 * <p><b>By binding, since 2026-09-27</b> ({@link BotIndex}). A reference's file is the one declaring the method
 * javac resolves it to — a nested class, a class of the same simple name in another package, a qualified
 * {@code com.x.Collect::body} all land where the compiler says. It was the first file declaring a type of the
 * written simple name until then, which picked the wrong one of two {@code Collect}s and followed a reference
 * that did not compile. One that resolves nowhere in the bot — a library's method, a class not written yet — is
 * still listed, with no file, so the caller can say why it cannot open it. A lambda is not a reference and is
 * not listed: there is no method to open.
 */
public final class ManagedTargets {

    /**
     * One method a {@code @Managed} value points at.
     *
     * @param label     as the value writes it, {@code Collect::body}
     * @param className the class the reference resolves to, qualified, or as written before {@code ::} when
     *                  it resolves to none
     * @param method    the method's name
     * @param file      the bot's file declaring the method, or {@code null} when none does
     */
    public record Target(String label, String className, String method, Path file) {

        /** The class's simple name, for a sentence about it. */
        public String simpleClassName() {
            return className.substring(className.lastIndexOf('.') + 1);
        }
    }

    private ManagedTargets() {}

    /** Every target the bot's {@code @Managed} values reference, in the order the walk meets them, each once. */
    public static List<Target> scan(ProjectConfig config, ProjectState state) {
        if (config == null) return List.of();
        return over(BotIndex.of(config, state));
    }

    /** The same, over {@code index}. */
    public static List<Target> over(BotIndex index) {
        return index.read(units -> {
            Map<String, Target> found = new LinkedHashMap<>();
            units.forEach((file, unit) -> unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration method) {
                    if (JavaManagedSource.managedAnnotation(method) == null || method.getBody() == null) {
                        return false;
                    }
                    // The whole body, not only a single return: a value written by hand still points at the
                    // methods it points at.
                    method.getBody().accept(new ASTVisitor() {
                        @Override
                        public boolean visit(ExpressionMethodReference reference) {
                            add(reference, reference.getExpression(), reference.getName());
                            return false;
                        }

                        @Override
                        public boolean visit(TypeMethodReference reference) {
                            add(reference, reference.getType(), reference.getName());
                            return false;
                        }

                        private void add(MethodReference reference, ASTNode owner, SimpleName name) {
                            String written = owner.toString();
                            String label = written + "::" + name.getIdentifier();
                            if (found.containsKey(label)) return;
                            IMethodBinding binding = reference.resolveMethodBinding();
                            if (binding == null) {
                                found.put(label, new Target(label, written, name.getIdentifier(), null));
                                return;
                            }
                            IMethodBinding declared = binding.getMethodDeclaration();
                            found.put(label, new Target(label,
                                    declared.getDeclaringClass().getErasure().getQualifiedName(),
                                    name.getIdentifier(), declaring(units, declared.getKey())));
                        }
                    });
                    return false;
                }
            }));
            return List.copyOf(found.values());
        });
    }

    /** The target labelled {@code label}, or {@code null}. */
    public static Target find(ProjectConfig config, ProjectState state, String label) {
        if (label == null) return null;
        for (Target target : scan(config, state)) {
            if (target.label().equals(label)) return target;
        }
        return null;
    }

    /** The file whose unit declares the binding {@code key}, or {@code null} when none of the bot's does. */
    private static Path declaring(Map<Path, CompilationUnit> units, String key) {
        for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
            if (entry.getValue().findDeclaringNode(key) != null) return entry.getKey();
        }
        return null;
    }
}
