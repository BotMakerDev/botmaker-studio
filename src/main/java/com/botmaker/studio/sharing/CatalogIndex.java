package com.botmaker.studio.sharing;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.DerivedFacts;
import com.botmaker.cli.catalog.Rank;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code catalog.json} as Studio reads it (2026-10-23): every listed plugin and bot, ranked by publisher, with
 * the facts the catalog's CI derived. It is the only file Studio reads about published work — no topic search,
 * no pom read per row.
 *
 * <p>Each row is read as the cli's own {@link Catalog} record (2026-10-24, schema 4), the one the catalog's index
 * writes: every approved release, newest first, each pinned. The newest release is the row's {@code tag}.
 *
 * <p>{@link #parse} never throws. This Studio is the reader that lags: a row it cannot read costs that row, a
 * field it does not know is skipped, and a body that is no catalog at all is {@link #EMPTY}. A row with no
 * release (the single {@code tag} of schema 3 is not read) is skipped. A bot's plugin repo that names no plugin row
 * (a plugin unlisted since the catalog was built) is kept as a pin; it simply matches no row.
 */
public record CatalogIndex(List<PluginRegistry.Plugin> plugins, List<GalleryEntry> bots) {

    public static final CatalogIndex EMPTY = new CatalogIndex(List.of(), List.of());

    private static final ObjectMapper MAPPER = CatalogJson.lenient();

    public CatalogIndex {
        plugins = List.copyOf(plugins);
        bots = List.copyOf(bots);
    }

    public boolean isEmpty() {
        return plugins.isEmpty() && bots.isEmpty();
    }

    /** The catalog {@code body} holds, {@link #EMPTY} when it holds none. */
    public static CatalogIndex parse(String body) {
        return read(body).orElse(EMPTY);
    }

    /**
     * The catalog {@code body} holds, empty when it is no catalog at all — which a catalog listing nothing is not:
     * {@code {"plugins": [], "bots": []}} is an answer, and replaces the one kept from the last read.
     */
    static Optional<CatalogIndex> read(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            System.err.println("Failed to parse catalog.json: " + e.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) return Optional.empty();
        List<PluginRegistry.Plugin> plugins = new ArrayList<>();
        for (JsonNode row : root.path("plugins")) {
            row(row, Catalog.Plugin.class).flatMap(CatalogIndex::plugin).ifPresent(plugins::add);
        }
        Map<String, PublisherRank> rankByRepo = new HashMap<>();
        for (PluginRegistry.Plugin plugin : plugins) rankByRepo.putIfAbsent(plugin.repo(), plugin.rank());
        List<GalleryEntry> bots = new ArrayList<>();
        for (JsonNode row : root.path("bots")) {
            row(row, Catalog.Bot.class).flatMap(bot -> bot(bot, rankByRepo)).ifPresent(bots::add);
        }
        return Optional.of(new CatalogIndex(plugins, bots));
    }

    /** One row as the cli's record, empty when it does not read as one. */
    private static <T> Optional<T> row(JsonNode row, Class<T> type) {
        try {
            return Optional.ofNullable(MAPPER.treeToValue(row, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<PluginRegistry.Plugin> plugin(Catalog.Plugin row) {
        String repo = trim(row.repo());
        List<Catalog.Plugin.Release> releases = row.releases().stream()
                .filter(r -> r != null && !trim(r.tag()).isEmpty()).toList();
        if (!isSlug(repo) || releases.isEmpty()) return Optional.empty();
        Catalog.Plugin.Release newest = releases.getFirst();
        String name = trim(row.name());
        return Optional.of(new PluginRegistry.Plugin(name.isEmpty() ? repoName(repo) : name,
                trim(row.coordinate()), repo, row.description(), row.tags(), trim(newest.minContractVersion()),
                newest.editorDependencies(), "", newest.tag(), rank(row.rank()), row.stars(), releases));
    }

    private static Optional<GalleryEntry> bot(Catalog.Bot row, Map<String, PublisherRank> rankByRepo) {
        String repo = trim(row.repo());
        List<Catalog.Bot.Release> releases = row.releases().stream()
                .filter(r -> r != null && !trim(r.tag()).isEmpty()).toList();
        if (!isSlug(repo) || releases.isEmpty()) return Optional.empty();
        Catalog.Bot.Release newest = releases.getFirst();
        List<PublishedBotPlugins.Pin> pins = new ArrayList<>();
        // A plugin no row lists vouches for nothing, as an unknown rank does.
        PublisherRank pluginsRank = PublisherRank.ADMIN;
        for (DerivedFacts.Pin pin : newest.plugins()) {
            String pinned = pin == null ? "" : trim(pin.repo());
            if (pinned.isEmpty()) continue;
            pins.add(new PublishedBotPlugins.Pin(pinned, trim(pin.version())));
            pluginsRank = PublisherRank.lower(pluginsRank, rankByRepo.getOrDefault(pinned, PublisherRank.COMMUNITY));
        }
        String name = repoName(repo);
        return Optional.of(new GalleryEntry(name, repo.substring(0, repo.indexOf('/')), name, row.description(),
                row.tags(), trim(row.forkOf()), newest.tag(), rank(row.rank()), row.stars(), pins, pluginsRank,
                releases));
    }

    private static PublisherRank rank(Rank rank) {
        return PublisherRank.fromId(rank == null ? "" : rank.id());
    }

    /** {@code owner/repo}: one slash, neither side blank. */
    private static boolean isSlug(String repo) {
        int slash = repo.indexOf('/');
        return slash > 0 && slash == repo.lastIndexOf('/') && slash < repo.length() - 1;
    }

    private static String repoName(String repo) {
        return repo.substring(repo.indexOf('/') + 1);
    }

    private static String trim(String text) {
        return text == null ? "" : text.trim();
    }
}
