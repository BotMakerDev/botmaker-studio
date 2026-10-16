package com.botmaker.studio.migrate;

import com.botmaker.studio.nav.Uses;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every use of an old element in the bot, found by binding in the bot parsed against the old jars
 * (2026-10-16), through the one finder of uses ({@link Uses}). The bot's own {@code @Migrate} rules are not
 * searched: they are written against the new API, and a rule's own body is never one of its uses.
 */
public final class Sites {

    private Sites() {}

    /**
     * One use.
     *
     * @param where the declaration it is in, as the bot writes it ({@code Screens.HOME}); the class when it is in
     *              none
     */
    public record Site(Path file, OldElement element, Uses.Shape shape, int start, int length, int line,
                       String where) {

        static Site of(Path file, CompilationUnit unit, OldElement element, Uses.Found found) {
            ASTNode node = found.node();
            return new Site(file, element, found.shape(), node.getStartPosition(), node.getLength(),
                    unit.getLineNumber(node.getStartPosition()), Sites.where(node));
        }
    }

    /** Every use in {@code old} of each element of {@code elements}, by key; the probe source is not searched. */
    static List<Site> in(BotIndex old, Map<String, OldElement> elements, Path probe) {
        if (elements.isEmpty()) return List.of();
        return old.read(units -> {
            List<Site> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                if (file.equals(probe)) return;
                for (Uses.Found found : Uses.in(unit, elements.keySet(), true)) {
                    out.add(Site.of(file, unit, elements.get(found.key()), found));
                }
            });
            return List.copyOf(out);
        });
    }

    /** {@code Class.member} of the declaration around {@code node}, or the class alone. */
    static String where(ASTNode node) {
        String member = null;
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (member == null && at instanceof MethodDeclaration method) member = method.getName().getIdentifier();
            if (member == null && at instanceof FieldDeclaration field && !field.fragments().isEmpty()) {
                member = ((VariableDeclarationFragment) field.fragments().getFirst()).getName().getIdentifier();
            }
            if (at instanceof AbstractTypeDeclaration type && !(at.getParent() instanceof BodyDeclaration)) {
                return type.getName().getIdentifier() + (member == null ? "" : "." + member);
            }
        }
        return member == null ? "" : member;
    }
}
