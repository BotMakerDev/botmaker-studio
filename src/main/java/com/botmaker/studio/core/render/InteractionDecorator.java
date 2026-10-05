package com.botmaker.studio.core.render;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.nav.SourceNavigation;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.ui.app.trial.TrialMenu;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import org.eclipse.jdt.core.dom.IMethodBinding;

/**
 * Wires the per-block right-click menu (copy / paste after / breakpoint, Go to Definition on a call, ▶ Try on a
 * statement and ▶ Run on a method that takes nothing — {@link TrialMenu}). A read-only block gets Go to Definition
 * and ▶ Try alone, since neither edits anything.
 */
public final class InteractionDecorator implements BlockDecorator {

    @Override
    public void decorate(Node node, AbstractCodeBlock block, CodeEditorService context) {
        MenuItem definition = goToDefinition(block, context);
        // Trying a statement edits nothing, so a read-only block offers it as it offers Go to Definition.
        MenuItem trial = TrialMenu.item(block, context.getEventBus(),
                () -> node.getScene() == null ? null : node.getScene().getWindow()).orElse(null);
        if (block.isReadOnly()) {
            ContextMenu reading = new ContextMenu();
            if (definition != null) reading.getItems().add(definition);
            if (trial != null) reading.getItems().add(trial);
            if (!reading.getItems().isEmpty()) show(node, com.botmaker.studio.ui.render.menu.MenuTracker.track(reading));
            return;
        }

        ContextMenu menu = com.botmaker.studio.ui.render.menu.MenuTracker.track(new ContextMenu());

        if (definition != null) menu.getItems().add(definition);
        if (trial != null) menu.getItems().add(trial);
        if (definition != null || trial != null) menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        var blockItems = block.blockMenuItems(context);
        if (!blockItems.isEmpty()) {
            menu.getItems().addAll(blockItems);
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }

        MenuItem copy = new MenuItem("Copy (Ctrl+C)");
        copy.setOnAction(ev -> {
            context.getState().setHighlightedBlock(block);
            context.getEventBus().publish(new CoreApplicationEvents.CopyRequestedEvent());
        });

        MenuItem paste = new MenuItem("Paste After (Ctrl+V)");
        paste.setOnAction(ev -> {
            context.getState().setHighlightedBlock(block);
            context.getEventBus().publish(new CoreApplicationEvents.PasteRequestedEvent());
        });

        menu.getItems().addAll(copy, paste);
        if (block.canHoldBreakpoint()) {
            MenuItem breakpoint = new MenuItem();
            breakpoint.textProperty().bind(
                    javafx.beans.binding.Bindings.when(block.breakpointActiveProperty())
                            .then("Remove Breakpoint")
                            .otherwise("Add Breakpoint"));
            breakpoint.setOnAction(ev -> block.toggleBreakpoint());
            menu.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), breakpoint);
        }

        show(node, menu);
    }

    private static void show(Node node, ContextMenu menu) {
        node.setOnContextMenuRequested(e -> {
            menu.show(node, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /**
     * "Go to Definition" when the block is a call — the bot's own function lands on its block, a library's
     * opens read-only (Navigate ▸ Go to Declaration does the going) — or null for anything else.
     */
    static MenuItem goToDefinition(AbstractCodeBlock block, CodeEditorService context) {
        if (!(SourceNavigation.bindingOf(block.getAstNode()).orElse(null) instanceof IMethodBinding method)) {
            return null;
        }
        MenuItem item = new MenuItem("Go to Definition of " + (method.isConstructor()
                ? method.getDeclaringClass().getName() : method.getName()));
        item.setOnAction(ev -> {
            context.getState().setHighlightedBlock(block);
            context.getEventBus().publish(new CoreApplicationEvents.GoToDefinitionRequestedEvent());
        });
        return item;
    }
}
