package com.botmaker.studio.ui.app;

import com.botmaker.plugin.api.ToolbarGroup;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.SeparatorMenuItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Which toolbar groups a project shows, and the right-click menu that changes it.
 *
 * <p><b>Hidden, not visible.</b> The persisted set names what to switch <em>off</em>, so a group this
 * project has never seen shows up: a release that adds one — {@code OVERLAY} did — would otherwise be
 * invisible in every project written before it, which reads as a broken install rather than as a setting.
 *
 * <p><b>A group, not a plugin, and not an item.</b> A group is what a user has a sentence about — <i>I never
 * run from the toolbar</i> — and {@link ToolbarGroup#RUN} holds Studio's own buttons beside whatever a
 * plugin put there, so hiding a <em>plugin</em> instead would answer a different question and leave the
 * host's half of the group behind. An item is the plugin's own declaration, and a bar edited button by
 * button is a bar nobody can support.
 *
 * <p><b>Not an overflow policy.</b> {@link OverflowBar} already answers <i>there is no room for this right
 * now</i>, reversibly and by itself; this answers <i>I never want this</i>, and so it must survive a resize.
 *
 * <p>Nothing here crosses the contract. Visibility is decided after {@code PluginHost.mergeToolbarItems} has
 * sorted the items and has already refused {@link ToolbarGroup#STUDIO} to plugins; a plugin cannot read this
 * setting, cannot set it, and cannot tell that it happened. That refusal and this one are unrelated and read
 * alike, which is worth stating once: {@code STUDIO} is closed to a <em>plugin</em> and open to the
 * <em>user</em>, who is hiding a section of their own bar.
 */
public final class ToolbarVisibility {

    private ToolbarVisibility() {}

    /**
     * The groups named in {@code persisted}, ignoring any name this Studio does not know.
     *
     * <p>Ignored rather than refused: the file is written by whichever Studio last had the project open, and
     * one unrecognised name must not cost the user the rest of the list. Case-insensitive and trimmed for
     * the same reason — this file is hand-editable.
     */
    public static Set<ToolbarGroup> hidden(List<String> persisted) {
        Set<ToolbarGroup> out = EnumSet.noneOf(ToolbarGroup.class);
        if (persisted == null) return out;
        for (String name : persisted) {
            if (name == null || name.isBlank()) continue;
            for (ToolbarGroup group : ToolbarGroup.values()) {
                if (group.name().equalsIgnoreCase(name.trim())) {
                    out.add(group);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * How {@code hidden} is written to {@code settings.json} — the enum names, in <b>declaration</b> order.
     *
     * <p>Declaration order rather than the set's own, so the file reads the same whichever order the user
     * ticked the boxes in and a diff shows only what actually changed.
     */
    public static List<String> wire(Collection<ToolbarGroup> hidden) {
        List<String> out = new ArrayList<>();
        for (ToolbarGroup group : ToolbarGroup.values()) {
            if (hidden != null && hidden.contains(group)) out.add(group.name());
        }
        return List.copyOf(out);
    }

    /**
     * A user-facing name for a group.
     *
     * <p>It is here rather than on {@link ToolbarGroup} because a group's <em>heading</em> is a word this
     * window chooses: the contract's own rule is that a plugin picks a group and cannot create one, so the
     * enum carries the identity and the host carries what it is called. Compare {@code ValueType.label()},
     * which <em>is</em> the contract's, because a stored type has to read the same in every host.
     */
    public static String label(ToolbarGroup group) {
        return switch (group) {
            case PROJECT -> "Project";
            case AUTHORING -> "Authoring";
            case RUN -> "Run";
            case TOOLS -> "Tools";
            case OVERLAY -> "Overlay";
            case STUDIO -> "Studio";
        };
    }

    /**
     * The right-click menu: one checkbox per group, checked when the group is shown.
     *
     * <p>{@code onChange} is handed the whole new hidden set rather than the group that moved, because the
     * caller's job is to persist a set and rebuild a bar — and a caller told only the delta has to keep its
     * own copy of the state, which is the second copy that goes wrong.
     */
    public static ContextMenu menu(Set<ToolbarGroup> hidden, Consumer<Set<ToolbarGroup>> onChange) {
        Set<ToolbarGroup> current = hidden == null ? EnumSet.noneOf(ToolbarGroup.class) : hidden;
        ContextMenu menu = new ContextMenu();
        for (ToolbarGroup group : ToolbarGroup.values()) {
            CheckMenuItem item = new CheckMenuItem(label(group));
            item.setSelected(!current.contains(group));
            item.setOnAction(e -> {
                Set<ToolbarGroup> next = EnumSet.noneOf(ToolbarGroup.class);
                next.addAll(current);
                if (item.isSelected()) next.remove(group);
                else next.add(group);
                onChange.accept(next);
            });
            menu.getItems().add(item);
        }
        menu.getItems().add(new SeparatorMenuItem());
        CheckMenuItem all = new CheckMenuItem("Show all");
        all.setSelected(current.isEmpty());
        // Always "show everything", never a toggle: unticking it would have to mean hiding every group,
        // which leaves a bar with nothing on it to right-click and no way back.
        all.setOnAction(e -> onChange.accept(EnumSet.noneOf(ToolbarGroup.class)));
        menu.getItems().add(all);
        return menu;
    }
}
