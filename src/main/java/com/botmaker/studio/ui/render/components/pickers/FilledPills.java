package com.botmaker.studio.ui.render.components.pickers;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.core.plugin.FilledType;
import com.botmaker.core.plugin.PluginHost;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.ArrayList;
import java.util.List;

/**
 * Studio's drawing of a {@link FilledType}: a pill naming the type, the Java and what the bot does with it in its
 * tooltip. The host declares the type and draws nothing; this is where it becomes an editor.
 */
public final class FilledPills {

    /** The host's claimants and the editors built from them, replaced together when a bind replaces the first. */
    private record Built(List<PluginHost.OwnedSlot> from, List<PluginHost.OwnedEditor> editors) {}

    private static volatile Built built = new Built(List.of(), List.of());

    private FilledPills() {}

    /**
     * Every slot claimant as an editor, in the host's order, a filled type as its pill in its plugin's place: what
     * a slot or a row asks to draw it. Built once per bind, since a slot asks on every draw.
     */
    public static List<PluginHost.OwnedEditor> withEditors() {
        List<PluginHost.OwnedSlot> slots = PluginHost.ownedSlots();
        Built last = built;
        if (last.from() == slots) return last.editors();
        List<PluginHost.OwnedEditor> editors = new ArrayList<>();
        for (PluginHost.OwnedSlot slot : slots) {
            switch (slot) {
                case PluginHost.OwnedEditor editor -> editors.add(editor);
                case PluginHost.OwnedFilled filled -> {
                    FilledType<?> type = filled.type();
                    editors.add(new PluginHost.OwnedEditor(filled.plugin(), filled.pluginName(),
                            SlotEditor.forType(type.type()).draw(() -> ctx -> pill(type, ctx), () -> ctx -> pill(type, ctx))));
                }
            }
        }
        List<PluginHost.OwnedEditor> out = List.copyOf(editors);
        built = new Built(slots, out);
        return out;
    }

    static Node pill(FilledType<?> type, ValueContext ctx) {
        Label pill = new Label(type.label());
        pill.getStyleClass().add(StyleClasses.VALUE_LABEL);
        pill.setTooltip(new Tooltip(FilledType.help(type.label(), ctx.source())));
        return pill;
    }
}
