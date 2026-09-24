package com.botmaker.studio.assist;

import java.util.List;

/**
 * What one tool call did. A refusal is an ordinary answer, not an exception: its reasons go back to the model
 * as the tool's result, which is how it learns to try something else.
 */
public sealed interface Outcome {

    /** Whether the edit landed in the turn's working copy. */
    boolean accepted();

    /** The edit is in the working copy; nothing reaches the project until the turn commits. */
    record Accepted(String summary) implements Outcome {
        @Override public boolean accepted() { return true; }
    }

    /** Nothing changed. {@code reasons} are sentences a model can act on — a compile error with its line, an unknown id. */
    record Refused(List<String> reasons) implements Outcome {
        public Refused {
            reasons = List.copyOf(reasons);
        }

        static Refused because(String reason) {
            return new Refused(List.of(reason));
        }

        @Override public boolean accepted() { return false; }
    }
}
