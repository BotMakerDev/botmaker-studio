package com.botmaker.studio.runtime.agent.run;

import com.botmaker.runlog.api.Level;
import com.botmaker.runlog.api.LogLine;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * The bot's own {@code botmaker-shared} {@code Diag}, reached by reflection because its version is the bot's, not
 * this Studio's: its lines come to the run ({@code Diag.setSink}), and the run's debug switch is mirrored to it
 * ({@code Diag.set}). A bot without shared, or with one older than the sink, has {@link #NONE}.
 */
class DiagBridge {

    /** No {@code Diag} to reach. */
    static final DiagBridge NONE = new DiagBridge();

    private static final String DIAG = "com.botmaker.shared.Diag";

    private DiagBridge() {}

    static DiagBridge attach(AgentRun run) {
        Class<?> diag;
        try {
            diag = Class.forName(DIAG, true, ClassLoader.getSystemClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return NONE;
        }
        try {
            Method set = diag.getMethod("set", boolean.class);
            Method setSink = diag.getMethod("setSink", Consumer.class);
            Lines lines = new Lines(run);
            setSink.invoke(null, (Consumer<Object>) lines::accept);
            Reached reached = new Reached(set);
            reached.set(run.debug());
            return reached;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.err.println("[BotMaker] shared's own lines stay on the console: " + e);
            return NONE;
        }
    }

    /** Turns the bot's {@code Diag} on or off. */
    void set(boolean on) {
    }

    private static final class Reached extends DiagBridge {
        private final Method set;

        Reached(Method set) {
            this.set = set;
        }

        @Override
        void set(boolean on) {
            try {
                set.invoke(null, on);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Diag keeps its own switch; only its printing differs from the run's.
            }
        }
    }

    /** Reads each of {@code Diag}'s {@code TelemetryEvent.Log} records by its accessors, found once. */
    private static final class Lines {
        private final AgentRun run;
        private volatile Accessors accessors;

        Lines(AgentRun run) {
            this.run = run;
        }

        void accept(Object log) {
            if (log == null) return;
            try {
                Accessors read = accessors;
                if (read == null || read.type != log.getClass()) accessors = read = new Accessors(log.getClass());
                run.accept(read.line(log), null, false);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // A line of a shape this Studio cannot read is lost; the bot runs on.
            }
        }
    }

    private static final class Accessors {
        final Class<?> type;
        final Method level, source, text, count, atMillis, rect, writerClass, writerMethod, className, line;

        Accessors(Class<?> type) throws NoSuchMethodException {
            this.type = type;
            level = type.getMethod("level");
            source = type.getMethod("source");
            text = type.getMethod("text");
            count = type.getMethod("count");
            atMillis = type.getMethod("atMillis");
            rect = type.getMethod("rect");
            writerClass = type.getMethod("writerClass");
            writerMethod = type.getMethod("writerMethod");
            className = type.getMethod("className");
            line = type.getMethod("line");
        }

        LogLine line(Object log) throws ReflectiveOperationException {
            Object r = rect.invoke(log);
            LogLine.Rect where = null;
            if (r != null) {
                Class<?> t = r.getClass();
                where = new LogLine.Rect((int) t.getMethod("x").invoke(r), (int) t.getMethod("y").invoke(r),
                        (int) t.getMethod("width").invoke(r), (int) t.getMethod("height").invoke(r));
            }
            return new LogLine((long) atMillis.invoke(log), Level.fromId((String) level.invoke(log)),
                    (String) source.invoke(log), (String) text.invoke(log), (int) count.invoke(log), where,
                    (String) writerClass.invoke(log), (String) writerMethod.invoke(log),
                    (String) className.invoke(log), (int) line.invoke(log));
        }
    }
}
