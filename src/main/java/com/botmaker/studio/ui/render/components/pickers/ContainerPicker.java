package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.source.ValueTypeResolver;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import com.botmaker.studio.ui.app.params.ValueEditors;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;

import java.lang.reflect.Type;
import java.util.Optional;

/**
 * A {@code List}, {@code Map}, {@code Set} or {@code Deque} slot on the canvas, as one pill — "List · 3 items"
 * — that opens the Parameters window's own rows in a popover (picker 6e2, the maintainer's choice over one
 * picker per argument). Apply writes the whole container back through the grammar; Cancel and Escape write
 * nothing.
 *
 * <p>The containers are the host's grammar, so this picker is the host's, like the enum dropdown: it runs
 * after every plugin's. It claims a slot only when the value is written as the container's own call: a
 * variable or another call is a reference, and editing a copy of what it held would silently replace it.
 */
public final class ContainerPicker {

    private ContainerPicker() {}

    static SpecialTypePicker asSpecialType() {
        return SpecialTypePicker.of(ContainerPicker::matches, ContainerPicker::create);
    }

    /**
     * Whether {@code form} is a container and {@code source} is that container's own call. A slot with no
     * expression has nothing to replace, so it keeps the canvas's ordinary empty-slot menu.
     */
    static boolean claims(ValueGrammar grammar, Type form, String source) {
        if (ValueTypes.container(form).isEmpty() || source == null || source.isBlank()) return false;
        return grammar.partsOfInitializer(form, source).isPresent();
    }

    /** What the pill says: the container and how much it holds. */
    static String summary(Type form, int count) {
        String name = ValueTypes.container(form).map(ContainerPicker::noun).orElse("Value");
        String amount = count == 0 ? "empty" : count == 1 ? "1 item" : count + " items";
        return name + " · " + amount;
    }

    private static String noun(ValueContainer<?> container) {
        if (container == ValueContainer.MAP) return "Map";
        if (container == ValueContainer.SET) return "Set";
        if (container == ValueContainer.DEQUE) return "Stack / queue";
        return "List";
    }

    private static boolean matches(PickerContext ctx) {
        if (ctx == null || ctx.arg() == null) return false;
        Expression node = ctx.arg().node();
        return claims(PluginHost.grammar(), formOf(ctx), node == null ? null : node.toString());
    }

    /**
     * The slot's value type: the parameter's declared type when it is a container (it says the element type
     * even for {@code List.of()}), else the expression's own.
     */
    static Type formOf(PickerContext ctx) {
        ValueGrammar grammar = PluginHost.grammar();
        if (ctx.paramType() instanceof ResolvedType.Bound bound) {
            Type declared = ValueTypeResolver.ofBinding(grammar, bound.binding());
            if (ValueTypes.container(declared).isPresent()) return declared;
        }
        Expression node = ctx.arg().node();
        ITypeBinding own = node == null ? null : node.resolveTypeBinding();
        return ValueTypeResolver.ofBinding(grammar, own);
    }

    private static Node create(PickerContext ctx) {
        ValueGrammar grammar = PluginHost.grammar();
        Type form = formOf(ctx);
        Expression node = ctx.arg().node();
        Optional<SourceNode> written = node == null ? Optional.empty() : Optional.of(new SourceNode(node, null));
        int count = written.flatMap(w -> grammar.partsOfInitializer(form, w)).map(java.util.List::size).orElse(0);

        Button pill = new Button(summary(form, count));
        pill.getStyleClass().add("container-pill");
        pill.setOnAction(e -> open(ctx.context(), pill, form, written));
        return pill;
    }

    private static void open(CodeEditorService context, Node anchor, Type form, Optional<SourceNode> written) {
        BotRecords records = context == null ? BotRecords.none()
                : BotRecords.scan(context.getConfig(), context.getState(), PluginHost.grammar());
        ValueEditors.Editor editor = ParamValueWidgets.valueEditor(form, written,
                context == null ? null : context.getConfig(), records);

        Popup popup = new Popup();
        popup.setAutoHide(false);
        popup.setHideOnEscape(true);

        Label title = new Label(ValueTypes.sourceName(form));
        title.getStyleClass().add("nav-popup-title");
        ScrollPane scroll = new ScrollPane(editor.node());
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(420);
        scroll.setPrefViewportHeight(260);

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> popup.hide());
        Button apply = new Button("Apply");
        apply.setDefaultButton(true);
        apply.setOnAction(e -> {
            Optional<JavaValue> value = editor.read().get();
            popup.hide();
            Expression target = written.map(w -> (Expression) w.node()).orElse(null);
            if (value.isPresent() && target != null && context != null) {
                context.getCodeEditor().replaceWithValue(target, value.get());
            }
        });
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, cancel, apply);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(8, title, scroll, buttons);
        box.getStyleClass().add("nav-popup");
        popup.getContent().add(box);
        ThemedWindows.addStylesheet(popup.getScene());
        ThemedWindows.applyThemeClass(box);

        Bounds at = anchor.localToScreen(anchor.getBoundsInLocal());
        if (at == null || anchor.getScene() == null) return;
        popup.show(anchor.getScene().getWindow(), at.getMinX(), at.getMaxY() + 4);
    }
}
