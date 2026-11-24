// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.render.theme;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only live reload of {@code blocks.css}: with the system property {@value #PROPERTY} naming the source file
 * ({@code mvn javafx:run} sets it), a save re-skins every open window at once, without a restart. JavaFX keys its
 * stylesheet cache by URL, so each change is copied to a fresh temp file and the windows are pointed at it.
 * Without the property nothing here runs and {@link #url} is the classpath resource.
 */
public final class DevCss {

    /** The path of {@code src/main/resources/css/blocks.css}; unset outside a dev run. */
    public static final String PROPERTY = "botmaker.dev.css";

    private static final Logger LOG = LoggerFactory.getLogger(DevCss.class);

    private static String current;
    private static Path tempDir;
    private static int generation;
    private static boolean watching;

    private DevCss() {}

    /** The stylesheet URL a scene should carry: the live copy in a dev run, else {@code classpath}'s. */
    public static synchronized String url(URL classpath) {
        start();
        return current != null ? current : classpath.toExternalForm();
    }

    private static void start() {
        if (watching) return;
        String source = System.getProperty(PROPERTY);
        if (source == null || source.isBlank()) return;
        watching = true;
        Path file = Path.of(source);
        try {
            tempDir = Files.createTempDirectory("botmaker-dev-css");
            tempDir.toFile().deleteOnExit();
            current = copy(file);
            Thread watcher = new Thread(() -> watch(file), "dev-css-watch");
            watcher.setDaemon(true);
            watcher.start();
            LOG.info("Dev CSS: watching {}", file);
        } catch (IOException e) {
            LOG.warn("Dev CSS off: {}", e.toString());
            current = null;
        }
    }

    private static String copy(Path file) throws IOException {
        Path dir = Files.createDirectory(tempDir.resolve("g" + (++generation)));
        Path target = dir.resolve("blocks.css");
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        target.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        return target.toUri().toString();
    }

    private static void watch(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        try (WatchService service = dir.getFileSystem().newWatchService()) {
            dir.register(service, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            while (true) {
                WatchKey key = service.take();
                boolean ours = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path p && p.getFileName().equals(file.getFileName())) ours = true;
                }
                key.reset();
                if (!ours) continue;
                Thread.sleep(80); // editors write in two steps
                reload(file);
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.warn("Dev CSS watcher stopped: {}", e.toString());
        }
    }

    private static void reload(Path file) {
        String old;
        String fresh;
        synchronized (DevCss.class) {
            try {
                old = current;
                fresh = copy(file);
                current = fresh;
            } catch (IOException e) {
                LOG.warn("Dev CSS reload failed: {}", e.toString());
                return;
            }
        }
        Platform.runLater(() -> {
            int scenes = 0;
            for (Window window : new ArrayList<>(Window.getWindows())) {
                Scene scene = window.getScene();
                if (scene == null) continue;
                scenes += swap(scene.getStylesheets(), old, fresh);
                if (scene.getRoot() instanceof javafx.scene.control.DialogPane pane) {
                    swap(pane.getStylesheets(), old, fresh);
                }
            }
            LOG.info("Dev CSS: reloaded into {} scene(s)", scenes);
        });
    }

    private static int swap(List<String> sheets, String old, String fresh) {
        int at = old == null ? -1 : sheets.indexOf(old);
        if (at < 0) return 0;
        sheets.set(at, fresh);
        return 1;
    }
}
