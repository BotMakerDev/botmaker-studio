package com.botmaker.studio.ui.render.components.types;

import com.botmaker.studio.project.params.TestValues;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type chooser's menu under the pointer (feedback 3, 2026-09-27): a pick must land on the first click
 * every time, and a double-click is a pick and a close.
 */
class TypeChooserTest extends FxHeadlessTest {

    private TypeChooser chooser;

    @Override
    public void start(Stage stage) {
        chooser = new TypeChooser(() -> TypeCatalog.of(TypeCatalog.Purpose.DECLARATION, TestValues.GRAMMAR,
                List.of(), List.of()));
        chooser.setType(int.class);
        stage.setScene(new javafx.scene.Scene(new StackPane(chooser), 300, 200));
        stage.show();
    }

    private ContextMenu opened() {
        ContextMenu[] menu = new ContextMenu[1];
        interact(() -> menu[0] = chooser.open());
        return menu[0];
    }

    private static CustomMenuItem entry(ContextMenu menu, String label) {
        return menu.getItems().stream()
                .filter(item -> item instanceof CustomMenuItem custom
                        && custom.getContent().lookup(".type-chooser-name") instanceof javafx.scene.control.Label name
                        && name.getText().equals(label))
                .map(CustomMenuItem.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("no entry " + label));
    }

    /**
     * The items a click lands on are the items the menu keeps. A pick used to rebuild every row, so the next
     * row under the pointer was a fresh node that had seen no mouse-enter and swallowed a click.
     */
    @Test
    void aPickKeepsTheRowsAndMovesTheHighlight() {
        ContextMenu menu = opened();
        List<MenuItem> before = List.copyOf(menu.getItems());
        CustomMenuItem text = entry(menu, "String");

        interact(() -> text.fire());
        interact(() -> { });   // anything queued by the pick runs before the rows are compared

        assertEquals(before.size(), menu.getItems().size());
        for (int i = 0; i < before.size(); i++) assertSame(before.get(i), menu.getItems().get(i));
        assertTrue(text.getStyleClass().contains("type-chooser-current"));
        assertFalse(entry(menu, "int").getStyleClass().contains("type-chooser-current"));
        interact(menu::hide);
        assertEquals(String.class, chooser.type());
    }

    @Test
    void aDoubleClickPicksAndCloses() {
        ContextMenu menu = opened();
        Node row = entry(menu, "double").getContent();

        interact(() -> row.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, true, false, false, null)));

        assertFalse(menu.isShowing());
        assertEquals(double.class, chooser.type());
    }

    /** A map's key is picked by clicking it in the header first: a pick used to reach only the value. */
    @Test
    void aClickedPartOfTheHeaderIsThePartAPickChanges() {
        interact(() -> chooser.setType(com.botmaker.studio.plugin.grammar.ValueTypes.mapOf(String.class,
                Integer.class)));
        ContextMenu menu = opened();
        Node header = ((CustomMenuItem) menu.getItems().getFirst()).getContent();
        Node key = header.lookupAll(".type-path-part").stream()
                .filter(n -> n instanceof javafx.scene.control.Label l && l.getText().equals("String"))
                .findFirst().orElseThrow(() -> new AssertionError("no key in the header"));

        interact(() -> key.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null)));
        interact(() -> entry(menu, "int").fire());
        interact(menu::hide);

        assertEquals(com.botmaker.studio.plugin.grammar.ValueTypes.mapOf(int.class, Integer.class), chooser.type());
    }

    @Test
    void doneLeadsTheHeader() {
        ContextMenu menu = opened();
        Node header = ((CustomMenuItem) menu.getItems().getFirst()).getContent();
        Node first = ((javafx.scene.layout.HBox) header).getChildren().getFirst();
        assertTrue(first instanceof javafx.scene.control.Button done && done.getText().equals("Done"));
        interact(() -> ((javafx.scene.control.Button) first).fireEvent(new ActionEvent()));
        assertFalse(menu.isShowing());
    }
}
