package com.botmaker.studio.nav;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Find Usages (2026-09-26): every name in a unit that resolves to one binding, and {@link #across} the bot.
 *
 * <p><b>The one answer to "where is this used"</b> (2026-09-27). The Usages tab, a class rename from the
 * explorer and a parameter's rename and removal all ask here, so no caller counts uses by searching text —
 * which is how a removed {@code @Param} left {@code Parameters.j} in a file the window said nothing about.
 *
 * <p>Bindings are compared by {@link #keyOf key}, and a generic member by its declaration's key, so
 * {@code List<String>.add} and {@code List<Integer>.add} are one method — the question is "where is this
 * declaration used", not "where is this instantiation".
 */
public final class Usages {

    private Usages() {}

    /**
     * One occurrence.
     *
     * @param file        the source it is in
     * @param start       its offset in that source, which finds the block once the file is open
     * @param line        1-based
     * @param enclosing   the function it sits in ({@code main}), or the type for a field initialiser
     * @param text        the source line, trimmed
     * @param declaration whether this is the declaration itself rather than a use
     */
    public record Usage(Path file, int start, int line, String enclosing, String text, boolean declaration) {}

    /** What a binding is compared by. Null for a binding that has no stable key. */
    public static String keyOf(IBinding binding) {
        IBinding declared = switch (binding) {
            case IMethodBinding m -> m.getMethodDeclaration();
            case IVariableBinding v -> v.getVariableDeclaration();
            case ITypeBinding t -> t.getErasure();
            case null -> null;
            default -> binding;
        };
        return declared == null ? null : declared.getKey();
    }

    /** Every name in {@code cu} bound to {@code key}, in source order. */
    public static List<Usage> in(Path file, String source, CompilationUnit cu, String key) {
        List<Usage> out = new ArrayList<>();
        if (cu == null || key == null) return out;
        String[] lines = source.split("\n", -1);
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                IBinding binding = name.resolveBinding();
                if (binding == null || !key.equals(keyOf(binding))) return false;
                int line = cu.getLineNumber(name.getStartPosition());
                String text = line >= 1 && line <= lines.length ? lines[line - 1].trim() : "";
                out.add(new Usage(file, name.getStartPosition(), line, enclosing(name), text,
                        name.isDeclaration()));
                return false;
            }
        });
        return out;
    }

    /**
     * Every name bound to {@code key} across the bot, file by file in walk order. The one walk every "where is
     * this used" asks: the Usages tab, every rename ({@link Refactor}), a parameter's removal, a function's
     * calls.
     *
     * @param name the declaration's simple name — a file that never spells it cannot use it, so it is not
     *             walked. A cheap guard, not a match: what is reported is decided by the binding alone
     */
    public static List<Usage> across(BotIndex index, String key, String name) {
        List<Usage> out = new ArrayList<>();
        if (key == null) return out;
        index.read(units -> {
            units.forEach((file, unit) -> {
                String source = index.sources().get(file);
                if (name == null || source.contains(name)) out.addAll(in(file, source, unit, key));
            });
            return null;
        });
        return out;
    }

    /** {@link #across} the open project, each file read from its buffer before the disk. */
    public static List<Usage> inProject(ProjectConfig config, ProjectState state, String key, String name) {
        return across(BotIndex.of(config, state), key, name);
    }

    /**
     * The binding the name starting at {@code start} in {@code unit} stands for, or null — the handle every
     * refactor starts from: the canvas knows where a name is, never what it binds to in another file.
     */
    public static IBinding bindingAt(CompilationUnit unit, int start) {
        if (unit == null || start < 0) return null;
        ASTNode node = NodeFinder.perform(unit, start, 0);
        if (node instanceof SimpleName name) return name.resolveBinding();
        if (node != null && node.getParent() instanceof SimpleName name) return name.resolveBinding();
        return null;
    }

    /**
     * Every other name in {@code declaration}'s scope that stands for the same local variable — a method's
     * parameter, a local, a lambda's or a loop's or a {@code catch}'s variable. The declaration itself is not
     * listed.
     *
     * <p>By binding wherever there is one; a name with <em>no</em> binding — an inferred lambda parameter in a
     * file whose target type did not resolve — is matched by spelling, inside the variable's own scope and
     * never into a nested lambda that declares the same name. That fallback is the only spelling match left
     * anywhere in "where is this used", and it cannot leave the scope that makes it right.
     */
    public static List<SimpleName> local(SimpleName declaration) {
        List<SimpleName> found = new ArrayList<>();
        ASTNode scope = scopeOf(declaration);
        if (scope == null) return found;
        String name = declaration.getIdentifier();
        IBinding declared = declaration.resolveBinding();
        scope.accept(new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression nested) {
                if (nested == scope) return true;
                return declared != null || nested.parameters().stream()
                        .map(Usages::lambdaParameterName)
                        .noneMatch(name::equals);
            }

            @Override
            public boolean visit(SimpleName node) {
                if (node == declaration || !node.getIdentifier().equals(name) || !canBeVariable(node)) return false;
                IBinding bound = node.resolveBinding();
                if (declared != null && bound != null) {
                    if (declared.isEqualTo(bound)) found.add(node);
                } else {
                    found.add(node);
                }
                return false;
            }
        });
        return found;
    }

    /**
     * The whole of the node a local variable can be named in: the lambda, loop, {@code catch} or method that
     * declares it, or the block of a local declaration. Null for a name that declares no local.
     */
    private static ASTNode scopeOf(SimpleName declaration) {
        if (!(declaration.getParent() instanceof VariableDeclaration declaring)
                || declaring.getParent() instanceof FieldDeclaration) {
            return null;
        }
        ASTNode holder = declaring.getParent();
        if (holder instanceof LambdaExpression || holder instanceof EnhancedForStatement
                || holder instanceof CatchClause || holder instanceof MethodDeclaration) {
            return holder;
        }
        if (holder instanceof VariableDeclarationExpression expression) return expression.getParent();
        if (holder instanceof VariableDeclarationStatement statement) return statement.getParent();
        // A pattern variable, a resource: the enclosing method is a superset of its scope, and a binding tells
        // the rest apart.
        for (ASTNode n = declaring; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration || n instanceof LambdaExpression) return n;
        }
        return null;
    }

    private static String lambdaParameterName(Object parameter) {
        return parameter instanceof VariableDeclaration variable ? variable.getName().getIdentifier() : null;
    }

    /** False for the names that can never denote a variable: {@code foo()}, {@code x.foo}, {@code a.b}. */
    private static boolean canBeVariable(SimpleName node) {
        StructuralPropertyDescriptor location = node.getLocationInParent();
        return location != MethodInvocation.NAME_PROPERTY
                && location != FieldAccess.NAME_PROPERTY
                && location != SuperFieldAccess.NAME_PROPERTY
                && location != QualifiedName.NAME_PROPERTY
                && location != MethodDeclaration.NAME_PROPERTY;
    }

    /**
     * Where the name of field {@code fieldName}, declared in the class {@code className} of {@code unit}, starts
     * — the handle {@link Refactor} takes — or -1 when the unit declares no such field.
     */
    public static int fieldStart(CompilationUnit unit, String className, String fieldName) {
        int[] start = {-1};
        if (unit == null) return -1;
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                if (start[0] >= 0 || !(fragment.getParent() instanceof FieldDeclaration)) return false;
                if (!fragment.getName().getIdentifier().equals(fieldName)) return false;
                if (!(fragment.getParent().getParent() instanceof AbstractTypeDeclaration type)
                        || !type.getName().getIdentifier().equals(className)) return false;
                start[0] = fragment.getName().getStartPosition();
                return false;
            }
        });
        return start[0];
    }

    /**
     * {@code text} with the name at each of {@code starts} changed from {@code oldName} to {@code newName}.
     * The offsets are the ones {@link #in} reported for this very text, so each is a whole identifier; they are
     * applied back to front so the earlier ones still hold. A start that does not spell {@code oldName} —
     * the text moved since it was parsed — is skipped.
     */
    public static String renamed(String text, List<Integer> starts, String oldName, String newName) {
        StringBuilder out = new StringBuilder(text);
        starts.stream().distinct().sorted(Comparator.reverseOrder()).forEach(start -> {
            if (text.startsWith(oldName, start)) out.replace(start, start + oldName.length(), newName);
        });
        return out.toString();
    }

    private static String enclosing(ASTNode node) {
        for (ASTNode n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration m) return m.getName().getIdentifier();
            if (n instanceof AbstractTypeDeclaration t) return t.getName().getIdentifier();
        }
        return "";
    }
}
