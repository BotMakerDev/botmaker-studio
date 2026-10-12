package com.botmaker.studio.blocks;

import com.botmaker.studio.blocks.misc.AnnotationRow;
import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * A type the bot declares, the file's own or nested in another, and its members in source order: a
 * {@link ClassBlock}, an {@link InterfaceBlock} or a {@link RecordBlock} (2026-10-12: an interface was drawn as a
 * class, and a record or any nested type not at all). Each draws its annotations, its header ({@link TypeHeader}:
 * what it is, extends and implements) and its members; only a class adds members from here.
 */
public abstract class TypeBlock extends AbstractCodeBlock implements BlockWithChildren {

    private final List<CodeBlock> members = new ArrayList<>();

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
        for (CodeBlock member : members) container.getChildren().add(member.getUINode(context));
        return container;
    }
}
