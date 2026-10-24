package com.botmaker.studio.sharing;

import com.botmaker.cli.catalog.Catalog;
import com.botmaker.cli.catalog.DerivedFacts;
import com.botmaker.studio.project.UserLibrary;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Read side of the catalog's plugins — the answer to "which plugins exist?", which
 * {@code META-INF/services} cannot give.
 *
 * <p>`ServiceLoader` is how the host <em>instantiates</em> a plugin already on the classpath; getting it
 * onto the classpath is a Maven coordinate, and finding that coordinate is the problem this solves.
 * Installing one is therefore an ordinary dependency added through {@code LibraryService} — the plugin
 * platform's whole point is that a plugin is a normal library, and a bespoke install path would be a
 * privilege the first-party plugin has and a third party's does not.
 *
 * <p><b>The catalog says the version (2026-10-23).</b> Since 2026-10-12 a plugin installed JitPack's newest
 * build, because the registry's {@code verifiedVersion} lagged behind pull requests that failed its gate. The
 * catalog's entries are checked by machine and merged automatically, so an entry's releases are the ones that
 * passed the check (every one kept since 2026-10-24, newest first), and nothing else is installed. Read through
 * {@link CatalogClient}, one request per
 * session; any failure is the catalog kept from the last read, else empty, and never stops anybody editing
 * their bot.
 */
public final class PluginRegistry {

    private final CatalogClient catalog;

    public PluginRegistry() {
        this(CatalogClient.session());
    }

    PluginRegistry(CatalogClient catalog) {
        this.catalog = catalog;
    }

    /**
     * One plugin's row of {@code catalog.json}.
     *
     * @param coordinate {@code groupId:artifactId}, with no version — the catalog names a plugin, not a
     *                   release
     * @param version what gets installed: the catalog's {@code tag}, spelled as git has it (a pin is the tag
     *                name, {@code 43-real-versions.md}); a dev build's own version for a dev-mode row
     * @param editorDependencies {@code groupId:artifactId:version} of everything this plugin needs on the
     *                           <em>editor's</em> classpath and does not bring itself — the dependencies it
     *                           declares {@code optional}, which is exactly the set a resolve does not reach.
     *                           Absent is the ordinary case: a plugin whose dependencies are ordinary needs
     *                           no list, because they are transitive. See {@code MavenService.installPlugin}.
     * @param tag the newest release the catalog approved
     * @param rank who published it
     * @param releases every release the catalog approved, newest first, each pinned to its commit and the sha256
     *                 of its jar and pom (2026-10-24); {@code minContractVersion} and {@code editorDependencies}
     *                 are the newest one's
     */
    public record Plugin(String id, String name, String coordinate, String repo, String description,
                         List<String> tags, String minContractVersion,
                         List<String> editorDependencies, String version, String tag, PublisherRank rank,
                         int stars, List<Catalog.Plugin.Release> releases) {

        public Plugin {
            releases = releases == null ? List.of() : List.copyOf(releases);
            editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
            id = id == null ? "" : id.trim();
            name = name == null ? "" : name.trim();
            coordinate = coordinate == null ? "" : coordinate.trim();
            repo = repo == null ? "" : repo.trim();
            description = description == null ? "" : description;
            tags = tags == null ? List.of() : List.copyOf(tags);
            minContractVersion = minContractVersion == null ? "" : minContractVersion.trim();
            tag = tag == null ? "" : tag.trim();
            version = version == null || version.isBlank() ? tag : version.trim();
            rank = rank == null ? PublisherRank.COMMUNITY : rank;
        }

        /**
         * The order every list of plugins is shown in (2026-10-23): official first, then trusted, then
         * community, the most starred first within a rank, then by name.
         */
        public static final Comparator<Plugin> RANKED = Comparator.comparing(Plugin::rank)
                .thenComparing(Comparator.comparingInt(Plugin::stars).reversed())
                .thenComparing(Plugin::name, String.CASE_INSENSITIVE_ORDER);

        /** A row the catalog did not rank — a dev build, a test's: community, installing {@code version}. */
        public Plugin(String id, String name, String coordinate, String repo, String description,
                      List<String> tags, String minContractVersion, List<String> editorDependencies,
                      String version) {
            this(id, name, coordinate, repo, description, tags, minContractVersion, editorDependencies, version,
                    "", PublisherRank.COMMUNITY, 0);
        }

        /** A row approving {@code tag} alone, with no pins: a test's, or one built by hand. */
        public Plugin(String id, String name, String coordinate, String repo, String description,
                      List<String> tags, String minContractVersion, List<String> editorDependencies,
                      String version, String tag, PublisherRank rank, int stars) {
            this(id, name, coordinate, repo, description, tags, minContractVersion, editorDependencies, version,
                    tag, rank, stars, tag == null || tag.isBlank() ? List.of()
                            : List.of(new Catalog.Plugin.Release(tag.trim(), null, null, null, minContractVersion,
                            editorDependencies)));
        }

        /** This entry installing {@code newVersion}. */
        public Plugin withVersion(String newVersion) {
            return new Plugin(id, name, coordinate, repo, description, tags, minContractVersion, editorDependencies,
                    newVersion, tag, rank, stars, releases);
        }

        /** The release the catalog approved as {@code version}, spelled as git has it; empty when it approved none. */
        public Optional<Catalog.Plugin.Release> release(String version) {
            return releases.stream().filter(r -> r.tag().equals(version)).findFirst();
        }

        /**
         * The catalog plugins this one brings: the ids its newest release's pom declares, which a project adding
         * it gets transitively. New Project locks their cards "brought by" this one.
         */
        public List<String> brings() {
            if (releases.isEmpty()) return List.of();
            return releases.getFirst().plugins().stream().filter(Objects::nonNull).map(DerivedFacts.Pin::id)
                    .filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
        }

        /** The tags the catalog approved, newest first: what Plugins & Libraries offers. */
        public List<String> approvedTags() {
            return releases.stream().map(Catalog.Plugin.Release::tag).toList();
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
         * it would cost the user every plugin. The catalog's own builder is where a malformed list is an error,
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
            // BotMaker's former groupId counts (2026-10-06): a bot pinning the SDK as com.github.LiQiyeDev has
            // it installed, and offering it again under the new groupId declared it twice.
            return libraries.stream().anyMatch(lib -> com.botmaker.studio.plugin.DuplicatePlugins.sameArtifact(
                    lib.groupId(), lib.artifactId(), groupId(), artifactId()));
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

    /**
     * The entry of {@code entries} for {@code library}: the one at its exact coordinate, else one naming the same
     * artifact under BotMaker's other groupId (2026-10-07) — an entry still at {@code com.github.LiQiyeDev}
     * accounts for the {@code com.github.BotMakerDev} copy. Empty when none does.
     */
    public static java.util.Optional<Plugin> entryFor(List<Plugin> entries, UserLibrary library) {
        String coordinate = library.groupId() + ":" + library.artifactId();
        return entries.stream().filter(e -> e.isInstallable() && e.coordinate().equals(coordinate)).findFirst()
                .or(() -> entries.stream().filter(e -> e.isInstallable() && e.isInstalledIn(List.of(library)))
                        .findFirst());
    }

    /** Every listed plugin, each at its catalog tag. Empty when no catalog can be read. */
    public CompletableFuture<List<Plugin>> browse() {
        return catalog.fetch().thenApply(CatalogIndex::plugins);
    }
}
