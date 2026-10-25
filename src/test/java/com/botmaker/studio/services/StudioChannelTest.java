package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A second launch hands its link to the Studio already running (2026-10-25). */
class StudioChannelTest {

    @TempDir
    Path dir;

    @Test
    void aLinkReachesTheListeningStudio() throws Exception {
        Path socket = dir.resolve("s.sock");
        LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        try (StudioChannel channel = StudioChannel.listen(socket, got::add).orElseThrow()) {
            assertTrue(StudioChannel.handOver(socket, "botmaker://run/a/b"));
            assertEquals("botmaker://run/a/b", got.poll(5, TimeUnit.SECONDS));
            assertTrue(StudioChannel.listen(socket, got::add).isEmpty(), "one listener per socket");
        }
        assertFalse(Files.exists(socket), "closing removes it");
    }

    @Test
    void withNoStudioTheLaunchKeepsItsLink() throws Exception {
        Path socket = dir.resolve("s.sock");
        assertFalse(StudioChannel.handOver(socket, "botmaker://run/a/b"));
        Files.writeString(socket, "left by a Studio that ended");
        assertFalse(StudioChannel.handOver(socket, "botmaker://run/a/b"));

        LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        try (StudioChannel channel = StudioChannel.listen(socket, got::add).orElseThrow()) {
            assertTrue(StudioChannel.handOver(socket, "one\ntwo"), "the stale file is replaced");
            assertEquals(List.of("one two"), List.of(got.poll(5, TimeUnit.SECONDS)), "one line per hand-over");
        }
    }
}
