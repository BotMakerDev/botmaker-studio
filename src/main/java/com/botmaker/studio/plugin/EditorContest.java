package com.botmaker.studio.plugin;

import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.ContextMenuEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Which plugin's editor draws a value two plugins both claim.
 *
 * <p>A contest is legitimate here, which is what makes this surface different from every other one the host
 * merges. Two plugins claiming a value-type <i>id</i> is a clash — the id is what a project file holds, so a
 * winner chosen by load order silently retypes somebody's variables, and {@code valueTypes()} refuses it. Two
 * plugins each having an honest editor for a rectangle is not a clash at all: neither is wrong, so there is
 * nothing to refuse and nothing the host can adjudicate. What was wrong was the silence — first-wins in
 * {@code ServiceLoader}'s walk order, with no way for the person looking at the wrong widget to say so.
 *
 * <p><b>The host detects, the user decides.</b> Detection is exact and free: the walks over the plugin editors
 * already call {@code matches} on every editor until one claims the value, so not stopping at the first hit
 * costs one more cheap call per claimant and needs no global scan of a type universe.
 *
 * <p><b>Ordering is the whole mechanism.</b> Every one of those walks takes the first claimant, so a verdict
 * is applied by moving the preferred plugin's editors to the front — which is why this is a pure function
 * over a list and an owner function, and why no caller's loop has to change. Three walks each applying a
 * verdict their own way is three places for it to be applied differently.
 *
 * <p>Nothing crosses the contract. There is deliberately no priority on
 * {@link com.botmaker.plugin.api.SlotEditor}: a plugin that declares its own precedence wins forever the
 * moment it writes a big number, which is the back door the platform exists to close.
 */
public final class EditorContest {

    private EditorContest() {}

    /** One claimant, as the menu shows it: the plugin's id, and the name a person reads. */
    public record Claim(String pluginId, String pluginName) {}

    /**
     * {@code claimants} with every editor owned by {@code preferredPluginId} moved to the front, in their own
     * order, and everything else following in its own order.
     *
     * <p>A stable partition rather than a sort. The fallback order is plugin load order, and a verdict about
     * one plugin must not reorder the ones it says nothing about. A {@code null} or unrecognised id — the
     * ordinary state after a plugin is uninstalled — leaves the list exactly as it was rather than emptying
     * it, because the failure mode of getting that wrong is a slot with no editor at all.
     */
    public static <T> List<T> ordered(List<T> claimants, Function<T, String> owner, String preferredPluginId) {
        if (claimants.size() < 2 || preferredPluginId == null || preferredPluginId.isBlank()) {
            return List.copyOf(claimants);
        }
        List<T> preferred = new ArrayList<>();
        List<T> rest = new ArrayList<>();
        for (T claimant : claimants) {
            if (preferredPluginId.equals(owner.apply(claimant))) preferred.add(claimant);
            else rest.add(claimant);
        }
        preferred.addAll(rest);
        return List.copyOf(preferred);
    }

    /**
     * The <i>Edit with</i> menu for a contested value: one radio item per claimant, the current winner
     * selected.
     *
     * <p>The header names the type, because a verdict applies to every value of it — this slot, every other
     * slot of the same type, and the Parameters window's row for it.
     *
     * @param typeName  the fully qualified Java type the verdict is keyed on
     * @param claimants every plugin whose editor claimed this value, in the order they would be tried
     * @param chosen    the plugin id currently drawing it
     * @param onChoose  handed the chosen plugin id; persists the verdict and redraws
     */
    public static ContextMenu menu(String typeName, List<Claim> claimants, String chosen,
                                   Consumer<String> onChoose) {
        Menu editWith = new Menu("Edit with");
        ToggleGroup group = new ToggleGroup();
        for (Claim claim : claimants) {
            RadioMenuItem item = new RadioMenuItem(claim.pluginName());
            item.setToggleGroup(group);
            item.setSelected(claim.pluginId().equals(chosen));
            item.setOnAction(e -> onChoose.accept(claim.pluginId()));
            editWith.getItems().add(item);
        }
        Label header = new Label(simple(typeName) + " — " + claimants.size() + " plugins offer an editor");
        header.setDisable(true);
        CustomMenuItem title = new CustomMenuItem(header, false);
        return new ContextMenu(title, editWith);
    }

    /** The last segment of a qualified name, for a menu header nobody wants to read a package in. */
    private static String simple(String typeName) {
        if (typeName == null || typeName.isBlank()) return "This value";
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    /**
     * Shows {@code menu} on {@code node}'s right-click <b>without taking the property over</b>.
     *
     * <p>{@code addEventHandler} rather than {@code setOnContextMenuRequested}: the node is a plugin's own
     * editor and may have its own menu, and the setter would silently replace it. Added as a handler, both
     * run — and if the plugin's consumes the event first, ours correctly does not fire. A plugin's own menu
     * winning is the right outcome: it knows more about its widget than we know about its type.
     */
    public static void arm(Node node, ContextMenu menu) {
        EventHandler<ContextMenuEvent> handler = e -> {
            menu.show(node, e.getScreenX(), e.getScreenY());
            e.consume();
        };
        node.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, handler);
    }
}
