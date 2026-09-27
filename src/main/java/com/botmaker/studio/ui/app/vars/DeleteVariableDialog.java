package com.botmaker.studio.ui.app.vars;

import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.parser.UseFix;
import com.botmaker.studio.parser.factories.InitializerFactory;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.app.Refactors;
import com.botmaker.studio.ui.app.RefusalDialog;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deleting a variable something still reads: <b>refused, with where and what instead</b> (2026-09-27).
 *
 * <p>The ✕ on a declare block used to remove the line and nothing else, which left a file that does not
 * compile; then this was its own window asking what the uses should become. It is now the refusal every
 * refactor uses ({@link RefusalDialog}): the delete does not happen, each use is a link to its block, and the
 * ways through are buttons — point the uses at another variable of the same type (an exact repair), or put
 * the type's default where each was (a guess, so the function is marked {@code @Refactor}). An unused
 * variable still deletes with one press and no window.
 *
 * <h2>Syntactic candidates, like the screen next door</h2>
 *
 * <p>The other variables are gathered by walking the method, not from {@code VariableScopeVisitor}: that reads
 * {@code IVariableBinding}s, which a file mid-edit routinely doesn't have. Same reasoning as
 * {@link EditVariableDialog}.
 */
public final class DeleteVariableDialog {

    private DeleteVariableDialog() {}

    /**
     * Deletes {@code decl}, or refuses while anything reads it and offers the fixes.
     *
     * @param decl the declaration behind the ✕ that was pressed
     */
    public static void confirmAndDelete(CodeEditorService context, Window owner, VariableDeclarationStatement decl) {
        if (context == null || decl == null) return;
        if (decl.fragments().size() != 1
                || !(decl.fragments().getFirst() instanceof VariableDeclarationFragment fragment)) {
            context.getCodeEditor().deleteStatement(decl);
            return;
        }
        MethodDeclaration method = AstRewriteHelper.enclosingMethod(decl);
        List<SimpleName> uses = AstRewriteHelper.referencesWithin(fragment.getName());
        if (uses.isEmpty()) {
            context.getCodeEditor().deleteStatement(decl);
            return;
        }

        String name = fragment.getName().getIdentifier();
        List<RefusalDialog.Choice> fixes = new ArrayList<>();
        for (String other : sameTypedInScope(method, decl, fragment, uses)) {
            fixes.add(new RefusalDialog.Choice("Use " + other + " instead",
                    () -> context.getCodeEditor().deleteVariable(decl, new UseFix.Rename(other))));
        }
        ResolvedType type = ProjectAnalyzer.resolveType(decl.getType());
        fixes.add(new RefusalDialog.Choice("Replace with " + defaultValueSource(context, decl, type),
                () -> context.getCodeEditor().deleteVariable(decl, UseFix.DEFAULT)));

        RefusalDialog.show(owner, name + " wasn't deleted", stillRead(name, uses.size(), method),
                usagesOf(context, uses), fixes, Refactors.reveal(context));
    }

    private static String stillRead(String name, int count, MethodDeclaration method) {
        String where = method == null ? "" : " in " + method.getName().getIdentifier() + "()";
        return "\"" + name + "\" is still used " + count + (count == 1 ? " time" : " times") + where
                + ". Remove " + (count == 1 ? "that use" : "those uses") + " first, or point "
                + (count == 1 ? "it" : "them") + " somewhere else below. A default value is a guess, so the "
                + "function is marked for review. Nothing has changed.";
    }

    /** The uses as the refusal lists them: each a link to its block in the open file. */
    private static List<Usages.Usage> usagesOf(CodeEditorService context, List<SimpleName> uses) {
        ProjectFile active = context.getState().getActiveFile();
        Path file = active == null ? null : active.getPath();
        String[] lines = context.getState().getCurrentCode() == null ? new String[0]
                : context.getState().getCurrentCode().split("\n", -1);
        List<Usages.Usage> out = new ArrayList<>();
        for (SimpleName use : uses) {
            CompilationUnit unit = (CompilationUnit) use.getRoot();
            int line = unit.getLineNumber(use.getStartPosition());
            MethodDeclaration method = AstRewriteHelper.enclosingMethod(use);
            out.add(new Usages.Usage(file, use.getStartPosition(), line,
                    method == null ? "" : method.getName().getIdentifier(),
                    line >= 1 && line <= lines.length ? lines[line - 1].trim() : use.getIdentifier(), false));
        }
        return out;
    }

    /**
     * The default as it will be written, so the button can show it rather than describe it.
     *
     * <p>Built with the same factory the rewrite uses, on the live AST but with no rewriter of its own — this is
     * a node made to be printed and thrown away, never applied.
     */
    private static String defaultValueSource(CodeEditorService context, VariableDeclarationStatement decl,
                                             ResolvedType type) {
        Expression value = InitializerFactory.createDefaultInitializer(decl.getAST(), type,
                context.getState().getCompilationUnit().orElse(null), context.getState());
        return value == null ? "null" : value.toString();
    }

    /**
     * Every other variable of the same declared type that {@code method} binds before the first use — the
     * parameters and the locals, matched on the type <em>as written</em>.
     *
     * <p>Textual type matching is the honest test without bindings: {@code List<Point>} and {@code List<Point>}
     * are the same type here, and anything that only a compiler could tell apart is not something to offer as a
     * silent substitution anyway.
     */
    private static List<String> sameTypedInScope(MethodDeclaration method, VariableDeclarationStatement decl,
                                                 VariableDeclarationFragment fragment, List<SimpleName> uses) {
        if (method == null) return List.of();
        String wanted = decl.getType().toString();
        String excluded = fragment.getName().getIdentifier();
        int firstUse = uses.stream().mapToInt(SimpleName::getStartPosition).min().orElse(Integer.MAX_VALUE);
        Set<String> names = new LinkedHashSet<>();

        for (Object parameter : method.parameters()) {
            if (parameter instanceof SingleVariableDeclaration p && p.getType().toString().equals(wanted)
                    && !p.getName().getIdentifier().equals(excluded)) {
                names.add(p.getName().getIdentifier());
            }
        }
        if (method.getBody() != null) {
            method.getBody().accept(new ASTVisitor() {
                @Override
                public boolean visit(VariableDeclarationStatement statement) {
                    if (statement == decl || !statement.getType().toString().equals(wanted)) return true;
                    for (Object each : statement.fragments()) {
                        if (each instanceof VariableDeclarationFragment f
                                && !f.getName().getIdentifier().equals(excluded)
                                && f.getStartPosition() < firstUse) {
                            names.add(f.getName().getIdentifier());
                        }
                    }
                    return true;
                }
            });
        }
        return new ArrayList<>(names);
    }
}
