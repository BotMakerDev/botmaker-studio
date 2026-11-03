package com.botmaker.studio.runtime.agent;

/**
 * Where the call trace's lines go: the run agent's inner half ({@code runtime.agent.run.AgentRun}), which keeps,
 * prints and sends them. A JDK-only interface, so {@link TracedCalls}, which every bot class links to, names no
 * class of the inner half.
 */
public interface RunLines {

    /** Whether a debug line given now is kept anywhere: shown, or held for the lines before a problem. */
    boolean recorded();

    /**
     * One traced call, a debug line of the run, attributed by the agent to the bot's own line that made it.
     *
     * @param writerClass  the class called, which the Trace tab filters by
     * @param writerMethod that class's method
     */
    void call(String source, String text, int count, String writerClass, String writerMethod);

    /** The run is ending: the last lines are written and the log closed. */
    void ended();
}
