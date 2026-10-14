package com.botmaker.studio.plugin.grammar;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A value's classes written by their simple names, each with its import (2026-10-13).
 *
 * <p>A part a plugin hands over as the Java it is written as — a flow card's body, {@code Research::body} — is
 * kept as written ({@link JavaValue#kept}) and needs no import in the file it came from. Written into another
 * file it may: {@code Flows.java} sits in {@code plugins/sdk}, the body's class in the bot's own package, and the
 * bot stopped compiling. And a part spelled in full so that it needs none, {@code
 * com.botmaker.sdk.api.flow.ActivityBody.none()}, is the full name the bot's author should not have to read.
 */
public final class ShortNames {

    private ShortNames() {}

    /**
     * {@code value} with each full class name — {@code com.x.Type}, its package the lowercase segments before the
     * first capitalised one — written {@code Type} and imported, and each simple name of one of the bot's
     * top-level classes ({@code botTypes}, canonical names) imported. A simple name the value already imports
     * another class by, or two of the bot's classes share, is left as it is.
     */
    public static JavaValue imported(JavaValue value, Collection<String> botTypes) {
        Map<String, String> imported = new LinkedHashMap<>();
        for (String each : value.imports()) imported.put(simple(each), each);
        Map<String, String> bot = new HashMap<>();
        Set<String> shared = new LinkedHashSet<>();
        for (String canonical : botTypes) {
            // The bot's own are known to be classes: a one-segment package is still one.
            if (!isTopLevel(canonical, 2)) continue;
            if (bot.putIfAbsent(simple(canonical), canonical) != null) shared.add(simple(canonical));
        }
        shared.forEach(bot::remove);

        Expression node = (Expression) ASTNode.copySubtree(AST.newAST(AST.getJLSLatest(), false), value.node());
        Set<String> imports = new LinkedHashSet<>(value.imports());
        List<QualifiedName> full = new ArrayList<>();
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(QualifiedName name) {
                String written = name.getFullyQualifiedName();
                if (isTopLevel(written, 3) && claim(imported, simple(written), written)) {
                    full.add(name);
                    return false;
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName name) {
                String known = bot.get(name.getIdentifier());
                if (known != null && namesAType(name)) claim(imported, name.getIdentifier(), known);
                return false;
            }
        });
        for (QualifiedName name : full) {
            SimpleName now = node.getAST().newSimpleName(name.getName().getIdentifier());
            if (name == node) node = now;
            else replace(name, now);
        }
        imports.addAll(imported.values());
        // Shown as written while nothing in it moved; a name made short is shown short.
        return full.isEmpty() ? new JavaValue(node, List.copyOf(imports), value.source())
                : JavaValue.built(node, imports);
    }

    /**
     * {@code node}, a tree not yet in a file, with each full class name ({@code com.x.Type}, as
     * {@link #imported} reads one) written {@code Type} where {@code free} says that simple name may stand for it,
     * each such class handed to {@code imports}. Changed in place; the root is answered, since it may be replaced.
     */
    public static Expression shortened(Expression node, Predicate<String> free, Consumer<String> imports) {
        List<QualifiedName> full = new ArrayList<>();
        node.accept(new ASTVisitor() {
            @Override
            public boolean visit(QualifiedName name) {
                String written = name.getFullyQualifiedName();
                if (isTopLevel(written, 3) && free.test(written)) {
                    full.add(name);
                    return false;
                }
                return true;
            }
        });
        Expression root = node;
        for (QualifiedName name : full) {
            imports.accept(name.getFullyQualifiedName());
            SimpleName now = node.getAST().newSimpleName(name.getName().getIdentifier());
            if (name == root) root = now;
            else replace(name, now);
        }
        return root;
    }

    /** Whether {@code simple} may stand for {@code canonical}: free, or already that class. Takes it when free. */
    private static boolean claim(Map<String, String> imported, String simple, String canonical) {
        return imported.putIfAbsent(simple, canonical) == null || imported.get(simple).equals(canonical);
    }

    /**
     * Whether {@code name} stands where a class is written first: a method reference's or a call's target, or the
     * first segment of a qualified name — never a call's method, a field, or a constant.
     */
    private static boolean namesAType(SimpleName name) {
        StructuralPropertyDescriptor at = name.getLocationInParent();
        return at == ExpressionMethodReference.EXPRESSION_PROPERTY || at == MethodInvocation.EXPRESSION_PROPERTY
                || at == QualifiedName.QUALIFIER_PROPERTY;
    }

    /**
     * {@code pkg.Type}: lowercase segments, then exactly one capitalised one, {@code least} segments or more. A
     * name written in a value needs three: one lowercase segment is as likely a variable's field,
     * {@code settings.DEFAULT}, and is left alone.
     */
    private static boolean isTopLevel(String canonical, int least) {
        String[] segments = canonical.split("\\.");
        if (segments.length < least) return false;
        for (int i = 0; i < segments.length; i++) {
            boolean upper = !segments[i].isEmpty() && Character.isUpperCase(segments[i].charAt(0));
            if (upper != (i == segments.length - 1)) return false;
        }
        return true;
    }

    private static String simple(String canonical) {
        return canonical.substring(canonical.lastIndexOf('.') + 1);
    }

    @SuppressWarnings("unchecked")
    private static void replace(Name old, Name now) {
        ASTNode parent = old.getParent();
        StructuralPropertyDescriptor at = old.getLocationInParent();
        if (at instanceof ChildListPropertyDescriptor list) {
            List<ASTNode> siblings = (List<ASTNode>) parent.getStructuralProperty(list);
            siblings.set(siblings.indexOf(old), now);
        } else {
            parent.setStructuralProperty(at, now);
        }
    }
}
