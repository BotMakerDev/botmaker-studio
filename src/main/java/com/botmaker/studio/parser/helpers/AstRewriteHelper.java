package com.botmaker.studio.parser.helpers;

import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.nav.Usages;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.RangeMarker;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Common utilities for AST rewriting operations.
 */
public class AstRewriteHelper {

    /**
     * Applies an ASTRewrite to source code and returns the modified code.
     * @param rewriter The ASTRewrite to apply
     * @param originalCode The original source code
     * @return The modified code, or original code if rewrite fails
     */
    public static String applyRewrite(ASTRewrite rewriter, String originalCode) {
        IDocument document = new Document(originalCode);
        try {
            // The repository's layout, not JDT's default: null options indent an inserted node with a tab.
            TextEdit edits = rewriter.rewriteAST(document, SourceFormatter.rewriteOptions());
            edits.apply(document);
            return document.get();
        } catch (Exception e) {
            e.printStackTrace();
            return originalCode;
        }
    }

    /**
     * Applies {@code rewriter}, then inserts {@code text} at what {@code offset} in the <em>original</em> code
     * has become — for edits that must land at a raw source position the AST cannot name.
     *
     * <p>The position is tracked with a {@link RangeMarker} added to the rewrite's own edit tree and applied
     * with {@link TextEdit#UPDATE_REGIONS}, so Eclipse shifts it for us. A plain
     * "{@code offset + (newLength - oldLength)}" delta would only be right when every edit happens to precede
     * the offset; the marker is correct wherever the other edits land.
     *
     * <p>Falls back to a plain {@link #applyRewrite} if the marker can't be attached (it would overlap an
     * edit) — better to lose the extra insertion than to corrupt the file.
     */
    public static String applyRewriteAndInsertAt(ASTRewrite rewriter, String originalCode, int offset, String text) {
        IDocument document = new Document(originalCode);
        try {
            TextEdit edits = rewriter.rewriteAST(document, SourceFormatter.rewriteOptions());
            RangeMarker marker = new RangeMarker(offset, 0);
            try {
                edits.addChild(marker);
            } catch (MalformedTreeException overlapping) {
                edits.apply(document);
                return document.get();
            }
            edits.apply(document, TextEdit.UPDATE_REGIONS);
            document.replace(marker.getOffset(), 0, text);
            return document.get();
        } catch (Exception e) {
            e.printStackTrace();
            return originalCode;
        }
    }

    /**
     * Removes an AST node and applies the change.
     */
    public static String removeNode(CompilationUnit cu, String originalCode, ASTNode node) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        rewriter.remove(node, null);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Renames a SimpleName node and applies the change.
     */
    public static String renameSimpleName(CompilationUnit cu, String originalCode,
                                          SimpleName nameNode, String newName) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        rewriter.replace(nameNode, ast.newSimpleName(newName), null);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * Renames a local variable — any kind: a declared local, a method's parameter, a lambda's, a loop's, a
     * {@code catch}'s or a pattern's — and every use of it in its scope, so the result still compiles.
     *
     * <p>What a use is, is {@link Usages#local}'s answer, and nothing here decides it: by binding wherever
     * there is one, by spelling only inside the variable's own scope where there is none. There were five
     * renamers here until 2026-09-27, one per kind of variable, each walking a scope of its own choosing with
     * its own mix of bindings and spelling — and {@link #renameSimpleName}, which renames the one node it is
     * handed, is not a rename of anything anyone uses.
     */
    public static String renameLocal(String originalCode, SimpleName declName, String newName) {
        ASTRewrite rewriter = ASTRewrite.create(declName.getAST());
        renameLocal(rewriter, declName, newName);
        return applyRewrite(rewriter, originalCode);
    }

    /**
     * {@link #renameLocal} <em>inside an in-flight rewrite</em>: it neither creates the {@link ASTRewrite} nor
     * applies it, because a method parameter's rename is one step of a whole-signature rewrite that has to
     * land as a single edit.
     */
    public static void renameLocal(ASTRewrite rewriter, SimpleName declName, String newName) {
        rewriter.set(declName, SimpleName.IDENTIFIER_PROPERTY, newName, null);
        for (SimpleName use : Usages.local(declName)) {
            rewriter.set(use, SimpleName.IDENTIFIER_PROPERTY, newName, null);
        }
    }

    /**
     * Every use of the local variable {@code declName} declares, the declaration itself excluded — what "is
     * this variable used?" means to the Variables screen and to a signature change dropping a parameter.
     * {@link Usages#local}, under the name its callers know.
     */
    public static List<SimpleName> referencesWithin(SimpleName declName) {
        return Usages.local(declName);
    }

    /** The {@link MethodDeclaration} {@code node} sits in, or null when it sits outside one. */
    public static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) return method;
        }
        return null;
    }

    /**
     * Returns the statement {@link ListRewrite} for a body block, whether it is backed by a
     * {@link Block} or a {@link SwitchCase}.
     */
    public static ListRewrite getListRewriteForBody(ASTRewrite rewriter, BodyBlock body) {
        ASTNode node = body.getAstNode();
        if (node instanceof Block) {
            return rewriter.getListRewrite(node, Block.STATEMENTS_PROPERTY);
        } else if (node instanceof SwitchCase) {
            return rewriter.getListRewrite(node.getParent(), SwitchStatement.STATEMENTS_PROPERTY);
        }
        throw new IllegalArgumentException("Unsupported body node type: " + node.getClass());
    }
}
