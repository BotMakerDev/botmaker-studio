package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.slot.SlotRun;
import com.botmaker.studio.plugin.EditorContest;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.HostSlotContext;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.services.ProjectSettingsService;
import javafx.application.Platform;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * The plugin tier of {@link PickerRegistry} — every loaded plugin's {@link SlotEditor}s, offered a slot of a
 * bot's source.
 *
 * <p>The Parameters window has had this since phase 11 ({@code ValueEditors.fromPlugin}); this is the same
 * thing for the canvas, and the pair is what makes the contract's promise true rather than aspirational: one
 * editor, written once against {@code ValueContext}, drawn in both places. The only difference between the two
 * call sites is which context is built — {@code HostValueContext} there, {@link HostSlotContext} here — and
 * that difference is exactly the call site a slot has and a row does not.
 *
 * <p><b>A plugin's editor is third-party code drawn inside our window.</b> One that throws costs the user that
 * slot's widget and nothing more: the next editor is offered the value, and the generic expression pill is
 * still behind them all. The same rule, and the same reasoning, as the Parameters window's.
 */
final class PluginPickers {

    private PluginPickers() {}

    /** The first plugin editor that claims {@code ctx}, built; or {@code null} when none does. */
    static Node nodeFor(PickerContext ctx) {
        return dispatch(ctx, true, null);
    }

    /** As above, for a slot the host knows to be one of a run of sibling arguments. */
    static Node nodeFor(PickerContext ctx, SlotRun run) {
        return dispatch(ctx, true, run);
    }

    /**
     * The preferred claimant's {@link SlotEditor#preview}, or {@code null} — for a slot that is shown and may
     * not be edited. Built over the same context an editor gets, so {@code matches} and {@code preview} read
     * the same source text.
     */
    static Node previewFor(PickerContext ctx) {
        List<PluginHost.OwnedEditor> editors = PluginHost.ownedSlotEditors();
        if (editors.isEmpty() || ctx == null) return null;
        HostSlotContext context = new HostSlotContext(ctx.context(), ctx.arg(), ctx.paramType(),
                ctx.className(), ctx.methodName(), ctx.argIndex(),
                HostServices.forProject(ctx.context() == null ? null : ctx.context().getConfig()), null);
        List<PluginHost.OwnedEditor> claimants = new ArrayList<>();
        for (PluginHost.OwnedEditor owned : editors) {
            try {
                if (owned.editor().matches(context)) claimants.add(owned);
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Plugin slot editor failed for " + typeLabel(ctx) + ": " + e);
            }
        }
        String typeName = ctx.paramType() == null ? null : ctx.paramType().qualifiedName();
        for (PluginHost.OwnedEditor owned : EditorContest.ordered(
                claimants, PluginHost.OwnedEditor::pluginId, PluginHost.preferredEditorFor(typeName))) {
            try {
                Node node = owned.editor().preview(context);
                if (node != null) return node;
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Plugin slot preview failed for " + typeLabel(ctx) + ": " + e);
            }
        }
        return null;
    }

    /** Whether any plugin editor claims {@code ctx}, without building anything. */
    static boolean hasPicker(PickerContext ctx) {
        return dispatch(ctx, false, null) != null;
    }

    /**
     * One walk of the plugin list, either asking or asking-and-building.
     *
     * <p>Written once because the two must agree: a {@code hasPicker} that said yes where {@code pickerNodeFor}
     * returns null leaves a slot advertised as editable and drawn as a bare pill. When {@code build} is false
     * the answer is a sentinel rather than a node, so a matching editor is never constructed just to be
     * discarded — {@code matches} is documented as cheap, {@code create} is not.
     */
    private static Node dispatch(PickerContext ctx, boolean build, SlotRun run) {
        List<PluginHost.OwnedEditor> editors = PluginHost.ownedSlotEditors();
        if (editors.isEmpty() || ctx == null) return null;

        HostSlotContext context = new HostSlotContext(ctx.context(), ctx.arg(), ctx.paramType(),
                ctx.className(), ctx.methodName(), ctx.argIndex(),
                HostServices.forProject(ctx.context() == null ? null : ctx.context().getConfig()), run);

        // Every claimant, not the first — which is what makes a contest visible at all. matches() is cheap by
        // contract and this walk happened anyway; what changes is that it does not stop.
        List<PluginHost.OwnedEditor> claimants = new ArrayList<>();
        for (PluginHost.OwnedEditor owned : editors) {
            try {
                if (owned.editor().matches(context)) claimants.add(owned);
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Plugin slot editor failed for " + typeLabel(ctx) + ": " + e);
            }
        }
        if (claimants.isEmpty()) return null;
        if (!build) return MATCHED;

        String typeName = ctx.paramType() == null ? null : ctx.paramType().qualifiedName();
        List<PluginHost.OwnedEditor> ordered = EditorContest.ordered(
                claimants, PluginHost.OwnedEditor::pluginId, PluginHost.preferredEditorFor(typeName));

        for (PluginHost.OwnedEditor owned : ordered) {
            Node node;
            try {
                node = owned.editor().create(context);
            } catch (RuntimeException | LinkageError e) {
                // Unchanged rule: a plugin's editor is third-party code drawn inside our window, and one that
                // throws costs the user that widget and nothing more.
                System.err.println("Plugin slot editor failed for " + typeLabel(ctx) + ": " + e);
                continue;
            }
            if (node == null) continue;
            if (ordered.size() > 1) offerTheOthers(node, typeName, ordered, owned.pluginId(), ctx);
            return node;
        }
        return null;
    }

    /**
     * Attaches the <i>Edit with</i> menu to a contested slot, and persists whatever the user picks.
     *
     * <p>The redraw is explicit rather than a {@code SettingsChangedEvent} subscription: the canvas does not
     * listen for one, and making it would re-render the whole program on every unrelated settings write — the
     * same objection the toolbar's own comment records. What a verdict changes is which widget a slot is drawn
     * with, which is exactly what {@code rerenderActiveFile} is for.
     */
    private static void offerTheOthers(Node node, String typeName, List<PluginHost.OwnedEditor> ordered,
                                       String drawing, PickerContext ctx) {
        if (typeName == null || typeName.isBlank() || ctx.context() == null) return;

        List<EditorContest.Claim> claims = new ArrayList<>();
        for (PluginHost.OwnedEditor owned : ordered) {
            claims.add(new EditorContest.Claim(owned.pluginId(), owned.pluginName()));
        }
        EditorContest.arm(node, EditorContest.menu(typeName, claims, drawing, pluginId -> {
            ProjectSettingsService settings = ProjectSettingsService.forProject(ctx.context());
            settings.update(settings.current().withPreferredEditor(typeName, pluginId))
                    .thenRun(() -> Platform.runLater(ctx.context()::rerenderActiveFile));
        }));
    }

    /** The type a failure was about, for one stderr line; a slot with no resolved type still says something. */
    private static String typeLabel(PickerContext ctx) {
        return ctx.paramType() == null ? "?" : ctx.paramType().simpleName();
    }

    /** Stands for "some editor claims this" in the detection-only walk; never attached to a scene. */
    private static final Node MATCHED = new javafx.scene.Group();
}
