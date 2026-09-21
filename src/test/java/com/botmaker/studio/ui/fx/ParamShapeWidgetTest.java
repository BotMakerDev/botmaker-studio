package com.botmaker.studio.ui.fx;

import com.botmaker.plugin.api.parameters.ParameterGroup;
import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.Range;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.studio.plugin.ValueWire;
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
 */
class ParamShapeWidgetTest extends FxHeadlessTest {

    private static final ValueType TEXT = ValueWire.type("TEXT");
    private static final ValueType WHOLE_NUMBER = ValueWire.type("WHOLE_NUMBER");
    private static final ValueType POINT = ValueWire.type("POINT");
    private static final ValueType DIRECTION = ValueWire.type("DIRECTION");

    private Node widgetFor(ParameterRow row) {
        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build(ParameterGroup.DEFAULT_ID, row, null, sink));
        return built[0];
    }

    /**
     * One row as a plugin's own store would hand it over: seeded with the form's default initialiser and
     * carrying the declared choices as that store normalises them.
     */
    private static ParameterRow row(String name, ValueForm form, List<String> options) {
        return ParameterRow.named(name, form)
                .value(ValueWire.defaultInitializer(form))
                .options(ValueWire.normalizeOptions(options, form.leaf(), Range.NONE))
                .build();
    }

    private static ParameterRow row(String name, ValueForm form) {
        return row(name, form, List.of());
    }

    private List<Node> childrenOf(Node node) {
        return node instanceof Pane pane ? List.copyOf(pane.getChildren()) : List.of();
    }

    /**
     * Every one of the SDK's directions has a square on the pad. The table listed only the screen spelling
     * ({@code UP}, {@code DOWN}) while the SDK ships the compass, so all four constants missed the grid and
     * fell into the row of named buttons kept for the odd one out — a pad that positioned nothing.
     */
    @Test
    void everyDirectionTheSdkHasHasASquareOnThePad() {
        com.botmaker.studio.TestSupport.assumeSdkPluginBound();
        List<String> known = ValueWire.fixedOptions(DIRECTION);
        assertFalse(known.isEmpty(), "the SDK enum is what the pad is built from");

        Node pad = widgetFor(row("way", ValueForm.of(DIRECTION)));
        List<Node> parts = childrenOf(pad);

        assertEquals(1, parts.size(),
                "a second row means constants the grid had no square for and fell through to name buttons");
        assertEquals(known.size(), childrenOf(parts.getFirst()).size(),
                "one square per direction the SDK has");
    }

    /**
     * The two list cells, side by side on the same form. What tells them apart is the row's own choices, and
     * nothing else: a list with a set declared is tick boxes, a list with none is the user's to fill in.
     */
    @Test
    void aListIsTicksWithASetDeclaredAndTheUsersOwnWithout() {
        List<String> skills = List.of("mine", "fish", "cook");

        ParameterRow many = row("many", ValueForm.listOf(ValueForm.of(TEXT)), skills);
        List<Node> ticks = childrenOf(widgetFor(many));
        assertEquals(skills.size(), ticks.size());
        for (Node tick : ticks) assertInstanceOf(CheckBox.class, tick);

        // A textarea and not a Pane, so it has no children to count: text is written one per line.
        ParameterRow open = row("open", ValueForm.listOf(ValueForm.of(TEXT)));
        assertInstanceOf(TextArea.class, widgetFor(open),
                "a list with nothing declared is the user's to fill in");
    }

    /** Every other type's list is a growable column of that type's own editor, empty to begin with. */
    @Test
    void aListOfSomethingOtherThanTextIsRowsOfItsOwnEditor() {
        ParameterRow spots = row("spots", ValueForm.listOf(ValueForm.of(POINT)));

        List<Node> parts = childrenOf(widgetFor(spots));

        assertFalse(parts.isEmpty(), "the empty state still has to say something and offer Add");
        assertTrue(parts.stream().noneMatch(part -> part instanceof CheckBox),
                "nothing to tick: no set has been declared");
    }

    @Test
    void aDeclaredSetOnALeafIsRadioButtons() {
        ValueForm number = ValueForm.of(WHOLE_NUMBER);

        List<Node> rows = childrenOf(widgetFor(row("size", number, List.of("1", "2", "3"))));

        assertEquals(3, rows.size());
        for (Node button : rows) assertInstanceOf(RadioButton.class, button);
        assertEquals("2", ((RadioButton) rows.get(1)).getUserData());
    }

    /** With nothing declared the same leaf is the free-value editor, not an empty radio group. */
    @Test
    void aLeafWithNothingDeclaredIsItsOwnEditor() {
        assertTrue(childrenOf(widgetFor(row("size", ValueForm.of(WHOLE_NUMBER)))).isEmpty(),
                "a spinner is one control, not a column of choices");
    }

    /**
     * A map is two columns of the leaf editors its key and value types have, with an Add row beneath — the
     * cell `Map<String, Integer>` gets, and the checkpoint the whole type tree was built for.
     */
    @Test
    void aMapIsTwoColumnsAndAnAddRow() {
        ParameterRow retries = row("retries",
                ValueForm.mapOf(ValueForm.of(TEXT), ValueForm.of(WHOLE_NUMBER)));

        List<Node> parts = childrenOf(widgetFor(retries));

        assertFalse(parts.isEmpty(), "an empty map still says so and offers Add");
        assertTrue(parts.stream().noneMatch(part -> part instanceof CheckBox),
                "a map is not a set of choices");
    }

    /**
     * The reader hands back the Java the field takes, written from the option's own stored text and never
     * from the button's label. They part company the moment an option carries a graphic — a template's
     * thumbnail, a colour swatch — and a value read off a label would then be whatever the label said.
     */
    @Test
    void whatIsReadBackIsTheValueWrittenAsJavaAndNotTheLabel() {
        com.botmaker.studio.TestSupport.assumeSdkPluginBound();
        ParameterRow choices = row("mode", ValueForm.of(TEXT), List.of("fast", "slow"));

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build(ParameterGroup.DEFAULT_ID, choices, null, sink));
        List<Node> buttons = childrenOf(built[0]);
        interact(() -> ((RadioButton) buttons.get(1)).setSelected(true));

        assertEquals(1, sink.size());
        assertEquals("\"slow\"", sink.getFirst().read().get());
    }
}
