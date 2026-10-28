package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.PluginLog;
import com.botmaker.plugin.host.LoadedPlugin;
import com.botmaker.plugin.host.PluginCoordinate;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each plugin wrote through {@link com.botmaker.plugin.api.StudioServices#log()}, kept per plugin for the
 * Plugins window's <i>Log</i> fold and echoed to Studio's standard error with the plugin's name in front.
 *
 * <p><b>Attributed by the writer's class, not by who was handed the services.</b> One {@link HostServices} serves
 * every plugin, so {@link #CALLER} looks down the stack at each line for the first class a bound plugin's jar
 * holds ({@link PluginHost#pluginOwning}). The toolkit and the JDK are nobody's and are walked past; a line no
 * plugin wrote goes to standard error only.
 *
 * <p>Kept for the Studio session, by coordinate, so a plugin's lines outlive a rebind: the last {@value #KEPT}
 * of each.
 */
public final class PluginLogs {

    static final int KEPT = 500;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Map<PluginCoordinate, Deque<Line>> LINES = new ConcurrentHashMap<>();

    /** How bad a line is. */
    public enum Level {
        INFO, WARN, ERROR;

        /** Whether the user should look at the plugin's log: a warning or worse. */
        public boolean notable() {
            return this != INFO;
        }
    }

    /** One line a plugin wrote, at the time it wrote it. */
    public record Line(LocalTime at, Level level, String text) {

        /** {@code 14:02:11 WARN  tailscale not found}, as the fold shows it. */
        public String display() {
            return CLOCK.format(at) + " " + String.format("%-5s", level) + " " + text;
        }
    }

    /** The log {@link HostServices#log()} hands every plugin: each line goes to the plugin whose class wrote it. */
    public static final PluginLog CALLER = new PluginLog() {
        @Override
        public void info(String message) {
            write(caller(), Level.INFO, message, null);
        }

        @Override
        public void warn(String message) {
            write(caller(), Level.WARN, message, null);
        }

        @Override
        public void error(String message, Throwable cause) {
            write(caller(), Level.ERROR, message, cause);
        }
    };

    private PluginLogs() {}

    /** A log written as {@code plugin}, for what the host itself says about it (a lifecycle step that threw). */
    public static PluginLog of(LoadedPlugin plugin) {
        Optional<LoadedPlugin> as = Optional.of(plugin);
        return new PluginLog() {
            @Override
            public void info(String message) {
                write(as, Level.INFO, message, null);
            }

            @Override
            public void warn(String message) {
                write(as, Level.WARN, message, null);
            }

            @Override
            public void error(String message, Throwable cause) {
                write(as, Level.ERROR, message, cause);
            }
        };
    }

    /** {@code coordinate}'s lines this session, oldest first; empty for a plugin that wrote none. */
    public static List<Line> lines(PluginCoordinate coordinate) {
        Deque<Line> kept = LINES.get(coordinate);
        if (kept == null) return List.of();
        synchronized (kept) {
            return List.copyOf(kept);
        }
    }

    static void clear() {
        LINES.clear();
    }

    private static Optional<LoadedPlugin> caller() {
        return WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != PluginLogs.class && !type.getName().startsWith(PluginLogs.class.getName()))
                .map(PluginHost::pluginOwning)
                .flatMap(Optional::stream)
                .findFirst());
    }

    private static void write(Optional<LoadedPlugin> plugin, Level level, String message, Throwable cause) {
        String text = cause == null ? String.valueOf(message) : message + ": " + cause;
        System.err.println(plugin.map(p -> "[" + p.name() + "] ").orElse("") + level + " " + text);
        if (cause != null) cause.printStackTrace();
        plugin.ifPresent(p -> {
            Deque<Line> kept = LINES.computeIfAbsent(p.coordinate(), c -> new ArrayDeque<>());
            synchronized (kept) {
                kept.addLast(new Line(LocalTime.now().truncatedTo(ChronoUnit.SECONDS), level, text));
                while (kept.size() > KEPT) kept.removeFirst();
            }
        });
    }
}
