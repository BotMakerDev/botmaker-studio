package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.core.ValueSlot;
import com.botmaker.studio.plugin.HostParameters;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.QualifiedName;

/**
 * A dropdown over the project's variables, shown inline on a slot that already holds one
 * ({@code Parameters.RETRIES}). Swapping one variable for another was a walk down Change ▸ Activities ▸ tag ▸
 * name to reach a list the slot could have been showing all along — the same picker the expression submenu
 * offers, one click away instead of four.
 *
 * <p>It claims only slots that <em>already</em> reference a variable. A picker that claimed every slot a
 * variable could fill would replace the generic pill on every {@code int} in the project, which is a different
 * (and much larger) decision than "let me change which variable this is".
 *
 * <p>Offered variables are filtered to the slot's type, so a {@code boolean} slot lists the flags. The one
 * already in the slot is always listed even when it doesn't match — the type it was chosen for may have moved,
 * and a dropdown that omits its own current value reads as though the code says something it doesn't.
 */
public final class VariablePicker {

    private VariablePicker() {}

    /**
     * The field name this slot references, or null when it isn't a parameter reference at all.
     *
     * <p>Any declared holder counts: {@code Activities.MINING} and {@code Parameters.REST} are both
     * parameters of the SDK plugin's, and a slot holding one is a slot the user may want to point at the
     * other. Which class the <em>replacement</em> is written on is asked afresh, not copied from what is
     * there — see {@link #create}.
     *
     * <p><b>{@code qualifiers} is a parameter rather than something this looks up</b>, and that is what keeps
     * the method a pure question about the expression: the set of classes that hold parameters comes from the
     * loaded plugins ({@link HostParameters#isQualifier}), which needs a project, while <em>is this a
     * qualified reference at all</em> needs only the node. It replaced a two-constant enum naming
     * {@code Activities} and {@code Parameters} — one plugin's class names, written down in the host.
     */
    static String referencedVariable(ValueSlot arg, java.util.function.Predicate<String> qualifiers) {
        ASTNode node = arg == null ? null : arg.node();
        if (node instanceof QualifiedName qualified) {
            return qualifiers.test(qualified.getQualifier().toString())
                    ? qualified.getName().getIdentifier() : null;
        }
        if (node instanceof FieldAccess access) {
            return qualifiers.test(access.getExpression().toString())
                    ? access.getName().getIdentifier() : null;
        }
        return null;
    }

    /** The same, over the parameter groups the open project's plugins declare. */
    private static String referencedVariable(CodeEditorService context, ValueSlot arg) {
        return referencedVariable(arg,
                q -> HostParameters.isQualifier(context.getConfig(), context.getState(), q));
    }

    public static Node create(CodeEditorService context, ValueSlot arg, ResolvedType slotType) {
        String current = referencedVariable(context, arg);
        ComboBox<String> combo = new ComboBox<>();
        combo.getStyleClass().add("block-selector");
        combo.setTooltip(new Tooltip("Which project variable this is — edit them in Project ▸ Parameters"));
        for (HostParameters.Parameter parameter
                : HostParameters.compatibleWith(context.getConfig(), context.getState(), slotType)) {
            combo.getItems().add(parameter.row().name());
        }
        if (current != null && !combo.getItems().contains(current)) combo.getItems().add(current);
        combo.setValue(current);
        combo.setOnAction(e -> {
            String picked = combo.getValue();
            if (picked == null || picked.equals(current)) return;
            // The class the picked name is declared on, asked of the plugin that declares it rather than
            // taken from the qualifier already in the slot: swapping a flag for a value moves the reference
            // between two classes, and keeping the old qualifier would write Activities.REST.
            String qualifier = HostParameters.qualifierOf(context.getConfig(), context.getState(), picked);
            if (qualifier == null) return;   // the name left the declaration between opening and picking
            context.getCodeEditor().replaceWithFieldReference(arg.node(), qualifier, picked);
        });
        return combo;
    }

    /**
     * The {@link SpecialTypePicker} entry: matches a slot already holding {@code <a declared class>.<name>}.
     */
    public static SpecialTypePicker asSpecialType() {
        return new SpecialTypePicker() {
            @Override public boolean matches(PickerContext ctx) {
                return ctx.context() != null && ctx.context().getProjectAnalyzer() != null
                        && referencedVariable(ctx.context(), ctx.arg()) != null;
            }
            @Override public Node create(PickerContext ctx) {
                return VariablePicker.create(ctx.context(), ctx.arg(), ctx.paramType());
            }
        };
    }
}
