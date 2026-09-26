package com.botmaker.studio.core.render;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A call's right-click menu offers Go to Definition, which asks Navigate to go there (2026-09-26). */
class GoToDefinitionMenuTest extends FxHeadlessTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public static void run() {
                    int count = 1;
                    helper(count);
                    System.out.println("done");
                }
                static void helper(int times) {}
            }
            """;

    @Override
    public void start(Stage stage) {
        stage.show();
    }

    @Test
    void aCallToTheBotsOwnFunctionOrALibraryOffersIt() {
        EditorFixture fixture = new EditorFixture(SOURCE);

        assertEquals("Go to Definition of helper", item(fixture, "helper").getText());
        assertEquals("Go to Definition of println", item(fixture, "println").getText());
    }

    @Test
    void aDeclarationOffersNothing() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        AbstractCodeBlock declaration = blockOf(fixture, n -> n instanceof VariableDeclarationStatement).orElseThrow();

        assertNull(InteractionDecorator.goToDefinition(declaration, fixture.context()));
    }

    @Test
    void choosingItHighlightsTheBlockAndAsksToGo() {
        EditorFixture fixture = new EditorFixture(SOURCE);
        List<Object> asked = new ArrayList<>();
        fixture.bus().subscribe(CoreApplicationEvents.GoToDefinitionRequestedEvent.class, asked::add);
        AbstractCodeBlock call = callBlock(fixture, "helper");

        InteractionDecorator.goToDefinition(call, fixture.context()).fire();

        assertEquals(1, asked.size());
        assertSame(call, fixture.state.getHighlightedBlock().orElseThrow());
    }

    private static MenuItem item(EditorFixture fixture, String method) {
        MenuItem item = InteractionDecorator.goToDefinition(callBlock(fixture, method), fixture.context());
        assertNotNull(item, method);
        return item;
    }

    private static AbstractCodeBlock callBlock(EditorFixture fixture, String method) {
        return blockOf(fixture, n -> invocation(n) instanceof MethodInvocation m
                && m.getName().getIdentifier().equals(method))
                .orElseThrow(() -> new AssertionError("no block for " + method));
    }

    private static ASTNode invocation(ASTNode node) {
        return node instanceof ExpressionStatement s ? s.getExpression() : node;
    }

    private static Optional<AbstractCodeBlock> blockOf(EditorFixture fixture,
                                                       java.util.function.Predicate<ASTNode> wanted) {
        for (CodeBlock block : fixture.state.getNodeToBlockMap().values()) {
            if (block instanceof AbstractCodeBlock b && wanted.test(b.getAstNode())) return Optional.of(b);
        }
        return Optional.empty();
    }
}
