package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.types.ResolvedType;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.Statement;

public class IfBlock extends AbstractStatementBlock implements BlockWithChildren, BranchingBlock {

    private ExpressionBlock condition;
    private BodyBlock thenBody;
    private StatementBlock elseStatement;

    // Flag to alter rendering if this block is part of an 'else if' chain
    private boolean isElseIf = false;

    public IfBlock(String id, IfStatement astNode) {
        super(id, astNode);
    }

    public void setCondition(ExpressionBlock condition) { this.condition = condition; }
    public void setThenBody(BodyBlock thenBody) { this.thenBody = thenBody; }
    public void setElseStatement(StatementBlock elseStatement) { this.elseStatement = elseStatement; }

    public void setIsElseIf(boolean isElseIf) {
        this.isElseIf = isElseIf;
    }

    @Override
    public java.util.List<CodeBlock> getChildren() {
        java.util.List<CodeBlock> children = new java.util.ArrayList<>();
        if (condition != null) children.add(condition);
        if (thenBody != null) children.add(thenBody);
        // The else branch is either a BodyBlock or a nested IfBlock (an else-if chain) — both are CodeBlocks.
        if (elseStatement != null) children.add(elseStatement);
        return children;
    }

    /**
     * The {@code then} body (uncaptioned — it reads as the {@code if}'s own body), then the else branch when
     * there is one: {@code "else"} over a {@link BodyBlock}, or {@code "else if (…)"} over the nested
     * {@link IfBlock} that continues the chain. A flattening renderer recurses into that nested block's own
     * branches, which is what keeps an {@code else if} chain drawn flat rather than stepping right each time.
     */
    @Override
    public java.util.List<Branch> branches() {
        java.util.List<Branch> out = new java.util.ArrayList<>();
        if (thenBody != null) out.add(new Branch(null, thenBody));
        if (elseStatement instanceof IfBlock chained) {
            out.add(new Branch("else if (" + conditionText(chained) + ")", chained));
        } else if (elseStatement != null) {
            out.add(new Branch("else", elseStatement));
        }
        return out;
    }

    /** {@code chained}'s condition as source text, for a caption. {@code "…"} when the slot is still empty. */
    private static String conditionText(IfBlock chained) {
        ExpressionBlock c = chained.condition;
        return (c == null || c.getAstNode() == null) ? "…" : c.getAstNode().toString();
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FLOW;
    }

    /**
     * {@code If ⟨condition⟩ ⊕}, the {@code then} body, and then whichever tail this {@code if} has — the first
     * spec here whose <em>shape</em> varies, because an {@code if} is three different sentences depending on
     * what follows it.
     *
     * <p>The three tails are declared rather than drawn: an {@code else if} is one {@code BODY} holding the
     * nested block that continues the chain, a plain {@code else} is a row ({@code Else ⊕ ✕}) followed by its
     * own {@code BODY}, and an {@code if} with no else is a lone ⊕. Everything after the first body is branch
     * chrome, which is exactly the tail {@code CompactSpecRow} drops for a {@link BranchingBlock}: the HUD
     * already draws those branches as captioned rows out of {@link #branches()}, so this spec and that method
     * describe the same thing at two densities instead of disagreeing about it.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode(isElseIf ? "Else If" : "If"))
                .slot("condition", () ->
                        SentenceLayoutBuilder.expressionSlotNode(condition, context, ResolvedType.BOOLEAN))
                // A change button rather than the header builder's own, so the handler can capture the event
                // source: the menu opens on the button that was clicked.
                .picker("change", () -> createAddButton(e ->
                        showExpressionMenuAndReplace((Button) e.getSource(), context, ResolvedType.BOOLEAN,
                                condition != null ? (Expression) condition.getAstNode() : null)))
                .body("then", () -> thenBody == null ? null : createIndentedBody(thenBody, context, "if-body"));

        if (elseStatement instanceof IfBlock chained) {
            spec.body("else-if", () -> chainedNode(chained, context));
        } else if (elseStatement instanceof BodyBlock elseBody) {
            spec.label("else-kw", () -> SentenceLayoutBuilder.keywordNode("Else"))
                    .picker("else-to-else-if", () -> createAddButton(e ->
                            context.getCodeEditor().convertElseToElseIf((IfStatement) this.astNode)))
                    .custom("else-spacer", BlockUIComponents::createSpacer)
                    // Removing the else is an edit like any other: no control on a locked block.
                    .picker("else-delete", () -> isReadOnly() ? null
                            : BlockUIComponents.createDeleteButton(() ->
                                    context.getCodeEditor().deleteElseFromIfStatement((IfStatement) this.astNode)))
                    .body("else-body", () -> createIndentedBody(elseBody, context, "if-body"));
        } else {
            spec.picker("add-else", () -> createAddButton(e ->
                    context.getCodeEditor().addElseToIfStatement((IfStatement) this.astNode)));
        }

        return spec.build();
    }

    /**
     * The nested {@code else if}, drawn by the block itself so the chain stays flat on screen.
     *
     * <p>The child comes with its own gutter padding; it is pulled back left by exactly the gutter width so
     * {@code Else If} aligns with {@code If} rather than stepping right once per link.
     */
    private Node chainedNode(IfBlock chained, CodeEditorService context) {
        chained.setIsElseIf(true);
        Node node = chained.getUINode(context);
        double gutter = com.botmaker.studio.ui.render.theme.BlockTheme.current().spacing().gutter();
        VBox.setMargin(node, new Insets(0, 0, 0, -gutter));
        return node;
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withStyleClass("if-block")
                .withDeleteButton(deleteAction(context))
                .build();
    }

    @Override
    public int getBreakpointLine(CompilationUnit cu) {
        if (condition != null) return condition.getBreakpointLine(cu);
        return super.getBreakpointLine(cu);
    }

    @Override
    public com.botmaker.studio.core.CodeBlock getHighlightTarget() {
        return condition != null ? condition : this;
    }
}
