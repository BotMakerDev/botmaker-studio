package com.botmaker.studio.parser.helpers;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PatternInstanceofExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeLiteral;

/**
 * Whether an expression put where another one stood needs parentheses to keep meaning what the tree says.
 *
 * <p>{@code ASTRewrite} prints a replacement as its own tree and adds no parentheses, so a sum dropped onto
 * one side of a product — {@code a * b} with {@code a} replaced by {@code c + d} — was written
 * {@code c + d * b}, which Java reads as {@code c + (d * b)}. The canvas showed one thing and the file said
 * another. The rule here is deliberately coarse: a <em>primary</em> (a name, a literal, a call, an access,
 * something already in parentheses) never needs them, and anything else does wherever an operator or a
 * {@code .} binds to it. A pair of parentheses too many costs nothing; one too few changes the program.
 */
public final class Precedence {

    private Precedence() {}

    /** {@code replacement}, wrapped in parentheses when standing where {@code replaced} stood needs them. */
    public static Expression wrapIfNeeded(AST ast, Expression replacement, ASTNode replaced) {
        if (!needsParentheses(replacement, replaced)) return replacement;
        ParenthesizedExpression parens = ast.newParenthesizedExpression();
        parens.setExpression(replacement);
        return parens;
    }

    /** Whether {@code replacement}, written where {@code replaced} is, has to be parenthesised. */
    public static boolean needsParentheses(Expression replacement, ASTNode replaced) {
        if (replacement == null || replaced == null || isPrimary(replacement)) return false;
        ASTNode parent = replaced.getParent();
        return switch (parent) {
            case InfixExpression ignored -> true;
            case PrefixExpression ignored -> true;
            case PostfixExpression ignored -> true;
            case CastExpression ignored -> true;
            case InstanceofExpression ignored -> true;
            case PatternInstanceofExpression ignored -> true;
            case FieldAccess access -> access.getExpression() == replaced;
            case MethodInvocation call -> call.getExpression() == replaced;
            case ArrayAccess access -> access.getArray() == replaced;
            case ConditionalExpression conditional -> conditional.getExpression() == replaced
                    ? replacement instanceof ConditionalExpression || replacement instanceof Assignment
                            || replacement instanceof LambdaExpression
                    : replacement instanceof Assignment;
            case null, default -> false;
        };
    }

    /** An expression no operator can split: it reads as one thing wherever it is written. */
    public static boolean isPrimary(Expression expression) {
        return expression instanceof Name
                || expression instanceof NumberLiteral || expression instanceof StringLiteral
                || expression instanceof CharacterLiteral || expression instanceof BooleanLiteral
                || expression instanceof NullLiteral || expression instanceof TextBlock
                || expression instanceof TypeLiteral || expression instanceof ThisExpression
                || expression instanceof MethodInvocation || expression instanceof SuperMethodInvocation
                || expression instanceof FieldAccess || expression instanceof SuperFieldAccess
                || expression instanceof ArrayAccess || expression instanceof ParenthesizedExpression
                || expression instanceof ClassInstanceCreation;
    }
}
