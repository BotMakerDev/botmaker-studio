package com.botmaker.studio.blocks.var;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.*;

public class AssignmentBlock extends AbstractStatementBlock {

    private ExpressionBlock leftHandSide;
    private ExpressionBlock rightHandSide;
    private String operator;

    /**
     * Every assignment operator Java has, by the word the selector shows. The last six were missing until
     * 2026-09-24, so {@code flags |= MASK} drew a selector showing "set to" — and picking anything from it
     * wrote that over the author's operator.
     */
    public static final String[] ASSIGN_NAMES = {
            "set to", "add", "subtract", "multiply by", "divide by", "keep remainder of dividing by",
            "bitwise and with", "bitwise or with", "bitwise xor with",
            "shift left by", "shift right by", "shift right (unsigned) by"
    };

    public static final String[] ASSIGN_SYMBOLS = {
            "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>="
    };

    private static final String[] OPERATOR_NAMES = concat(ASSIGN_NAMES, "increment", "decrement");

    private static final String[] OPERATOR_SYMBOLS = concat(ASSIGN_SYMBOLS, "++", "--");

    private static String[] concat(String[] head, String... tail) {
        String[] all = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /** The word the selector shows for {@code symbol} ({@code "="} → "set to"), or the symbol itself. */
    public static String operatorName(String symbol) {
        for (int i = 0; i < OPERATOR_SYMBOLS.length; i++) {
            if (OPERATOR_SYMBOLS[i].equals(symbol)) return OPERATOR_NAMES[i];
        }
        return symbol;
    }

    public AssignmentBlock(String id, ExpressionStatement astNode) {
        super(id, astNode);
        initializeOperator(astNode);
    }

    private void initializeOperator(ExpressionStatement astNode) {
        if (astNode.getExpression() instanceof Assignment) {
            this.operator = ((Assignment) astNode.getExpression()).getOperator().toString();
        } else if (astNode.getExpression() instanceof PostfixExpression) {
            this.operator = ((PostfixExpression) astNode.getExpression()).getOperator().toString();
        } else if (astNode.getExpression() instanceof PrefixExpression) {
            this.operator = ((PrefixExpression) astNode.getExpression()).getOperator().toString();
        } else {
            this.operator = "=";
        }
    }

    public void setLeftHandSide(ExpressionBlock leftHandSide) { this.leftHandSide = leftHandSide; }
    public void setRightHandSide(ExpressionBlock rightHandSide) { this.rightHandSide = rightHandSide; }

    @Override
    protected BlockCategory category() {
        return BlockCategory.VARIABLES;
    }

    /**
     * {@code [target] [operator] [value] [+]} — with the last two absent for {@code ++} and {@code --}, which
     * have no value to assign.
     *
     * <p>The operator is declared as a {@code PICKER} in both states: a live selector when this block may be
     * edited, and the same word as a plain label when it may not. It stays one component because it is one
     * thing on screen — a locked block offering a dropdown that refuses every pick is the failure the whole
     * null-is-absence rule exists to avoid, and the read-only spelling is what the block already did.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                // The target is a slot to look at, never a drop target: what is being assigned *to* is chosen
                // by name, not by dragging a call onto it.
                .slot("target", () -> leftHandSide != null
                        ? leftHandSide.getUINode(context)
                        : createExpressionDropZone(context))
                .picker("operator", () -> operatorNode(context));

        if (operator.equals("++") || operator.equals("--")) return spec.build();

        return spec
                .slot("value", () -> rightHandSide != null
                        ? SentenceLayoutBuilder.expressionSlotNode(rightHandSide, context, ResolvedType.UNKNOWN)
                        : createEmptySlot(context, ResolvedType.UNKNOWN))
                .picker("change", () -> createAddButton(e ->
                        showExpressionMenu((javafx.scene.control.Button) e.getSource(), context)))
                .build();
    }

    /** The operator control: a selector, or a plain word when this block is locked. */
    private Node operatorNode(CodeEditorService context) {
        if (isReadOnly()) return SentenceLayoutBuilder.keywordNode(operatorDisplayName());
        return com.botmaker.studio.ui.render.components.SelectorComponents.createOperatorSelector(
                OPERATOR_NAMES,
                OPERATOR_SYMBOLS,
                operator,
                newOperator -> {
                    this.operator = newOperator;
                    if (this.astNode instanceof ExpressionStatement) {
                        Expression expr = ((ExpressionStatement) this.astNode).getExpression();
                        context.getCodeEditor().updateAssignmentOperator(expr, newOperator);
                    }
                });
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return BlockLayout.header()
                .withCustomNode(renderSpec(context))
                .withDeleteButton(deleteAction(context))
                .build();
    }

    /** The friendly name the selector would show for the current operator symbol ("=" → "set to"). */
    private String operatorDisplayName() {
        return operatorName(operator);
    }

    private void showExpressionMenu(javafx.scene.control.Button button, CodeEditorService context) {
        // UPDATED: Use ResolvedType instead of string types
        ResolvedType targetType = ResolvedType.UNKNOWN;

        if (leftHandSide != null && leftHandSide.getAstNode() != null) {
            Expression lhsExpr = (org.eclipse.jdt.core.dom.Expression) leftHandSide.getAstNode();
          ITypeBinding binding = lhsExpr.resolveTypeBinding();
            if (binding != null) {
                targetType = ResolvedType.of(binding);
            }
        }

      Expression toReplace = null;
        if (rightHandSide != null) {
            toReplace = (org.eclipse.jdt.core.dom.Expression) rightHandSide.getAstNode();
        }

        showExpressionMenuAndReplace(button, context, targetType, toReplace);
    }
}
