package com.botmaker.studio.blocks;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * What a type declaration's header says besides its name, as the source says it: {@code <T>}, {@code extends Bar},
 * {@code implements Outcome}. Part of what the type <em>is</em> — an activity is an enum that implements
 * {@code Outcome} — so a block drawing a type shows it (2026-10-12: the enum block left it out).
 */
public final class TypeClauses {

    private TypeClauses() {}

    /** {@code "Class"}, {@code "Interface"}, {@code "Enum"} or {@code "Record"}: the keyword a header leads with. */
    public static String kind(AbstractTypeDeclaration type) {
        return switch (type) {
            case TypeDeclaration declared when declared.isInterface() -> "Interface";
            case EnumDeclaration ignored -> "Enum";
            case RecordDeclaration ignored -> "Record";
            default -> "Class";
        };
    }

    /** The type parameters as written, {@code <T extends Number>}, or {@code ""} for none. */
    public static String typeParameters(AbstractTypeDeclaration type) {
        List<?> parameters = switch (type) {
            case TypeDeclaration declared -> declared.typeParameters();
            case RecordDeclaration record -> record.typeParameters();
            default -> List.of();
        };
        if (parameters.isEmpty()) return "";
        List<String> written = new ArrayList<>();
        for (Object each : parameters) written.add(each.toString());
        return "<" + String.join(", ", written) + ">";
    }

    /**
     * The inheritance clauses, each one run of text in source order: {@code extends Bar}, then
     * {@code implements A, B}. An interface's super-interfaces are what it {@code extends}. Empty when there are none.
     */
    public static List<String> inheritance(AbstractTypeDeclaration type) {
        List<String> clauses = new ArrayList<>();
        switch (type) {
            case TypeDeclaration declared when declared.isInterface() ->
                    joined(clauses, "extends", declared.superInterfaceTypes());
            case TypeDeclaration declared -> {
                if (declared.getSuperclassType() != null) {
                    clauses.add("extends " + declared.getSuperclassType());
                }
                joined(clauses, "implements", declared.superInterfaceTypes());
            }
            case EnumDeclaration declared -> joined(clauses, "implements", declared.superInterfaceTypes());
            case RecordDeclaration declared -> joined(clauses, "implements", declared.superInterfaceTypes());
            default -> { }
        }
        return List.copyOf(clauses);
    }

    private static void joined(List<String> clauses, String keyword, List<?> types) {
        if (types.isEmpty()) return;
        List<String> written = new ArrayList<>();
        for (Object each : types) written.add(each.toString());
        clauses.add(keyword + " " + String.join(", ", written));
    }
}
