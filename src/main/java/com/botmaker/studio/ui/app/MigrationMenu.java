package com.botmaker.studio.ui.app;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents.MigrateTypeRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.SwitchUsesRequestedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.MigrationSteps;
import com.botmaker.studio.project.source.ValueTypeResolver;
import javafx.scene.control.MenuItem;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <i>Migrate type…</i> on a managed value's right-click (2026-10-12): a constant of an open set opens
 * {@link MigrationWindow} on its type's values, which says when no type fits. A value of a retired type also offers
 * <i>Switch uses to its copy</i>, for it alone ({@link MigrationActions}). Offered on a locked block too, since the
 * holder of an open set is locked on the canvas and neither edits it. Asked of every block a canvas draws, so it
 * decides by the field's type alone; which types fit, and whether a copy exists, are asked on the click.
 */
public final class MigrationMenu {

    private MigrationMenu() {}

    /** The items for {@code block}: none when it is not a constant of an open set. */
    public static List<MenuItem> items(CodeBlock block, EventBus bus) {
        if (bus == null || !(block.getAstNode() instanceof FieldDeclaration field)
                || !Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
            return List.of();
        }
        if (!(ValueTypeResolver.of(PluginHost.grammar(), field.getType(), 0, Set.of()) instanceof Class<?> type)
                || !MigrationSteps.held(PluginHost.managedValues(), type)) {
            return List.of();
        }
        List<MenuItem> out = new ArrayList<>();
        MenuItem migrate = new MenuItem("Migrate " + type.getSimpleName() + " values to another type…");
        migrate.setOnAction(e -> bus.publish(new MigrateTypeRequestedEvent(type)));
        out.add(migrate);
        if (ValueGrammar.retired(type) && field.fragments().size() == 1) {
            String name = ((VariableDeclarationFragment) field.fragments().getFirst()).getName().getIdentifier();
            MenuItem switchUses = new MenuItem("Switch uses of " + name + " to its copy");
            switchUses.setOnAction(e -> bus.publish(new SwitchUsesRequestedEvent(type, name)));
            out.add(switchUses);
        }
        return List.copyOf(out);
    }
}
