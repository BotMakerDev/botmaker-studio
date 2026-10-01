package com.botmaker.studio.runtime.agent;

/** A bot's own class, as the trace agent's tests see one: every call it makes into {@link TracedFixture}. */
public final class BotFixture {

    private BotFixture() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        TracedFixture.add(1, 2);
        new TracedFixture("x").greet("Ana");
        TracedFixture.quiet();
        TracedFixture.hiddenOne();
        for (int i = 0; i < 5; i++) {
            TracedFixture.poll();
        }
        TracedFixture.add(2, 2);
        try {
            TracedFixture.fail();
        } catch (IllegalStateException expected) {
            // The bot handles it; the trace still says it happened.
        }
        TracedFixture.count("a", "b");
    }
}
