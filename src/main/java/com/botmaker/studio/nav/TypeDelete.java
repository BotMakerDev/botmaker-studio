package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deleting a file of the bot (2026-10-13): allowed only when nothing is left pointing at what it declares.
 *
 * <p>Two checks, the first for the person, the second for the bot. The uses of every class the file declares,
 * nested ones included, from the bot's other files: a refusal lists them, each a place to go and remove it. Then
 * the bot parsed without the file: any new error refuses too, which catches what no binding names — a method
 * reference whose class went, an import of a nested type.
 *
 * <p>Whether the file is one Studio or a plugin needs (the entry point, a plugin's holder) is the caller's to
 * say: this class knows the bot's sources and nothing about who wrote them.
 */
public final class TypeDelete {

    private TypeDelete() {}

    /** Why {@code file} cannot go, or empty when deleting it leaves the bot compiling as well as before. */
    public static Optional<Refactor.Refused> refusal(BotIndex index, Path file) {
        Path at = file.toAbsolutePath().normalize();
        if (!index.sources().containsKey(at)) return Optional.empty();
        String name = at.getFileName().toString();

        List<Integer> starts = index.read(units -> typeNameStarts(units.get(at)));
        // By line: Helper.Mode.FAST is a use of Helper and of Mode, and one place to go.
        Map<String, Usages.Usage> byLine = new LinkedHashMap<>();
        for (int start : starts) {
            Refactor.uses(index, at, start).ifPresent(found -> found.stream()
                    .filter(use -> !use.file().toAbsolutePath().normalize().equals(at))
                    .forEach(use -> byLine.putIfAbsent(use.file() + ":" + use.line(), use)));
        }
        List<Usages.Usage> uses = List.copyOf(byLine.values());
        if (!uses.isEmpty()) {
            return Optional.of(new Refactor.Refused(uses.size() == 1
                    ? "One place in another file uses what " + name + " declares. Remove that use first."
                    : uses.size() + " places in other files use what " + name + " declares. Remove those uses first.",
                    uses, List.of()));
        }

        Map<Path, String> without = new LinkedHashMap<>(index.sources());
        without.remove(at);
        return index.firstNewError(index.replacing(without)).map(error -> new Refactor.Refused(
                "Deleting " + name + " would stop the bot compiling (" + error + "), so it was kept."));
    }

    /** Where each class's name starts in {@code unit}, nested ones included. */
    private static List<Integer> typeNameStarts(CompilationUnit unit) {
        List<Integer> out = new ArrayList<>();
        if (unit == null) return out;
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                return add(node);
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                return add(node);
            }

            @Override
            public boolean visit(RecordDeclaration node) {
                return add(node);
            }

            @Override
            public boolean visit(AnnotationTypeDeclaration node) {
                return add(node);
            }

            private boolean add(AbstractTypeDeclaration node) {
                out.add(node.getName().getStartPosition());
                return true;
            }
        });
        return out;
    }
}
