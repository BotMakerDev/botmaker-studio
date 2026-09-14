package com.botmaker.studio.blocks.flow;

import com.botmaker.studio.core.AbstractStatementBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.BodyBlock;
import com.botmaker.studio.core.BranchingBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * A <b>branch chain</b> — {@code found.when(m -> m.hasAny(ORE), () -> { … }).otherwise(() -> { … })} — drawn
 * as one row per link: the method as written, its condition as an editable boolean expression, and its body as
 * a droppable {@link BodyBlock}.
 *
 * <h2>It knows no library's vocabulary, and that is the point</h2>
 *
 * <p>This block replaced {@code MatchesSwitchBlock}, which existed only to edit a guarded
 * {@code switch (matches)} and therefore had to spell the SDK's {@code Matches}, its guard methods and the
 * pattern variable — the host holding one plugin's vocabulary on its behalf, with no way for a second plugin
 * to contribute a branching shape of its own. Everything drawn here comes from the <em>source</em> instead:
 * the captions are the method names the user's code actually uses, and a new branch copies one of them (see
 * {@code BranchChainHandler#branchMethodName}). So the same block draws any plugin's chain, and no SDK type
 * is named in this file.
 *
 * <p><b>The condition's lambda parameter is not drawn.</b> {@code m ->} is a second name for the value the
 * subject already names, and it is the library's choice rather than the user's — the same reasoning that took
 * the {@code found →} chip off {@code LambdaCallBlock}. The name is still in the source and still offered by
 * the body's expression menu; it is simply not a control.
 *
 * <p><b>Rows are addressed by index, never by AST node.</b> Every edit goes through {@code CodeEditor} with
 * this statement and a link index, so the block holds no JDT node that a re-parse could leave stale — the rule
 * {@code HostSlotContext} follows for the same reason.
 */
public class BranchChainBlock extends AbstractStatementBlock implements BlockWithChildren, BranchingBlock {

    /** One drawn link: what it is called, whether it can test anything, and the two blocks it owns. */
    public static final class LinkView {
        private final String method;
        private final boolean terminal;
        private ExpressionBlock condition;
        private BodyBlock body;

        LinkView(String method, boolean terminal) {
            this.method = method;
            this.terminal = terminal;
        }

        public void setCondition(ExpressionBlock condition) { this.condition = condition; }
        public void setBody(BodyBlock body) { this.body = body; }

        public String method() { return method; }
        public boolean isTerminal() { return terminal; }
        public BodyBlock body() { return body; }
    }

    private final String subject;
    private final List<LinkView> links = new ArrayList<>();

    public BranchChainBlock(String id, ASTNode astNode, String subject) {
        super(id, astNode);
        this.subject = subject == null ? "" : subject;
    }

    /** Appends a link in source order; the converter fills its condition and body afterwards. */
    public LinkView addLink(String method, boolean terminal) {
        LinkView link = new LinkView(method, terminal);
        links.add(link);
        return link;
    }

    @Override
    public List<CodeBlock> getChildren() {
        List<CodeBlock> children = new ArrayList<>();
        for (LinkView link : links) {
            if (link.condition != null) children.add(link.condition);
            if (link.body != null) children.add(link.body);
        }
        return children;
    }

    /**
     * One branch per link, captioned with what the source says — {@code when m.hasAny(ORE)} and
     * {@code otherwise} — so a compact renderer can flatten the chain without re-deriving any of it.
     */
    @Override
    public List<Branch> branches() {
        List<Branch> out = new ArrayList<>();
        for (LinkView link : links) {
            if (link.body == null) continue;
            out.add(new Branch(caption(link), link.body));
        }
        return out;
    }

    @Override
    protected BlockCategory category() {
        return BlockCategory.FLOW;
    }

    /**
     * {@code Branch on ⟨subject⟩}, then one row and one body per link — a spec whose length is the chain's
     * length, which is what makes this block worth declaring rather than drawing.
     *
     * <p>Component ids are positional ({@code link1}, {@code link1-add}), the same convention
     * {@code MethodInvocationBlock} uses for its arguments and for the same reason: the id has to name the same
     * thing across a re-parse so a renderer that reconciles two specs can tell a changed row from a new one.
     *
     * <p>Everything after the first body is chrome about a branch, so {@code CompactSpecRow} draws only the
     * {@code Branch on …} header in the HUD and lets the tree draw the links out of {@link #branches()}. The
     * captions there are the source's own method names, which is why nothing in this file spells a library's
     * vocabulary.
     */
    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .label("kw", () -> SentenceLayoutBuilder.keywordNode("Branch on"))
                .custom("subject", this::subjectChip);

        for (int i = 0; i < links.size(); i++) {
            LinkView link = links.get(i);
            int index = i;
            String id = "link" + i;

            spec.label(id, () -> SentenceLayoutBuilder.keywordNode(link.method()));
            // A terminal link gets no condition slot — there is nothing to test — and no ⊕, because a branch
            // inserted after the fallback could never run. Its ⊕ sits on the row before it, which is where a
            // user reaching for "one more branch" is already looking.
            if (!link.isTerminal()) {
                spec.slot(id + "-condition", () ->
                                SentenceLayoutBuilder.expressionSlotNode(link.condition, context, ResolvedType.BOOLEAN))
                        .picker(id + "-change", () -> createAddButton(e -> showExpressionMenuAndReplace(
                                (Button) e.getSource(),
                                context,
                                ResolvedType.BOOLEAN,
                                link.condition != null ? (Expression) link.condition.getAstNode() : null)));
            }
            spec.custom(id + "-spacer", BlockUIComponents::createSpacer)
                    .picker(id + "-add", () -> addBranchButton(context, index))
                    .picker(id + "-delete", () -> isReadOnly() || links.size() <= 1 ? null
                            : BlockUIComponents.createDeleteButton(
                                    () -> context.getCodeEditor().removeBranchLink((Statement) this.astNode, index)))
                    .body(id + "-body", () -> createIndentedBody(link.body, context, "branch-chain-body"));
        }

        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpecStacked(context)
                .withStyleClass("branch-chain-block")
                .withDeleteButton(deleteAction(context))
                .build();
    }

    /** {@code found} — says what the chain is over without claiming to know its type. */
    private Label subjectChip() {
        Label chip = new Label(subject.isBlank() ? "…" : subject);
        chip.getStyleClass().addAll("block-chip", "branch-chain-subject");
        chip.setTooltip(new Tooltip("Every branch below asks about this one value, as it was at this moment."));
        return chip;
    }

    /**
     * The {@code +} that inserts another branch after this row. Null — no control at all — when there is
     * nothing sensible to insert: a read-only block, a row that is the fallback, or a chain made only of a
     * fallback, which has no branch method for a new link to copy.
     */
    private Button addBranchButton(CodeEditorService context, int index) {
        if (isReadOnly() || links.get(index).isTerminal()) return null;
        Button button = createAddButton(e ->
                context.getCodeEditor().addBranchLink((Statement) this.astNode, index));
        if (button != null) button.setTooltip(new Tooltip("Add another branch after this one"));
        return button;
    }

    /** A branch's caption for a compact renderer: {@code when m.hasAny(ORE)}, or just the method when terminal. */
    private static String caption(LinkView link) {
        if (link.isTerminal()) return link.method();
        ExpressionBlock condition = link.condition;
        String text = (condition == null || condition.getAstNode() == null) ? "…" : condition.getAstNode().toString();
        return link.method() + " " + text;
    }

    /** The first condition is what a breakpoint lands on; a chain of only a fallback falls back to the call. */
    @Override
    public com.botmaker.studio.core.CodeBlock getHighlightTarget() {
        for (LinkView link : links) {
            if (link.condition != null) return link.condition;
        }
        return this;
    }
}
