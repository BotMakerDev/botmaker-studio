package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotAnnotation;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeMethodReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Every use of a set of declarations in one unit, each with the node that is the whole use and how it uses the
 * declaration (2026-10-16): a call through any receiver, {@code super} or none, a {@code new}, a {@code this(…)}
 * or {@code super(…)}, a method reference, a field read or written, a constant as a {@code case} label, a type
 * named as a type or as a qualifier, an import.
 *
 * <p>{@link Usages} answers "which names stand for it", which is what a rename rewrites; this answers "which
 * expressions use it", which is what a change of shape rewrites — the call whose arguments move, the
 * {@code new} a rule replaces whole. Both compare by {@link Usages#keyOf}, so nothing is matched by spelling.
 * A signature change ({@code parser/refactor/MethodReferences}) and the author's migration ({@code migrate/})
 * both find their uses here.
 */
public final class Uses {

    private Uses() {}

    /** How a use uses its declaration. */
    public enum Shape {
        /** A call whose value is used. */
        CALL,
        /** A call that is a whole statement. */
        STATEMENT,
        /** {@code new Owner(…)}: of a constructor, or of a type. */
        CREATION,
        /** {@code this(…)} or {@code super(…)}: a constructor called from another. */
        DELEGATION,
        /** A field read. */
        READ,
        /** A field assigned or incremented. */
        WRITE,
        /** A constant as a {@code case} label. */
        CASE,
        /** {@code Owner::member}: named, not called, so there are no arguments. */
        REFERENCE,
        /** A type named as a type, or as the qualifier of a static member. */
        TYPE_NAME,
        /** An import of it. */
        IMPORT
    }

    /** One use: the node that is the whole of it, the key of the declaration it uses, and its shape. */
    public record Found(ASTNode node, String key, Shape shape) {}

    /**
     * Every use in {@code unit} of a declaration whose key is in {@code keys}, in source order, an outer use
     * before the uses inside it.
     *
     * @param skipRules whether the bot's {@code @Migrate} rules are left out: they are written against the API a
     *                  migration moves to, so none of their uses is one the migration rewrites
     */
    public static List<Found> in(CompilationUnit unit, Set<String> keys, boolean skipRules) {
        List<Found> out = new ArrayList<>();
        if (unit == null || keys.isEmpty()) return out;
        unit.accept(new ASTVisitor() {

            @Override
            public boolean visit(MethodDeclaration method) {
                return !skipRules || BotAnnotation.MIGRATE.on(method) == null;
            }

            @Override
            public boolean visit(ImportDeclaration declaration) {
                if (!declaration.isOnDemand()) add(declaration, declaration.resolveBinding(), Shape.IMPORT);
                return false;
            }

            @Override
            public boolean visit(MethodInvocation call) {
                if (fits(call.resolveMethodBinding(), call.arguments().size())) {
                    add(call, call.resolveMethodBinding(), statement(call) ? Shape.STATEMENT : Shape.CALL);
                }
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation call) {
                if (fits(call.resolveMethodBinding(), call.arguments().size())) {
                    add(call, call.resolveMethodBinding(), statement(call) ? Shape.STATEMENT : Shape.CALL);
                }
                return true;
            }

            @Override
            public boolean visit(ConstructorInvocation call) {
                add(call, call.resolveConstructorBinding(), Shape.DELEGATION);
                return true;
            }

            @Override
            public boolean visit(SuperConstructorInvocation call) {
                add(call, call.resolveConstructorBinding(), Shape.DELEGATION);
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation creation) {
                IMethodBinding constructor = creation.resolveConstructorBinding();
                boolean found = fits(constructor, creation.arguments().size())
                        && add(creation, constructor, Shape.CREATION);
                if (!found) found = add(creation, creation.getType().resolveBinding(), Shape.CREATION);
                if (!found) return true;
                // The created type is the use's own; its parts, and its type's arguments, may hold uses of their own.
                if (creation.getExpression() != null) creation.getExpression().accept(this);
                if (creation.getType() instanceof ParameterizedType parameterized) {
                    for (Object argument : parameterized.typeArguments()) ((ASTNode) argument).accept(this);
                }
                for (Object argument : creation.arguments()) ((ASTNode) argument).accept(this);
                if (creation.getAnonymousClassDeclaration() != null) {
                    creation.getAnonymousClassDeclaration().accept(this);
                }
                return false;
            }

            @Override
            public boolean visit(ExpressionMethodReference reference) {
                return reference(reference);
            }

            @Override
            public boolean visit(TypeMethodReference reference) {
                return reference(reference);
            }

            @Override
            public boolean visit(SuperMethodReference reference) {
                return reference(reference);
            }

            @Override
            public boolean visit(CreationReference reference) {
                return reference(reference);
            }

            private boolean reference(MethodReference reference) {
                add(reference, reference.resolveMethodBinding(), Shape.REFERENCE);
                return true;
            }

            @Override
            public boolean visit(SimpleType type) {
                return type(type);
            }

            @Override
            public boolean visit(QualifiedType type) {
                return type(type);
            }

            @Override
            public boolean visit(NameQualifiedType type) {
                return type(type);
            }

            private boolean type(Type type) {
                return !add(type, type.resolveBinding(), Shape.TYPE_NAME);
            }

            @Override
            public boolean visit(QualifiedName name) {
                IBinding binding = name.resolveBinding();
                if (binding instanceof IVariableBinding variable && !variable.isField()) return true;
                if (!add(name, binding, binding instanceof ITypeBinding ? Shape.TYPE_NAME : fieldShape(name))) {
                    return true;
                }
                // An instance field's receiver may hold a use; a type's qualifier is its package or outer type.
                return !(binding instanceof ITypeBinding);
            }

            @Override
            public boolean visit(FieldAccess access) {
                add(access, access.resolveFieldBinding(), fieldShape(access));
                access.getExpression().accept(this);
                return false;
            }

            @Override
            public boolean visit(SuperFieldAccess access) {
                add(access, access.resolveFieldBinding(), fieldShape(access));
                return false;
            }

            @Override
            public boolean visit(SimpleName name) {
                if (name.getLocationInParent() == QualifiedName.NAME_PROPERTY
                        || name.getLocationInParent() == FieldAccess.NAME_PROPERTY
                        || name.getLocationInParent() == SuperFieldAccess.NAME_PROPERTY
                        || name.getLocationInParent() == MethodInvocation.NAME_PROPERTY
                        || name.getLocationInParent() == SuperMethodInvocation.NAME_PROPERTY
                        || name.isDeclaration()) {
                    return false;
                }
                IBinding binding = name.resolveBinding();
                if (binding instanceof ITypeBinding) add(name, binding, Shape.TYPE_NAME);
                else if (binding instanceof IVariableBinding variable && variable.isField()) {
                    add(name, binding, fieldShape(name));
                }
                return false;
            }

            /** Records the use when {@code binding} is one of the keys; whether it was. */
            private boolean add(ASTNode node, IBinding binding, Shape shape) {
                String key = binding == null ? null : Usages.keyOf(binding);
                if (key == null || !keys.contains(key)) return false;
                out.add(new Found(node, key, shape));
                return true;
            }
        });
        return out;
    }

    /**
     * Whether a call of {@code arguments} arguments can be {@code bound}: a binding javac only recovered names the
     * nearest method even for a call of the wrong arity, which does not compile against it and is not its use.
     */
    private static boolean fits(IMethodBinding bound, int arguments) {
        return bound != null && (bound.isVarargs() || bound.getParameterTypes().length == arguments);
    }

    private static boolean statement(ASTNode call) {
        return call.getLocationInParent() == ExpressionStatement.EXPRESSION_PROPERTY;
    }

    /** A field use's shape: written, a {@code case} label, or read. */
    private static Shape fieldShape(ASTNode use) {
        if (use.getLocationInParent() == Assignment.LEFT_HAND_SIDE_PROPERTY) return Shape.WRITE;
        if (use.getParent() instanceof PostfixExpression) return Shape.WRITE;
        if (use.getParent() instanceof PrefixExpression prefix
                && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                || prefix.getOperator() == PrefixExpression.Operator.DECREMENT)) {
            return Shape.WRITE;
        }
        if (use.getParent() instanceof SwitchCase) return Shape.CASE;
        return Shape.READ;
    }
}
