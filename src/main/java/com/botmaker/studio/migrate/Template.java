package com.botmaker.studio.migrate;

import com.botmaker.studio.plugin.grammar.JavaExpressions;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A rule's body with a site's parts put where its parameters stand — as text, so each part keeps the spelling
 * and the comments the bot wrote it with. The body is never run (2026-10-16).
 *
 * <p>A simple name spelled like a parameter is that parameter: only a member's, a type's or an annotation
 * member's own name and the right of a qualified name are left alone. A body that declares a name a parameter
 * has (a lambda's own {@code file}) is refused: which is which is not clear. A part that is not a primary
 * expression is put in parentheses wherever it would otherwise bind differently.
 */
final class Template {

    private Template() {}

    /** One part of a site: its text, with the uses inside it already rewritten, and whether it is primary. */
    record Part(String text, boolean primary) {

        static Part of(ASTNode node, String text) {
            return new Part(text, Template.primary(node));
        }
    }

    /**
     * {@code body} with each parameter replaced by its part, or empty when it does not parse or declares a
     * parameter's name.
     *
     * @param statement whether {@code body} is a statement rather than an expression
     */
    static Optional<String> filled(String body, boolean statement, Map<String, Part> parts) {
        ASTNode root = statement ? statement(body) : JavaExpressions.parse(body);
        if (root == null) return Optional.empty();
        int base = root.getStartPosition();
        List<SimpleName> uses = new ArrayList<>();
        boolean[] shadowed = {false};
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (!parts.containsKey(name.getIdentifier())) return false;
                StructuralPropertyDescriptor at = name.getLocationInParent();
                if (at == VariableDeclarationFragment.NAME_PROPERTY || at == SingleVariableDeclaration.NAME_PROPERTY) {
                    shadowed[0] = true;
                } else if (at != MethodInvocation.NAME_PROPERTY && at != FieldAccess.NAME_PROPERTY
                        && at != QualifiedName.NAME_PROPERTY && at != SimpleType.NAME_PROPERTY
                        && at != MemberValuePair.NAME_PROPERTY && at != SuperMethodInvocation.NAME_PROPERTY
                        && at != SuperFieldAccess.NAME_PROPERTY) {
                    uses.add(name);
                }
                return false;
            }
        });
        if (shadowed[0]) return Optional.empty();
        String text = body.substring(base, base + root.getLength());
        uses.sort(Comparator.comparingInt(ASTNode::getStartPosition).reversed());
        StringBuilder out = new StringBuilder(text);
        for (SimpleName use : uses) {
            Part part = parts.get(use.getIdentifier());
            String put = part.primary() || bare(use) ? part.text() : "(" + part.text() + ")";
            int from = use.getStartPosition() - base;
            out.replace(from, from + use.getLength(), put);
        }
        return Optional.of(out.toString());
    }

    /** {@code source} as one statement, or {@code null} when it is not exactly one. */
    private static Statement statement(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_STATEMENTS);
        parser.setSource(source.toCharArray());
        Map<String, String> options = new HashMap<>(JavaCore.getOptions());
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        if (!(parser.createAST(null) instanceof Block block) || block.statements().size() != 1) return null;
        return (Statement) block.statements().getFirst();
    }

    /**
     * Whether a value standing at {@code node}'s place needs no parentheses whatever it is: an argument, an
     * initialiser, a statement's or a {@code return}'s whole expression, or already in parentheses.
     */
    static boolean bare(ASTNode node) {
        StructuralPropertyDescriptor at = node.getLocationInParent();
        return at == null
                || at == MethodInvocation.ARGUMENTS_PROPERTY || at == ClassInstanceCreation.ARGUMENTS_PROPERTY
                || at == SuperMethodInvocation.ARGUMENTS_PROPERTY || at == ConstructorInvocation.ARGUMENTS_PROPERTY
                || at == SuperConstructorInvocation.ARGUMENTS_PROPERTY
                || at == EnumConstantDeclaration.ARGUMENTS_PROPERTY
                || at == VariableDeclarationFragment.INITIALIZER_PROPERTY
                || at == ReturnStatement.EXPRESSION_PROPERTY || at == ExpressionStatement.EXPRESSION_PROPERTY
                || at == ArrayInitializer.EXPRESSIONS_PROPERTY || at == ParenthesizedExpression.EXPRESSION_PROPERTY;
    }

    /** Whether {@code node} binds tighter than any operator: it can stand anywhere an expression can. */
    static boolean primary(ASTNode node) {
        return node instanceof Name || node instanceof NumberLiteral || node instanceof StringLiteral
                || node instanceof CharacterLiteral || node instanceof BooleanLiteral || node instanceof NullLiteral
                || node instanceof TextBlock || node instanceof MethodInvocation
                || node instanceof SuperMethodInvocation || node instanceof FieldAccess
                || node instanceof SuperFieldAccess || node instanceof ParenthesizedExpression
                || node instanceof ClassInstanceCreation || node instanceof ArrayAccess
                || node instanceof ArrayCreation || node instanceof ThisExpression || node instanceof TypeLiteral;
    }
}
