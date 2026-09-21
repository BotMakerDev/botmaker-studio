package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.parameters.ParameterGroup;
import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.studio.ui.app.params.ParamValueWidgets;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value cell for a field typed with one of the bot's own records: one labelled row per component, and a
 * constructor call read back.
 *
 * <p>It lives in this package rather than beside the other widget tests so it can build a {@link BotRecords}
 * out of source text, which is the only fixture it needs — no project on disk, and no plugin bound.
 */
class RecordValueCellTest extends FxHeadlessTest {

    private static final String POINT = """
            package com.example.bot;

            public record Point(int x, int y) {}
            """;

    private static final ValueForm.Declared FORM = new ValueForm.Declared("com.example.bot.Point", List.of());

    private static List<Node> childrenOf(Node node) {
        return node instanceof Pane pane ? List.copyOf(pane.getChildren()) : List.of();
    }

    @Test
    void aRecordIsOneLabelledRowPerComponentAndNoAddRow() {
        BotRecords records = BotRecords.of(TestValues.CATALOG, List.of(POINT));
        ParameterRow row = ParameterRow.named("origin", FORM).value("new Point(1, 2)").build();

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build(
                ParameterGroup.DEFAULT_ID, row, null, records, sink));
        List<Node> rows = childrenOf(built[0]);

        assertEquals(2, rows.size(), "one row per component, and no Add: a record is as long as it is");
        assertEquals("x", ((Label) childrenOf(rows.getFirst()).getFirst()).getText());
        assertEquals("y", ((Label) childrenOf(rows.get(1)).getFirst()).getText());
        assertEquals(1, sink.size());
    }

    /**
     * What the components read back is one constructor call, written fully qualified so the line compiles
     * wherever the field is declared.
     *
     * <p>Needs a plugin bound, because the cell turns a component's typed text into Java through the
     * <em>host's</em> catalog — the same one every other value cell asks. With nothing bound the reader
     * answers blank, which is what stops a window writing over a field it cannot spell.
     */
    @Test
    void theComponentsReadBackOneFullyQualifiedConstructorCall() {
        com.botmaker.studio.TestSupport.assumeSdkPluginBound();
        BotRecords records = BotRecords.of(
                com.botmaker.studio.plugin.PluginHost.valueTypes(), List.of(POINT));
        ParameterRow row = ParameterRow.named("origin", FORM).value("new Point(1, 2)").build();

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build(
                ParameterGroup.DEFAULT_ID, row, null, records, sink));
        List<Node> rows = childrenOf(built[0]);

        assertEquals("new com.example.bot.Point(1, 2)", sink.getFirst().read().get());

        // What is typed into a component is what the next write carries.
        Node field = childrenOf(rows.getFirst()).get(1);
        interact(() -> ((TextInputControl) field).setText("7"));
        assertEquals("new com.example.bot.Point(7, 2)", sink.getFirst().read().get());
    }

    /** With no records in hand the same row is the read-only cell, and nothing can write over it. */
    @Test
    void withoutTheProjectsSourcesTheCellIsReadOnly() {
        ParameterRow row = ParameterRow.named("origin", FORM).value("new Point(1, 2)").build();

        List<ParamValueWidgets.ValueEditor> sink = new ArrayList<>();
        Node[] built = new Node[1];
        interact(() -> built[0] = ParamValueWidgets.build(ParameterGroup.DEFAULT_ID, row, null, sink));

        assertTrue(sink.isEmpty(), "a read-only cell adds no reader, so nothing can write over the field");
        assertEquals("new Point(1, 2)", ((Label) childrenOf(built[0]).getFirst()).getText());
    }
}
