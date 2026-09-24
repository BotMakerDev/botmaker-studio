package com.botmaker.studio.parser.factories;

import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.palette.ExpressionType.Form;
import com.botmaker.studio.palette.ExpressionType.InfixOp;
import com.botmaker.studio.palette.ExpressionType.Literal;
import com.botmaker.studio.palette.ExpressionType.Op;
import com.botmaker.studio.palette.ExpressionType.PrefixOp;
import com.botmaker.studio.palette.ExpressionType.Reference;
import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.handlers.LambdaCallHandler;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.DefaultNames;
import org.eclipse.jdt.core.dom.*;

public class ExpressionFactory {

    public static Expression createDefaultExpression(EditContext ctx, ExpressionType type,
                                                     ResolvedType contextType) {
        AST ast = ctx.ast();
        return switch (type) {
            case Literal l -> switch (l.kind()) {
                case TEXT -> createStringLiteral(ast, "text");
                case NUMBER -> ast.newNumberLiteral("0");
                case TRUE -> ast.newBooleanLiteral(true);
                case FALSE -> ast.newBooleanLiteral(false);
            };
            case Reference r -> switch (r.kind()) {
                case FUNCTION_CALL -> {
                    MethodInvocation mi = ast.newMethodInvocation();
                    mi.setName(ast.newSimpleName("method"));
                    yield mi;
                }
                case VARIABLE -> ast.newSimpleName(DefaultNames.DEFAULT_VARIABLE);
                // ACTIVITY is only ever inserted as a concrete Activities.<name> field reference (via
                // ExpressionChoice.Field), never created blank from the palette; placeholder for exhaustiveness.
                case ACTIVITY -> ast.newSimpleName(DefaultNames.DEFAULT_VARIABLE);
                case SUB_LIST -> ast.newArrayInitializer();
                case ENUM_CONSTANT -> createEnumConstantExpression(ast, contextType);
                case INSTANTIATION -> createInstantiation(ctx, contextType);
            };
            case InfixOp op -> createInfixExpression(ast, op.operator());
            case PrefixOp op -> createPrefixExpression(ast, mapPrefix(op.operator()));
            case Form f -> createForm(ctx, f.kind(), contextType);
        };
    }

    /**
     * A form seeded so it compiles where it lands: each slot holds a value of the type the slot wants (the
     * same defaults a dropped block's arguments get), each type is the slot's type or {@code Object}.
     */
    private static Expression createForm(EditContext ctx, Form.Kind kind, ResolvedType contextType) {
        AST ast = ctx.ast();
        boolean known = contextType != null && !contextType.isUnknown();
        return switch (kind) {
            case CHOOSE -> {
                ConditionalExpression choose = ast.newConditionalExpression();
                choose.setExpression(ast.newBooleanLiteral(true));
                choose.setThenExpression(defaultValue(ctx, contextType));
                choose.setElseExpression(defaultValue(ctx, contextType));
                yield choose;
            }
            case NEGATE -> {
                PrefixExpression negate = ast.newPrefixExpression();
                negate.setOperator(PrefixExpression.Operator.MINUS);
                negate.setOperand(ast.newNumberLiteral("1"));
                yield negate;
            }
            case CAST -> {
                ResolvedType type = known ? contextType : ResolvedType.named("Object");
                CastExpression cast = ast.newCastExpression();
                cast.setType(ProjectAnalyzer.createSimpleTypeNode(ast, type));
                cast.setExpression(defaultValue(ctx, type));
                ctx.addImportForType(type);
                yield cast;
            }
            case INSTANCEOF -> {
                InstanceofExpression check = ast.newInstanceofExpression();
                check.setLeftOperand(ast.newNullLiteral());
                check.setRightOperand(ast.newSimpleType(ast.newSimpleName("Object")));
                yield check;
            }
            case NEW_ARRAY -> {
                ResolvedType type = known && contextType.isArray() ? contextType : ResolvedType.named("int[]");
                ArrayCreation creation = ast.newArrayCreation();
                Type node = ProjectAnalyzer.createSimpleTypeNode(ast, type);
                creation.setType(node instanceof ArrayType array ? array : ast.newArrayType(node));
                creation.dimensions().add(ast.newNumberLiteral("10"));
                ctx.addImportForType(type);
                yield creation;
            }
            case CHARACTER -> {
                CharacterLiteral character = ast.newCharacterLiteral();
                character.setCharValue('a');
                yield character;
            }
            case CLASS_LITERAL -> {
                TypeLiteral literal = ast.newTypeLiteral();
                literal.setType(ast.newSimpleType(ast.newSimpleName("Object")));
                yield literal;
            }
            case LAMBDA -> createLambda(ctx, contextType);
        };
    }

    /**
     * A lambda for a functional interface slot: one parameter per parameter of its method, a block body for a
     * method that returns nothing, and a default value as the body for one that returns something.
     */
    private static Expression createLambda(EditContext ctx, ResolvedType contextType) {
        AST ast = ctx.ast();
        IMethodBinding method = contextType instanceof ResolvedType.Bound bound
                ? bound.binding().getFunctionalInterfaceMethod() : null;
        int arity = method == null ? 0 : method.getParameterTypes().length;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < arity; i++) names.add(arity == 1 ? "value" : "value" + (i + 1));
        LambdaExpression lambda = LambdaCallHandler.emptyBlockLambda(ast, names);
        if (method != null && !"void".equals(method.getReturnType().getName())) {
            lambda.setBody(defaultValue(ctx, ResolvedType.of(method.getReturnType())));
        }
        return lambda;
    }

    /** A value of {@code type} that compiles, or {@code null} for a type nothing seeds. */
    private static Expression defaultValue(EditContext ctx, ResolvedType type) {
        if (type == null || type.isUnknown()) return ctx.ast().newNullLiteral();
        Expression value = InitializerFactory.createDefaultInitializer(ctx, type);
        return value != null ? value : ctx.ast().newNullLiteral();
    }

    private static Expression createInstantiation(EditContext ctx, ResolvedType contextType) {
        // Same rule as a seeded argument (InitializerFactory#newInstance): name a constructor the type actually
        // declares, rather than assuming a no-arg one exists. Picking "new T()" from the expression menu used to
        // produce the identical uncompilable text a seeded argument did.
        boolean known = contextType != null && !contextType.isUnknown();
        ResolvedType type = known ? contextType : ResolvedType.named("Object");
        ClassInstanceCreation cic = InitializerFactory.newInstance(ctx.ast(), type, ctx.analyzer());
        ctx.addImportForSimpleName(type.simpleName());
        return cic;
    }

    private static StringLiteral createStringLiteral(AST ast, String value) {
        StringLiteral literal = ast.newStringLiteral();
        literal.setLiteralValue(value);
        return literal;
    }

    private static Expression createEnumConstantExpression(AST ast, ResolvedType contextType) {
        if (contextType != null && contextType.isEnum()) {
            java.util.List<String> constants = contextType.enumConstants();
            String constName = constants.isEmpty() ? "VALUE" : constants.getFirst();
            return ast.newQualifiedName(
                    ast.newSimpleName(contextType.simpleName()),
                    ast.newSimpleName(constName)
            );
        }
        return ast.newQualifiedName(ast.newSimpleName("MyEnum"), ast.newSimpleName("VALUE"));
    }

    private static Expression createInfixExpression(AST ast, Op op) {
        InfixExpression infixExpr = ast.newInfixExpression();

        // Logic ops default to boolean operands; math/comparison default to 0.
        if (op == Op.AND || op == Op.OR) {
            infixExpr.setLeftOperand(ast.newBooleanLiteral(true));
            infixExpr.setRightOperand(ast.newBooleanLiteral(true));
        } else {
            infixExpr.setLeftOperand(ast.newNumberLiteral("0"));
            infixExpr.setRightOperand(ast.newNumberLiteral("0"));
        }

        infixExpr.setOperator(mapInfix(op));
        return infixExpr;
    }

    private static InfixExpression.Operator mapInfix(Op op) {
        return switch (op) {
            case PLUS -> InfixExpression.Operator.PLUS;
            case MINUS -> InfixExpression.Operator.MINUS;
            case TIMES -> InfixExpression.Operator.TIMES;
            case DIVIDE -> InfixExpression.Operator.DIVIDE;
            case REMAINDER -> InfixExpression.Operator.REMAINDER;
            case EQUALS -> InfixExpression.Operator.EQUALS;
            case NOT_EQUALS -> InfixExpression.Operator.NOT_EQUALS;
            case GREATER -> InfixExpression.Operator.GREATER;
            case LESS -> InfixExpression.Operator.LESS;
            case GREATER_EQUALS -> InfixExpression.Operator.GREATER_EQUALS;
            case LESS_EQUALS -> InfixExpression.Operator.LESS_EQUALS;
            case AND -> InfixExpression.Operator.CONDITIONAL_AND;
            case OR -> InfixExpression.Operator.CONDITIONAL_OR;
            case NOT -> throw new IllegalArgumentException("NOT is a prefix operator");
        };
    }

    private static PrefixExpression.Operator mapPrefix(Op op) {
        if (op == Op.NOT) return PrefixExpression.Operator.NOT;
        throw new IllegalArgumentException("Not a prefix operator: " + op);
    }

    private static Expression createPrefixExpression(AST ast, PrefixExpression.Operator op) {
        PrefixExpression prefix = ast.newPrefixExpression();
        prefix.setOperator(op);
        prefix.setOperand(ast.newBooleanLiteral(true));
        return prefix;
    }
}
