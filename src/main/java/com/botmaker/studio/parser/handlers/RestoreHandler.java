package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.vcs.BlockDiff;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puts one function back as an earlier version had it ({@code docs/refactor/39-versions.md} §6): the old
 * declaration replaces the live one, or — when the file no longer has it — lands where it used to sit among
 * its siblings. Pure {@code (cu, code) -> code}, like every handler.
 *
 * <p>The old declaration is copied <b>as the text the user had</b>, through a string placeholder, not rebuilt
 * as a tree: it is their own code coming back, comments and layout included, and nothing here decides what it
 * says. (The rule against placeholders is about a value Studio writes — {@code 36} — which this is not.)
 */
public final class RestoreHandler {

    private RestoreHandler() {}

    /**
     * {@code code} with the function {@code signature} ({@link BlockDiff#signature}) as {@code versionSource}
     * has it, plus the imports its body names that the file lacks; null when the version has no such
     * function, or no longer has the type it belonged to.
     */
    public static String replaceMethod(CompilationUnit cu, String code, String versionSource, String signature) {
        CompilationUnit old = SourceParser.parse(versionSource);
        MethodDeclaration from = BlockDiff.methods(old).get(signature);
        if (from == null) return null;
        String text = versionSource.substring(from.getStartPosition(), from.getStartPosition() + from.getLength());

        ASTRewrite rewrite = ASTRewrite.create(cu.getAST());
        ASTNode placeholder = rewrite.createStringPlaceholder(text, ASTNode.METHOD_DECLARATION);
        Map<String, MethodDeclaration> live = BlockDiff.methods(cu);
        MethodDeclaration current = live.get(signature);
        if (current != null) {
            rewrite.replace(current, placeholder, null);
        } else if (!addBack(rewrite, placeholder, from, live, cu)) {
            return null;
        }
        addImports(rewrite, cu, old, from);
        return AstRewriteHelper.applyRewrite(rewrite, code);
    }

    /**
     * Inserts {@code placeholder} into the live type that matches {@code from}'s: after the nearest function
     * above it in the old type that the file still has, else before the nearest below, else last.
     */
    private static boolean addBack(ASTRewrite rewrite, ASTNode placeholder, MethodDeclaration from,
                                   Map<String, MethodDeclaration> live, CompilationUnit cu) {
        AbstractTypeDeclaration oldType = (AbstractTypeDeclaration) from.getParent();
        AbstractTypeDeclaration liveType = type(cu, typePath(from));
        if (liveType == null) return false;
        ListRewrite members = rewrite.getListRewrite(liveType, liveType.getBodyDeclarationsProperty());
        List<?> siblings = oldType.bodyDeclarations();
        int at = siblings.indexOf(from);
        for (int k = at - 1; k >= 0; k--) {
            MethodDeclaration anchor = liveTwin(siblings.get(k), live, liveType);
            if (anchor != null) {
                members.insertAfter(placeholder, anchor, null);
                return true;
            }
        }
        for (int k = at + 1; k < siblings.size(); k++) {
            MethodDeclaration anchor = liveTwin(siblings.get(k), live, liveType);
            if (anchor != null) {
                members.insertBefore(placeholder, anchor, null);
                return true;
            }
        }
        members.insertLast(placeholder, null);
        return true;
    }

    private static MethodDeclaration liveTwin(Object oldMember, Map<String, MethodDeclaration> live,
                                              AbstractTypeDeclaration liveType) {
        if (!(oldMember instanceof MethodDeclaration m)) return null;
        MethodDeclaration twin = live.get(BlockDiff.signature(m));
        return twin != null && twin.getParent() == liveType ? twin : null;
    }

    private static String typePath(BodyDeclaration member) {
        String signature = BlockDiff.signature((MethodDeclaration) member);
        return signature.substring(0, signature.indexOf('#'));
    }

    private static AbstractTypeDeclaration type(CompilationUnit cu, String path) {
        List<?> level = cu.types();
        AbstractTypeDeclaration found = null;
        for (String name : path.split("\\.")) {
            found = null;
            for (Object o : level) {
                if (o instanceof AbstractTypeDeclaration t && t.getName().getIdentifier().equals(name)) found = t;
            }
            if (found == null) return null;
            level = found.bodyDeclarations();
        }
        return found;
    }

    /** Each single-type import of the old file whose simple name the old function uses and the file lacks. */
    private static void addImports(ASTRewrite rewrite, CompilationUnit cu, CompilationUnit old,
                                   MethodDeclaration from) {
        Set<String> used = new HashSet<>();
        from.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                used.add(node.getIdentifier());
                return true;
            }
        });
        Set<String> have = new HashSet<>();
        for (Object o : cu.imports()) have.add(key((ImportDeclaration) o));
        AST ast = cu.getAST();
        ListRewrite imports = rewrite.getListRewrite(cu, CompilationUnit.IMPORTS_PROPERTY);
        for (Object o : old.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            if (imp.isOnDemand() || have.contains(key(imp))) continue;
            String name = imp.getName().getFullyQualifiedName();
            if (!used.contains(name.substring(name.lastIndexOf('.') + 1))) continue;
            ImportDeclaration added = ast.newImportDeclaration();
            added.setName(ast.newName(name));
            added.setStatic(imp.isStatic());
            imports.insertLast(added, null);
        }
    }

    private static String key(ImportDeclaration imp) {
        return (imp.isStatic() ? "static " : "") + imp.getName().getFullyQualifiedName()
                + (imp.isOnDemand() ? ".*" : "");
    }
}
