package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.parser.EditContext;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.util.List;

public class EnumManipulationHandler {

    /**
     * Replaces an expression with {@code enumType.constantName} (an enum constant or a {@code Class.FIELD}
     * reference — same {@link QualifiedName} shape). Also imports {@code enumType} so the reference resolves:
     * picking {@code Key.B} from the enum menu used to emit a bare {@code Key.B} with no
     * {@code import …interaction.Key}, giving the generated bot a "cannot find symbol Key". A same-file /
     * same-package type (e.g. an activity's nested {@code Outcome}) resolves to no import, so this is safe there.
     */
    public static String replaceWithEnumConstant(EditContext ctx, String originalCode,
                                                 Expression toReplace, String enumType, String constantName) {
        AST ast = ctx.ast();

        // A nested enum is named through its outer class (Activities.Collect), which is what gets imported.
        QualifiedName newConst = ast.newQualifiedName(
                ast.newName(enumType),
                ast.newSimpleName(constantName)
        );

        ctx.rewriter().replace(toReplace, newConst, null);
        int dot = enumType.indexOf('.');
        ctx.addImportForSimpleName(dot < 0 ? enumType : enumType.substring(0, dot));
        return ctx.applyTo(originalCode);
    }

    /** How code names {@code enumType}: its simple name, or {@code Activities.Collect} for a type nested in a class. */
    public static String referableName(ResolvedType enumType) {
        if (enumType instanceof ResolvedType.Bound bound && bound.binding().isMember()) {
            StringBuilder name = new StringBuilder(bound.binding().getErasure().getName());
            for (ITypeBinding outer = bound.binding().getDeclaringClass(); outer != null; outer = outer.getDeclaringClass()) {
                name.insert(0, outer.getErasure().getName() + ".");
            }
            return name.toString();
        }
        return enumType.simpleName();
    }

    // ... [Previous methods omitted for brevity, ensure they are kept]
    // Re-inserting required methods to ensure compilation if copy-pasted:
    public static String addEnumToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl, String enumName, int index) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        EnumDeclaration newEnum = ast.newEnumDeclaration();
        newEnum.setName(ast.newSimpleName(enumName));
        newEnum.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        EnumConstantDeclaration const1 = ast.newEnumConstantDeclaration();
        const1.setName(ast.newSimpleName("OPTION_A"));
        newEnum.enumConstants().add(const1);
        ListRewrite listRewrite = rewriter.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        listRewrite.insertAt(newEnum, index, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
    /** {@code public enum <name> { <constants> }} as a member of {@code typeDecl}, at {@code index}. */
    public static String addEnumToClass(CompilationUnit cu, String originalCode, TypeDeclaration typeDecl,
                                        com.botmaker.studio.palette.EnumDraft draft, int index) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        EnumDeclaration newEnum = ast.newEnumDeclaration();
        newEnum.setName(ast.newSimpleName(draft.name()));
        newEnum.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        for (String constant : draft.constants()) {
            EnumConstantDeclaration declaration = ast.newEnumConstantDeclaration();
            declaration.setName(ast.newSimpleName(constant));
            newEnum.enumConstants().add(declaration);
        }
        ListRewrite listRewrite = rewriter.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        listRewrite.insertAt(newEnum, Math.min(index, typeDecl.bodyDeclarations().size()), null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
    public static String deleteEnumFromClass(CompilationUnit cu, String originalCode, EnumDeclaration enumDecl) {
        return AstRewriteHelper.removeNode(cu, originalCode, enumDecl);
    }
    public static String addEnumConstant(CompilationUnit cu, String originalCode, EnumDeclaration enumNode, String constantName) {
        AST ast = cu.getAST();
        ASTRewrite rewriter = ASTRewrite.create(ast);
        ListRewrite listRewrite = rewriter.getListRewrite(enumNode, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        EnumConstantDeclaration newConst = ast.newEnumConstantDeclaration();
        newConst.setName(ast.newSimpleName(constantName));
        listRewrite.insertLast(newConst, null);
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
    public static String deleteEnumConstant(CompilationUnit cu, String originalCode, EnumDeclaration enumNode, int index) {
        ASTRewrite rewriter = ASTRewrite.create(cu.getAST());
        ListRewrite listRewrite = rewriter.getListRewrite(enumNode, EnumDeclaration.ENUM_CONSTANTS_PROPERTY);
        List<?> constants = enumNode.enumConstants();
        if (index >= 0 && index < constants.size()) {
            listRewrite.remove((ASTNode) constants.get(index), null);
        }
        return AstRewriteHelper.applyRewrite(rewriter, originalCode);
    }
}
