package com.botmaker.studio.services.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A program on a real PTY: its output, its exit code, keystrokes, a size, and an end. */
@DisabledOnOs(OS.WINDOWS)
class PtySessionTest {

    @TempDir Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();

    private PtySession start(String script, int cols, int rows) throws Exception {
        return PtySession.start(List.of("/bin/sh", "-c", script), dir, Map.of("BM_PROBE", "here"), cols, rows,
                chunk -> {
                    synchronized (out) {
                        out.writeBytes(chunk);
                    }
                }, exit::complete);
    }

    private String output() {
        synchronized (out) {
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void runsInTheDirectoryWithTheEnvironmentAndReportsTheExitCode() throws Exception {
        try (PtySession ignored = start("pwd; echo $BM_PROBE $TERM; exit 3", 80, 24)) {
            assertEquals(3, exit.get(10, TimeUnit.SECONDS));
            String text = output();
            assertTrue(text.contains(dir.toRealPath().toString()), text);
            assertTrue(text.contains("here xterm-256color"), text);
        }
    }

    @Test
    void startsAtTheSizeItIsGivenAndFollowsAResize() throws Exception {
        try (PtySession session = start("stty size; read x; stty size", 100, 30)) {
            waitFor("30 100");
            session.resize(120, 40);
            session.write("\n".getBytes(StandardCharsets.UTF_8));
            assertEquals(0, exit.get(10, TimeUnit.SECONDS));
            assertTrue(output().contains("40 120"), output());
        }
    }

    @Test
    void keystrokesReachTheProgram() throws Exception {
        try (PtySession session = start("read line; echo got:$line", 80, 24)) {
            session.write("hello\r".getBytes(StandardCharsets.UTF_8));
            assertEquals(0, exit.get(10, TimeUnit.SECONDS));
            assertTrue(output().contains("got:hello"), output());
        }
    }

    @Test
    void closeEndsAProgramThatWouldRunForever() throws Exception {
        PtySession session = start("sleep 600", 80, 24);
        assertTrue(session.isAlive());
        session.close();
        assertFalse(session.isAlive());
        exit.get(10, TimeUnit.SECONDS);
        session.close();
    }

    private void waitFor(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!output().contains(text)) {
            if (System.nanoTime() > deadline) throw new AssertionError("never printed " + text + ": " + output());
            Thread.sleep(20);
        }
    }
}
