package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.studio.project.UserLibrary;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read side of the plugin registry — the answer to "which plugins exist?", which
 * {@code META-INF/services} cannot give.
 *
 * <p>`ServiceLoader` is how the host <em>instantiates</em> a plugin already on the classpath; getting it
 * onto the classpath is a Maven coordinate, and finding that coordinate is the problem this solves.
 * Installing one is therefore an ordinary dependency added through {@code LibraryService} — the plugin
 * platform's whole point is that a plugin is a normal library, and a bespoke install path would be a
 * privilege the first-party plugin has and a third party's does not.
 *
 * <p>Deliberately shaped like {@link GitHubGallery}: one unauthenticated request for a generated
 * {@code index.json} on the raw CDN — no API rate limit, no account — and any failure resolving to an empty
 * catalog rather than throwing. The dialog degrades to a message; a registry nobody can reach must never
 * stop somebody editing their bot.
 */
public final class PluginRegistry {

    private final GitHubClient client;

    public PluginRegistry(GitHubClient client) {
        this.client = client;
    }

    /**
     * One plugin's entry, as the registry's CI generated it.
     *
     * <p>Unknown properties are ignored because this Studio is the reader that lags: a field a newer
     * {@code botmaker publish} writes must not make the whole catalog unreadable.
     *
     * @param coordinate {@code groupId:artifactId}, with no version — the index names a plugin, not a
     *                   release
     * @param verifiedVersion the version the registry's own checks last ran against, which is what gets
     *                        installed: the newest tag may be one nothing has ever loaded
     * @param editorDependencies {@code groupId:artifactId:version} of everything this plugin needs on the
     *                           <em>editor's</em> classpath and does not bring itself — the dependencies it
     *                           declares {@code optional}, which is exactly the set a resolve does not reach.
     *                           Absent is the ordinary case: a plugin whose dependencies are ordinary needs
     *                           no list, because they are transitive. See {@code MavenService.installPlugin}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Plugin(String id, String name, String coordinate, String repo, String description,
                         List<String> tags, String minContractVersion, List<String> valueTypeIds,
                         List<String> editorDependencies, String verifiedVersion, String verifiedAt) {

        public Plugin {
            editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
            id = id == null ? "" : id.trim();
            name = name == null ? "" : name.trim();
            coordinate = coordinate == null ? "" : coordinate.trim();
            repo = repo == null ? "" : repo.trim();
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            valueTypeIds = valueTypeIds == null ? List.of() : List.copyOf(valueTypeIds);
            minContractVersion = minContractVersion == null ? "" : minContractVersion.trim();
            verifiedVersion = verifiedVersion == null ? "" : verifiedVersion.trim();
            verifiedAt = verifiedAt == null ? "" : verifiedAt.trim();
        }

        public String groupId() {
            int colon = coordinate.indexOf(':');
            return colon < 0 ? "" : coordinate.substring(0, colon);
        }

        public String artifactId() {
            int colon = coordinate.indexOf(':');
            return colon < 0 ? "" : coordinate.substring(colon + 1);
        }

        /**
         * {@link #editorDependencies} as libraries, skipping anything that is not
         * {@code groupId:artifactId:version}.
         *
         * <p>Skipping rather than throwing, for the reason the whole parse is lenient: <b>this Studio is the
         * reader that lags</b>. A malformed entry costs one plugin one dependency; refusing the catalog over
         * it would cost the user every plugin. The registry's own gate is where a malformed list is an error,
         * because that is the reader who can tell somebody to fix it.
         */
        public List<UserLibrary> editorLibraries() {
            return editorDependencies.stream()
                    .map(text -> text == null ? new String[0] : text.trim().split(":"))
                    .filter(parts -> parts.length == 3
                            && !parts[0].isBlank() && !parts[1].isBlank() && !parts[2].isBlank())
                    .map(parts -> new UserLibrary(parts[0], parts[1], parts[2]))
                    .toList();
        }

        /** A plugin with no resolvable coordinate cannot be installed, whatever else the entry says. */
        public boolean isInstallable() {
            return !groupId().isBlank() && !artifactId().isBlank();
        }

        public String htmlUrl() {
            return repo.isBlank() ? "" : "https://github.com/" + repo;
        }

        /**
         * Whether this plugin is among the project's libraries — by <b>coordinate, not version</b>, because
         * a plugin the user pinned to an older version is still installed and offering to install it again
         * would be a lie.
         */
        public boolean isInstalledIn(List<UserLibrary> libraries) {
            return libraries.stream().anyMatch(lib -> lib.groupId().equals(groupId())
                    && lib.artifactId().equals(artifactId()));
        }

        /** Free-text match over name / id / description / tags, the same shape as {@link GalleryEntry}. */
        public boolean matches(String query) {
            if (query == null || query.isBlank()) return true;
            String q = query.toLowerCase();
            return name.toLowerCase().contains(q)
                    || id.toLowerCase().contains(q)
                    || description.toLowerCase().contains(q)
                    || tags.stream().anyMatch(t -> t.toLowerCase().contains(q));
        }
    }

    /** Fetches the whole catalog. Empty when the registry is unset, unreachable or malformed. */
    public CompletableFuture<List<Plugin>> browse() {
        if (!GitHubConfig.isRegistryConfigured()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client.getFirstString(GitHubConfig.registryIndexRawUrls()).thenApply(PluginRegistry::parse);
    }

    /** Package-private and static so the parse can be tested without a network or a client. */
    static List<Plugin> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            return List.of(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(body, Plugin[].class));
        } catch (Exception e) {
            System.err.println("Failed to parse the plugin registry index.json: " + e.getMessage());
            return List.of();
        }
    }
}
