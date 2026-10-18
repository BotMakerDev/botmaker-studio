package com.botmaker.studio.blocks;

import com.botmaker.studio.blocks.misc.AnnotationRow;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.NodeFinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A type the bot declares, the file's own or nested in another, and its members in source order: a
 * {@link ClassBlock}, an {@link InterfaceBlock} or a {@link RecordBlock} (2026-10-12: an interface was drawn as a
 * class, and a record or any nested type not at all). Each draws its annotations, its header ({@link TypeHeader}:
 * what it is, extends and implements) and its members; only a class adds members from here.
 */
public abstract class TypeBlock extends AbstractCodeBlock implements BlockWithChildren {

    private final List<CodeBlock> members = new ArrayList<>();
    /** Set by {@link #drawMembersOnDemand}: each member is a blank until the canvas draws it. */
    private boolean membersOnDemand;

    protected TypeBlock(String id, AbstractTypeDeclaration astNode) {
        super(id, astNode);
    }

    /** A type is not a line the bot stops on; its members' statements are. */
    @Override
    public boolean canHoldBreakpoint() { return false; }

    protected AbstractTypeDeclaration declaration() {
        return (AbstractTypeDeclaration) astNode;
    }

    /** The header as it reads in source: {@code Foo<T> extends Bar implements Baz}. */
    public String headerText() {
        return TypeHeader.text(declaration());
    }

    public void addBodyDeclaration(CodeBlock block) {
        members.add(block);
    }

    @Override
    public List<CodeBlock> getChildren() {
        return new ArrayList<>(members);
    }

    /** The style class that tells this kind of type's card from a class's, or null for a class's own. */
    protected String styleClass() {
        return null;
    }

    /** The type's annotations and its header, the first rows of its block. */
    protected VBox frame(CodeEditorService context) {
        VBox container = new VBox(10);
        container.setPadding(new Insets(15));
        container.getStyleClass().add("class-block");
        if (styleClass() != null) container.getStyleClass().add(styleClass());
        Node annotations = AnnotationRow.of(declaration(), context, !isReadOnly());
        if (annotations != null) container.getChildren().add(annotations);
        container.getChildren().add(TypeHeader.of(declaration()));
        return container;
    }

    /** The header, then each member, each editable on its own; nothing to add or drop between them. */
    @Override
    protected Node createUINode(CodeEditorService context) {
        VBox container = frame(context);
        for (CodeBlock member : members) container.getChildren().add(memberNode(member, context));
        return container;
    }

    /**
     * Draws each member, and each member of the types nested here, only when the canvas asks: a
     * {@link MemberPlaceholder} stands for it until then (2026-10-18). Called before the node is built; other
     * views (a library class, a version's preview) never call it and draw every member.
     */
    public void drawMembersOnDemand() {
        membersOnDemand = true;
        for (CodeBlock member : members) {
            if (member instanceof TypeBlock nested) nested.drawMembersOnDemand();
        }
    }

    /** {@code member}'s node, or the blank that stands for it while members are drawn on demand. */
    protected Node memberNode(CodeBlock member, CodeEditorService context) {
        return membersOnDemand ? new MemberPlaceholder(member, () -> drawMember(member, context))
                : drawMember(member, context);
    }

    /** Draws one member; a class also lets a method be dragged. */
    protected Node drawMember(CodeBlock member, CodeEditorService context) {
        return member.getUINode(context);
    }

    /**
     * Draws the member {@code block} is in, and each type around it, wherever a blank still stands for one:
     * what a reveal, a find or the debugger needs before it can reach the block's node. Nothing is drawn for a
     * block outside the types of {@code blocks}. True when a blank was drawn.
     */
    public static boolean drawAround(CodeBlock block, Map<ASTNode, CodeBlock> blocks) {
        ASTNode start = block.getAstNode();
        // A comment hangs off the compilation unit's comment list, not the tree: start from the node it sits in.
        if (start instanceof Comment comment && comment.getAlternateRoot() != null) {
            start = NodeFinder.perform(comment.getAlternateRoot(), comment.getStartPosition(), comment.getLength());
        }
        Deque<ASTNode> outward = new ArrayDeque<>();
        for (ASTNode node = start; node != null; node = node.getParent()) outward.push(node);
        boolean drew = false;
        ASTNode outer = null;
        for (ASTNode node : outward) {   // the compilation unit first
            if (outer instanceof AbstractTypeDeclaration && node instanceof BodyDeclaration
                    && blocks.get(outer) instanceof TypeBlock type && type.getUINode() instanceof Pane pane) {
                CodeBlock member = blocks.get(node);
                for (Node child : List.copyOf(pane.getChildren())) {
                    if (child instanceof MemberPlaceholder blank && blank.member() == member) {
                        blank.draw();
                        drew = true;
                    }
                }
            }
            outer = node;
        }
        return drew;
    }
}
