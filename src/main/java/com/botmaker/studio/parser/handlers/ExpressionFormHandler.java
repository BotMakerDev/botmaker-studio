package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.JavaSnippets;
import com.botmaker.studio.parser.helpers.Precedence;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PatternInstanceofExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.TypePattern;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;

/**
 * The edits the value blocks make that are not "put another value here": the type a cast converts to, the type
 * an {@code instanceof} checks, the class of an {@code X.class}, the element type of {@code new T[n]}, a
 * character, a text block, a number spelled in hex, and an expression typed as Java.
 *
 * <p>Each takes what the user typed and answers the new source, or {@code null} when the text is not what the
 * slot needs — a type that does not parse, two characters where one goes. The caller turns a {@code null} into
 * a status line; nothing is ever written half-right.
 */
public final class ExpressionFormHandler {

    private ExpressionFormHandler() {}

    /**
     * Makes {@code owner} name the type {@code typeText}: the type of a cast, of a plain or pattern
     * {@code instanceof}, of a class literal, of a declaration inside a {@code for} or {@code try (…)}, or the
     * element type of an array creation. Each simple name in it is imported when the project knows it.
     */
    public static String setType(EditContext ctx, String originalCode, ASTNode owner, String typeText) {
        Type parsed = JavaSnippets.type(typeText);
        if (parsed == null) return null;
        AST ast = ctx.ast();
        Type type = (Type) ASTNode.copySubtree(ast, parsed);
        switch (owner) {
            case CastExpression cast -> ctx.rewriter().set(cast, CastExpression.TYPE_PROPERTY, type, null);
            case InstanceofExpression check -> {
                if (type instanceof PrimitiveType) return null;
                ctx.rewriter().set(check, InstanceofExpression.RIGHT_OPERAND_PROPERTY, type, null);
            }
            case PatternInstanceofExpression check -> {
                if (type instanceof PrimitiveType
                        || !(check.getPattern() instanceof TypePattern pattern)
                        || !(pattern.getPatternVariable() instanceof SingleVariableDeclaration variable)) {
                    return null;
                }
                ctx.rewriter().set(variable, SingleVariableDeclaration.TYPE_PROPERTY, type, null);
            }
            case TypeLiteral literal -> ctx.rewriter().set(literal, TypeLiteral.TYPE_PROPERTY, type, null);
            case VariableDeclarationExpression declaration ->
                    ctx.rewriter().set(declaration, VariableDeclarationExpression.TYPE_PROPERTY, type, null);
            case ArrayCreation creation -> {
                if (type instanceof ArrayType) return null;
                ArrayType arrayType = ast.newArrayType(type, creation.getType().getDimensions());
                ctx.rewriter().set(creation, ArrayCreation.TYPE_PROPERTY, arrayType, null);
            }
            case null, default -> {
                return null;
            }
        }
        importSimpleNames(ctx, parsed);
        return ctx.applyTo(originalCode);
    }

    /** What {@code owner} names as its type, as {@link #setType} reads it back. */
    public static String typeText(ASTNode owner) {
        Type type = typeNode(owner);
        return type == null ? "" : type.toString();
    }

    /** The type node {@code owner} names — for an array creation, its element type — or {@code null}. */
    public static Type typeNode(ASTNode owner) {
        return switch (owner) {
            case CastExpression cast -> cast.getType();
            case InstanceofExpression check -> check.getRightOperand();
            case PatternInstanceofExpression check
                    when check.getPattern() instanceof TypePattern pattern
                    && pattern.getPatternVariable() instanceof SingleVariableDeclaration variable ->
                    variable.getType();
            case TypeLiteral literal -> literal.getType();
            case VariableDeclarationExpression declaration -> declaration.getType();
            case ArrayCreation creation -> creation.getType().getElementType();
            case null, default -> null;
        };
    }

    /** {@code literal} set to the one character {@code text} holds, or {@code null} when it holds another count. */
    public static String setCharacter(EditContext ctx, String originalCode, CharacterLiteral literal, String text) {
        if (text == null || text.codePointCount(0, text.length()) != 1 || text.length() != 1) return null;
        CharacterLiteral replacement = ctx.ast().newCharacterLiteral();
        replacement.setCharValue(text.charAt(0));
        ctx.rewriter().replace(literal, replacement, null);
        return ctx.applyTo(originalCode);
    }

    /**
     * {@code block} set to hold {@code content}, written as a text block again: each line at the indentation
     * the block's first line had, so the file keeps its shape, and the closing quotes right after the last
     * line, so no newline is added that the user did not type.
     */
    public static String setTextBlock(EditContext ctx, String originalCode, TextBlock block, String content) {
        if (content == null) return null;
        String indent = firstLineIndent(block.getEscapedValue());
        StringBuilder escaped = new StringBuilder("\"\"\"\n");
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace("\\", "\\\\").replace("\"\"\"", "\\\"\"\"");
            escaped.append(line.isEmpty() ? "" : indent).append(line);
            if (i < lines.length - 1) escaped.append('\n');
        }
        escaped.append("\"\"\"");
        TextBlock replacement = ctx.ast().newTextBlock();
        replacement.setEscapedValue(escaped.toString());
        ctx.rewriter().replace(block, replacement, null);
        return ctx.applyTo(originalCode);
    }

    /** The whitespace in front of a text block's first line of content, from its source spelling. */
    static String firstLineIndent(String escapedValue) {
        int start = escapedValue.indexOf('\n');
        if (start < 0) return "";
        int end = start + 1;
        while (end < escapedValue.length() && (escapedValue.charAt(end) == ' ' || escapedValue.charAt(end) == '\t')) {
            end++;
        }
        return escapedValue.substring(start + 1, end);
    }

    /**
     * {@code literal} respelled as {@code token} — {@code 0xFF}, {@code 1_000}, {@code 2.5e3} — when that is one
     * number literal; a sign is an operator in Java, so {@code -1} is refused here and belongs to the value
     * around it.
     */
    public static String setNumberToken(EditContext ctx, String originalCode, NumberLiteral literal, String token) {
        if (!(JavaSnippets.expression(token) instanceof NumberLiteral parsed)) return null;
        ctx.rewriter().set(literal, NumberLiteral.TOKEN_PROPERTY, parsed.getToken(), null);
        return ctx.applyTo(originalCode);
    }

    /**
     * {@code target} replaced by the Java expression {@code text}, spliced in as typed so the user's own spacing
     * survives, and wrapped in parentheses where the operator around it would otherwise split it. {@code null}
     * when the text is not one well-formed expression.
     */
    public static String replaceWithSource(String originalCode, Expression target, String text) {
        Expression parsed = JavaSnippets.expression(text);
        if (target == null || parsed == null) return null;
        String written = text.strip();
        if (Precedence.needsParentheses(parsed, target)) written = "(" + written + ")";
        int start = target.getStartPosition();
        return originalCode.substring(0, start) + written + originalCode.substring(start + target.getLength());
    }

    /** Imports every unqualified class name {@code type} mentions — {@code Map<String, Point>} names two. */
    private static void importSimpleNames(EditContext ctx, Type type) {
        type.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleType simple) {
                if (simple.getName() instanceof SimpleName name) ctx.addImportForSimpleName(name.getIdentifier());
                return true;
            }
        });
    }
}
