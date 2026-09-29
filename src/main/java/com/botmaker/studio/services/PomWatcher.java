package com.botmaker.studio.services;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tells {@link LibraryService} when the project's {@code pom.xml} changed on disk, whoever changed it.
 *
 * <p>Until 2026-09-29 a plugin appeared only when Studio itself wrote the pom, when the project was opened, or
 * when the user pressed Reload — so a plugin added from a terminal ({@code mvn}), an IDE or a {@code git pull}
 * was on disk and absent from the editor, which read as "installing a plugin does not work".
 *
 * <p>The watch is on the project <em>directory</em> ({@link WatchService} cannot watch a file) and filtered to
 * {@code pom.xml}. Editors save in bursts — write, rename, touch — so the call waits for {@link #QUIET_MS} of
 * silence. Whether anything changed is not this class's question: {@link LibraryService#pomChanged()} compares
 * the pom with the one it last bound, so Studio's own writes, already bound, do nothing a second time.
 */
final class PomWatcher implements AutoCloseable {

    /** How long the pom must stay unchanged before it is read. */
    static final long QUIET_MS = 700;

    private final WatchService watcher;
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(work -> {
        Thread thread = new Thread(work, "pom-watch-debounce");
        thread.setDaemon(true);
        return thread;
    });
    private final Runnable onChange;
    private ScheduledFuture<?> pending;

    private PomWatcher(Path projectDir, Runnable onChange) throws IOException {
        this.onChange = onChange;
        this.watcher = FileSystems.getDefault().newWatchService();
        projectDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        Thread loop = new Thread(this::loop, "pom-watch");
        loop.setDaemon(true);
        loop.start();
    }

    /** Starts watching, or answers {@code null} when the platform cannot — the editor works without it. */
    static PomWatcher start(Path projectDir, Runnable onChange) {
        try {
            return new PomWatcher(projectDir, onChange);
        } catch (IOException | UnsupportedOperationException e) {
            System.err.println("pom.xml is not watched; outside edits need Reload: " + e.getMessage());
            return null;
        }
    }

    private void loop() {
        try {
            while (true) {
                WatchKey key = watcher.take();
                boolean pom = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path name && name.toString().equals("pom.xml")) pom = true;
                }
                if (pom) schedule();
                if (!key.reset()) return;
            }
        } catch (InterruptedException | ClosedWatchServiceException stopped) {
            // closed with the project
        }
    }

    private synchronized void schedule() {
        if (pending != null) pending.cancel(false);
        pending = debounce.schedule(onChange, QUIET_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        debounce.shutdownNow();
        try {
            watcher.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }
}
