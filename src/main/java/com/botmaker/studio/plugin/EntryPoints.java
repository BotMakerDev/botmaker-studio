package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.MemberEntry;
import com.botmaker.studio.parser.StatementPlacement;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BiPredicate;

/**
 * The bound plugins' entry points ({@code @EntryPoint}, the SDK's {@code Bot.runFlow}): the call in {@code main}
 * that starts a bot. A project whose {@code main} calls none never starts one, and nothing said so until
 * 2026-10-12 — a new SDK bot's {@code main} only prints, and its entry point sat in a submenu.
 */
public final class EntryPoints {

    private EntryPoints() {}

    /**
     * One entry point a plugin offers.
     *
     * @param facade the class serving it
     * @param method its name
     * @param label  what a user reads: the catalog's label ({@code Run activity flow}), else {@code Bot.runFlow}
     */
    public record Entry(FacadeEntry facade, String method, String label) {}

    /** Every entry point the bound plugins offer, in catalog order. */
    public static List<Entry> offered() {
        List<Entry> out = new ArrayList<>();
        for (FacadeEntry facade : PluginHost.menuFacades()) {
            for (String method : staticMethodNames(facade)) {
                if (!facade.offers(method) || !facade.entryPoint(method)) continue;
                String label = facade.member(method).map(MemberEntry::displayLabel)
                        .filter(shown -> !shown.equals(method)).orElse(facade.simpleName() + "." + method);
                out.add(new Entry(facade, method, label));
            }
        }
        return out;
    }

    /** Whether {@code root} calls any bound plugin's entry point. */
    public static boolean calledIn(ASTNode root) {
        return calledIn(root, (facade, method) -> PluginHost.menuFacades().stream()
                .anyMatch(each -> each.simpleName().equals(facade) && each.entryPoint(method)));
    }

    /**
     * Whether {@code root} calls a method {@code isEntry} accepts, written {@code Facade.method(…)} or
     * {@code com.x.Facade.method(…)} — the receiver names the class without a binding. A static import and a
     * helper method that calls it are not seen: the hint then shows, and the menu still leads with the entry.
     */
    static boolean calledIn(ASTNode root, BiPredicate<String, String> isEntry) {
        if (root == null) return false;
        boolean[] found = {false};
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                String receiver = switch (node.getExpression()) {
                    case SimpleName simple -> simple.getIdentifier();
                    case QualifiedName qualified -> qualified.getName().getIdentifier();
                    case null, default -> null;
                };
                if (receiver != null && isEntry.test(receiver, node.getName().getIdentifier())) found[0] = true;
                return !found[0];
            }
        });
        return found[0];
    }

    /** The method {@code node} sits in, or null outside one. */
    public static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    /** {@code unit}'s {@code public static void main(String[])}, or empty when it declares none. */
    public static Optional<MethodDeclaration> main(CompilationUnit unit) {
        if (unit == null) return Optional.empty();
        MethodDeclaration[] found = {null};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (found[0] == null && node.getBody() != null && StatementPlacement.inMain(node.getBody())) {
                    found[0] = node;
                }
                return false;
            }
        });
        return Optional.ofNullable(found[0]);
    }

    private static TreeSet<String> staticMethodNames(FacadeEntry facade) {
        TreeSet<String> names = new TreeSet<>();
        try {
            for (Method method : facade.type().getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) names.add(method.getName());
            }
        } catch (LinkageError | SecurityException unreadable) {
            // A facade whose signatures name a missing class offers no entry point here; its menu still lists it.
        }
        return names;
    }
}
