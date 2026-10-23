package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code catalog.json} as Studio reads it: the spec's example, what a lagging reader skips, and offline. */
class CatalogIndexTest {

    /** The spec's example ({@code 2026-10-23-catalog-design.md} § catalog.json). */
    private static final String SPEC = """
            { "schemaVersion": 3, "builtAt": "2026-10-23T18:00:00Z",
              "plugins": [ { "id": "com.botmaker.sdk", "repo": "BotMakerDev/botmaker-sdk", "tag": "v2.1.0", "rank": "admin",
                             "coordinate": "com.github.BotMakerDev:botmaker-sdk", "name": "BotMaker SDK",
                             "description": "…", "tags": ["…"], "stars": 4, "minContractVersion": "v0.11.0",
                             "editorDependencies": ["g:a:1"] } ],
              "bots":    [ { "repo": "somedev/firestone-bot", "tag": "v1.2.0", "rank": "trusted",
                             "description": "…", "tags": ["…"], "stars": 3, "forkOf": "LiQiyeDev/firestone",
                             "plugins": [ { "id": "com.botmaker.sdk", "version": "2.1.0" } ] } ] }
            """;

    @Test
    void theSpecsExampleReadsWhole() {
        CatalogIndex index = CatalogIndex.parse(SPEC);

        PluginRegistry.Plugin sdk = index.plugins().getFirst();
        assertEquals("com.botmaker.sdk", sdk.id());
        assertEquals("BotMaker SDK", sdk.name());
        assertEquals("v2.1.0", sdk.version());
        assertEquals(PublisherRank.ADMIN, sdk.rank());
        assertEquals(4, sdk.stars());
        assertEquals("v0.11.0", sdk.minContractVersion());
        assertEquals(List.of("g:a:1"), sdk.editorDependencies());

        GalleryEntry bot = index.bots().getFirst();
        assertEquals("somedev/firestone-bot", bot.slug());
        assertEquals("firestone-bot", bot.name());
        assertEquals("v1.2.0", bot.tag());
        assertEquals(PublisherRank.TRUSTED, bot.rank());
        assertEquals(3, bot.stars());
        assertEquals("based on LiQiyeDev/firestone", bot.basedOnLine());
        assertEquals(List.of(new PublishedBotPlugins.Pin("com.botmaker.sdk", "2.1.0")), bot.plugins());
    }

    @Test
    void aBodyThatIsNoCatalogIsEmpty() {
        assertSame(CatalogIndex.EMPTY, CatalogIndex.parse(null));
        assertSame(CatalogIndex.EMPTY, CatalogIndex.parse(""));
        assertSame(CatalogIndex.EMPTY, CatalogIndex.parse("not json at all"));
        assertSame(CatalogIndex.EMPTY, CatalogIndex.parse("[1, 2]"));
        assertTrue(CatalogIndex.parse("{\"plugins\": 7, \"bots\": \"x\"}").isEmpty());
    }

    /** Review Focus 4: a plugin unlisted after the bot merged leaves a pin that matches no row, and no crash. */
    @Test
    void aPinWhosePluginIsGoneIsKept() {
        CatalogIndex index = CatalogIndex.parse("""
                {"plugins": [], "bots": [{"repo": "a/b", "tag": "v1", "plugins": [{"id": "gone.plugin"}]}]}""");
        assertEquals(List.of(new PublishedBotPlugins.Pin("gone.plugin", "")), index.bots().getFirst().plugins());
    }

    @Test
    void aRowItCannotReadCostsThatRowOnly() {
        CatalogIndex index = CatalogIndex.parse("""
                {"plugins": [{"repo": "o/no-id"}, {"id": "x", "repo": "not-a-slug"}, 5,
                             {"id": "ok", "repo": "o/ok", "tag": "v1", "rank": "galactic", "whatIsThis": 42}],
                 "bots": [{"repo": "o/a/b"}, {"tag": "v1"}, {"repo": "o/fine", "stars": "many"}]}""");

        assertEquals(List.of("ok"), index.plugins().stream().map(PluginRegistry.Plugin::id).toList());
        assertEquals(PublisherRank.COMMUNITY, index.plugins().getFirst().rank(), "an unknown rank vouches for nothing");
        assertEquals(List.of("o/fine"), index.bots().stream().map(GalleryEntry::slug).toList());
        assertEquals(0, index.bots().getFirst().stars());
    }

    @Test
    void rankIdsAreTotal() {
        assertEquals(PublisherRank.ADMIN, PublisherRank.fromId("Admin"));
        assertEquals(PublisherRank.COMMUNITY, PublisherRank.fromId(null));
        assertEquals("Official", PublisherRank.ADMIN.displayName());
    }

    /** One request a session; the last catalog read answers offline; a failure is asked again. */
    @Test
    void theClientAsksOnceAndKeepsTheLastCatalogForOffline(@TempDir Path dir) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> answer = new AtomicReference<>(SPEC);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String body = answer.get();
            byte[] bytes = (body == null ? "Not Found" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            List<String> urls = List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/catalog.json");
            Path cache = dir.resolve("cache/catalog.json");

            CatalogClient online = new CatalogClient(new GitHubClient(), urls, cache);
            assertEquals(1, online.fetch().join().plugins().size());
            assertEquals(1, online.fetch().join().bots().size());
            assertEquals(1, requests.get(), "one request a session");

            answer.set(null);
            CatalogClient offline = new CatalogClient(new GitHubClient(), urls, cache);
            assertEquals("com.botmaker.sdk", offline.fetch().join().plugins().getFirst().id(), "the kept catalog");
            offline.fetch().join();
            assertEquals(3, requests.get(), "a failed read is asked again");

            CatalogClient nothing = new CatalogClient(new GitHubClient(), urls, dir.resolve("none.json"));
            assertSame(CatalogIndex.EMPTY, nothing.fetch().join());

            answer.set("{\"schemaVersion\": 3, \"plugins\": [], \"bots\": []}");
            CatalogClient emptied = new CatalogClient(new GitHubClient(), urls, cache);
            assertTrue(emptied.fetch().join().isEmpty(), "a catalog listing nothing is an answer");
            emptied.fetch().join();
            assertEquals(5, requests.get(), "and is not asked again");
            assertTrue(emptied.kept().isEmpty(), "it replaces the kept one");
        } finally {
            server.stop(0);
        }
    }
}
