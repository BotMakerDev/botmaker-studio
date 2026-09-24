package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.components.LayoutComponents;
import com.botmaker.studio.ui.render.layout.BlockLayout;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.SwitchExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * A switch that is a value — {@code int days = switch (month) { case FEB -> 28; default -> 31; };}. Drawn as
 * {@code switch ⟨subject⟩} over one row per case: {@code case ⟨label⟩, ⟨label⟩ → ⟨value⟩} when the case gives
 * a value, over a body to drop blocks into when it is a block ending in {@code yield}, and over the statement
 * block when it throws.
 *
 * <p>Only the arrow form is drawn. A switch expression written with {@code case X:} labels is a list of
 * statements with {@code yield}s among them, and is shown as source ({@link SourceExpressionBlock}).
 */
public class SwitchExpressionBlock extends AbstractExpressionBlock implements BlockWithChildren {

    /** One {@code case … ->} of the switch: its labels (none for {@code default}) and exactly one result. */
    public static final class Case {
        private final boolean isDefault;
        private final List<ExpressionBlock> labels = new ArrayList<>();
        private ExpressionBlock value;
        private BodyBlock body;
        private StatementBlock statement;

        public Case(boolean isDefault) {
            this.isDefault = isDefault;
        }

        public void addLabel(ExpressionBlock label) { labels.add(label); }
        public void setValue(ExpressionBlock value) { this.value = value; }
        public void setBody(BodyBlock body) { this.body = body; }
        public void setStatement(StatementBlock statement) { this.statement = statement; }

        List<CodeBlock> blocks() {
            List<CodeBlock> out = new ArrayList<>(labels);
            if (value != null) out.add(value);
            if (body != null) out.add(body);
            if (statement != null) out.add(statement);
            return out;
        }
    }

    private ExpressionBlock subject;
    private final List<Case> cases = new ArrayList<>();

    public SwitchExpressionBlock(String id, SwitchExpression astNode) {
        super(id, astNode);
    }

    public void setSubject(ExpressionBlock subject) { this.subject = subject; }
    public void addCase(Case switchCase) { cases.add(switchCase); }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        if (subject != null) children.add(subject);
        for (Case c : cases) children.addAll(c.blocks());
        return children;
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("switch"));
        addOperand(spec, "subject", subject, context, ResolvedType.UNKNOWN);
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            int index = i;
            spec.body("case" + i, () -> caseNode(c, index, context));
        }
        spec.body("add-case", () -> {
            if (isReadOnly()) return null;
            Button add = BlockUIComponents.createAddButton(e -> context.getCodeEditor()
                    .addCaseToSwitchExpression((SwitchExpression) getAstNode()));
            add.setText("+ case");
            add.getStyleClass().add("switch-add-case");
            javafx.scene.control.Tooltip.install(add, new javafx.scene.control.Tooltip(
                    "Add a case with a label this switch does not use yet"));
            return add;
        });
        return spec.build();
    }

    /** One case: its label row, then — for a block or a throw — what it runs, indented beneath. */
    private Node caseNode(Case c, int index, CodeEditorService context) {
        ResolvedType subjectType = typeOf(subject);
        ResolvedType valueType = ownType();
        SentenceLayoutBuilder row = BlockLayout.sentence();
        if (c.isDefault) {
            row.addKeyword("default");
        } else {
            row.addKeyword("case");
            for (int i = 0; i < c.labels.size(); i++) {
                ExpressionBlock label = c.labels.get(i);
                if (i > 0) row.addLabel(",");
                row.addExpressionSlot(label, context, subjectType);
                row.addNode(changeButton(label, subjectType, context));
            }
        }
        row.addKeyword("→");
        if (c.value != null) {
            row.addExpressionSlot(c.value, context, valueType);
            row.addNode(changeButton(c.value, valueType, context));
        }
        if (!isReadOnly() && cases.size() > 1) {
            Button remove = BlockUIComponents.createDeleteButton(() -> context.getCodeEditor()
                    .removeCaseFromSwitchExpression((SwitchExpression) getAstNode(), index));
            javafx.scene.control.Tooltip.install(remove, new javafx.scene.control.Tooltip("Remove this case"));
            row.addNode(remove);
        }
        VBox node = new VBox(2, row.build());
        if (c.body != null) {
            node.getChildren().add(LayoutComponents.createIndentedBody(c.body.getUINode(context), "switch-case-body"));
        } else if (c.statement != null) {
            node.getChildren().add(LayoutComponents.createIndentedBody(c.statement.getUINode(context), "switch-case-body"));
        }
        return node;
    }

    private Button changeButton(ExpressionBlock value, ResolvedType expected, CodeEditorService context) {
        Button change = createChangeButton(e -> showExpressionMenuAndReplace((Button) e.getSource(), context,
                expected, (Expression) value.getAstNode()));
        if (change != null) change.getStyleClass().add("small-change-button");
        return change;
    }

    private static ResolvedType typeOf(ExpressionBlock block) {
        if (block == null || !(block.getAstNode() instanceof Expression expression)) return ResolvedType.UNKNOWN;
        ITypeBinding binding = expression.resolveTypeBinding();
        return binding == null ? ResolvedType.UNKNOWN : ResolvedType.of(binding);
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context).build();
    }
}
