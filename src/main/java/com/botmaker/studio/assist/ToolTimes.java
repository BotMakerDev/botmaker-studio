package com.botmaker.studio.assist;

import com.botmaker.shared.config.CacheDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Every assistant tool call's time, one tab-separated row each in {@code tool-times.tsv} under Studio's cache:
 * when it ended, the tool, how long it took in milliseconds, and whether it answered an error. Studio's own tools and
 * the plugins' alike, measured where the MCP server hands the call over, so a session's slow tools add up from the
 * file ({@code sort -k3 -n}) instead of from the client's transcript. The SDK's replies say where the time went
 * inside a call; this says how long the whole call took.
 *
 * <p>Best effort: a row that cannot be written is dropped, and the call's answer is the same. The file is moved
 * aside to {@code tool-times.old.tsv} once it passes {@link #ROLL_BYTES}, keeping the one before. Two Studios
 * running at once share the file; one moving it aside while the other writes may leave a file without its header,
 * which only a reader of the columns notices.
 */
final class ToolTimes {

    static final String HEADER = "ended\ttool\tms\tresult\n";
    private static final long ROLL_BYTES = 4L * 1024 * 1024;

    private final Path file;

    ToolTimes(Path file) {
        this.file = file;
    }

    /**
     * The cache's file, the one Studio writes; one that writes nothing when the cache has no place (a Windows
     * account without {@code LOCALAPPDATA}): built while the MCP tools load, it must not take them down with it.
     */
    static ToolTimes inCache() {
        try {
            return new ToolTimes(CacheDirs.cacheRoot().resolve("tool-times.tsv"));
        } catch (RuntimeException noCache) {
            return new ToolTimes(null);
        }
    }

    /** Adds the row of one call that took {@code nanos}. */
    synchronized void record(String tool, long nanos, boolean error) {
        if (file == null) return;
        String row = Instant.now().truncatedTo(ChronoUnit.MILLIS) + "\t" + tool + "\t" + nanos / 1_000_000 + "\t"
                + (error ? "error" : "ok") + "\n";
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > ROLL_BYTES) {
                Files.move(file, file.resolveSibling("tool-times.old.tsv"), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(file)) Files.writeString(file, HEADER, StandardCharsets.UTF_8);
            Files.writeString(file, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A timing row is not worth failing a call over.
        }
    }
}
