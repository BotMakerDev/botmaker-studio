package com.botmaker.studio.project.source;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * Which class a name written in a bot's source means, decided the way javac decides it when no binding is
 * at hand.
 *
 * <p><b>What this replaced (2026-09-24)</b> is a suffix match: {@code name.endsWith("." + simple)}, which
 * took any {@code Param} from any package for the contract's. The rule here is the Java Language
 * Specification's, §6.4.1 and §7.5: a simple name means the class a single-type import names, else a class
 * of the unit's own package, else one an on-demand import reaches, else one of {@code java.lang}. A
 * qualified name means exactly what it spells. Nothing is inferred from letter case.
 *
 * <p><b>A binding answers first wherever there is one</b> ({@link BotAnnotation#marks}); this is the answer
 * for a unit parsed without a classpath — an edit on text just handed over, a test, a project whose pom does
 * not resolve. Where the two could differ (a nested class shadowing an import) the binding is right and this
 * is conservative: it answers {@code false}.
 */
public final class SourceNames {

    private SourceNames() {}

    /** Whether {@code written} names the class whose canonical name is {@code canonical}. */
    public static boolean refersTo(Name written, String canonical) {
        if (written == null || canonical == null || canonical.isEmpty()) return false;
        if (written instanceof QualifiedName qualified) return qualified.getFullyQualifiedName().equals(canonical);
        String simple = ((SimpleName) written).getIdentifier();
        int dot = canonical.lastIndexOf('.');
        String own = dot < 0 ? canonical : canonical.substring(dot + 1);
        if (!own.equals(simple)) return false;
        String owner = dot < 0 ? "" : canonical.substring(0, dot);
        CompilationUnit unit = unitOf(written);
        if (unit == null) return false;

        boolean onDemand = false;
        for (Object each : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            if (declaration.isStatic()) continue;
            String imported = declaration.getName().getFullyQualifiedName();
            if (declaration.isOnDemand()) {
                onDemand |= imported.equals(owner);
            } else if (imported.equals(canonical)) {
                return true;
            } else if (imported.endsWith("." + simple)) {
                // A single-type import of another class with this simple name shadows everything below it.
                return false;
            }
        }
        String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
        return pkg.equals(owner) || onDemand || owner.equals("java.lang");
    }

    private static CompilationUnit unitOf(ASTNode node) {
        return node.getRoot() instanceof CompilationUnit unit ? unit : null;
    }
}
