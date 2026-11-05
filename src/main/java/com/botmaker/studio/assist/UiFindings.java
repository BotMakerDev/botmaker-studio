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
 * What a walk through a dev Studio's windows found (2026-10-28), one tab-separated row each in {@code ui-findings.tsv}
 * beside {@code tool-times.tsv}: when, the kind, how long in milliseconds (0 for an error), and what. Every error
 * {@link UiEvents} caught, every stall {@link FxWatch} saw over {@link #STALL_MS}, and every {@code ui} press or type
 * with its time to idle, so a click-test walk compares its steps with the previous walk's.
 *
 * <p>Best effort, as the core's {@code ToolTimes}: a row that cannot be written is dropped, and the file is moved aside to
 * {@code ui-findings.old.tsv} once it passes {@link #ROLL_BYTES}. Writes nothing until {@link #install}, so tests write
 * no file.
 */
final class UiFindings {

    static final String HEADER = "at\tkind\tms\twhat\n";
    /** A stall is a finding from this long. */
    static final long STALL_MS = 500;
    private static final long ROLL_BYTES = 4L * 1024 * 1024;

    /** The kinds a row is of. */
    enum Kind {
        /** An exception no handler caught, or a line Studio wrote to stderr. */
        ERROR("error"),
        /** The FX thread busy for a while: Studio's windows did not answer. */
        STALL("stall"),
        /** A {@code ui} press or type, with its time to idle. */
        STEP("step");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }

    private static volatile Path file;

    private UiFindings() {}

    /** Rows go to the cache's file from now on; none when the cache has no place. */
    static void install() {
        try {
            file = CacheDirs.cacheRoot().resolve("ui-findings.tsv");
        } catch (RuntimeException noCache) {
            file = null;
        }
    }

    /** Adds one row; {@code what} on one line. */
    static synchronized void record(Kind kind, long ms, String what) {
        Path to = file;
        if (to == null) return;
        String row = Instant.now().truncatedTo(ChronoUnit.MILLIS) + "\t" + kind.id() + "\t" + ms + "\t"
                + what.replaceAll("[\t\r\n]+", " ") + "\n";
        try {
            Files.createDirectories(to.getParent());
            if (Files.exists(to) && Files.size(to) > ROLL_BYTES) {
                Files.move(to, to.resolveSibling("ui-findings.old.tsv"), StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(to)) Files.writeString(to, HEADER, StandardCharsets.UTF_8);
            Files.writeString(to, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // A finding's row is not worth failing Studio over.
        }
    }
}
