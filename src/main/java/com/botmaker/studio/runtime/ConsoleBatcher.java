package com.botmaker.studio.runtime;

import com.botmaker.studio.palette.InputKind;
import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What a process prints, on its way to the Run tab: read off its pipes in chunks, held here, and handed to the
 * FX thread at most once every {@link #FLUSH_MS}.
 *
 * <p>One per run, debug session or compile, and the only path from a pipe to the console. It exists because
 * there were two: a run was batched and a debug session posted one {@code Platform.runLater} per line, so a
 * {@code while (true) print(…)} under Follow filled the FX queue faster than it drained and froze the window
 * past the end of the session.
 *
 * <p>A flush carries at most {@link #MAX_PER_FLUSH} characters. Past that the <em>oldest</em> text is dropped
 * and the flush says how much: in a flood, the lines worth reading are the latest ones.
 *
 * <p>{@code BM-INPUT} markers are taken out before anything is held, so a prompt is never among what a flood
 * drops.
 */
public final class ConsoleBatcher implements AutoCloseable {

    static final long FLUSH_MS = 100;
    static final int MAX_PER_FLUSH = 4096;

    private final Consumer<String> sink;
    private final Consumer<InputKind> inputRequested;
    private final StringBuilder buffer = new StringBuilder();
    private final List<Thread> readers = new ArrayList<>();
    private final ScheduledExecutorService flusher;
    private long skipped;

    /**
     * @param sink           receives each flush, on the FX thread
     * @param inputRequested receives each {@code BM-INPUT} marker read from a pump with markers on, on the FX thread
     */
    public ConsoleBatcher(Consumer<String> sink, Consumer<InputKind> inputRequested) {
        this.sink = sink;
        this.inputRequested = inputRequested;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "console-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleAtFixedRate(this::flush, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
    }

    /** Reads {@code stream} to its end on a thread of its own; {@link #finish()} waits for it. */
    public void pump(InputStream stream, boolean detectMarkers, String threadName) {
        Thread reader = new Thread(() -> read(stream, detectMarkers), threadName);
        reader.setDaemon(true);
        synchronized (readers) {
            readers.add(reader);
        }
        reader.start();
    }

    public void append(String text) {
        if (text.isEmpty()) return;
        synchronized (buffer) {
            buffer.append(text);
            int over = buffer.length() - MAX_PER_FLUSH;
            if (over > 0) {
                // Cut at the next line break inside the kept part, so the console never opens on half a line.
                int cut = buffer.indexOf("\n", over);
                if (cut < 0 || cut - over > 200) cut = over - 1;
                buffer.delete(0, cut + 1);
                skipped += cut + 1;
            }
        }
    }

    /** What the next flush would hand over, emptied out of the buffer; null when there is nothing. */
    String drain() {
        synchronized (buffer) {
            if (buffer.isEmpty() && skipped == 0) return null;
            String text = skipped == 0 ? buffer.toString()
                    : "[… " + skipped + " characters not shown — output was faster than the console]\n" + buffer;
            buffer.setLength(0);
            skipped = 0;
            return text;
        }
    }

    /** Waits for every pump to reach the end of its stream, then hands over what is left. */
    public void finish() throws InterruptedException {
        List<Thread> started;
        synchronized (readers) {
            started = List.copyOf(readers);
        }
        for (Thread reader : started) reader.join();
        close();
        flush();
    }

    /** Stops the timer. What is still held is dropped, unless {@link #finish()} is what called this. */
    @Override
    public void close() {
        flusher.shutdownNow();
    }

    private void flush() {
        String text = drain();
        if (text != null) Platform.runLater(() -> sink.accept(text));
    }

    private void read(InputStream stream, boolean detectMarkers) {
        byte[] chunk = new byte[4096];
        StringBuilder carry = new StringBuilder();
        try (stream) {
            int len;
            while ((len = stream.read(chunk)) != -1) {
                String text = new String(chunk, 0, len, StandardCharsets.UTF_8);
                if (detectMarkers) {
                    text = extractInputMarkers(text, carry,
                            kind -> Platform.runLater(() -> inputRequested.accept(kind)));
                }
                append(text);
            }
        } catch (IOException ignored) {
            // The process ended or was killed: its pipe closing is how a pump learns that.
        }
    }

    /**
     * Strips {@code \u0001BM-INPUT:<type>\u0001} markers the SDK prints right before a blocking read, handing each
     * to {@code onMarker}. {@code carry} holds a trailing partial marker split across reads, completed by the next
     * chunk; everything else is returned for the console.
     */
    static String extractInputMarkers(String text, StringBuilder carry, Consumer<InputKind> onMarker) {
        String s = carry + text;
        carry.setLength(0);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf(InputKind.MARKER_DELIMITER, i);
            if (start < 0) { out.append(s, i, s.length()); break; }
            out.append(s, i, start);
            int end = s.indexOf(InputKind.MARKER_DELIMITER, start + 1);
            if (end < 0) { carry.append(s, start, s.length()); break; } // incomplete marker — hold for next chunk
            String token = s.substring(start + 1, end);
            i = end + 1;
            if (token.startsWith(InputKind.MARKER_PREFIX)) {
                onMarker.accept(InputKind.fromMarkerToken(token.substring(InputKind.MARKER_PREFIX.length()))
                        .orElse(null));
                if (i < s.length() && s.charAt(i) == '\n') i++; // swallow the marker's own newline
            } else {
                out.append(InputKind.MARKER_DELIMITER).append(token).append(InputKind.MARKER_DELIMITER); // not ours
            }
        }
        return out.toString();
    }
}
