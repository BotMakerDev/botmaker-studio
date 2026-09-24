package com.botmaker.studio.core.render;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PatternInstanceofExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;

import java.util.Set;

/**
 * What a block looks like from across the canvas: the outline it is drawn with, whatever it says inside.
 *
 * <p>This is the half of block styling that is <b>not</b> colour. A block's colour follows from what it does
 * ({@code BlockCategory}); its shape follows from what it <em>is</em> in the program — a line that runs, a
 * line that holds other lines, a value, a yes/no value, the definition everything else sits in. Five shapes
 * cover every block, which is what lets {@code blocks.css} style ~40 block classes with one rule per shape
 * rather than one per class: a block added tomorrow gets the right outline without a line of CSS, because
 * its shape is worked out here from the syntax tree it was built from. See
 * {@code docs/refactor/37-block-styling.md} for how each one is drawn.
 *
 * <p>{@link #NONE} is not a shape but its absence: a body (a list of statements has no outline of its own —
 * the block holding it draws one) and the class card.
 */
public enum BlockShape {
    /** No outline of its own: a statement list, the class card. Gets neither {@code block} nor a shape class. */
    NONE("none", null),
    /** A statement that runs and is done: a declaration, an assignment, a print, a call. */
    STACK("stack", "shape-stack"),
    /** A statement that holds statements: a loop, an if, a switch. Drawn as a C around its body. */
    C_BLOCK("c-block", "shape-c"),
    /** A value: a number, a name, a sum, a call whose result is used. Round-ended. */
    REPORTER("reporter", "shape-reporter"),
    /** A yes/no value: a comparison, {@code &&}, {@code !}, {@code instanceof}. Square-ended, so it reads as a condition. */
    BOOLEAN("boolean", "shape-boolean"),
    /** A definition the program's lines sit in: a method, a constructor, a {@code static} block. */
    HAT("hat", "shape-hat");

    private static final Set<InfixExpression.Operator> BOOLEAN_OPERATORS = Set.of(
            InfixExpression.Operator.LESS, InfixExpression.Operator.GREATER,
            InfixExpression.Operator.LESS_EQUALS, InfixExpression.Operator.GREATER_EQUALS,
            InfixExpression.Operator.EQUALS, InfixExpression.Operator.NOT_EQUALS,
            InfixExpression.Operator.CONDITIONAL_AND, InfixExpression.Operator.CONDITIONAL_OR);

    private final String id;
    private final String styleClass;

    BlockShape(String id, String styleClass) {
        this.id = id;
        this.styleClass = styleClass;
    }

    /** A stable name, for the styling doc and for tests. */
    public String id() {
        return id;
    }

    /** The CSS class a block of this shape carries, or {@code null} for {@link #NONE}. */
    public String styleClass() {
        return styleClass;
    }

    /**
     * The shape of a value, read off the expression it was built from: {@link #BOOLEAN} when the expression
     * can only be a yes/no, {@link #REPORTER} otherwise.
     *
     * <p>Syntax first, because it needs no bindings and a canvas is often drawn from a tree parsed without
     * them: a comparison, {@code &&}/{@code ||}, {@code !}, {@code instanceof} and {@code true}/{@code false}
     * are boolean whatever they compare. Only then the binding, when there is one — which is what makes a
     * call to a {@code boolean} method a condition-shaped block. A parenthesised value is the shape of what is
     * inside it.
     */
    public static BlockShape ofValue(ASTNode node) {
        if (node instanceof ParenthesizedExpression parens) return ofValue(parens.getExpression());
        if (node instanceof BooleanLiteral
                || node instanceof InstanceofExpression
                || node instanceof PatternInstanceofExpression) {
            return BOOLEAN;
        }
        if (node instanceof PrefixExpression prefix && prefix.getOperator() == PrefixExpression.Operator.NOT) {
            return BOOLEAN;
        }
        if (node instanceof InfixExpression infix && BOOLEAN_OPERATORS.contains(infix.getOperator())) {
            return BOOLEAN;
        }
        if (node instanceof Expression expression) {
            ITypeBinding type = expression.resolveTypeBinding();
            if (type != null && isBoolean(type.getQualifiedName())) return BOOLEAN;
        }
        return REPORTER;
    }

    private static boolean isBoolean(String qualifiedName) {
        return "boolean".equals(qualifiedName) || "java.lang.Boolean".equals(qualifiedName);
    }
}
