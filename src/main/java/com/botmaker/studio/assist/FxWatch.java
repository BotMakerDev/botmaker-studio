package com.botmaker.studio.assist;

import javafx.application.Platform;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * When a dev Studio's windows stopped answering, and where its code was meanwhile (2026-10-28), so a walk through
 * them finds a slow handler as well as a broken one. A daemon thread asks the FX thread for nothing every
 * {@link #PING_MS}; an ask it answers {@link #STALL_MS} late or later is a stall, and while it lasts the FX thread's
 * stack is sampled every {@link #SAMPLE_MS}. A stall keeps its length and the frames most sampled: each sample's
 * topmost BotMaker frame, else its top frame.
 *
 * <p>Process-wide, started once with {@link UiEvents#install}, only where {@link UiTools#enabled}; a stall over
 * {@link UiFindings#STALL_MS} is a finding too.
 */
final class FxWatch {

    static final long PING_MS = 100;
    static final long STALL_MS = 150;
    static final long SAMPLE_MS = 20;
    static final int KEPT = 200;
    private static final int FRAMES = 3;
    private static final String OURS = "com.botmaker.";

    /** One stall, {@code seq} counting from 1; {@code frames} the most sampled first, each with its count. */
    record Stall(long seq, Instant at, long ms, List<String> frames) {
        /** Its length and where it was. */
        String line() {
            return ms + " ms" + (frames.isEmpty() ? "" : " in " + String.join(", ", frames));
        }
    }

    private static final Deque<Stall> STALLS = new ArrayDeque<>();
    private static long seq;
    private static Thread watcher;

    private FxWatch() {}

    /** Starts the watch once; the FX toolkit must be running. */
    static synchronized void start() {
        if (watcher != null) return;
        Thread fx = UiTools.onFx(Thread::currentThread);
        watcher = Thread.ofPlatform().daemon().name("fx-watch").start(() -> watch(fx));
    }

    private static void watch(Thread fx) {
        try {
            while (fx.isAlive()) {
                AtomicLong answered = new AtomicLong();
                long sent = System.nanoTime();
                Platform.runLater(() -> answered.set(System.nanoTime()));
                Map<String, Integer> counts = new HashMap<>();
                while (answered.get() == 0 && fx.isAlive()) {
                    Thread.sleep(SAMPLE_MS);
                    if (answered.get() == 0 && System.nanoTime() - sent >= STALL_MS * 1_000_000) {
                        counts.merge(where(fx.getStackTrace()), 1, Integer::sum);
                    }
                }
                long ms = (answered.get() - sent) / 1_000_000;
                if (answered.get() != 0 && ms >= STALL_MS) add(ms, counts);
                Thread.sleep(PING_MS);
            }
        } catch (InterruptedException | IllegalStateException stopped) {
            // Interrupted, or the FX toolkit exited: nothing left to watch.
        }
    }

    /** A sample's topmost BotMaker frame, else its top frame; "" for an empty stack. */
    static String where(StackTraceElement[] stack) {
        for (StackTraceElement element : stack) {
            if (element.getClassName().startsWith(OURS)) return frame(element);
        }
        return stack.length == 0 ? "" : frame(stack[0]);
    }

    private static String frame(StackTraceElement element) {
        String type = element.getClassName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + element.getMethodName() + ":" + element.getLineNumber();
    }

    static synchronized void add(long ms, Map<String, Integer> counts) {
        List<String> frames = counts.entrySet().stream().filter(e -> !e.getKey().isEmpty())
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())).limit(FRAMES)
                .map(e -> e.getKey() + " ×" + e.getValue()).toList();
        Stall stall = new Stall(++seq, Instant.now(), ms, frames);
        STALLS.addLast(stall);
        while (STALLS.size() > KEPT) STALLS.removeFirst();
        if (ms >= UiFindings.STALL_MS) UiFindings.record(UiFindings.Kind.STALL, ms, String.join(", ", frames));
    }

    /** The newest stall's number; 0 before any. */
    static synchronized long last() {
        return seq;
    }

    /** The stalls after {@code seen}, oldest first. */
    static synchronized List<Stall> after(long seen) {
        List<Stall> out = new ArrayList<>();
        for (Stall stall : STALLS) {
            if (stall.seq() > seen) out.add(stall);
        }
        return out;
    }
}
