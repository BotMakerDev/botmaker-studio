package com.botmaker.studio.blocks.expr;

import com.botmaker.studio.core.AbstractExpressionBlock;
import com.botmaker.studio.core.BlockWithChildren;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.ExpressionBlock;
import com.botmaker.studio.core.component.ComponentSpec;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.components.TextFieldComponents;
import com.botmaker.studio.ui.render.layout.SentenceLayoutBuilder;
import javafx.scene.Node;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A declaration where Java puts one inside a statement: the start of a {@code for} that counts two things
 * ({@code for (int i = 0, j = n; …)}) and a {@code try} resource ({@code try (var in = open())}). Read as
 * {@code T name = ⟨value⟩}, a name and a value per variable. A name is renamed with its uses inside the
 * statement that declares it, which is its whole scope.
 */
public class DeclarationExpressionBlock extends AbstractExpressionBlock implements BlockWithChildren {

    private final Map<VariableDeclarationFragment, ExpressionBlock> initializers = new HashMap<>();

    public DeclarationExpressionBlock(String id, VariableDeclarationExpression astNode) {
        super(id, astNode);
    }

    public void setInitializer(VariableDeclarationFragment fragment, ExpressionBlock initializer) {
        initializers.put(fragment, initializer);
    }

    @Override
    public List<CodeBlock> getChildren() {
        return fragments().stream()
                .map(initializers::get)
                .filter(java.util.Objects::nonNull)
                .map(CodeBlock.class::cast)
                .toList();
    }

    private List<VariableDeclarationFragment> fragments() {
        return ((VariableDeclarationExpression) astNode).fragments().stream()
                .map(VariableDeclarationFragment.class::cast)
                .toList();
    }

    @Override
    public ComponentSpec componentSpec(CodeEditorService context) {
        VariableDeclarationExpression declaration = (VariableDeclarationExpression) astNode;
        ResolvedType type = declaration.getType().resolveBinding() == null
                ? ResolvedType.UNKNOWN : ResolvedType.of(declaration.getType().resolveBinding());
        ComponentSpec.Builder spec = ComponentSpec.builder()
                .custom("type", () -> typeField(declaration, context));
        List<VariableDeclarationFragment> fragments = fragments();
        for (int i = 0; i < fragments.size(); i++) {
            VariableDeclarationFragment fragment = fragments.get(i);
            if (i > 0) spec.label("and" + i, () -> SentenceLayoutBuilder.keywordNode("and"));
            spec.custom("name" + i, () -> TextFieldComponents.createVariableName(
                    fragment.getName().getIdentifier(), !isReadOnly(),
                    name -> context.getCodeEditor().renameScopedVariable(fragment.getName(), name)));
            ExpressionBlock initializer = initializers.get(fragment);
            if (initializer != null) {
                spec.label("eq" + i, () -> SentenceLayoutBuilder.keywordNode("="));
                addOperand(spec, "value" + i, initializer, context, type);
            }
        }
        return spec.build();
    }

    @Override
    protected Node createUINode(CodeEditorService context) {
        return renderSpec(context);
    }
}
