package com.botmaker.studio.plugin;

import java.util.function.Supplier;

/**
 * A plugin's assistant tool call as one Project History safety version. A call's writes land on several threads
 * and hops, so no {@link WriteSteps} batch holds them all: a crop_pictures of six boxes wrote the bot's pictures,
 * their tags and its screens apart, and took a version before each (2026-10-16). Each write is still its own undo
 * step; the version is taken once, before the call's first write.
 *
 * <p>Not per thread, unlike {@link WriteSteps}: calls running side by side share the one version taken before the
 * first of their writes. A call that starts while another's version is already taken takes none of its own, and a
 * plugin write the user makes meanwhile (a window's hand edit) none either: rare, and the version before it holds
 * what both started from.
 */
public final class ToolCalls {

    private static final Object LOCK = new Object();
    private static int open;
    private static boolean checkpointed;

    private ToolCalls() {}

    /** Runs {@code call}, a plugin tool's, so the versions its writes would take are one. */
    public static <T> T once(Supplier<T> call) {
        synchronized (LOCK) {
            if (open++ == 0) checkpointed = false;
        }
        try {
            return call.get();
        } finally {
            synchronized (LOCK) {
                open--;
            }
        }
    }

    /** Whether a write takes its version: always outside a call, only the first one while calls are open. */
    static boolean firstCheckpoint() {
        synchronized (LOCK) {
            if (open == 0) return true;
            if (checkpointed) return false;
            checkpointed = true;
            return true;
        }
    }
}
