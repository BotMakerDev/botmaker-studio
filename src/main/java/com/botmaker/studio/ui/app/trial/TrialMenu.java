package com.botmaker.studio.ui.app.trial;

import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.core.StatementBlock;
import com.botmaker.studio.events.CoreApplicationEvents.MethodRunRequestedEvent;
import com.botmaker.studio.events.CoreApplicationEvents.TryRequestedEvent;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.services.trial.TrialCaller;
import javafx.scene.control.MenuItem;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Statement;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one menu item both editors offer to run a piece of the bot on its own: ▶ Try on a statement of a method,
 * ▶ Run on a method that takes nothing and is static — an activity's body. The canvas puts it in a block's
 * right-click menu, the overlay editor in a row's ⋮; both publish the same request, which {@link Trials} answers.
 *
 * <p>Offered only while a bound plugin has a trial entry ({@code trial(Bot::trial)}): a bot without one can only
 * run whole, and an item that always refused would be noise.
 */
public final class TrialMenu {

    private TrialMenu() {}

    /** The item for {@code block}, or empty when it is neither a statement of a method nor a runnable method. */
    public static Optional<MenuItem> item(CodeBlock block, EventBus bus, Supplier<Window> owner) {
        if (block == null || TrialCaller.entry().isEmpty()) return Optional.empty();
        ASTNode node = block.getAstNode();
        // The tree is read again when the item is picked: a block that survives a re-parse keeps its node and
        // its menu, and is handed the new tree.
        if (node instanceof MethodDeclaration method) {
            if (runnable(method).isEmpty()) return Optional.empty();
            MenuItem item = new MenuItem("▶ Run " + method.getName().getIdentifier() + "() on its own");
            item.setOnAction(e -> {
                if (block.getAstNode() instanceof MethodDeclaration now) {
                    runnable(now).ifPresent(binding -> bus.publish(new MethodRunRequestedEvent(
                            packageOf(binding), binding.getDeclaringClass().getErasure().getQualifiedName(),
                            now.getName().getIdentifier(), !"void".equals(binding.getReturnType().getName()),
                            owner.get())));
                }
            });
            return Optional.of(item);
        }
        if (!(block instanceof StatementBlock)) return Optional.empty();
        Statement statement = block.enclosingStatement();
        if (statement == null || !inMethodBody(statement)) return Optional.empty();
        MenuItem item = new MenuItem("▶ Try this statement");
        item.setOnAction(e -> bus.publish(new TryRequestedEvent(block.enclosingStatement(), owner.get())));
        return Optional.of(item);
    }

    /**
     * {@code method}'s binding when it is static, takes nothing and has a body, and is not private — the caller is
     * another class of the package.
     */
    public static Optional<IMethodBinding> runnable(MethodDeclaration method) {
        if (method.getBody() == null || !method.parameters().isEmpty() || method.isConstructor()
                || !Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())) {
            return Optional.empty();
        }
        return Optional.ofNullable(method.resolveBinding());
    }

    /** The package {@code method}'s class is in; empty for the default package. */
    public static String packageOf(IMethodBinding method) {
        var pkg = method.getDeclaringClass().getPackage();
        return pkg == null || pkg.isUnnamed() ? "" : pkg.getName();
    }

    private static boolean inMethodBody(Statement statement) {
        for (ASTNode at = statement.getParent(); at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) return method.getBody() != null;
        }
        return false;
    }
}
