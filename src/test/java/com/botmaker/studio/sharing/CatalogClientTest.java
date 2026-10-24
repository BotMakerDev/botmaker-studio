package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** New Project asks the catalog again on every open (2026-10-24): a bot published since Studio started shows. */
class CatalogClientTest {

    private static String catalog(String botRepo) {
        return """
                {"plugins": [{"id": "com.botmaker.sdk", "repo": "BotMakerDev/botmaker-sdk",
                              "releases": [{"tag": "v2.1.0"}]}],
                 "bots": [{"repo": "%s", "releases": [{"tag": "v1.0.0",
                                                       "plugins": [{"id": "com.botmaker.sdk", "version": "v2.1.0"}]}]}]}
                """.formatted(botRepo);
    }

    private static List<String> bots(CatalogIndex index) {
        return index.bots().stream().map(GalleryEntry::slug).toList();
    }

    private record Served(HttpServer server, AtomicReference<String> answer, AtomicInteger requests) {
        List<String> urls() {
            return List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/catalog.json");
        }
    }

    private static Served serve(String body) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> answer = new AtomicReference<>(body);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new Served(server, answer, requests);
    }

    @Test
    void refreshAsksAgain(@TempDir Path dir) throws Exception {
        Served served = serve(catalog("a/first"));
        try {
            CatalogClient client = new CatalogClient(new GitHubClient(), served.urls(), dir.resolve("catalog.json"));
            assertEquals(List.of("a/first"), bots(client.fetch().join()));

            served.answer().set(catalog("b/second"));
            assertEquals(List.of("a/first"), bots(client.fetch().join()), "fetch alone shares the session's answer");
            CatalogClient.Reply refreshed = client.refresh().join();
            assertEquals(List.of("b/second"), bots(refreshed.index()));
            assertTrue(refreshed.fresh(), "GitHub answered");
            assertEquals(List.of("b/second"), bots(client.fetch().join()), "later readers share the new answer");
            assertEquals(2, served.requests().get(), "one more request, for the refresh only");
        } finally {
            served.server().stop(0);
        }
    }

    /** Review Focus 4: a refresh that fails after a good read answers with the kept bots, never with none. */
    @Test
    void refreshFailingKeepsKept(@TempDir Path dir) throws Exception {
        Served served = serve(catalog("a/first"));
        Path cache = dir.resolve("catalog.json");
        CatalogClient client = new CatalogClient(new GitHubClient(), served.urls(), cache);
        assertTrue(client.keptAt().isEmpty(), "nothing kept yet");
        assertEquals(List.of("a/first"), bots(client.fetch().join()));
        served.server().stop(0);
        // Changed on disk, so the answer below can only have come from the kept file.
        Files.writeString(cache, catalog("k/kept"));
        Instant written = Instant.parse("2026-10-20T08:00:00Z");
        Files.setLastModifiedTime(cache, FileTime.from(written));

        CatalogClient.Reply refreshed = client.refresh().join();
        assertEquals(List.of("k/kept"), bots(refreshed.index()));
        assertFalse(refreshed.fresh(), "the kept copy, which New Project calls offline");
        assertEquals(Optional.of(written), client.keptAt(), "the date the offline line names");
    }
}
