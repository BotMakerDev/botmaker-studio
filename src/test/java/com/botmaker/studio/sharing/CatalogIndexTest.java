package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code catalog.json} as Studio reads it: the spec's example, what a lagging reader skips, and offline. */
class CatalogIndexTest {

    private static final String JAR_V210 = "1".repeat(64);
    private static final String POM_V210 = "2".repeat(64);
    private static final String COMMIT_V120 = "c".repeat(40);

    /**
     * Schema 5 (2026-10-27): every approved release, newest first, each pinned; a plugin is its repo and coordinate,
     * with no id; what the cli's index writes.
     */
    private static final String SPEC = """
            { "schemaVersion": 5, "builtAt": "2026-10-27T18:00:00Z",
              "plugins": [ { "repo": "BotMakerDev/botmaker-sdk", "rank": "admin",
                             "coordinate": "com.github.BotMakerDev:botmaker-sdk", "name": "BotMaker SDK",
                             "description": "…", "tags": ["…"], "stars": 4,
                             "releases": [ { "tag": "v2.1.0", "commit": "%s", "jarSha256": "%s", "pomSha256": "%s",
                                             "minContractVersion": "v0.11.0", "editorDependencies": ["g:a:1"] },
                                           { "tag": "v2.0.0", "commit": "%s", "minContractVersion": "v0.10.0" } ] } ],
              "bots":    [ { "repo": "somedev/firestone-bot", "rank": "trusted",
                             "description": "…", "tags": ["…"], "stars": 3, "forkOf": "LiQiyeDev/firestone",
                             "releases": [ { "tag": "v1.2.0", "commit": "%s",
                                             "plugins": [ { "repo": "BotMakerDev/botmaker-sdk", "version": "2.1.0" } ] },
                                           { "tag": "v1.1.0", "commit": "%s" } ] } ] }
            """.formatted("a".repeat(40), JAR_V210, POM_V210, "b".repeat(40), COMMIT_V120, "d".repeat(40));

    @Test
    void theSpecsExampleReadsWhole() {
        CatalogIndex index = CatalogIndex.parse(SPEC);

        PluginRegistry.Plugin sdk = index.plugins().getFirst();
        assertEquals("BotMakerDev/botmaker-sdk", sdk.repo());
        assertEquals("BotMaker SDK", sdk.name());
        assertEquals("v2.1.0", sdk.version(), "the newest approved release is installed");
        assertEquals(PublisherRank.ADMIN, sdk.rank());
        assertEquals(4, sdk.stars());
        assertEquals("v0.11.0", sdk.minContractVersion(), "the newest release's");
        assertEquals(List.of("g:a:1"), sdk.editorDependencies());
        assertEquals(List.of("v2.1.0", "v2.0.0"), sdk.approvedTags());
        assertEquals(JAR_V210, sdk.release("v2.1.0").orElseThrow().jarSha256());
        assertEquals(POM_V210, sdk.release("v2.1.0").orElseThrow().pomSha256());
        assertTrue(sdk.release("v1.0.0").isEmpty());

        GalleryEntry bot = index.bots().getFirst();
        assertEquals("somedev/firestone-bot", bot.slug());
        assertEquals("firestone-bot", bot.name());
        assertEquals("v1.2.0", bot.tag());
        assertEquals(PublisherRank.TRUSTED, bot.rank());
        assertEquals(3, bot.stars());
        assertEquals("based on LiQiyeDev/firestone", bot.basedOnLine());
        assertEquals(List.of(new PublishedBotPlugins.Pin("BotMakerDev/botmaker-sdk", "2.1.0")), bot.plugins());
        assertEquals(PublisherRank.ADMIN, bot.pluginsRank(), "its pin names the admin row by repo");
        assertEquals(List.of("v1.2.0", "v1.1.0"), bot.approvedTags());
        assertEquals(COMMIT_V120, bot.commitOf("v1.2.0"), "what Install and Get vX.Y take");
        assertEquals("", bot.commitOf("v9.9.9"));
    }

    /** What a plugin brings is its newest release's {@code plugins}: New Project locks those cards. */
    @Test
    void pluginBringsFromNewestRelease() {
        CatalogIndex index = CatalogIndex.parse("""
                {"plugins": [{"repo": "o/sdk",
                              "releases": [{"tag": "v2", "plugins": [{"repo": "o/basics", "version": "v0.5.0"}]},
                                           {"tag": "v1", "plugins": [{"repo": "o/old-one", "version": "v1"}]}]},
                             {"repo": "o/basics", "releases": [{"tag": "v0.5.0"}]}]}""");
        assertEquals(List.of("o/basics"), index.plugins().get(0).brings());
        assertEquals(List.of(), index.plugins().get(1).brings());
    }

    /** A bot release's {@code bmkSha256} (2026-10-25) is what an opened {@code .bmk} must match; an older one has none. */
    @Test
    void aBotReleaseCarriesItsBmkSha() {
        CatalogIndex index = CatalogIndex.parse("""
                {"bots": [{"repo": "o/miner", "releases": [{"tag": "v2", "commit": "c2", "bmkSha256": "ab12"},
                                                           {"tag": "v1", "commit": "c1"}]}]}""");
        GalleryEntry bot = index.bots().getFirst();
        assertEquals(Optional.of("ab12"), bot.bmkShaOf("v2"));
        assertEquals(Optional.empty(), bot.bmkShaOf("v1"));
        assertEquals(Optional.empty(), bot.bmkShaOf("v3"));
    }

    /** No reading of the old single {@code tag}: a schema-3 row has no release, so it is skipped. */
    @Test
    void aRowWithNoReleaseIsSkipped() {
        CatalogIndex index = CatalogIndex.parse("""
                {"schemaVersion": 3, "plugins": [{"repo": "o/x", "tag": "v1"}],
                 "bots": [{"repo": "o/b", "tag": "v1"}, {"repo": "o/c", "releases": []}]}""");
        assertTrue(index.isEmpty());
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
                {"plugins": [], "bots": [{"repo": "a/b",
                                          "releases": [{"tag": "v1", "plugins": [{"repo": "o/gone"}]}]}]}""");
        assertEquals(List.of(new PublishedBotPlugins.Pin("o/gone", "")), index.bots().getFirst().plugins());
    }

    @Test
    void aRowItCannotReadCostsThatRowOnly() {
        CatalogIndex index = CatalogIndex.parse("""
                {"plugins": [{"name": "no repo", "releases": [{"tag": "v1"}]},
                             {"repo": "not-a-slug", "releases": [{"tag": "v1"}]}, 5,
                             {"id": "a field schema 5 dropped", "repo": "o/ok", "rank": "galactic", "whatIsThis": 42,
                              "releases": [{"tag": "v1", "since": "a field this Studio does not know"}]}],
                 "bots": [{"repo": "o/a/b", "releases": [{"tag": "v1"}]}, {"releases": [{"tag": "v1"}]},
                          {"repo": "o/odd", "stars": "many", "releases": [{"tag": "v1"}]},
                          {"repo": "o/fine", "releases": [{"tag": "v1"}]}]}""");

        assertEquals(List.of("o/ok"), index.plugins().stream().map(PluginRegistry.Plugin::repo).toList());
        assertEquals(PublisherRank.COMMUNITY, index.plugins().getFirst().rank(), "an unknown rank vouches for nothing");
        assertEquals(List.of("o/fine"), index.bots().stream().map(GalleryEntry::slug).toList(),
                "a row whose field is the wrong type costs that row");
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
            assertEquals("BotMakerDev/botmaker-sdk", offline.fetch().join().plugins().getFirst().repo(),
                    "the kept catalog");
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
