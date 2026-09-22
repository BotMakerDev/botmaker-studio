package com.botmaker.studio.ui.fx;

import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which control a parameter gets, and on what the answer depends: its {@link ValueForm}, and whether the row
 * declares a set of choices.
 *
 * <p>It used to depend on the deleted {@code ValueShape}, which answered two unrelated questions at once — how many
 * values there are, and whether they come from a set somebody wrote down. The second is a fact about the
 * <em>row</em>, so it lives on the row's options and the form says nothing about it (2026-09-20). What
 * survives from the shape era is the rule the shapes were split to get: the widget follows the declaration,
 * so a parameter never changes control because of something the author cannot see.
 *
 * <p>No plugin is bound here, and none is needed: the JDK's literal types are read by the host's grammar
 * itself, and a leaf no plugin draws is the read-only field — which is still exactly one control.
 */
class ParamShapeWidgetTest extends FxHeadlessTest {

    private static final ValueForm TEXT = ValueForm.of(String.class);
    private static final ValueForm WHOLE_NUMBER = ValueForm.of(int.class);
    private static final ValueForm POINT = ValueForm.of("com.botmaker.sdk.api.geometry.Point");

    private Node widgetFor(ParameterRow row, ValueForm form) {
        return widgetFor(row, form, new ArrayList<>());
    }

    private Node widgetFor(ParameterRow row, ValueForm form, List<ParamValueWidgets.ValueEditor> sink) {
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build("", row, form, null, sink));
        return built[0];
    }

    /** One row seeded with the form's fresh initialiser and carrying the declared choices as written. */
    private static ParameterRow row(String name, ValueForm form, List<String> options) {
        return ParameterRow.named(name, form.sourceName())
                .value(PluginHost.grammar().freshInitializer(form).orElse(""))
                .options(options)
                .build();
    }

    private static ParameterRow row(String name, ValueForm form) {
        return row(name, form, List.of());
    }

    private List<Node> childrenOf(Node node) {
        return node instanceof Pane pane ? List.copyOf(pane.getChildren()) : List.of();
    }

    /**
     * The two list cells, side by side on the same form. What tells them apart is the row's own choices, and
     * nothing else: a list with a set declared is tick boxes, a list with none is the user's to fill in.
     */
    @Test
    void aListIsTicksWithASetDeclaredAndTheUsersOwnWithout() {
        List<String> skills = List.of("mine", "fish", "cook");
        ValueForm texts = ValueForm.listOf(TEXT);

        List<Node> ticks = childrenOf(widgetFor(row("many", texts, skills), texts));
        assertEquals(skills.size(), ticks.size());
        for (Node tick : ticks) assertInstanceOf(CheckBox.class, tick);

        // A textarea and not a Pane, so it has no children to count: text is written one per line.
        assertInstanceOf(TextArea.class, widgetFor(row("open", texts), texts),
                "a list with nothing declared is the user's to fill in");
    }

    /** Every other type's list is a growable column of that type's own editor, empty to begin with. */
    @Test
    void aListOfSomethingOtherThanTextIsRowsOfItsOwnEditor() {
        ValueForm spots = ValueForm.listOf(POINT);

        List<Node> parts = childrenOf(widgetFor(row("spots", spots), spots));

        assertFalse(parts.isEmpty(), "the empty state still has to say something and offer Add");
        assertTrue(parts.stream().noneMatch(part -> part instanceof CheckBox),
                "nothing to tick: no set has been declared");
    }

    /**
     * A declared choice stands for its <b>Java</b>, not its label: {@code "2"} written by the author is the
     * {@code int} literal {@code 2}.
     */
    @Test
    void aDeclaredSetOnALeafIsRadioButtons() {
        List<Node> rows = childrenOf(widgetFor(row("size", WHOLE_NUMBER, List.of("1", "2", "3")), WHOLE_NUMBER));

        assertEquals(3, rows.size());
        for (Node button : rows) assertInstanceOf(RadioButton.class, button);
        assertEquals("2", ((ValueGrammar.Written) rows.get(1).getUserData()).source());
    }

    /** A choice that is not a value of the type cannot be picked, rather than being written as it is. */
    @Test
    void aChoiceThatIsNotAValueOfTheTypeIsDisabled() {
        List<Node> rows = childrenOf(widgetFor(row("size", WHOLE_NUMBER, List.of("1", "many")), WHOLE_NUMBER));

        assertFalse(rows.getFirst().isDisabled());
        assertTrue(rows.get(1).isDisabled());
    }

    /** With nothing declared the same leaf is the one editor for its type, not an empty radio group. */
    @Test
    void aLeafWithNothingDeclaredIsItsOwnEditor() {
        assertTrue(childrenOf(widgetFor(row("size", WHOLE_NUMBER), WHOLE_NUMBER)).isEmpty(),
                "one control, not a column of choices");
    }

    /**
     * A map is two columns of the leaf editors its key and value types have, with an Add row beneath — the
     * cell `Map<String, Integer>` gets, and the checkpoint the whole type tree was built for.
     */
    @Test
    void aMapIsTwoColumnsAndAnAddRow() {
        ValueForm retries = ValueForm.mapOf(TEXT, WHOLE_NUMBER);

        List<Node> parts = childrenOf(widgetFor(row("retries", retries), retries));

        assertFalse(parts.isEmpty(), "an empty map still says so and offers Add");
        assertTrue(parts.stream().noneMatch(part -> part instanceof CheckBox),
                "a map is not a set of choices");
    }

    /**
     * The reader hands back the Java the field takes, written from the option's own value and never from the
     * button's label. They part company the moment an option carries a graphic — a template's thumbnail, a
     * colour swatch — and a value read off a label would then be whatever the label said.
     */
    @Test
    void whatIsReadBackIsTheValueWrittenAsJavaAndNotTheLabel() {
        ParameterRow choices = row("mode", TEXT, List.of("fast", "slow"));

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        List<Node> buttons = childrenOf(widgetFor(choices, TEXT, sink));
        interact(() -> ((RadioButton) buttons.get(1)).setSelected(true));

        assertEquals(1, sink.size());
        assertEquals("\"slow\"", sink.getFirst().read().get());
    }

    /** A stored value is matched against the choices by its value, so {@code "fast"} selects "fast". */
    @Test
    void theStoredValueSelectsItsChoice() {
        ParameterRow choices = ParameterRow.named("mode", "String").value("\"fast\"")
                .options(List.of("fast", "slow")).build();

        List<Node> buttons = childrenOf(widgetFor(choices, TEXT));

        assertTrue(((RadioButton) buttons.getFirst()).isSelected());
        assertFalse(((RadioButton) buttons.get(1)).isSelected());
    }
}
