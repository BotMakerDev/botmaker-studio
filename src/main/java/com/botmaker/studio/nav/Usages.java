package com.botmaker.studio.nav;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Find Usages (2026-09-26): every name in a unit that resolves to one binding. Pure over one
 * {@link CompilationUnit}; the Usages tab runs it over each of the bot's sources and groups what comes back.
 *
 * <p>Bindings are compared by {@link #keyOf key}, and a generic member by its declaration's key, so
 * {@code List<String>.add} and {@code List<Integer>.add} are one method — the question is "where is this
 * declaration used", not "where is this instantiation".
 */
public final class Usages {

    private Usages() {}

    /**
     * One occurrence.
     *
     * @param file        the source it is in
     * @param start       its offset in that source, which finds the block once the file is open
     * @param line        1-based
     * @param enclosing   the function it sits in ({@code main}), or the type for a field initialiser
     * @param text        the source line, trimmed
     * @param declaration whether this is the declaration itself rather than a use
     */
    public record Usage(Path file, int start, int line, String enclosing, String text, boolean declaration) {}

    /** What a binding is compared by. Null for a binding that has no stable key. */
    public static String keyOf(IBinding binding) {
        IBinding declared = switch (binding) {
            case IMethodBinding m -> m.getMethodDeclaration();
            case IVariableBinding v -> v.getVariableDeclaration();
            case ITypeBinding t -> t.getErasure();
            case null -> null;
            default -> binding;
        };
        return declared == null ? null : declared.getKey();
    }

    /** Every name in {@code cu} bound to {@code key}, in source order. */
    public static List<Usage> in(Path file, String source, CompilationUnit cu, String key) {
        List<Usage> out = new ArrayList<>();
        if (cu == null || key == null) return out;
        String[] lines = source.split("\n", -1);
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                IBinding binding = name.resolveBinding();
                if (binding == null || !key.equals(keyOf(binding))) return false;
                int line = cu.getLineNumber(name.getStartPosition());
                String text = line >= 1 && line <= lines.length ? lines[line - 1].trim() : "";
                out.add(new Usage(file, name.getStartPosition(), line, enclosing(name), text,
                        name.isDeclaration()));
                return false;
            }
        });
        return out;
    }

    private static String enclosing(ASTNode node) {
        for (ASTNode n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration m) return m.getName().getIdentifier();
            if (n instanceof AbstractTypeDeclaration t) return t.getName().getIdentifier();
        }
        return "";
    }
}
