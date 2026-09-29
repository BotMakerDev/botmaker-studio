package com.botmaker.studio.services;

import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pom edited outside Studio rebinds by itself (2026-09-29): a plugin added from a terminal or an IDE used
 * to be absent until Reload, which read as "installing a plugin does not work".
 */
class PomWatcherTest {

    private static final String POM = "<project><modelVersion>4.0.0</modelVersion></project>\n";

    @Test
    void aPomWriteIsReportedOnceItSettles(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), POM);
        CountDownLatch changed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        try (PomWatcher watcher = PomWatcher.start(project, () -> {
            calls.incrementAndGet();
            changed.countDown();
        })) {
            // A save in bursts, as an editor makes it: one call, after the quiet period.
            Files.writeString(project.resolve("pom.xml"), POM + "<!-- a -->");
            Files.writeString(project.resolve("pom.xml"), POM + "<!-- b -->");

            assertTrue(changed.await(10, TimeUnit.SECONDS), "the pom write was never reported");
            Thread.sleep(PomWatcher.QUIET_MS * 2);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void anotherFileInTheProjectIsNotAPomChange(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), POM);
        CountDownLatch changed = new CountDownLatch(1);

        try (PomWatcher watcher = PomWatcher.start(project, changed::countDown)) {
            Files.writeString(project.resolve("README.md"), "hello");

            assertFalse(changed.await(PomWatcher.QUIET_MS * 3, TimeUnit.MILLISECONDS));
        }
    }

    /** Studio's own write is bound already; the watcher's echo of it must not resolve a second time. */
    @Test
    void thePomAlreadyBoundIsNotRebound(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), POM);
        LibraryService libraries = new LibraryService(ProjectConfig.forDirectory(project),
                new ProjectState(), new TypeSummaryManager(Set.of()), new EventBus(false));
        try {
            libraries.watchPom();

            assertFalse(libraries.pomChanged().get(10, TimeUnit.SECONDS));
        } finally {
            libraries.close();
        }
    }
}
