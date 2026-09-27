package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A list or a set of an enum's constants is one toggle per constant (2026-09-27): what the user asked for
 * when a list of days was a column of day pickers.
 *
 * <p>The grammar declares {@code DayOfWeek} the way plugin-basics does; Studio depends on no plugin, so the
 * declaration is written here.
 */
class EnumTogglesTest extends FxHeadlessTest {

    private static final PluginType<DayOfWeek> DAYS = new PluginType<>() {
        @Override public Class<DayOfWeek> type() { return DayOfWeek.class; }
        @Override public DayOfWeek fresh() { return DayOfWeek.MONDAY; }
    };

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(DAYS), List.of());

    @Override
    public void start(Stage stage) {
    }

    private ValueEditors.Editor editorFor(Type form, String written) {
        ValueEditors.Editor[] built = new ValueEditors.Editor[1];
        interact(() -> built[0] = ParamValueWidgets.editor(GRAMMAR, form, SourceNode.parse(written),
                BotRecords.none(), ValueEditors.Context.of(null)));
        return built[0];
    }

    private static List<ToggleButton> toggles(Node strip) {
        return ((Pane) strip).getChildren().stream().map(node -> assertInstanceOf(ToggleButton.class, node)).toList();
    }

    @Test
    void aListOfDaysIsSevenTogglesInTheWeeksOrder() {
        ValueEditors.Editor editor = editorFor(ValueTypes.listOf(DayOfWeek.class),
                "List.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)");
        List<ToggleButton> days = toggles(editor.node());

        assertEquals(7, days.size());
        assertEquals("Monday", days.getFirst().getText());
        assertTrue(days.get(4).isSelected(), "Friday is on");
        assertTrue(days.getFirst().isSelected(), "Monday is on");
    }

    @Test
    void untouchedItRewritesNothingAndTouchedItWritesTheWeeksOrder() {
        String written = "List.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)";
        ValueEditors.Editor editor = editorFor(ValueTypes.listOf(DayOfWeek.class), written);
        assertEquals(written, editor.read().get().orElseThrow().source());

        interact(() -> toggles(editor.node()).get(2).fire());

        String read = editor.read().get().map(value -> value.source()).orElse("");
        assertTrue(read.indexOf("MONDAY") < read.indexOf("WEDNESDAY")
                && read.indexOf("WEDNESDAY") < read.indexOf("FRIDAY"), read);
    }

    @Test
    void aSetOfDaysIsTogglesToo() {
        ValueEditors.Editor editor = editorFor(ValueTypes.of(com.botmaker.studio.plugin.grammar.ValueContainer.SET,
                List.of(DayOfWeek.class)), "Set.of()");
        assertEquals(7, toggles(editor.node()).size());
        assertEquals(Optional.of(false), Optional.of(toggles(editor.node()).getFirst().isSelected()));
    }
}
