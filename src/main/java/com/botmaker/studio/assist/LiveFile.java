package com.botmaker.studio.assist;

import java.util.Optional;

/**
 * The open file as an outside caller reaches it — the MCP endpoint, which runs on a server thread and so may
 * not read {@code ProjectState} itself. The implementation hops to the FX thread for both halves.
 */
public interface LiveFile {

    /** A turn over the active file as it is now, or empty when no file is open. */
    Optional<AssistTurn> begin();

    /**
     * Publishes {@code turn}'s edits as one step, unless the file changed since {@link #begin}. Answers a
     * sentence for the caller: applied, or why not.
     */
    String commit(AssistTurn turn, String label);
}
