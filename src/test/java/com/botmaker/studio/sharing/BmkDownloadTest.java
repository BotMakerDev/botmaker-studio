package com.botmaker.studio.sharing;

import com.botmaker.cli.catalog.Catalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Get's download (2026-10-25): the listed release's {@code .bmk}, checked against the catalog's pin as it lands. */
class BmkDownloadTest {

    private static final byte[] FILE = "a bot file".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cache;

    private HttpServer server;
    private String base;
    /** The release's JSON the fake API answers. */
    private final AtomicReference<String> release = new AtomicReference<>();
    private final AtomicReference<byte[]> asset = new AtomicReference<>(FILE);

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/LiQiyeDev/Firestone/releases/tags/v1.0.2", exchange -> {
            byte[] body = release.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/download/", exchange -> {
            exchange.sendResponseHeaders(200, asset.get().length);
            exchange.getResponseBody().write(asset.get());
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        release.set(assets("Firestone-1.0.2.bmk", "Firestone-1.0.2.zip"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String assets(String... names) {
        StringBuilder json = new StringBuilder("{\"assets\": [");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"name\": \"").append(names[i]).append("\", \"browser_download_url\": \"").append(base)
                    .append("/download/").append(names[i]).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static GalleryEntry listed(String bmkSha) {
        return new GalleryEntry("Firestone", "LiQiyeDev", "Firestone", "", List.of(), "", "v1.0.2",
                PublisherRank.ADMIN, 0, List.of(), PublisherRank.ADMIN,
                List.of(new Catalog.Bot.Release("v1.0.2", "7f57e0e", List.of(), bmkSha)));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private BmkDownload download() {
        return new BmkDownload(HttpClient.newHttpClient(), base, null);
    }

    @Test
    void thePinnedFileIsDownloadedIntoTheCache() throws Exception {
        List<long[]> progress = new ArrayList<>();
        Path file = download().fetch(listed(sha(FILE)), cache, (done, total) -> progress.add(new long[]{done, total}));
        assertEquals(cache.resolve("bmk/LiQiyeDev/Firestone/Firestone-1.0.2.bmk"), file);
        assertArrayEquals(FILE, Files.readAllBytes(file));
        assertEquals(FILE.length, progress.getLast()[0]);
        assertEquals(FILE.length, progress.getLast()[1]);
    }

    @Test
    void aFileReplacedSinceTheCheckIsRefusedAndDeleted() throws Exception {
        asset.set("another file".getBytes(StandardCharsets.UTF_8));
        IOException refused = assertThrows(IOException.class,
                () -> download().fetch(listed(sha(FILE)), cache, BmkDownload.Progress.NONE));
        assertTrue(refused.getMessage().contains("not the one the catalog checked"), refused.getMessage());
        assertFalse(Files.exists(cache.resolve("bmk/LiQiyeDev/Firestone/Firestone-1.0.2.bmk")));
    }

    @Test
    void aReleaseWithNoBmkSaysSo() throws Exception {
        release.set(assets("Firestone-1.0.2.zip"));
        IOException refused = assertThrows(IOException.class,
                () -> download().fetch(listed(sha(FILE)), cache, BmkDownload.Progress.NONE));
        assertTrue(refused.getMessage().contains("has no .bmk file"), refused.getMessage());
    }

    @Test
    void aBotWithNoPinnedFileIsNotReady() {
        assertFalse(BmkDownload.ready(listed("")));
        assertTrue(BmkDownload.ready(listed("ab".repeat(32))));
        assertThrows(IOException.class, () -> download().fetch(listed(""), cache, BmkDownload.Progress.NONE));
    }

    @Test
    void theAssetIsTheOneNamedForTheBotElseTheOnlyBmk() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals(Optional.of(base + "/download/Firestone-1.0.2.bmk"), BmkDownload.asset(
                json.readTree(assets("Other-1.0.2.bmk", "Firestone-1.0.2.bmk")), "Firestone-1.0.2.bmk")
                .map(BmkDownload.Asset::url));
        assertEquals(Optional.of("gamebot-1.0.2.bmk"), BmkDownload.asset(json.readTree(assets("gamebot-1.0.2.bmk")),
                "Firestone-1.0.2.bmk").map(BmkDownload.Asset::name));
        assertEquals(Optional.empty(), BmkDownload.asset(
                json.readTree(assets("a-1.bmk", "b-1.bmk")), "Firestone-1.0.2.bmk"), "two others: which one?");
        assertEquals(Optional.empty(), BmkDownload.asset(json.readTree(assets("...bmk")), "Firestone-1.0.2.bmk"),
                "a name that is no plain file name names no file in the cache");
    }

    @Test
    void aRefusedDownloadLeavesNothingInTheCache() throws Exception {
        asset.set("another file".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> download().fetch(listed(sha(FILE)), cache, BmkDownload.Progress.NONE));
        try (var left = Files.list(cache.resolve("bmk/LiQiyeDev/Firestone"))) {
            assertEquals(List.of(), left.toList(), "no .part either");
        }
    }
}
