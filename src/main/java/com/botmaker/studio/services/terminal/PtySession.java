package com.botmaker.studio.services.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One program on a pseudo-terminal: bytes in, bytes out, and a size.
 *
 * <p>No JavaFX here. What draws the screen is xterm.js in the Terminal tab; this is the wire between it and
 * the PTY and interprets nothing, so a full-screen program ({@code vim}, {@code htop}, an AI TUI) draws exactly
 * as it would in a desktop terminal. It is the same shape as {@code botmaker-remote-server}'s {@code Terminal}.
 *
 * <p>The bot does <em>not</em> run here: its {@code BM-INPUT} prompts read stdin line by line over pipes, which
 * is why the Run tab and the Terminal tab are two tabs.
 *
 * <p>The reader is one daemon thread per session, blocked on the PTY. Output reaches {@code sink} on that
 * thread; {@code exited} is called once with the exit code, also on that thread.
 */
public final class PtySession implements AutoCloseable {

    private final PtyProcess process;
    private final OutputStream input;

    private PtySession(PtyProcess process) {
        this.process = process;
        this.input = process.getOutputStream();
    }

    /**
     * Starts {@code command} in {@code directory} at {@code cols}×{@code rows}, with {@code extraEnv} over this
     * process's environment.
     */
    public static PtySession start(List<String> command, Path directory, Map<String, String> extraEnv,
                                   int cols, int rows, Consumer<byte[]> sink, IntConsumer exited)
            throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.putIfAbsent("LANG", "C.UTF-8");
        env.putAll(extraEnv);
        PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
                .setDirectory(directory.toString())
                .setEnvironment(env)
                .setInitialColumns(Math.max(cols, 2))
                .setInitialRows(Math.max(rows, 1))
                .setRedirectErrorStream(true)
                .start();
        PtySession session = new PtySession(process);
        Thread reader = new Thread(() -> session.pump(sink, exited), "pty-" + process.pid());
        reader.setDaemon(true);
        reader.start();
        return session;
    }

    /** The user's login shell: {@code $SHELL}, else {@code /bin/bash}; PowerShell on Windows. */
    public static List<String> defaultShell() {
        if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            return List.of("powershell.exe", "-NoLogo");
        }
        String shell = System.getenv("SHELL");
        return List.of(shell == null || shell.isBlank() ? "/bin/bash" : shell, "-l");
    }

    private void pump(Consumer<byte[]> sink, IntConsumer exited) {
        byte[] buffer = new byte[8192];
        try (InputStream out = process.getInputStream()) {
            int n;
            while ((n = out.read(buffer)) >= 0) {
                if (n > 0) {
                    byte[] chunk = new byte[n];
                    System.arraycopy(buffer, 0, chunk, 0, n);
                    sink.accept(chunk);
                }
            }
        } catch (IOException e) {
            // The PTY closed under us: the same as end of output.
        }
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        exited.accept(code);
    }

    /** Keystrokes, raw. A write to a program that has exited is dropped. */
    public void write(byte[] bytes) {
        try {
            input.write(bytes);
            input.flush();
        } catch (IOException e) {
            // The program is gone; the exit callback says so.
        }
    }

    public void resize(int cols, int rows) {
        if (cols < 2 || rows < 1 || !process.isAlive()) return;
        process.setWinSize(new WinSize(cols, rows));
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    /** Ends the program: hang-up first, then a kill if it has not gone within a second. Idempotent. */
    @Override
    public void close() {
        try {
            input.close();
        } catch (IOException e) {
            // Closing anyway.
        }
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
