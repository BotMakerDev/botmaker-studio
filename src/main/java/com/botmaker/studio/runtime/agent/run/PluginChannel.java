package com.botmaker.studio.runtime.agent.run;

import com.botmaker.plugin.api.TraceLine;
import com.botmaker.plugin.api.run.AskKind;
import com.botmaker.plugin.api.run.RunChannel;
import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.LogLine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One plugin's channel for the run ({@link RunChannel}), handed to it by its {@code DeclaredPlugin.botRun}. The
 * contract here is the bot's own, loaded from the bot's classpath: this class links it, so it is the one place of
 * the inner half that does, and a bot whose contract predates channels fails here alone ({@link BotPlugins}).
 */
final class PluginChannel implements RunChannel {

    private final AgentRun run;
    private final String plugin;

    PluginChannel(AgentRun run, String plugin) {
        this.run = run;
        this.plugin = plugin;
    }

    /**
     * Builds the plugin {@code pluginClass} names on {@code bot}'s loader and calls its {@code botRun} with its own
     * channel. A plugin whose contract has no {@code botRun} gets none.
     */
    static void hand(AgentRun run, String plugin, String pluginClass, ClassLoader bot)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(pluginClass, true, bot);
        Method botRun;
        try {
            botRun = type.getMethod("botRun", RunChannel.class);
        } catch (NoSuchMethodException older) {
            return;
        }
        Object declared = type.getConstructor().newInstance();
        try {
            botRun.invoke(declared, new PluginChannel(run, plugin));
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        }
    }

    @Override
    public void line(TraceLine line, Throwable cause) {
        if (line == null) return;
        if (line.level() == TraceLine.Level.DEBUG && !recorded()) return;
        LogLine.Rect rect = line.where().map(r -> new LogLine.Rect(r.x(), r.y(), r.width(), r.height()))
                .orElse(null);
        run.accept(new LogLine(line.at().toEpochMilli(), Level.fromId(line.level().id()), line.source(),
                line.text(), line.count(), rect, line.writerClass(), line.writerMethod(), line.className(),
                line.line().orElse(-1), values(line)), cause, true);
    }

    /** The line's plugin values; none from a bot whose contract predates them. */
    private static Map<String, String> values(TraceLine line) {
        try {
            return line.values();
        } catch (NoSuchMethodError older) {
            return Map.of();
        }
    }

    @Override
    public void details(Map<String, String> details) {
        run.details(plugin, details);
    }

    @Override
    public void frame(byte[] frame) {
        run.frame(frame);
    }

    @Override
    public boolean debug() {
        return run.debug();
    }

    @Override
    public void debug(boolean on) {
        run.debug(on);
    }

    @Override
    public boolean recorded() {
        return run.recorded();
    }

    @Override
    public Optional<String> ask(AskKind kind, String prompt, List<String> choices) {
        return run.ask(kind == null ? "" : kind.id(), prompt, choices == null ? List.of() : choices);
    }

    @Override
    public Optional<Path> folder() {
        return run.folder();
    }
}
