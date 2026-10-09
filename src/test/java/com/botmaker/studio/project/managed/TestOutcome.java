package com.botmaker.studio.project.managed;

/**
 * An enum set's element, as a test plugin would ship it: a bot's {@code enum Outcomes implements TestOutcome}
 * compiles against it, and the host reads each of that enum's constants as {@link #named} of its name.
 */
public interface TestOutcome {

    String name();

    /** What the host reads a constant of the bot's enum as: equal to another by name. */
    record Named(String name) implements TestOutcome {}

    static TestOutcome named(String name) {
        return new Named(name);
    }
}
