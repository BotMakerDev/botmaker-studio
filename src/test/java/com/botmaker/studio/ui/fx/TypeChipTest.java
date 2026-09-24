package com.botmaker.studio.ui.fx;

import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.parser.helpers.JavaSnippets;
import com.botmaker.studio.ui.render.components.TypeChip;
import com.botmaker.studio.ui.render.menu.TypePicker;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.CustomMenuItem;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type drawn by its structure ({@link TypeChip}) and picked from a list ({@link TypePicker}): the chip's
 * parts, the whole type respelled when one part changes, and what each place offers.
 */
class TypeChipTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {}

    private static List<String> words(Node node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Label label) out.add(label.getText());
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> collect(child, out));
    }

    private static long count(Node node, String styleClass) {
        long own = node.getStyleClass().contains(styleClass) ? 1 : 0;
        if (!(node instanceof Parent parent)) return own;
        return own + parent.getChildrenUnmodifiable().stream().mapToLong(child -> count(child, styleClass)).sum();
    }

    @Test
    void aNestedGenericIsDrawnAsBoxesInBoxes() {
        AtomicReference<Node> chip = new AtomicReference<>();
        interact(() -> chip.set(TypeChip.of("Map<String, List<int[]>>")));
        assertEquals(List.of("Map", "‹", "String", ",", "List", "‹", "int", "[ ]", "›", "›"), words(chip.get()));
        assertEquals(3, count(chip.get(), "type-chip-nested"), "String, List<int[]> and its int[] are nested chips");
        assertEquals(1, count(chip.get(), "type-chip-primitive"));
        assertEquals(1, count(chip.get(), "type-chip-dimension"));
        assertEquals(0, count(chip.get(), "type-chip-clickable"), "read-only: nothing to click");
    }

    @Test
    void aClickReportsThePartAndWhetherItIsATypeArgument() {
        Type map = JavaSnippets.type("Map<String, int[]>");
        List<TypeChip.Part> clicked = new ArrayList<>();
        AtomicReference<Node> chip = new AtomicReference<>();
        interact(() -> chip.set(TypeChip.of(map, clicked::add)));
        assertEquals(3, count(chip.get(), "type-chip-clickable"), "Map, String and int");

        List<Label> parts = new ArrayList<>();
        collectClickable(chip.get(), parts);
        interact(() -> parts.forEach(p -> Event.fireEvent(p, new MouseEvent(MouseEvent.MOUSE_CLICKED,
                0, 0, 0, 0, MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false,
                false, null))));
        assertEquals(map, clicked.get(0).type(), "the name of a generic type is the whole type");
        assertFalse(clicked.get(0).typeArgument());
        assertEquals("String", clicked.get(1).type().toString());
        assertTrue(clicked.get(1).typeArgument());
        assertEquals("int[]", clicked.get(2).type().toString(), "an array's element reports the array");
    }

    private static void collectClickable(Node node, List<Label> out) {
        if (node instanceof Label label && label.getStyleClass().contains("type-chip-clickable")) out.add(label);
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> collectClickable(child, out));
    }

    @Test
    void replacingOnePartRespellsTheWholeType() {
        Type map = JavaSnippets.type("Map<String, List<int[]>>");
        ParameterizedType generic = (ParameterizedType) map;
        Type list = (Type) generic.typeArguments().get(1);
        assertEquals("Map<String,Set<Point>>", TypeChip.replace(map, list, "Set<Point>").replace(" ", ""));
        assertEquals("Point[]", TypeChip.replace(map, map, "Point[]"));
        assertNull(TypeChip.replace(map, list, "not a type!"));
    }

    private ContextMenu picker(TypePicker.Options options, List<TypePicker.Choice> picked) {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        int a = 1;
                    }
                }
                """);
        AtomicReference<ContextMenu> menu = new AtomicReference<>();
        interact(() -> menu.set(TypePicker.create(options, null, f.context(),
                f.body("run").getStatements().getFirst().getAstNode(), picked::add)));
        return menu.get();
    }

    private static List<String> texts(ContextMenu menu) {
        return menu.getItems().stream().map(MenuItem::getText).filter(t -> t != null).toList();
    }

    @Test
    void aCatchOffersOnlyWhatCanBeThrown() {
        ContextMenu menu = picker(TypePicker.Options.of(TypePicker.Filter.THROWABLE), new ArrayList<>());
        List<String> items = texts(menu);
        assertTrue(items.contains("IOException"), items.toString());
        assertFalse(items.contains("Subject"), "a project class that is no exception: " + items);
        assertFalse(items.contains("int"), items.toString());
        assertFalse(items.contains("String"), items.toString());
    }

    @Test
    void aDeclarationOffersPrimitivesTheProjectAndJava() {
        ContextMenu menu = picker(TypePicker.Options.of(TypePicker.Filter.ANY), new ArrayList<>());
        List<String> items = texts(menu);
        assertTrue(items.containsAll(List.of("PRIMITIVES", "THIS PROJECT", "JAVA")), items.toString());
        assertTrue(items.containsAll(List.of("int", "Subject", "String", "List")), items.toString());
    }

    @Test
    void aGenericPickIsWrittenWithAnArgumentOnlyWhereOneIsAllowed() {
        List<TypePicker.Choice> picked = new ArrayList<>();
        ContextMenu raw = picker(TypePicker.Options.of(TypePicker.Filter.ANY), picked);
        ContextMenu typed = picker(TypePicker.Options.of(TypePicker.Filter.ANY).withTypeArguments(), picked);
        interact(() -> item(raw, "Map").fire());
        interact(() -> item(typed, "Map").fire());
        assertEquals("Map", picked.get(0).text());
        assertEquals("Map<Object, Object>", picked.get(1).text());
    }

    @Test
    void aTypeTheListsDoNotHoldIsOfferedAsTyped() {
        List<TypePicker.Choice> picked = new ArrayList<>();
        ContextMenu menu = picker(TypePicker.Options.of(TypePicker.Filter.ANY), picked);
        TextField search = (TextField) ((CustomMenuItem) menu.getItems().getFirst()).getContent();
        interact(() -> search.setText("java.util.function.Supplier<String>"));
        MenuItem typed = item(menu, "Use “java.util.function.Supplier<String>”");
        assertNotNull(typed, texts(menu).toString());
        interact(typed::fire);
        assertEquals("java.util.function.Supplier<String>", picked.getFirst().text());

        interact(() -> search.setText("not a type!"));
        assertTrue(texts(menu).contains("Not a type Java can read"), texts(menu).toString());
    }

    private static MenuItem item(ContextMenu menu, String text) {
        return menu.getItems().stream().filter(i -> text.equals(i.getText())).findFirst().orElse(null);
    }

    @Test
    void aDeclarationsTypeIsAChip() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        java.util.List<int[]> rows = null;
                    }
                }
                """);
        VariableDeclarationStatement declaration =
                (VariableDeclarationStatement) f.body("run").getStatements().getFirst().getAstNode();
        AtomicReference<Node> chip = new AtomicReference<>();
        interact(() -> chip.set(TypeChip.of(declaration.getType())));
        assertEquals(List.of("List", "‹", "int", "[ ]", "›"), words(chip.get()));
    }
}
