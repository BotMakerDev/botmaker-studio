package com.botmaker.studio.ui.app.params;

import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.studio.plugin.ConstantValues;
import com.botmaker.studio.plugin.EditorContest;
import com.botmaker.studio.plugin.HostServices;
import com.botmaker.studio.plugin.HostValueContext;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.managed.ManagedConstants;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The editor for one value of one leaf type, chosen from the bound plugins and handed the value's Java.
 *
 * <p><b>Every editor here is a plugin's (2026-09-22).</b> This class drew a check box for a flag, a spinner
 * for a whole number, a field for a decimal, three spinners for a time of day, a date picker, an arrow pad
 * for a direction, a mouse diagram and a searchable key list — a {@code switch} over seventeen type ids that
 * the host kept beside the plugins that owned the types. Each of those is a {@code PluginType.editor} now,
 * in plugin-basics for the JDK's and in the SDK for its own, so the Parameters window, the Runner and a slot
 * on the canvas draw one editor rather than two that had to be kept saying the same thing. What is left is
 * the dispatch and the read-only field for a type nobody claims.
 *
 * <p><b>Java in, a tree out.</b> An editor is given a {@link HostValueContext} over the value's source and
 * writes a value back through it; what this hands its caller is that context's {@code current()} tree, with
 * the imports it names. There is no second, "wire" spelling of a leaf any more, which is what stopped a
 * {@code java.awt.Color} parameter being rewritten the moment the window opened.
 *
 * <p><b>What was lost with the switch, said out loud:</b> a declared {@code @Param(min, max)} no longer
 * narrows a number's editor, because the contract has no member that carries a bound to a plugin's editor.
 * The bound is still read, still shown in the window's own column, and still the author's to keep.
 */
public final class ValueEditors {

    private ValueEditors() {}

    /**
     * A built editor: the control to show, and the value it currently holds as a tree with its imports —
     * empty when it holds nothing writable.
     */
    public record Editor(Node node, Supplier<Optional<JavaValue>> read) {

        /** An editor that shows {@code node} and never writes. */
        static Editor readOnly(Node node) {
            return new Editor(node, Optional::empty);
        }
    }

    /** What an editor may need beyond the value itself: the project a plugin's services are built for. */
    public record Context(ProjectConfig project) {

        public static Context of(ProjectConfig project) {
            return new Context(project);
        }

        public static Context none() {
            return new Context(null);
        }

        /**
         * The bot's {@code @Managed} constants, so a row holding {@code Pictures.ORE} is edited as the picture
         * and a picture equal to one is written back as the constant. Read from disk: a capture writes
         * {@code Pictures.java} to buffer and disk both, and a constant typed into the editor and not yet
         * saved is the one case this misses — the row then shows the name as written.
         */
        Supplier<List<ManagedConstants.Constant>> constants() {
            return project == null ? ConstantValues.NONE : () -> ManagedConstants.scan(project, null);
        }
    }

    /**
     * The editor for one value of {@code leaf}, seeded from {@code source} — the value's Java as it stands,
     * or {@code null} for a fresh one, which starts as the type's own fresh value.
     *
     * <p>Chosen by the type alone, which is what makes retyping a variable safe to handle by rebuilding the
     * row wholesale: the caller throws the old editor away rather than trying to reinterpret what was in it.
     */
    public static Editor editorFor(Type leaf, String source, Context ctx) {
        ValueGrammar grammar = PluginHost.grammar();
        // A fresh seed is written fully qualified: kept unedited, it is written back as it stands, and a kept
        // expression carries no imports.
        String seed = source != null ? source : grammar.freshInitializer(leaf).map(JavaValue::source).orElse("");
        Editor contributed = fromPlugin(leaf, seed, ctx);
        if (contributed != null) return contributed;
        // A type no plugin draws, and — read-only — a type nothing declares. The host cannot offer a
        // meaningful editor for a value it cannot read, and letting somebody type into it would destroy the
        // Java of a variable whose plugin is merely absent today.
        TextField field = new TextField(seed);
        field.setEditable(false);
        field.setTooltip(new Tooltip(grammar.known(leaf)
                ? "No installed plugin draws an editor for " + ValueTypes.sourceName(leaf) + ". It is kept as written."
                : "No installed plugin declares " + ValueTypes.sourceName(leaf) + ". It is kept as written."));
        return Editor.readOnly(field);
    }

    /**
     * The first plugin editor that claims {@code leaf} and draws something, or null when none does.
     *
     * <p><b>One editor serves this window and a slot in the source.</b> The predicate is written against a
     * {@link com.botmaker.plugin.api.slot.TypeRef} — the Java type — so the same {@code matches} that
     * recognises a {@code com.acme.Channel} argument in a bot's source recognises a variable of that type
     * here. {@link HostValueContext#typeRef} is the translation, and it is the whole of the bridge.
     *
     * <p>The context holds the value: an editor writes through {@code set(…)} and the returned {@code read}
     * asks the context rather than the widget, which is what lets a plugin build any node it likes without
     * telling the host how to read it back. Nothing is written until an editor writes: a value opened and
     * closed with no edit reads back exactly as it was.
     */
    private static Editor fromPlugin(Type leaf, String source, Context ctx) {
        HostValueContext context = HostValueContext.of(leaf, source, HostServices.forProject(ctx.project()), null,
                ctx.constants());
        for (SlotEditor editor : claimants(leaf, context)) {
            try {
                Node node = editor.create(context);
                if (node != null) return new Editor(node, context::current);
            } catch (RuntimeException | LinkageError e) {
                // A plugin's editor is third-party code drawn inside our dialog: one that throws must cost the
                // user that row's widget, never the window. The next editor is offered the value, and the
                // read-only field is still behind them all.
                System.err.println("Plugin slot editor failed for type " + ValueTypes.sourceName(leaf) + ": " + e);
            }
        }
        return null;
    }

    /**
     * Every plugin editor that claims {@code context}, in the order they should be tried — the user's chosen
     * plugin first when there is a contest and they have settled it.
     *
     * <p>Shared by both walks here, because a row and its option previews must not be drawn by two different
     * plugins. The key is the fully qualified Java type, which is what {@code ResolvedType.qualifiedName()}
     * spells on the canvas — one key space is what makes a verdict given on a block apply to this row.
     *
     * <p><b>This window offers no <i>Edit with</i> menu of its own</b>, and that is a stated gap rather than
     * an oversight: persisting a verdict needs a {@code ProjectSettingsService}, which {@link Context} does
     * not carry. So the canvas asks, and this window honours the answer.
     */
    private static List<SlotEditor> claimants(Type leaf, HostValueContext context) {
        List<PluginHost.OwnedEditor> editors = PluginHost.ownedSlotEditors();
        if (editors.isEmpty()) return List.of();

        List<PluginHost.OwnedEditor> claiming = new ArrayList<>();
        for (PluginHost.OwnedEditor owned : editors) {
            try {
                if (owned.editor().matches(context)) claiming.add(owned);
            } catch (RuntimeException | LinkageError e) {
                System.err.println("Plugin slot editor failed for type " + ValueTypes.sourceName(leaf) + ": " + e);
            }
        }
        String typeName = context.typeName();
        List<PluginHost.OwnedEditor> ordered = EditorContest.ordered(
                claiming, PluginHost.OwnedEditor::pluginId, PluginHost.preferredEditorFor(typeName));

        List<SlotEditor> result = new ArrayList<>();
        for (PluginHost.OwnedEditor owned : ordered) result.add(owned.editor());
        return result;
    }

    /**
     * The picture a plugin draws beside one declared choice of its own type, or {@code null} — in which case
     * the choice's own label is the whole of it.
     *
     * <p>The third place this window shows a value. {@link SlotEditor#preview} is asked with a context that
     * is deliberately inert — {@code set} goes nowhere, because a declared choice is a value being
     * <em>listed</em>, not one being edited — and its default is {@code null}.
     *
     * <p>The host drew a colour swatch and a direction arrow here itself until 2026-09-22. Both types are
     * plugins' now, and a preview a plugin's own editor can draw is the plugin's.
     */
    static Node optionGraphic(Type leaf, String source, Context ctx) {
        if (source == null || source.isBlank()) return null;
        HostValueContext context = HostValueContext.of(leaf, source, HostServices.forProject(ctx.project()), null,
                ctx.constants());
        for (SlotEditor editor : claimants(leaf, context)) {
            try {
                Node node = editor.preview(context);
                if (node != null) return node;
            } catch (RuntimeException | LinkageError e) {
                // Same rule as fromPlugin: a plugin's node is third-party code drawn inside our dialog, and
                // one that throws costs this option its picture, never the window.
                System.err.println("Plugin preview failed for type " + ValueTypes.sourceName(leaf) + ": " + e);
            }
        }
        return null;
    }

    /** Lets an editor fill the width it is given, which is what a form column wants and a toolbar does not. */
    public static void stretch(Node node) {
        if (node instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
        else if (node instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
    }
}
