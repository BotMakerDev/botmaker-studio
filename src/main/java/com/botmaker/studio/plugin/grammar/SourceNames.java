package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * Which class a name written in a bot's source means.
 *
 * <p><b>What this replaced (2026-09-24)</b> is a suffix match — {@code canonical.endsWith("." + written)} —
 * which took any {@code Param}, any {@code Duration}, from any package, for the one a plugin declares. The
 * answer here comes, in order, from:
 *
 * <ol>
 *   <li>the <b>binding</b>, when the unit was parsed against the project's classpath: final;</li>
 *   <li>the <b>unit's imports</b>, the way the Java Language Specification resolves a simple name (§6.4.1,
 *       §7.5): a single-type import, the unit's own package, an on-demand import, {@code java.lang}. A
 *       qualified name means what it spells, or its qualifier resolves to the enclosing class
 *       ({@code Flow.Activity});</li>
 *   <li>and, for an expression parsed <b>on its own</b>, detached from any file, its simple name — the one
 *       case with nothing to resolve against. It is the caller's to avoid, by keeping a value's expression
 *       in the unit it came from.</li>
 * </ol>
 */
public final class SourceNames {

    private SourceNames() {}

    /** Whether {@code written} names the class whose canonical name is {@code canonical}. */
    public static boolean refersTo(Name written, String canonical) {
        if (written == null || canonical == null || canonical.isEmpty()) return false;
        IBinding binding = written.resolveBinding();
        if (binding instanceof ITypeBinding type && !type.isRecovered()) {
            return canonical.equals(type.getErasure().getQualifiedName());
        }
        int dot = canonical.lastIndexOf('.');
        String own = dot < 0 ? canonical : canonical.substring(dot + 1);
        String owner = dot < 0 ? "" : canonical.substring(0, dot);
        if (written instanceof QualifiedName qualified) {
            if (qualified.getFullyQualifiedName().equals(canonical)) return true;
            return qualified.getName().getIdentifier().equals(own) && !owner.isEmpty()
                    && refersTo(qualified.getQualifier(), owner);
        }
        String simple = ((SimpleName) written).getIdentifier();
        if (!own.equals(simple)) return false;
        CompilationUnit unit = written.getRoot() instanceof CompilationUnit root ? root : null;
        if (unit == null || JavaExpressions.detached(written)) return true;
        // A member type is in scope inside the type that declares it, and inside every type nested in that one.
        for (ASTNode parent = written.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof AbstractTypeDeclaration enclosing && canonicalOf(enclosing).equals(owner)) {
                return true;
            }
        }

        boolean onDemand = false;
        for (Object each : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            if (declaration.isStatic()) continue;
            String imported = declaration.getName().getFullyQualifiedName();
            if (declaration.isOnDemand()) {
                onDemand |= imported.equals(owner);
            } else if (imported.equals(canonical)) {
                return true;
            } else if (lastSegment(imported).equals(simple)) {
                // A single-type import of another class with this simple name shadows everything below it.
                return false;
            }
        }
        return packageOf(unit).equals(owner) || onDemand || owner.equals("java.lang");
    }

    /**
     * The canonical name of a type declared in a unit — {@code com.bot.Outer.Inner} for a member type — from
     * its binding when there is one, and otherwise from the package and the enclosing declarations. A local or
     * anonymous class has no canonical name; it answers its simple name, which nothing matches.
     */
    public static String canonicalOf(AbstractTypeDeclaration declaration) {
        ITypeBinding binding = declaration.resolveBinding();
        if (binding != null && !binding.isRecovered() && binding.getQualifiedName() != null
                && !binding.getQualifiedName().isEmpty()) {
            return binding.getQualifiedName();
        }
        StringBuilder name = new StringBuilder(declaration.getName().getIdentifier());
        ASTNode parent = declaration.getParent();
        while (parent instanceof AbstractTypeDeclaration outer) {
            name.insert(0, outer.getName().getIdentifier() + ".");
            parent = outer.getParent();
        }
        if (!(parent instanceof CompilationUnit)) return declaration.getName().getIdentifier();
        String pkg = packageOf(declaration);
        return pkg.isEmpty() ? name.toString() : pkg + "." + name;
    }

    /** The unit's package, {@code ""} for the default one. */
    public static String packageOf(ASTNode node) {
        CompilationUnit unit = node != null && node.getRoot() instanceof CompilationUnit root ? root : null;
        return unit == null || unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
    }

    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
