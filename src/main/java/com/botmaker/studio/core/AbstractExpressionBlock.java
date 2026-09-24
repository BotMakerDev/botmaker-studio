package com.botmaker.studio.core;

import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.core.render.BlockShape;
import com.botmaker.studio.parser.handlers.ExpressionFormHandler;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import com.botmaker.studio.ui.render.menu.TypePicker;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.PatternInstanceofExpression;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;

import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.SelectorComponents;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;

import java.util.function.Consumer;
import java.util.function.Predicate;

public abstract class AbstractExpressionBlock extends AbstractCodeBlock implements ExpressionBlock {

    /** Style class flagging an identifier/field still holding its generated default name (styled in blocks.css). */
    protected static final String UNEDITED_STYLE_CLASS = "unedited-identifier";

    /** True while this block still carries an auto-generated default name the user hasn't confirmed. */
    protected boolean isUnedited = false;

    public AbstractExpressionBlock(String id, ASTNode astNode) {
        super(id, astNode);
    }

    /** A value is round-ended, a yes/no value square-ended — read off the expression, see {@link BlockShape#ofValue}. */
    @Override
    protected BlockShape shape() {
        return BlockShape.ofValue(astNode);
    }

    public boolean isUnedited() { return isUnedited; }

    /** Adds the unedited marker style class to {@code container} when this block is still unedited. */
    protected void applyUneditedClass(Node container) {
        if (isUnedited) {
            container.getStyleClass().add(UNEDITED_STYLE_CLASS);
        }
    }

    /** Clears the unedited flag and removes the marker style class from the rendered node. */
    public void markAsEdited() {
        this.isUnedited = false;
        if (uiNode != null) {
            uiNode.getStyleClass().remove(UNEDITED_STYLE_CLASS);
        }
    }

    protected Label createKeywordLabel(String text) { return BlockUIComponents.createKeywordLabel(text); }
    protected Label createOperatorLabel(String text) { return BlockUIComponents.createOperatorLabel(text); }
    protected ComboBox<String> createOperatorSelector(String[] names, String[] symbols, String currentSymbol, Consumer<String> onSymbolChange) {
        return SelectorComponents.createOperatorSelector(names, symbols, currentSymbol, onSymbolChange);
    }

    protected void showExpressionMenuAndReplace(Button button,
                                                CodeEditorService context,
                                                ResolvedType targetType,
                                                Expression toReplace) {
        showExpressionMenuAndReplace(button, context, targetType, toReplace, x -> true);
    }

    /**
     * Builds one call-argument "pill" for {@code arg}: its rendered node, a "+" change button wired to the
     * type-aware replace menu for {@code paramType}, and an optional {@code leadingLabel} (a parameter name or
     * type hint, may be null). Shared by {@code InstantiationBlock} / {@code MethodInvocationBlock} — only the
     * leading label differs between them; the pill's wash follows its surface, from CSS.
     */
    protected Node createArgumentPill(CodeEditorService context, ExpressionBlock arg, ResolvedType paramType,
                                      Node leadingLabel) {
        // Null when read-only: the pill renders as its argument alone, with nothing to change it by.
        Button changeBtn = createChangeButton(e ->
                showExpressionMenuAndReplace((Button) e.getSource(), context, paramType, (Expression) arg.getAstNode()));
        return BlockUIComponents.createArgumentPill(leadingLabel, arg.getUINode(context), changeBtn);
    }

    /**
     * Declares one operand of this value: its slot, and beside it the small button that replaces it from the
     * menu — the pair every operand of an operator block is. The button is absent on a locked block and on an
     * empty slot, which has nothing to replace.
     */
    protected void addOperand(ComponentSpec.Builder spec, String id, ExpressionBlock value,
                              CodeEditorService context, ResolvedType expected) {
        spec.slot(id, () -> SentenceLayoutBuilder.expressionSlotNode(value, context, expected))
                .picker(id + "-change", () -> {
                    if (value == null) return null;
                    return createChangeButton(e -> showExpressionMenuAndReplace((Button) e.getSource(),
                            context, expected, (Expression) value.getAstNode()));
                });
    }

    /**
     * The type {@code owner} names — a cast's, a check's, a class literal's, a declaration's, an array's element
     * — as a {@link com.botmaker.studio.ui.render.components.TypeChip} whose parts are picked, not typed; the
     * same chip with nothing to click when this block is locked. It was a text field, where {@code int[]} and
     * {@code Map<String, List<Point>>} were one run of punctuation to type right. A type the lists do not hold
     * is still typed, in the picker's search.
     */
    protected Node typeField(ASTNode owner, CodeEditorService context) {
        return TypePicker.chip(ExpressionFormHandler.typeNode(owner), typeOptions(owner), !isReadOnly(), context,
                owner, text -> context.getCodeEditor().setExpressionType(owner, text));
    }

    /** What each owner's type may be: a check names no primitive, an array creation names its element. */
    private static TypePicker.Options typeOptions(ASTNode owner) {
        return switch (owner) {
            case InstanceofExpression check -> TypePicker.Options.of(TypePicker.Filter.REFERENCE);
            case PatternInstanceofExpression check -> TypePicker.Options.of(TypePicker.Filter.REFERENCE);
            case TypeLiteral literal -> TypePicker.Options.of(TypePicker.Filter.ANY).withVoid();
            case ArrayCreation creation -> TypePicker.Options.of(TypePicker.Filter.ANY).withoutArrays();
            case VariableDeclarationExpression declaration ->
                    TypePicker.Options.of(TypePicker.Filter.ANY).withTypeArguments();
            default -> TypePicker.Options.of(TypePicker.Filter.ANY);
        };
    }

    /** This value's type from its own binding, or {@code UNKNOWN} when the tree has none. */
    protected ResolvedType ownType() {
        if (!(astNode instanceof Expression expression)) return ResolvedType.UNKNOWN;
        ITypeBinding binding = expression.resolveTypeBinding();
        return binding == null ? ResolvedType.UNKNOWN : ResolvedType.of(binding);
    }

    protected void showExpressionMenuAndReplace(Button button,
                                                CodeEditorService context,
                                                ResolvedType targetType,
                                                Expression toReplace,
                                                Predicate<ExpressionType> filter) {
        // See AbstractStatementBlock: a read-only block builds nothing that opens this.
        if (isReadOnly()) return;

        // Use 'this.astNode' as context for scope resolution
        ContextMenu menu = ExpressionMenu.create(
                targetType,
                false,
                context,
                this.astNode, // PASS THE CONTEXT NODE
                filter,
                selection -> applyExpressionSelection(context, toReplace, selection)
        );
        menu.show(button, javafx.geometry.Side.BOTTOM, 0, 0);
    }
}
