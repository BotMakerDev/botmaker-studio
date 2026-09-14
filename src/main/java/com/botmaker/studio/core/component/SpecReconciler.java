package com.botmaker.studio.core.component;

import java.util.Optional;

/**
 * What survives a re-parse: given the spec a block drew with and the spec the block that replaced it declares,
 * which component may keep the widget it already has.
 *
 * <p><b>The problem it exists for.</b> Every edit re-parses the file and builds an entirely new block tree, so
 * a widget the user is typing in is destroyed and rebuilt between keystrokes. The symptoms are visible all
 * over the editor and each has its own hand-written workaround: {@code EditorCanvas} restores its scroll
 * position by hand, {@code ProgramShapeOverlay} keeps a pending-focus field, and a text field loses its caret,
 * its selection and any in-progress IME composition.
 *
 * <p><b>Why it keeps one component and not every unchanged one.</b> The obvious rule — carry every component
 * whose id and kind are unchanged — is wrong, and wrong in the direction that edits the user's code. A widget
 * built inside a supplier closes over the block and the {@code ASTNode} it was built from; after a re-parse
 * both are dead objects, so a carried {@code TextField} would go on writing into a discarded tree. That is the
 * failure {@code HostSlotContext} already guards against by holding the <em>slot</em> and never the
 * expression, "so a popup outliving its own re-parse writes into the new node". Carrying is therefore opt-in
 * per component, through {@link BlockComponent#carried}, and is asked for only where the loss is felt: the
 * component that has focus.
 *
 * <p>Pure and JavaFX-free — it decides, it does not draw — which is what lets the rule be asserted with no
 * toolkit, the same split as {@code BlockTree} and {@code CompactSpecRow}'s.
 */
public final class SpecReconciler {

    private SpecReconciler() {}

    /**
     * The component of {@code fresh} that may keep {@code focusedId}'s existing widget, or empty when it must
     * be rebuilt.
     *
     * <p>Four conditions, and every one of them is a way the carry could be wrong rather than merely
     * unhelpful:
     *
     * <ol>
     *   <li><b>Something had focus.</b> A null or blank id is the ordinary case — nothing to carry.</li>
     *   <li><b>The id is still declared.</b> A spec's shape varies with the code it describes: an {@code if}
     *       that gained an {@code else} declares components the previous one did not, and a call that lost an
     *       argument drops {@code arg2} entirely. An id that is gone names a widget with nothing to point at.</li>
     *   <li><b>The kind is unchanged.</b> Ids are positional in the blocks that repeat them
     *       ({@code arg0}, {@code link1-condition}), so one edit can leave an id in place over a different
     *       shape. Carrying a {@code PICKER}'s widget into an {@code EXPRESSION_SLOT} is a category error the
     *       id alone cannot see.</li>
     *   <li><b>The new component asks to be carried.</b> {@link BlockComponent#isCarried} is the block
     *       promising it will re-point the widget's handlers at the AST node that exists now. Absent, the
     *       component is rebuilt — which is exactly what every component did before this existed, so a block
     *       that declares nothing is unaffected.</li>
     * </ol>
     *
     * @param drawn     the spec the widget on screen was built from
     * @param fresh     the spec declared by the block that replaced it, matched by {@code BlockId}
     * @param focusedId the component id that held focus, or {@code null}
     */
    public static Optional<BlockComponent> carry(ComponentSpec drawn, ComponentSpec fresh, String focusedId) {
        if (drawn == null || fresh == null || focusedId == null || focusedId.isBlank()) return Optional.empty();

        Optional<BlockComponent> before = drawn.find(focusedId);
        Optional<BlockComponent> after = fresh.find(focusedId);
        if (before.isEmpty() || after.isEmpty()) return Optional.empty();

        if (before.get().kind() != after.get().kind()) return Optional.empty();
        if (!after.get().isCarried()) return Optional.empty();

        return after;
    }

    /**
     * Hands {@code node} to {@code component}'s rebind hook, answering whether the carry actually happened.
     *
     * <p>A hook that throws loses the carry and nothing else: the caller rebuilds, which is the behaviour
     * every block had before this existed. The rule is the one every pass over block code here follows — a
     * block that misbehaves costs itself its own affordance and never the render pass. A block whose whole
     * tree failed to draw is what {@code DeclareClassVariableBlock}'s unconditional delete-button styling once
     * produced.
     */
    public static boolean rebind(BlockComponent component, javafx.scene.Node node) {
        if (component == null || node == null || !component.isCarried()) return false;
        try {
            component.rebind().accept(node);
            return true;
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Component " + component.id() + " could not take its widget back: " + e);
            return false;
        }
    }
}
