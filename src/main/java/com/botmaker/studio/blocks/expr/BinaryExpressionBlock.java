package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.layout.ExpressionSlots;
import com.botmaker.studio.ui.render.layout.WrappingSentencePane;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.Expression;

public class BinaryExpressionBlock extends AbstractExpressionBlock {

    private ExpressionBlock leftOperand;
    private ExpressionBlock rightOperand;
    private final java.util.List<ExpressionBlock> extendedOperands = new java.util.ArrayList<>();
    private String operator;
    private final ITypeBinding returnType;

    private static final String[] MATH_OPERATOR_NAMES = { "plus", "minus", "times", "divided by", "modulo" };
    private static final String[] MATH_OPERATOR_SYMBOLS = { "+", "-", "*", "/", "%" };

    public BinaryExpressionBlock(String id, InfixExpression astNode) {
        super(id, astNode);
        this.operator = astNode.getOperator().toString();
        this.returnType = astNode.resolveTypeBinding();
    }

    public void setLeftOperand(ExpressionBlock leftOperand) { this.leftOperand = leftOperand; }
    public void setRightOperand(ExpressionBlock rightOperand) { this.rightOperand = rightOperand; }
    /** An operand after the second, in {@code a + b + c}: the same operator joins each one on. */
    public void addExtendedOperand(ExpressionBlock operand) { extendedOperands.add(operand); }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // Wrapping rows, not plain HBoxes: a sum nested in a sentence folds onto a second line when the canvas
        // is narrow (or zoomed in) instead of holding every row above it at its one-line width.
        HBox container = new WrappingSentencePane(5);
        container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        HBox expressionBox = new WrappingSentencePane(5);
        expressionBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // Left operand + Change Button (null when read-only — nothing to change a locked operand by)
        if (leftOperand != null) {
            Node leftNode = leftOperand.getUINode(context);
            ExpressionSlots.makeDroppable(leftNode, leftOperand, context, ResolvedType.UNKNOWN);
            expressionBox.getChildren().add(leftNode);
            javafx.scene.control.Button changeLeft = createChangeButton(e ->
                    showExpressionMenuAndReplace((Button)e.getSource(), context, ResolvedType.INT,
                            (Expression) leftOperand.getAstNode())
            );
            if (changeLeft != null) {
                changeLeft.getStyleClass().add("small-change-button");
                expressionBox.getChildren().add(changeLeft);
            }
        }

        // Operator Selector (a plain label when read-only: no live control on a locked block)
        if (isMathOperator(operator) && !isReadOnly()) {
            javafx.scene.control.ComboBox<String> selector = createOperatorSelector(
                    MATH_OPERATOR_NAMES,
                    MATH_OPERATOR_SYMBOLS,
                    operator,
                    newOp -> {
                        this.operator = newOp;
                        context.getCodeEditor().updateBinaryOperator((InfixExpression) this.astNode, newOp);
                    }
            );
            expressionBox.getChildren().add(selector);
        } else {
            expressionBox.getChildren().add(createOperatorLabel(operator));
        }

        // Right operand + Change Button
        if (rightOperand != null) {
            Node rightNode = rightOperand.getUINode(context);
            ExpressionSlots.makeDroppable(rightNode, rightOperand, context, ResolvedType.UNKNOWN);
            expressionBox.getChildren().add(rightNode);
            javafx.scene.control.Button changeRight = createChangeButton(e ->
                    showExpressionMenuAndReplace((Button)e.getSource(), context, ResolvedType.INT,
                            (Expression) rightOperand.getAstNode())
            );
            if (changeRight != null) {
                changeRight.getStyleClass().add("small-change-button");
                expressionBox.getChildren().add(changeRight);
            }
        }

        // `a + b + c` is one InfixExpression with `c` as an extended operand; drawn from left and right alone it
        // read `a + b`, and `c` was invisible.
        for (ExpressionBlock extra : extendedOperands) {
            expressionBox.getChildren().add(createOperatorLabel(operator));
            Node extraNode = extra.getUINode(context);
            ExpressionSlots.makeDroppable(extraNode, extra, context, ResolvedType.UNKNOWN);
            expressionBox.getChildren().add(extraNode);
            Button changeExtra = createChangeButton(e ->
                    showExpressionMenuAndReplace((Button) e.getSource(), context, ResolvedType.INT,
                            (Expression) extra.getAstNode()));
            if (changeExtra != null) {
                changeExtra.getStyleClass().add("small-change-button");
                expressionBox.getChildren().add(changeExtra);
            }
        }

        container.getChildren().add(expressionBox);

        // Type indicator
        String typeName = (returnType != null) ? returnType.getName() : "unknown";
        javafx.scene.control.Label typeLabel = new javafx.scene.control.Label("→ " + typeName);
        typeLabel.getStyleClass().add("type-indicator-label");
        container.getChildren().add(typeLabel);

        return container;
    }

    private boolean isMathOperator(String op) {
        for (String mathOp : MATH_OPERATOR_SYMBOLS) {
            if (mathOp.equals(op)) return true;
        }
        return false;
    }
}
