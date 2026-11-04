package com.botmaker.studio.parser;

import com.botmaker.java.parse.SourceSet;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.function.UnaryOperator;

/**
 * Immutable per-edit context for the <b>write</b> path — the counterpart of the converter's
 * {@code ParseContext}, which has done the same job for the read path since the converter was written.
 *
 * <p>Every factory and handler under {@code parser.factories} / {@code parser.handlers} needs the same five
 * things to build a node: the {@link AST} to create it in, the {@link CompilationUnit} to import into, the
 * {@link ASTRewrite} recording the edit, the {@link ProjectAnalyzer} to ask what exists, and the
 * {@link SourceSet} to ask what else the bot declares. They were threaded as five separate parameters, which
 * is why the widest signatures in the package ran to eight — and why adding a sixth thing (the analyzer, when
 * {@code new T()} learned to name a real constructor) meant editing seven call chains.
 *
 * <p><b>Deliberately not in here:</b> the {@code ASTNode context} — the drop site whose visible variables a
 * seeded block is named from. That varies <em>within</em> a single edit as the factories recurse, so it is a
 * real argument, not ambient state. Same for the original source text: only the outermost entry point holds
 * it, and {@link #applyTo} is where it belongs.
 *
 * <p>{@code analyzer} and {@code sources} are nullable — the shorter entry points and most tests have neither,
 * and every consumer already treats them as best-effort. Which overloads the menus offer is not carried here:
 * it is the bound plugins' answer, {@code PaletteCuration.current()} (a {@code surface} component stood here
 * until 2026-09-28).
 *
 * @param favorites the overload the user pinned for a {@code Facade#method} key, or {@code null} for none; the
 *                  one thing an edit reads that is the project's and not its sources
 */
public record EditContext(AST ast, CompilationUnit cu, ASTRewrite rewriter,
                          ProjectAnalyzer analyzer, SourceSet sources, UnaryOperator<String> favorites) {

    /**
     * A context over {@code cu} with a freshly created rewriter — what an entry point that takes
     * {@code (CompilationUnit cu, String originalCode, …)} and returns new source builds first.
     */
    public static EditContext of(CompilationUnit cu, ProjectAnalyzer analyzer, SourceSet sources) {
        return of(cu, ASTRewrite.create(cu.getAST()), analyzer, sources);
    }

    /** As above, over an <em>existing</em> rewriter — for a nested edit that must land in the same rewrite. */
    public static EditContext of(CompilationUnit cu, ASTRewrite rewriter, ProjectAnalyzer analyzer,
                                 SourceSet sources) {
        return new EditContext(cu.getAST(), cu, rewriter, analyzer, sources, key -> null);
    }

    /** This context reading the user's pinned overloads from {@code pinned}. */
    public EditContext withFavorites(UnaryOperator<String> pinned) {
        return new EditContext(ast, cu, rewriter, analyzer, sources, pinned);
    }

    // addTemplatesImport() went on 2026-09-01 with the ImportManager method behind it. Nothing in Studio
    // writes a reference to the generated Templates class any more, so nothing needs it imported: a picture
    // is the SDK plugin's concept and its editors carry their own imports through SlotContext.replaceWith.

    /** Imports a type by identity — cannot fail to resolve. See {@link ImportManager#addImport}. */
    public void addImport(Class<?> type) {
        ImportManager.addImport(cu, rewriter, type);
    }

    /** Imports a fully-qualified name. */
    public void addImport(String qualifiedName) {
        ImportManager.addImport(cu, rewriter, qualifiedName);
    }

    /** Imports {@code type}, using its FQN when it carries one and resolving its simple name otherwise. */
    public void addImport(ResolvedType type) {
        ImportManager.addImport(cu, rewriter, type, sources);
    }

    /** Imports a type known only by simple name — the paste path and the menu-created references. */
    public void addImportForSimpleName(String simpleName) {
        ImportManager.addImportForSimpleName(cu, rewriter, simpleName, analyzer, sources);
    }

    /** Imports {@code type}'s <em>leaf</em> — a {@code Color[]} parameter needs {@code java.awt.Color}. */
    public void addImportForType(ResolvedType type) {
        ImportManager.addImportForType(cu, rewriter, type, analyzer, sources);
    }

    /** Applies this context's rewrite to {@code originalCode} and returns the new source. */
    public String applyTo(String originalCode) {
        return com.botmaker.studio.parser.helpers.AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}
