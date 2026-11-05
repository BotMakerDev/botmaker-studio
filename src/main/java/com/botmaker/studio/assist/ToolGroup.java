package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;

import java.util.List;
import java.util.Set;

/**
 * Tools a client with a screen attaches beside the MCP's own (plan phase 9), and what of its screen every call waits
 * for: Studio's {@code ui}, which drives its windows in a dev Studio, and the questions Studio has on screen. The MCP's
 * own tools name nothing of a screen; a host with none attaches {@link #NONE}.
 *
 * <p>In-process until phase 10, when a client registers its group over its channel and the core forwards its calls.
 */
public interface ToolGroup {

    /** No tools and no screen: nothing is ever asked on it. */
    ToolGroup NONE = new ToolGroup() {
    };

    /** The group's tools, served beside the MCP's own. */
    default List<AssistantTool<?>> tools() {
        return List.of();
    }

    /**
     * The names of the group's tools that answer what is on screen: they run while a question waits ({@link #asking})
     * and while a plugin move waits for the user's answer, which they give.
     */
    default Set<String> answering() {
        return Set.of();
    }

    /** Why no call may run now, a question on screen whose answer starts something, or empty when nothing waits. */
    default String busy() {
        return "";
    }

    /** Whether a question on screen waits for an answer one of {@link #answering} can give. */
    default boolean asking() {
        return false;
    }

    /** How the calls that asked on screen ended since this was last read, each once; what follows every answer. */
    default List<String> news() {
        return List.of();
    }

    /**
     * Runs {@code then} once none of the group's calls is in flight, at once when none is: an endpoint closing waits,
     * since a call that opens or closes a project hands the port over mid-call and its client would read the cut call
     * as the host being down. {@code then} may run on another thread.
     */
    default void whenQuiet(Runnable then) {
        then.run();
    }
}
