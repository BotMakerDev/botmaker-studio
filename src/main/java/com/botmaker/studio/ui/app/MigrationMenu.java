package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents.MigrateTypeRequestedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.project.managed.MigrationSteps;
import com.botmaker.studio.project.source.ValueTypeResolver;
import javafx.scene.control.MenuItem;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.Optional;
import java.util.Set;

/**
 * <i>Migrate type…</i> on a managed value's right-click (2026-10-12): a constant of an open set opens
 * {@link MigrationWindow} on its type's values, which says when no type fits. Offered on a locked block too,
 * since the holder of an open set is locked on the canvas and the migration copies, never edits it. Asked of
 * every block a canvas draws, so it decides by the field's type alone; which types fit is the window's question.
 */
public final class MigrationMenu {

    private MigrationMenu() {}

    /** The item for {@code block}, or empty when it is not a constant of an open set. */
    public static Optional<MenuItem> item(CodeBlock block, EventBus bus) {
        if (bus == null || !(block.getAstNode() instanceof FieldDeclaration field)
                || !Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
            return Optional.empty();
        }
        if (!(ValueTypeResolver.of(PluginHost.grammar(), field.getType(), 0, Set.of()) instanceof Class<?> type)
                || !MigrationSteps.held(PluginHost.managedValues(), type)) {
            return Optional.empty();
        }
        MenuItem item = new MenuItem("Migrate " + type.getSimpleName() + " values to another type…");
        item.setOnAction(e -> bus.publish(new MigrateTypeRequestedEvent(type)));
        return Optional.of(item);
    }
}
