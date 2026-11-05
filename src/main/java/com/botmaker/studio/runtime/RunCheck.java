package com.botmaker.studio.runtime;

import java.util.Optional;

/**
 * What a run asks before it compiles, of whoever judges the bot's code (Studio's diagnostics, which know the blocks
 * drawn): why it may not start now, said to the user, or empty when it may. Showing the problems is the checker's.
 */
@FunctionalInterface
public interface RunCheck {

    /** Lets every run start: a host that judges nothing before javac does. */
    RunCheck NONE = Optional::empty;

    Optional<String> refusal();
}
