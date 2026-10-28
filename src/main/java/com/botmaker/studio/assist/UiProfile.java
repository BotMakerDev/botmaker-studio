package com.botmaker.studio.assist;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code ui profile}: a JFR recording of where a dev Studio spends its time between {@code start} and {@code stop}
 * (2026-10-28). Each execution sample counts for its topmost BotMaker frame, else its top frame; {@code stop} answers
 * the FX thread's most sampled and the other threads', and keeps the recording for JDK Mission Control. A thread
 * that sleeps or waits is not sampled: this finds busy code, {@link FxWatch} finds a blocked FX thread too.
 */
final class UiProfile {

    static final String FX_THREAD = "JavaFX Application Thread";
    private static final Duration PERIOD = Duration.ofMillis(10);
    private static final int MOST_FX = 10;
    private static final int MOST_OTHER = 5;
    private static final String OURS = "com.botmaker.";

    private static Recording recording;
    private static Instant started;

    private UiProfile() {}

    static synchronized String start() {
        if (recording != null) throw new IllegalArgumentException("A profile is recording; stop it first.");
        Recording r = new Recording();
        r.enable("jdk.ExecutionSample").withPeriod(PERIOD);
        r.start();
        recording = r;
        started = Instant.now();
        return "Profiling; profile stop says where the time went.";
    }

    /** Stops the recording, keeps it as {@code into}, and sums it up. */
    static synchronized String stop(Path into) {
        if (recording == null) throw new IllegalArgumentException("No profile is recording; profile start begins one.");
        Recording r = recording;
        recording = null;
        try {
            r.stop();
            Files.createDirectories(into.getParent());
            r.dump(into);
            double seconds = Duration.between(started, Instant.now()).toMillis() / 1000.0;
            return summary(into, seconds);
        } catch (IOException failed) {
            throw new IllegalStateException("the profile could not be kept: " + failed.getMessage(), failed);
        } finally {
            r.close();
        }
    }

    private static String summary(Path file, double seconds) throws IOException {
        Map<String, Integer> fx = new HashMap<>();
        Map<String, Integer> other = new HashMap<>();
        for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
            if (!event.getEventType().getName().equals("jdk.ExecutionSample")) continue;
            RecordedStackTrace stack = event.getStackTrace();
            if (stack == null || stack.getFrames().isEmpty()) continue;
            RecordedThread thread = event.getThread("sampledThread");
            boolean onFx = thread != null && FX_THREAD.equals(thread.getJavaName());
            String where = where(stack.getFrames());
            (onFx ? fx : other).merge(onFx ? where : where + " (" + name(thread) + ")", 1, Integer::sum);
        }
        return String.format(Locale.ROOT, "Profiled %.1f s, a sample every %d ms; kept as %s.%n", seconds,
                PERIOD.toMillis(), file)
                + top("FX thread", fx, MOST_FX) + "\n" + top("Other threads", other, MOST_OTHER);
    }

    private static String name(RecordedThread thread) {
        return thread == null || thread.getJavaName() == null ? "?" : thread.getJavaName();
    }

    private static String where(List<RecordedFrame> frames) {
        for (RecordedFrame frame : frames) {
            if (frame.getMethod().getType().getName().startsWith(OURS)) return frame(frame);
        }
        return frame(frames.getFirst());
    }

    private static String frame(RecordedFrame frame) {
        String type = frame.getMethod().getType().getName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethod().getName() + ":" + frame.getLineNumber();
    }

    private static String top(String what, Map<String, Integer> counts, int most) {
        if (counts.isEmpty()) return what + ": no sample.";
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder out = new StringBuilder(what + " (" + total + " samples):");
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(most).forEach(e -> out.append("\n  ").append(e.getValue()).append(' ').append(e.getKey()));
        return out.toString();
    }
}
