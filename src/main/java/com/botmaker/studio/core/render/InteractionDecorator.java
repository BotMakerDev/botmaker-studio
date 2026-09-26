package com.botmaker.studio.core.render;

import com.botmaker.studio.core.AbstractCodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

/**
 * Wires the per-block right-click menu (copy / paste after). Skipped for read-only blocks, which are
 * non-interactive.
 */
public final class InteractionDecorator implements BlockDecorator {

    @Override
    public void decorate(Node node, AbstractCodeBlock block, CodeEditorService context) {
        if (block.isReadOnly()) return;

        ContextMenu menu = com.botmaker.studio.ui.render.menu.MenuTracker.track(new ContextMenu());

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

        node.setOnContextMenuRequested(e -> {
            menu.show(node, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }
}
