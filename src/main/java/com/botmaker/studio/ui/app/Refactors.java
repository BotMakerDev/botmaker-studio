package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.services.CodeEditorService;
import javafx.scene.Node;
import javafx.stage.Window;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.function.Consumer;

/**
 * The canvas's side of {@link Refactor}: a rename asked for on a block, planned against the whole bot, written
 * as one ↶ step ({@code CodeEditor.applyRefactor}) or refused through {@link RefusalDialog} with whatever fix
 * the refactor could vouch for.
 *
 * <p>Every name a block lets the user retype that other code can refer to comes here — a field, an enum and
 * its constants. They each renamed the declaration alone until 2026-09-27, so a used one was refused by the
 * compile check at best, and the user was never told why.
 */
public final class Refactors {

    private Refactors() {}

    /**
     * Renames what {@code name} declares, in the open file, and every use of it in the bot.
     *
     * @param anchor the control the new name was typed into, for the refusal's owner window
     */
    public static void rename(CodeEditorService context, Node anchor, SimpleName name, String newName) {
        ProjectFile active = context.getState().getActiveFile();
        if (active == null || active.getPath() == null || name == null) return;
        BotIndex index = BotIndex.of(context.getConfig(), context.getState());
        Refactor.Outcome outcome = Refactor.rename(index, active.getPath(), name.getStartPosition(), newName);
        switch (outcome) {
            case Refactor.Planned plan -> context.getCodeEditor().applyRefactor(plan, name);
            case Refactor.Refused refused -> RefusalDialog.show(windowOf(anchor),
                    name.getIdentifier() + " wasn't renamed", refused, reveal(context),
                    fix -> context.getCodeEditor().applyRefactor(fix, name));
        }
    }

    /** Lands on a use: opens its file and scrolls to its block, through the window's navigation. */
    public static Consumer<Usages.Usage> reveal(CodeEditorService context) {
        return use -> context.getEventBus().publish(
                new CoreApplicationEvents.RevealRequestedEvent(use.file(), use.start()));
    }

    private static Window windowOf(Node node) {
        return node == null || node.getScene() == null ? null : node.getScene().getWindow();
    }
}
