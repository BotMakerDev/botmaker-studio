package com.botmaker.studio.sharing;

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
 * <p>{@link #parse} never throws. This Studio is the reader that lags: a row it cannot read costs that row, a
 * field it does not know is skipped, and a body that is no catalog at all is {@link #EMPTY}. A bot's
 * {@code plugins} id that names no plugin row (a plugin unlisted since the catalog was built) is kept as a pin;
 * it simply matches no row.
 */
public record CatalogIndex(List<PluginRegistry.Plugin> plugins, List<GalleryEntry> bots) {

    public static final CatalogIndex EMPTY = new CatalogIndex(List.of(), List.of());

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            String id = text(row, "id");
            String repo = text(row, "repo");
            if (id.isBlank() || !isSlug(repo)) continue;
            String name = text(row, "name");
            plugins.add(new PluginRegistry.Plugin(id, name.isBlank() ? repoName(repo) : name, text(row, "coordinate"),
                    repo, text(row, "description"), texts(row, "tags"), text(row, "minContractVersion"),
                    texts(row, "editorDependencies"), "", text(row, "tag"),
                    PublisherRank.fromId(text(row, "rank")), row.path("stars").asInt(0)));
        }
        Map<String, PublisherRank> rankById = new HashMap<>();
        for (PluginRegistry.Plugin plugin : plugins) rankById.putIfAbsent(plugin.id(), plugin.rank());
        List<GalleryEntry> bots = new ArrayList<>();
        for (JsonNode row : root.path("bots")) {
            String repo = text(row, "repo");
            if (!isSlug(repo)) continue;
            List<PublishedBotPlugins.Pin> pins = new ArrayList<>();
            // A plugin no row lists vouches for nothing, as an unknown rank does.
            PublisherRank pluginsRank = PublisherRank.ADMIN;
            for (JsonNode pin : row.path("plugins")) {
                String id = text(pin, "id");
                if (id.isBlank()) continue;
                pins.add(new PublishedBotPlugins.Pin(id, text(pin, "version")));
                pluginsRank = PublisherRank.lower(pluginsRank, rankById.getOrDefault(id, PublisherRank.COMMUNITY));
            }
            String name = repoName(repo);
            bots.add(new GalleryEntry(name, repo.substring(0, repo.indexOf('/')), name, text(row, "description"),
                    texts(row, "tags"), text(row, "forkOf"), text(row, "tag"),
                    PublisherRank.fromId(text(row, "rank")), row.path("stars").asInt(0), pins, pluginsRank));
        }
        return Optional.of(new CatalogIndex(plugins, bots));
    }

    /** {@code owner/repo}: one slash, neither side blank. */
    private static boolean isSlug(String repo) {
        int slash = repo.indexOf('/');
        return slash > 0 && slash == repo.lastIndexOf('/') && slash < repo.length() - 1;
    }

    private static String repoName(String repo) {
        return repo.substring(repo.indexOf('/') + 1);
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isTextual() ? value.asText().trim() : "";
    }

    private static List<String> texts(JsonNode row, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode value : row.path(field)) {
            if (value.isTextual() && !value.asText().isBlank()) out.add(value.asText().trim());
        }
        return out;
    }
}
