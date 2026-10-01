package com.botmaker.studio.runtime.agent;

import com.botmaker.plugin.api.palette.Untraced;

/** A plugin's offered class, as the trace agent's tests see one. */
public final class TracedFixture {

    private final String name;

    public TracedFixture(String name) {
        this.name = name;
    }

    public static int add(int a, int b) {
        return twice(a + b) / 2;
    }

    /** Called by {@link #add} from inside the plugin: never traced, since only the bot's calls are rewritten. */
    public static int twice(int value) {
        return value * 2;
    }

    public String greet(String who) {
        return "Hi " + who + " from " + name;
    }

    @Untraced("polled")
    public static void quiet() {
    }

    public static void hiddenOne() {
    }

    public static boolean poll() {
        return false;
    }

    public static void fail() {
        throw new IllegalStateException("boom");
    }

    public static int count(String... values) {
        return values.length;
    }
}
