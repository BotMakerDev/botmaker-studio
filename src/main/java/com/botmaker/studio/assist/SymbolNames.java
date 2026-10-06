package com.botmaker.studio.assist;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A declaration of the bot as an assistant names it: {@code Collect} (a type), {@code Collect.body} (a method,
 * field or enum constant of it) or {@code Collect.body.count} (a parameter or local of that method). What the
 * name is found as is the declaration's name node, which is what {@code nav/Refactor} and {@code nav/Usages}
 * start from — so a model names a thing and never an offset.
 */
public final class SymbolNames {

    private SymbolNames() {}

    /** The declaration's name in {@code file}, starting at {@code start}; {@code what} says what it is. */
    public record Located(Path file, int start, String what) {}

    /**
     * The one declaration {@code symbol} names in {@code units}.
     *
     * @throws IllegalArgumentException with a sentence, when it names none or several
     */
    public static Located locate(Map<Path, CompilationUnit> units, String symbol) {
        String[] parts = symbol == null ? new String[0] : symbol.strip().replace("()", "").split("\\.");
        if (parts.length == 0 || parts.length > 3 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("Name it Type, Type.member or Type.method.local — not " + symbol + ".");
        }
        List<Located> found = new ArrayList<>();
        units.forEach((file, unit) -> {
            if (unit == null) return;
            unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(org.eclipse.jdt.core.dom.TypeDeclaration type) {
                    return type(file, type);
                }

                @Override
                public boolean visit(org.eclipse.jdt.core.dom.EnumDeclaration type) {
                    return type(file, type);
                }

                @Override
                public boolean visit(org.eclipse.jdt.core.dom.RecordDeclaration type) {
                    return type(file, type);
                }

                private boolean type(Path file, AbstractTypeDeclaration type) {
                    if (!type.getName().getIdentifier().equals(parts[0])) return true;
                    if (parts.length == 1) {
                        found.add(new Located(file, type.getName().getStartPosition(), "the type " + parts[0]));
                    } else {
                        members(file, type, parts, found);
                    }
                    return true;
                }
            });
        });
        if (found.size() == 1) return found.getFirst();
        if (found.isEmpty()) throw new IllegalArgumentException("The bot declares no " + symbol + ".");
        throw new IllegalArgumentException(symbol + " names " + found.size() + " declarations (overloads, or "
                + "classes of one name); Studio cannot tell which.");
    }

    private static void members(Path file, AbstractTypeDeclaration type, String[] parts, List<Located> found) {
        String member = parts[1];
        for (Object each : type.bodyDeclarations()) {
            BodyDeclaration declaration = (BodyDeclaration) each;
            if (declaration instanceof MethodDeclaration method && method.getName().getIdentifier().equals(member)) {
                if (parts.length == 2) {
                    found.add(new Located(file, method.getName().getStartPosition(), "the method " + member + "()"));
                } else {
                    locals(file, method, parts[2], found);
                }
            } else if (parts.length == 2 && declaration instanceof FieldDeclaration field) {
                for (Object fragment : field.fragments()) {
                    SimpleName name = ((VariableDeclarationFragment) fragment).getName();
                    if (name.getIdentifier().equals(member)) {
                        found.add(new Located(file, name.getStartPosition(), "the field " + member));
                    }
                }
            }
        }
        if (parts.length == 2 && type instanceof org.eclipse.jdt.core.dom.EnumDeclaration enumType) {
            for (Object each : enumType.enumConstants()) {
                SimpleName name = ((EnumConstantDeclaration) each).getName();
                if (name.getIdentifier().equals(member)) {
                    found.add(new Located(file, name.getStartPosition(), "the constant " + member));
                }
            }
        }
    }

    private static void locals(Path file, MethodDeclaration method, String local, List<Located> found) {
        int before = found.size();
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SingleVariableDeclaration declaration) {
                add(declaration.getName());
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                add(fragment.getName());
                return true;
            }

            private void add(SimpleName name) {
                // The first declaration of the name: a local declared twice in sibling blocks is two names, and
                // the first is the one an assistant reading the method from the top means.
                if (found.size() == before && name.getIdentifier().equals(local)) {
                    found.add(new Located(file, name.getStartPosition(),
                            "the local " + local + " of " + method.getName().getIdentifier() + "()"));
                }
            }
        });
    }
}
