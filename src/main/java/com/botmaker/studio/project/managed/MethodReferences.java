package com.botmaker.studio.project.managed;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.TypeMethodReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
 * <p>Syntax only, no bindings, like every reader of a bot's sources: a bot whose dependencies do not resolve
 * still lists its targets. A reference names its class by simple name ({@code Collect}) or qualified
 * ({@code com.x.Collect}); either is resolved to the first file of the bot that declares a type of that simple
 * name. One that resolves nowhere — a library's method, a class not written yet — is still listed, with no
 * file, so the caller can say why it cannot open it. A lambda is not a reference and is not listed: there is
 * no method to open.
 */
public final class MethodReferences {

    /**
     * One method a {@code @Managed} value points at.
     *
     * @param label     as the value writes it, {@code Collect::body}
     * @param className the class as written before {@code ::}, simple or qualified
     * @param method    the method's name
     * @param file      the bot's file declaring the class, or {@code null} when none does
     */
    public record Target(String label, String className, String method, Path file) {

        /** The class's simple name — what a type declaration in the bot's sources is matched on. */
        public String simpleClassName() {
            return className.substring(className.lastIndexOf('.') + 1);
        }
    }

    private MethodReferences() {}

    /** Every target the bot's {@code @Managed} values reference, in the order the walk meets them, each once. */
    public static List<Target> scan(ProjectConfig config, ProjectState state) {
        if (config == null) return List.of();
        Map<String, Target> found = new LinkedHashMap<>();
        Map<String, Path> declared = new HashMap<>();
        BotSources.scan(config, state, (file, source) -> {
            CompilationUnit unit = JavaParameterSource.parse(source);
            for (Target target : read(unit)) found.putIfAbsent(target.label(), target);
            declaredTypes(unit).forEach(name -> declared.putIfAbsent(name, file));
        });
        List<Target> out = new ArrayList<>();
        for (Target target : found.values()) {
            out.add(new Target(target.label(), target.className(), target.method(),
                    declared.get(target.simpleClassName())));
        }
        return List.copyOf(out);
    }

    /** The target labelled {@code label}, or {@code null}. */
    public static Target find(ProjectConfig config, ProjectState state, String label) {
        if (label == null) return null;
        for (Target target : scan(config, state)) {
            if (target.label().equals(label)) return target;
        }
        return null;
    }

    /**
     * The references inside {@code source}'s {@code @Managed} methods, with no file — pure, over one source.
     * The whole body is walked, not only a single {@code return}: a value written by hand still points at the
     * methods it points at.
     */
    public static List<Target> read(String source) {
        return read(JavaParameterSource.parse(source));
    }

    private static List<Target> read(CompilationUnit unit) {
        List<Target> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (JavaManagedSource.managedAnnotation(method) == null || method.getBody() == null) return false;
                method.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(ExpressionMethodReference reference) {
                        if (reference.getExpression() instanceof Name owner) {
                            add(out, owner.getFullyQualifiedName(), reference.getName().getIdentifier());
                        }
                        return false;
                    }

                    @Override
                    public boolean visit(TypeMethodReference reference) {
                        if (reference.getType() instanceof SimpleType owner) {
                            add(out, owner.getName().getFullyQualifiedName(), reference.getName().getIdentifier());
                        }
                        return false;
                    }
                });
                return false;
            }
        });
        return List.copyOf(out);
    }

    private static void add(List<Target> out, String className, String method) {
        String label = className + "::" + method;
        if (out.stream().noneMatch(t -> t.label().equals(label))) {
            out.add(new Target(label, className, method, null));
        }
    }

    /** The simple name of every type {@code unit} declares, nested ones included. */
    private static List<String> declaredTypes(CompilationUnit unit) {
        List<String> names = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof AbstractTypeDeclaration type) names.add(type.getName().getIdentifier());
            }
        });
        return names;
    }
}
