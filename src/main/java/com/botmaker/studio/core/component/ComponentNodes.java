package com.botmaker.studio.core.component;

import javafx.scene.Node;

/**
 * The trail from a widget on screen back to the component that declared it, and to the block that owns it.
 *
 * <p>A spec says what a block draws; nothing in the scene graph said which component a given widget came from,
 * so a focused node could not be named. {@link SpecReconciler} needs exactly that name — it decides what may
 * survive a re-parse by component id — and the id has to be readable from the node the user is actually typing
 * in, which may be several containers below the component's own root.
 *
 * <p>The stamp goes in {@link Node#getProperties()} rather than in a style class or a side map. A style class
 * reaches CSS, where an id like {@code arg0-remove} has no business; a side map keyed by {@code Node} is state
 * that outlives the nodes in it, which is the leak {@code UIManager.dispose} exists to prevent. Properties die
 * with the node.
 */
public final class ComponentNodes {

    /** Property key: the {@link BlockComponent#id()} that declared this node. */
    public static final String COMPONENT_ID = "botmaker.component.id";

    /** Property key: the {@code BlockId} of the block whose root this node is. */
    public static final String BLOCK_ID = "botmaker.block.id";

    private ComponentNodes() {}

    /** Marks {@code node} as the widget component {@code id} declared. */
    public static void stampComponent(Node node, String id) {
        if (node != null && id != null) node.getProperties().put(COMPONENT_ID, id);
    }

    /** Marks {@code node} as a block's root, carrying that block's stable id. */
    public static void stampBlock(Node node, String blockId) {
        if (node != null && blockId != null) node.getProperties().put(BLOCK_ID, blockId);
    }

    /**
     * The id of the component {@code node} belongs to — itself or the nearest stamped ancestor — or null.
     *
     * <p>Nearest rather than outermost: a text field sits inside the pane its component built, and an
     * expression slot holding a whole sub-block has that block's own components stamped below it. The
     * innermost answer is the one that names the widget the user is in.
     */
    public static String componentIdOf(Node node) {
        for (Node cursor = node; cursor != null; cursor = cursor.getParent()) {
            Object id = cursor.getProperties().get(COMPONENT_ID);
            if (id instanceof String found) return found;
        }
        return null;
    }

    /** The id of the block {@code node} belongs to — itself or the nearest stamped ancestor — or null. */
    public static String blockIdOf(Node node) {
        for (Node cursor = node; cursor != null; cursor = cursor.getParent()) {
            Object id = cursor.getProperties().get(BLOCK_ID);
            if (id instanceof String found) return found;
        }
        return null;
    }
}
