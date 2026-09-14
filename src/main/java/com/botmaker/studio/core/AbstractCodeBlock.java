package com.botmaker.studio.core;

import com.botmaker.studio.core.render.BlockDecorator;
import com.botmaker.studio.core.render.GutterDecorator;
import com.botmaker.studio.core.render.InteractionDecorator;
import com.botmaker.studio.core.render.ReadOnlyDecorator;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.ui.dnd.BlockEvent;
import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.ExpressionType;
import com.botmaker.studio.ui.render.components.BlockUIComponents;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;

import java.util.List;

public abstract class AbstractCodeBlock implements CodeBlock {
    protected final String id;

    // Not final since 2026-09-14: BlockReuse re-points a surviving block at the node it now describes.
    // Assigned in the constructor and then only by adopt(), which is the whole of the mutation.
    protected ASTNode astNode;

    protected Node uiNode;
    private Tooltip errorTooltip;

    // Breakpoint state — exposed as a property so the gutter circle and the :breakpoint pseudo-class
    // both track it without manual visual refreshes.
    private final BooleanProperty breakpointActive = new SimpleBooleanProperty(false);

    // Read-Only State
    protected boolean isReadOnly = false;

    // Cross-cutting state styling (backed by blocks.css), and the one-time decoration pipeline.
    private static final PseudoClass HIGHLIGHTED = PseudoClass.getPseudoClass("highlighted");
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass BREAKPOINT = PseudoClass.getPseudoClass("breakpoint");

    private static final List<BlockDecorator> DECORATORS = List.of(
            new GutterDecorator(),
            new ReadOnlyDecorator(),
            new InteractionDecorator()
    );

    public AbstractCodeBlock(String id, ASTNode astNode) {
        this.id = id;
        this.astNode = astNode;
    }

    @Override
    public String getId() { return id; }

    @Override
    public ASTNode getAstNode() { return astNode; }

    /**
     * Re-points this block at the node it now describes, after a re-parse produced a structurally identical
     * subtree.
     *
     * <p>This is the whole of what makes block reuse safe, and it works because of how the blocks are
     * written: a handler that needs its AST node reads {@code this.astNode} <em>when it fires</em>, not when
     * it was built. Re-pointing the field before anything can fire therefore leaves every such handler
     * correct, with no rebinding and no per-component opt-in.
     *
     * <p>Called only by {@link com.botmaker.studio.parser.BlockReuse}, which has already established that the
     * two nodes carry identical source text at an identical structural path. Do not call it from a block: a
     * block re-pointing itself is a block deciding it survived an edit, which is not its question to answer.
     * See {@code docs/refactor/30-block-reuse.md} §5.
     */
    public void adopt(ASTNode node) {
        if (node != null) this.astNode = node;
    }

    @Override
    public void setReadOnly(boolean readOnly) {
        this.isReadOnly = readOnly;
    }

    @Override
    public boolean isReadOnly() {
        return isReadOnly;
    }

    /**
     * What kind of block this is, for styling. Null means "no opinion" — the block keeps whatever appearance
     * its own rules give it.
     *
     * <p>Override this rather than writing a per-block colour: the category's colour is a {@code -bm-cat-*}
     * token in {@code blocks.css}, so one rule styles every block that shares a purpose, and changing the
     * palette is changing one line. A block that hard-codes its own hex is a block that will be missed the
     * next time the palette moves.
     */
    protected BlockCategory category() {
        return null;
    }

    /**
     * Extra right-click entries this kind of block offers, above the copy/paste/breakpoint items every block
     * has. Empty by default; {@code InteractionDecorator} adds a separator after them when there are any.
     *
     * <p>The hook exists so a block-specific action lives on the block that knows about it, rather than in a
     * growing {@code instanceof} ladder inside the decorator.
     */
    public List<javafx.scene.control.MenuItem> blockMenuItems(CodeEditorService context) {
        return List.of();
    }

    @Override
    public Node getUINode(CodeEditorService context) {
        if (uiNode == null) {
            uiNode = createUINode(context);
            // Toggle the :breakpoint pseudo-class whenever the breakpoint state changes.
            uiNode.pseudoClassStateChanged(BREAKPOINT, breakpointActive.get());
            breakpointActive.addListener((obs, was, on) -> uiNode.pseudoClassStateChanged(BREAKPOINT, on));

            BlockCategory category = category();
            if (category != null) uiNode.getStyleClass().add(category.styleClass());

            // The other half of the trail a focused widget follows home: the component id names which field
            // it is, this names which block declared it. Both are needed, because a component id is unique
            // within a spec and not across the file.
            com.botmaker.studio.core.component.ComponentNodes.stampBlock(uiNode, id);

            for (BlockDecorator decorator : DECORATORS) {
                decorator.decorate(uiNode, this, context);
            }
        }
        return uiNode;
    }

    @Override
    public Node getUINode() { return uiNode; }

    @Override
    public void highlight() {
        if (uiNode != null) uiNode.pseudoClassStateChanged(HIGHLIGHTED, true);
    }

    @Override
    public void unhighlight() {
        if (uiNode != null) uiNode.pseudoClassStateChanged(HIGHLIGHTED, false);
    }

    @Override
    public void setError(String message) {
        if (uiNode == null) return;
        uiNode.pseudoClassStateChanged(ERROR, true);
        if (errorTooltip == null) {
            errorTooltip = new Tooltip(message);
            Tooltip.install(uiNode, errorTooltip);
        } else {
            errorTooltip.setText(message);
        }
    }

    @Override
    public void clearError() {
        if (uiNode == null) return;
        uiNode.pseudoClassStateChanged(ERROR, false);
        if (errorTooltip != null) {
            Tooltip.uninstall(uiNode, errorTooltip);
            errorTooltip = null;
        }
    }

    @Override
    public int getBreakpointLine(CompilationUnit cu) {
        if (cu == null || astNode == null) return -1;
        return cu.getLineNumber(astNode.getStartPosition());
    }

    @Override
    public CodeBlock getHighlightTarget() { return this; }

    @Override
    public String getDetails() {
        return this.getClass().getSimpleName() + " (ID: " + this.getId() + ")";
    }

    @Override
    public boolean isBreakpoint() { return breakpointActive.get(); }

    @Override
    public void setBreakpoint(boolean enabled) {
        breakpointActive.set(enabled);
    }

    @Override
    public void toggleBreakpoint() {
        setBreakpoint(!isBreakpoint());
        if (uiNode != null) {
            uiNode.fireEvent(new BlockEvent.BreakpointToggleEvent(this, isBreakpoint()));
        }
    }

    /** Backs the gutter circle's visibility and the {@code :breakpoint} pseudo-class. */
    public BooleanProperty breakpointActiveProperty() { return breakpointActive; }

    /**
     * Which part of this block's rendered node the read-only wash belongs on. The whole of it, for every block
     * whose lock covers everything it draws.
     *
     * <p>A method whose <em>signature</em> is kept is not one of those: its body is editable and deliberately
     * so, and JavaFX composites opacity and saturation over an entire subtree — so marking the block root dimmed
     * the body the user is meant to work in, which reads as "this is locked too". See
     * {@link com.botmaker.studio.core.render.ReadOnlyDecorator}.
     *
     * @param root this block's rendered node, already built
     */
    public Node lockedSurface(Node root) {
        return root;
    }

    protected Node createExpressionDropZone(CodeEditorService context) {
        // If Read-Only, return a static label or empty region instead of a drop zone
        if (isReadOnly) {
            javafx.scene.control.Label lbl = new javafx.scene.control.Label("");
            lbl.setMinWidth(20);
            return lbl;
        }
        Region dropZone = new Region();
        context.getDragAndDropManager().markEmptyExpressionSlot(dropZone, this);
        return dropZone;
    }

    protected abstract Node createUINode(CodeEditorService context);

    /**
     * This block's declared {@link com.botmaker.studio.core.component.ComponentSpec} drawn at canvas density —
     * what a migrated block's {@code createUINode} hands to {@code BlockLayout.header().withCustomNode(…)}.
     *
     * <p>It exists so the lock is read in one place rather than per block: {@code locked} comes from this
     * block's own {@link #isReadOnly()}, which is what the whole block layer already means by locked. A block
     * that spelled it itself is a block that can disagree with the HUD about the same component.
     */
    protected Node renderSpec(CodeEditorService context) {
        return renderSpecRow(context).build();
    }

    /**
     * The same as {@link #renderSpec}, as the builder — for a block that has more to say about the row it
     * produces, such as a style class keyed on what the block is or a control appended after it.
     */
    protected com.botmaker.studio.ui.render.layout.ComponentLayoutBuilder renderSpecRow(CodeEditorService context) {
        return com.botmaker.studio.ui.render.layout.BlockLayout
                .components(componentSpec(context), isReadOnly());
    }

    /**
     * This block's spec drawn as stacked rows — for a block whose spec declares a {@code BODY}, where the
     * canvas draws the body inline between rows and the HUD draws it as nested rows of its own tree.
     *
     * <p>Returned as the builder rather than a node so a block can still say what its own chrome is: the outer
     * style class, the header row's style class, the delete action.
     */
    protected com.botmaker.studio.ui.render.layout.StackLayoutBuilder renderSpecStacked(CodeEditorService context) {
        return com.botmaker.studio.ui.render.layout.BlockLayout
                .stack(componentSpec(context), isReadOnly());
    }

    /**
     * The "change this expression" button, or null when this block is read-only — the layout builders skip
     * null nodes, so the affordance simply doesn't exist. Prefer this over
     * {@code BlockUIComponents.createChangeButton}, which knows nothing about locks.
     */
    protected Button createChangeButton(javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        if (isReadOnly) return null;
        return BlockUIComponents.createChangeButton(handler);
    }

    /** {@code action}, or null when this block is read-only, for builders that omit a control given null. */
    protected Runnable ifEditable(Runnable action) {
        return isReadOnly ? null : action;
    }

    /**
     * Applies the user's pick from {@link com.botmaker.studio.ui.render.menu.ExpressionMenu} to
     * {@code toReplace}: a plain {@link ExpressionType} swaps in a fresh expression block, while an
     * {@link ExpressionChoice} drives a richer rewrite (method call, instantiation, enum constant, or
     * variable reference). Shared by statement and expression blocks.
     */
    protected void applyExpressionSelection(CodeEditorService context, Expression toReplace, Object selection) {
        // Mirrors createExpressionDropZone's guard: a read-only block offers no menu to pick from, so this is
        // only reachable if a path forgot. CodeEditor would refuse the rewrite anyway — this keeps the refusal
        // from being reported to the user as if they had done something wrong.
        if (isReadOnly) return;
        ExpressionMenu.applySelection(context, toReplace, selection);
    }
}
