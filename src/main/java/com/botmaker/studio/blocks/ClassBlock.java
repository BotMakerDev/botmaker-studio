package com.botmaker.studio.blocks;

import com.botmaker.studio.blocks.func.MethodDeclarationBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.parser.helpers.MethodSignatures;
import com.botmaker.studio.ui.app.AddFunctionDialog;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.*;

import java.util.ArrayList;
import java.util.List;

/** A class, the file's own or nested in another, and the toolbar and drop targets that add its members. */
public class ClassBlock extends TypeBlock {

    private final String className;
    /** The {@code extends} superclass, or null when the class extends nothing. */
    private final String superClassName;
    /** The {@code implements} interfaces, in source order; empty when there are none. */
    private final List<String> interfaceNames = new ArrayList<>();
    private final BlockDragAndDropManager dragAndDropManager;

    /** A class; an interface is an {@link InterfaceBlock}, a record a {@link RecordBlock}. */
    public ClassBlock(String id, TypeDeclaration astNode, BlockDragAndDropManager manager) {
        super(id, astNode);
        if (astNode.isInterface()) throw new IllegalArgumentException(astNode.getName() + " is an interface");
        this.className = astNode.getName().getIdentifier();
        // Inheritance is part of what the class *is* — an activity stub is generated as
        // `class Mine extends Activity`, and hiding that made every stub look like a plain class.
        Type superType = astNode.getSuperclassType();
        this.superClassName = (superType == null) ? null : superType.toString();
        for (Object t : astNode.superInterfaceTypes()) interfaceNames.add(t.toString());
        this.dragAndDropManager = manager;
    }

    /** The name of the superclass this class extends, or {@code null} if it extends nothing. */
    public String getSuperClassName() { return superClassName; }

    /** The interfaces this class implements, in source order. Never null. */
    public List<String> getInterfaceNames() { return new ArrayList<>(interfaceNames); }

    @Override
    protected Node createUINode(CodeEditorService context) {
        // Annotations, then Class: Name<T> extends … implements … (TypeHeader).
        VBox container = frame(context);
        List<CodeBlock> bodyDeclarations = getChildren();

        // A read-only class is inert: no "Add Function", no drop targets between members, no drag-to-reorder.
        // The members still render — reading is the whole point of opening bundled library source, or someone
        // else's bot — they just can't be added to, removed, or shuffled. See project/LockResolver.
        boolean editable = !isReadOnly();

        // --- 1. ADD BUTTONS AT TOP (Call helper to get NEW instances) ---
        if (editable) {
            container.getChildren().add(createControlToolbar(context, 0));
            container.getChildren().add(createClassMemberSeparator(context, 0));
        }

        for (int i = 0; i < bodyDeclarations.size(); i++) {
            CodeBlock block = bodyDeclarations.get(i);

            Node node = block.getUINode(context);

            // Make methods draggable for reordering. makeBlockMovable installs the onDragDetected
            // handler itself, so call it directly at render time (not from inside an onDragDetected).
            if (editable && block instanceof MethodDeclarationBlock) {
                context.getDragAndDropManager().makeBlockMovable(node, block);
            }

            container.getChildren().add(node);
            if (editable) {
                container.getChildren().add(createClassMemberSeparator(context, i + 1));
            }
        }

        // --- 2. ADD BUTTONS AT BOTTOM (Call helper AGAIN to get NEW instances) ---
        if (editable) {
            container.getChildren().add(createControlToolbar(context, bodyDeclarations.size()));
        }

        return container;
    }

    /**
     * A fresh set of buttons, adding at {@code insertIndex}: the top bar puts a new member first, the bottom bar
     * last — each where the button is (2026-09-27; both appended until then, so the top one added out of sight).
     */
    private Node createControlToolbar(CodeEditorService context, int insertIndex) {
        // Using HBox to put them side-by-side, or VBox if you prefer them stacked
        javafx.scene.layout.HBox toolbar = new javafx.scene.layout.HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // "Add Constructor" is intentionally hidden — the bot's generated class has no user-authored
        // constructors, and exposing one only invites broken generated code. Keep only "Add Function".
        Button addMethodBtn = new Button("+ Add Function");
        addMethodBtn.getStyleClass().addAll("block-action-button", "block-action-button--primary");
        addMethodBtn.setOnAction(e -> addFunction(context, addMethodBtn, insertIndex));

        // Beside Add Function since 2026-09-26: an enum is a type the class declares, so it is added where the
        // class's members are, not from a body's statement menu. Painted in the enum block's own colour.
        Button addEnumBtn = new Button("+ Add Enum");
        addEnumBtn.getStyleClass().addAll("block-action-button", "block-action-button--enum");
        addEnumBtn.setOnAction(e -> addEnum(context, addEnumBtn, insertIndex));

        toolbar.getChildren().addAll(addMethodBtn, addEnumBtn);
        return toolbar;
    }

    /** Asks for the enum's name and values, then declares it as a member of this class. */
    private void addEnum(CodeEditorService context, Node source, int insertIndex) {
        TypeDeclaration typeDecl = (TypeDeclaration) this.astNode;
        Window owner = source.getScene() == null ? null : source.getScene().getWindow();
        com.botmaker.studio.ui.app.vars.DefineEnumDialog.ask(owner,
                        com.botmaker.studio.parser.factories.StatementFactory.uniqueTypeName(typeDecl, "MyEnum"),
                        com.botmaker.studio.parser.factories.StatementFactory.declaredTypeNames(typeDecl))
                .ifPresent(draft -> context.getCodeEditor().addEnumToClass(typeDecl, draft, insertIndex));
    }

    /**
     * Asks what the function should be, then writes it. The dialog is told which <em>signatures</em> are taken
     * — see {@link MethodSignatures#declaredIn}, which reads them from the AST so a member the editor hides
     * still counts, and which compares whole signatures so an overload is not mistaken for a duplicate.
     */
    private void addFunction(CodeEditorService context, Node source, int insertIndex) {
        TypeDeclaration typeDecl = (TypeDeclaration) this.astNode;
        Window owner = source.getScene() == null ? null : source.getScene().getWindow();

        new AddFunctionDialog(owner, MethodSignatures.declaredIn(typeDecl))
                .withProject(com.botmaker.studio.project.params.BotRecords.scan(context.getConfig(),
                        context.getState(), com.botmaker.studio.plugin.PluginHost.grammar()))
                .showAndWait().ifPresent(draft ->
                context.getCodeEditor().addFunctionToClass(typeDecl, draft, insertIndex));
    }

    private Region createClassMemberSeparator(CodeEditorService context, int insertIndex) {
        Region separator = new Region();
        separator.setMinHeight(15);
        separator.setMaxHeight(15);
        // No inline background: it would override the :drag-over-* pseudo-class feedback (inline styles
        // beat author stylesheets). A Region is transparent by default.
        separator.getStyleClass().add("class-member-separator");

        context.getDragAndDropManager().addClassMemberDropHandlers(separator, this, insertIndex);

        return separator;
    }
}
