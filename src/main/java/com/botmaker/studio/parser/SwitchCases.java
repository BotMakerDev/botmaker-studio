package com.botmaker.studio.parser;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;

import java.util.HashSet;
import java.util.Set;

/**
 * The label a new case of a switch can take without clashing with one it has: a duplicate label is a compile
 * error, and "+ case" writing {@code case 0} twice was the easy way to one.
 */
final class SwitchCases {

    private SwitchCases() {}

    /**
     * A label {@code switchExpr} does not use yet, typed after its subject: the first enum constant it has no
     * case for, the smallest free non-negative number, or {@code "caseN"} for a string. {@code null} when there
     * is none — an enum every constant of which is already a case — or the subject's type is unknown.
     */
    static Expression freshLabel(AST ast, SwitchExpression switchExpr) {
        ITypeBinding subject = switchExpr.getExpression() == null ? null
                : switchExpr.getExpression().resolveTypeBinding();
        Set<String> used = new HashSet<>();
        for (Object o : switchExpr.statements()) {
            if (!(o instanceof SwitchCase sc)) continue;
            for (Object label : sc.expressions()) used.add(labelText((ASTNode) label));
        }
        if (subject == null) return null;
        if (subject.isEnum()) {
            // Declaration order where the file declares the enum: a binding's fields come back in no promised order.
            if (switchExpr.getRoot() instanceof CompilationUnit cu
                    && cu.findDeclaringNode(subject) instanceof EnumDeclaration declaration) {
                for (Object o : declaration.enumConstants()) {
                    String constant = ((EnumConstantDeclaration) o).getName().getIdentifier();
                    if (!used.contains(constant)) return ast.newSimpleName(constant);
                }
                return null;
            }
            for (IVariableBinding field : subject.getDeclaredFields()) {
                if (field.isEnumConstant() && !used.contains(field.getName())) return ast.newSimpleName(field.getName());
            }
            return null;
        }
        String name = subject.getQualifiedName();
        if (name.equals("java.lang.String")) {
            for (int i = 1; ; i++) {
                String candidate = "case" + i;
                if (!used.contains(candidate)) {
                    StringLiteral literal = ast.newStringLiteral();
                    literal.setLiteralValue(candidate);
                    return literal;
                }
            }
        }
        if (subject.isPrimitive() || name.equals("java.lang.Integer")) {
            for (int i = 0; ; i++) {
                if (!used.contains(String.valueOf(i))) return ast.newNumberLiteral(String.valueOf(i));
            }
        }
        return null;
    }

    private static String labelText(ASTNode label) {
        return switch (label) {
            case SimpleName name -> name.getIdentifier();
            case StringLiteral literal -> literal.getLiteralValue();
            case NumberLiteral number -> number.getToken();
            default -> label.toString();
        };
    }
}
