package com.botmaker.studio.ui.fx;

import com.botmaker.studio.blocks.func.CallOwner;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.parser.ExpressionChoice;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.ui.render.menu.ExpressionMenu;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the value menu offers after its review: no submenu that would open empty, Array Item where an array
 * fits, no nested list outside a list — and whose code a call runs, as its block says it.
 */
class ExpressionMenuReviewTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {}

    private static String source(String body) {
        return """
                package com.mybot;
                public class Subject {
                    public void run() {
                %s
                    }
                }
                """.formatted(body.indent(8));
    }

    private static Expression initializer(EditorFixture f, int statement) {
        VariableDeclarationStatement declaration =
                (VariableDeclarationStatement) f.body("run").getStatements().get(statement).getAstNode();
        return ((VariableDeclarationFragment) declaration.fragments().getFirst()).getInitializer();
    }

    private ContextMenu menu(EditorFixture f, Expression slot, List<Object> picked) {
        CodeEditorService context = f.context();
        ResolvedType type = ResolvedType.of(slot.resolveTypeBinding());
        AtomicReference<ContextMenu> ref = new AtomicReference<>();
        interact(() -> ref.set(ExpressionMenu.create(type, false, context, slot, null, picked::add)));
        return ref.get();
    }

    private static List<String> topLevel(ContextMenu menu) {
        return menu.getItems().stream().map(MenuItem::getText).filter(t -> t != null).toList();
    }

    @Test
    void anArrayOfFittingItemsIsOfferedAsArrayItem() {
        EditorFixture f = new EditorFixture(source("int[] scores = {1, 2};\nint best = 0;"));
        List<Object> picked = new ArrayList<>();
        ContextMenu menu = menu(f, initializer(f, 1), picked);

        Menu arrayItem = (Menu) menu.getItems().stream()
                .filter(i -> "Array Item".equals(i.getText())).findFirst().orElse(null);
        assertNotNull(arrayItem, topLevel(menu).toString());
        MenuItem scores = arrayItem.getItems().getFirst();
        assertEquals("scores[0]", scores.getText());
        interact(scores::fire);
        assertEquals(new ExpressionChoice.ArrayItem("scores"), picked.getFirst());

        f.editor.replaceWithArrayItem(initializer(f, 1), "scores");
        assertNotNull(f.lastCode, f.statusMessages.toString());
        assertTrue(f.lastCode.contains("int best = scores[0];"), f.lastCode);
    }

    @Test
    void noSubmenuIsOfferedThatWouldOpenEmpty() {
        EditorFixture f = new EditorFixture(source("boolean done = false;"));
        ContextMenu menu = menu(f, initializer(f, 0), new ArrayList<>());
        List<String> items = topLevel(menu);
        assertFalse(items.contains("Parameters"), "no plugin declares a parameter: " + items);
        assertFalse(items.contains("Array Item"), "no array in scope: " + items);
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu sub) assertFalse(sub.getItems().isEmpty(), sub.getText() + " opens empty");
        }
    }

    @Test
    void aNestedListIsOfferedOnlyInsideAList() {
        EditorFixture f = new EditorFixture(source("int[][] grid = {{1}};\nint[] row = null;"));
        List<String> inRow = topLevelLeaves(menu(f, initializer(f, 1), new ArrayList<>()));
        assertFalse(inRow.contains("Sub-List"), inRow.toString());
    }

    private static List<String> topLevelLeaves(ContextMenu menu) {
        List<String> out = new ArrayList<>();
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu sub) sub.getItems().forEach(i -> out.add(i.getText()));
            else out.add(item.getText());
        }
        return out;
    }

    @Test
    void aCallSaysWhoseCodeItRuns() {
        EditorFixture f = new EditorFixture("""
                package com.mybot;
                public class Subject {
                    public void run() {
                        helper();
                        Math.max(1, 2);
                    }
                    void helper() {}
                }
                """);
        List<MethodInvocation> calls = new ArrayList<>();
        f.state.getCompilationUnit().orElseThrow().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                calls.add(node);
                return true;
            }
        });
        assertEquals(CallOwner.PROJECT, CallOwner.of(calls.get(0).resolveMethodBinding()));
        assertEquals(CallOwner.JAVA, CallOwner.of(calls.get(1).resolveMethodBinding()));
        assertEquals(CallOwner.PROJECT, CallOwner.of(null));
    }
}
