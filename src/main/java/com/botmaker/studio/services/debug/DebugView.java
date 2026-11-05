package com.botmaker.studio.services.debug;

import java.util.function.BooleanSupplier;

/**
 * Who shows a debug session (plan phase 7): Studio's canvas, which finds the block for a file and a line in whatever
 * it draws at that moment. The debugger names a place in the bot's sources, never a block. {@code live} says whether
 * the session that sent a stop is still the one running; a view that waits (to switch files first) asks it again.
 */
public interface DebugView {

    /** The debuggee started, and its output is a new one. From the debugger's own thread. */
    void started();

    /** The debuggee runs again after a pause. From the thread that resumed it. */
    void resumed();

    /**
     * The debuggee stopped at {@code hit} ({@code null} when outside the bot's own code, at {@code line}), its frames
     * read in {@code snapshot}. On the view's thread.
     */
    void paused(FollowPacer.Hit hit, int line, DebugSnapshot snapshot, BooleanSupplier live);

    /** One window of Follow: where the bot was while it ran on. On the view's thread. */
    void followed(FollowPacer.Frame frame, BooleanSupplier live);

    /** The session ended, however it did. From whichever thread saw it end. */
    void finished();
}
