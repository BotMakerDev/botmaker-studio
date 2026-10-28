package com.botmaker.studio.assist;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What went wrong in a dev Studio that {@code ui} serves (2026-10-28), so a walk through its windows finds bugs
 * itself: every exception no one caught, on any thread, and every line Studio wrote to {@code System.err} (JavaFX's
 * CSS warnings, a stack trace a handler printed). Each is numbered; {@code ui} answers with those after the last
 * number it saw. A stack trace keeps its first line, its causes and its first BotMaker frame; the JDK's frames are
 * dropped.
 *
 * <p>Process-wide, installed once by {@link #install}, and only where {@link UiTools#enabled}: an installed Studio's
 * {@code System.err} is never wrapped.
 */
public final class UiEvents {

    /** The kinds an event is of. */
    enum Kind {
        /** An exception no handler caught. */
        UNCAUGHT,
        /** A line Studio wrote to {@code System.err}. */
        STDERR
    }

    /** One thing that went wrong, {@code seq} counting from 1. */
    record Event(long seq, Kind kind, String text) {
        String line() {
            return (kind == Kind.UNCAUGHT ? "uncaught " : "stderr ") + text;
        }
    }

    static final int KEPT = 300;
    private static final int LINE_CHARS = 300;
    private static final String OURS = "com.botmaker.";
    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    /** Set while the uncaught handler prints what it already recorded. */
    private static final ThreadLocal<Boolean> QUIET = ThreadLocal.withInitial(() -> false);
    private static long seq;
    private static boolean installed;
    /** In a stack trace written to stderr: its BotMaker frame is still to be kept. */
    private static boolean wantFrame;

    private UiEvents() {}

    /** Wraps {@code System.err} and catches what no handler did, once; nothing unless {@link UiTools#enabled}. */
    public static synchronized void install() {
        if (installed || !UiTools.enabled()) return;
        installed = true;
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            uncaught(thread.getName(), failure);
            // Printed as before, but not recorded a second time as stderr lines.
            QUIET.set(true);
            try {
                if (before != null) {
                    before.uncaughtException(thread, failure);
                } else {
                    System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                    failure.printStackTrace();
                }
            } finally {
                QUIET.set(false);
            }
        });
        System.setErr(new PrintStream(new Tee(System.err), true, StandardCharsets.UTF_8));
    }

    /** Records {@code failure}, thrown on {@code thread} and caught by no handler. */
    static void uncaught(String thread, Throwable failure) {
        add(Kind.UNCAUGHT, "on " + thread + ": " + summary(failure));
    }

    /** {@code failure}'s class and message, its causes', and the first BotMaker frame it went through. */
    static String summary(Throwable failure) {
        StringBuilder out = new StringBuilder();
        String frame = null;
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (!out.isEmpty()) out.append(" ← ");
            out.append(t.getClass().getSimpleName());
            if (t.getMessage() != null && !t.getMessage().isBlank()) out.append(": ").append(t.getMessage().strip());
            if (frame == null) {
                for (StackTraceElement element : t.getStackTrace()) {
                    if (element.getClassName().startsWith(OURS)) {
                        frame = element.getClassName().substring(element.getClassName().lastIndexOf('.') + 1)
                                + "." + element.getMethodName() + ":" + element.getLineNumber();
                        break;
                    }
                }
            }
        }
        return out + (frame == null ? "" : " at " + frame);
    }

    /** A line written to stderr; a stack frame is kept only when it is the trace's first BotMaker one. */
    static synchronized void stderr(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty()) return;
        if (stripped.startsWith("at ")) {
            if (wantFrame && stripped.startsWith("at " + OURS)) {
                wantFrame = false;
                add(Kind.STDERR, "  " + stripped);
            }
            return;
        }
        if (stripped.startsWith("...") && stripped.endsWith("more")) return;
        wantFrame = true;
        add(Kind.STDERR, stripped);
    }

    private static synchronized void add(Kind kind, String text) {
        String cut = text.length() > LINE_CHARS ? text.substring(0, LINE_CHARS) + "…" : text;
        EVENTS.addLast(new Event(++seq, kind, cut));
        while (EVENTS.size() > KEPT) EVENTS.removeFirst();
    }

    /** The newest event's number; 0 before any. */
    static synchronized long last() {
        return seq;
    }

    /** The events after {@code seen}, oldest first. */
    static synchronized List<Event> after(long seen) {
        List<Event> out = new ArrayList<>();
        for (Event event : EVENTS) {
            if (event.seq() > seen) out.add(event);
        }
        return out;
    }

    /** The newest {@code count} events, oldest first. */
    static synchronized List<Event> newest(int count) {
        List<Event> all = new ArrayList<>(EVENTS);
        return all.subList(Math.max(0, all.size() - count), all.size());
    }

    /** Copies every byte to the real stderr and hands each whole line to {@link #stderr}. */
    private static final class Tee extends OutputStream {
        private final PrintStream real;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Tee(PrintStream real) {
            this.real = real;
        }

        @Override
        public synchronized void write(int b) {
            real.write(b);
            collect(b);
        }

        @Override
        public synchronized void write(byte[] bytes, int off, int len) {
            real.write(bytes, off, len);
            for (int i = off; i < off + len; i++) collect(bytes[i]);
        }

        private void collect(int b) {
            if (b != '\n') {
                pending.write(b);
                return;
            }
            if (!QUIET.get()) stderr(pending.toString(StandardCharsets.UTF_8));
            pending.reset();
        }

        @Override
        public void flush() {
            real.flush();
        }
    }
}
