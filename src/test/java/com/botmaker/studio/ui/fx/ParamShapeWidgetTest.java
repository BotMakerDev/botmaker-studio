package com.botmaker.studio.ui.fx;

import com.botmaker.studio.project.params.ParameterRow;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.java.grammar.JavaValue;
import com.botmaker.java.grammar.ValueTypes;
import com.botmaker.java.grammar.ValueGrammar;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which control a parameter gets, and on what the answer depends: its {@link Type}, and nothing else.
 *
 * <p>It used to depend on the deleted {@code ValueShape}, and then on the row's declared choices too; those went
 * with {@code @Param(options)} on 2026-10-29. The rule that survives: the widget follows the declaration, so a
 * parameter never changes control because of something the author cannot see.
 *
 * <p>No plugin is bound here, and none is needed: the JDK's literal types are read by the host's grammar
 * itself, and a leaf no plugin draws is the read-only field — which is still exactly one control.
 */
class ParamShapeWidgetTest extends FxHeadlessTest {

    private static final Type TEXT = String.class;
    private static final Type WHOLE_NUMBER = int.class;
    private static final Type POINT = new ValueTypes.Unknown("com.botmaker.sdk.api.geometry.Point");

    private Node widgetFor(ParameterRow row, Type form) {
        return widgetFor(row, form, new ArrayList<>());
    }

    private Node widgetFor(ParameterRow row, Type form, List<ParamValueWidgets.ValueEditor> sink) {
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build("", row, form, null, sink));
        return built[0];
    }

    /** One row seeded with the form's fresh initialiser. */
    private static ParameterRow row(String name, Type form) {
        return ParameterRow.named(name, ValueTypes.sourceName(form))
                .value(PluginHost.grammar().freshInitializer(form).map(JavaValue::source).orElse(""))
                .build();
    }

    private List<Node> childrenOf(Node node) {
        return node instanceof Pane pane ? List.copyOf(pane.getChildren()) : List.of();
    }

    /** A list of text is the user's to fill in, one per line. */
    @Test
    void aListOfTextIsLines() {
        Type texts = ValueTypes.listOf(TEXT);

        // A textarea and not a Pane, so it has no children to count: text is written one per line.
        assertInstanceOf(TextArea.class, widgetFor(row("open", texts), texts));
    }

    /** Every other type's list is a growable column of that type's own editor, empty to begin with. */
    @Test
    void aListOfSomethingOtherThanTextIsRowsOfItsOwnEditor() {
        Type spots = ValueTypes.listOf(POINT);

        List<Node> parts = childrenOf(widgetFor(row("spots", spots), spots));

        assertFalse(parts.isEmpty(), "the empty state still has to say something and offer Add");
        assertTrue(parts.stream().noneMatch(part -> part instanceof CheckBox), "nothing to tick");
    }

    /** A leaf is the one editor for its type, not a column of choices. */
    @Test
    void aLeafIsItsOwnEditor() {
        assertTrue(childrenOf(widgetFor(row("size", WHOLE_NUMBER), WHOLE_NUMBER)).isEmpty(),
                "one control, not a column of choices");
    }

    /**
     * A map is two columns of the leaf editors its key and value types have, with an Add row beneath — the
     * cell `Map<String, Integer>` gets, and the checkpoint the whole type tree was built for.
     */
    @Test
    void aMapIsTwoColumnsAndAnAddRow() {
        Type retries = ValueTypes.mapOf(TEXT, WHOLE_NUMBER);

        List<Node> parts = childrenOf(widgetFor(row("retries", retries), retries));

        assertFalse(parts.isEmpty(), "an empty map still says so and offers Add");
        assertTrue(parts.stream().noneMatch(part -> part instanceof CheckBox), "a map is not a set of choices");
    }

    /**
     * A map of lists is editable, not read-only: every part is drawn as deep as the type goes (2026-09-26), and
     * untouched it reads back the Java it was seeded with.
     */
    @Test
    void aNestedValueIsEditedAtEveryDepthAndReadsBackAsWritten() {
        ValueGrammar grammar = PluginHost.grammar();
        Type table = ValueTypes.mapOf(TEXT, ValueTypes.listOf(WHOLE_NUMBER));
        JavaValue numbers = grammar.compose(ValueTypes.listOf(WHOLE_NUMBER), List.of(
                grammar.spell(int.class, 1).orElseThrow(), grammar.spell(int.class, 2).orElseThrow())).orElseThrow();
        Type entry = ValueTypes.of(com.botmaker.java.grammar.ValueContainer.ENTRY,
                List.of(TEXT, ValueTypes.listOf(WHOLE_NUMBER)));
        JavaValue pair = grammar.compose(entry, List.of(grammar.spell(String.class, "a").orElseThrow(), numbers))
                .orElseThrow();
        JavaValue seed = grammar.compose(table, List.of(pair)).orElseThrow();
        ParameterRow row = ParameterRow.named("table", ValueTypes.sourceName(table)).value(seed.source()).build();

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        widgetFor(row, table, sink);

        assertEquals(1, sink.size(), "a nested value is a cell that writes, not a read-only label");
        assertTrue(seed.sameJava(sink.getFirst().read().get().orElseThrow()),
                sink.getFirst().read().get().orElseThrow().source());
    }
}
