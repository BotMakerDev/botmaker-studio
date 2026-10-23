package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.sharing.CatalogClient;
import com.botmaker.studio.sharing.CatalogIndex;
import com.botmaker.studio.sharing.PluginRegistry;
import com.botmaker.studio.sharing.PublisherRank;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the catalog says about the plugins a project binds (2026-10-23), checked at two moments of
 * {@link PluginHost#bind}.
 *
 * <p><b>Before a jar is loaded</b> ({@link #admit}): a plugin's constructor runs the moment it loads, so a
 * community plugin, or one the catalog does not list, is loaded only after the user agreed to run it on this
 * computer — asked once, the yes kept in {@link CommunityConsent}. A no leaves it off the loader, reported as a
 * failure, and it is asked again on the next bind. Who is asked is {@link CommunityConsent#needsAsking}.
 *
 * <p><b>After the plugins are constructed</b> ({@link #checkIds}): a plugin loaded from a catalog coordinate
 * must say the id that entry lists. The catalog's own check compares them, but runs the plugin to read its id,
 * and a plugin that exits the JVM there is never compared. So Studio compares again and drops a plugin whose id
 * is not its entry's; a dev build and a coordinate the catalog does not list are not compared. By then its
 * constructor has run and its jar stays on the project's loader: the check keeps a listed plugin from binding
 * under another entry's id, and the consent above is what keeps unreviewed code from running.
 *
 * <p>A dev build skips both only when this computer installed it ({@link #installedHere}): dev mode is read from
 * the project's own files, which a cloned bot may bring.
 *
 * <p>Four ways round the question were closed on 2026-10-23 (Phase 7b, after a security review): a library
 * sharing a package with a plugin let through is left off; a row vouches only for the jar JitPack built at its
 * tag ({@link ReleaseChecksums}), not for any jar at that version; the user's own plugin is exempt only as their
 * catalog row at its tag, not by groupId; and a dev build counts as installed here only when Maven's metadata
 * agrees with its {@code _remote.repositories}.
 *
 * <p>Studio installs one at start ({@link PluginHost#guard}); a host with none (the tests, a headless run)
 * binds everything as before.
 */
public final class PluginGate {

    /** Asks the user whether to load one plugin. Answers no when it cannot ask. */
    @FunctionalInterface
    public interface Asker {
        boolean agrees(Asked asked);
    }

    /**
     * One plugin to ask about.
     *
     * @param coordinate {@code groupId:artifactId}, blank when the jar's could not be read
     * @param jar        the jar, as {@code <artifact> <version>}
     * @param row        its catalog row, empty when the catalog does not list it
     */
    public record Asked(String coordinate, String jar, Optional<PluginRegistry.Plugin> row) {

        /** The question, in words: whose plugin, why it is asked, what a yes does. */
        public String question() {
            String what = row.map(r -> "“" + (r.name().isBlank() ? r.id() : r.name()) + "” (" + jar + ") by "
                            + r.repo().substring(0, Math.max(0, r.repo().indexOf('/')))
                            + (r.rank() == PublisherRank.COMMUNITY ? " is a community plugin."
                            : " is not the release the catalog lists (" + r.tag() + ") as JitPack built it, or "
                            + "Studio could not check that, so its " + r.rank().displayName() + " rank does not "
                            + "vouch for it: any project can name its own Maven repository and serve another jar "
                            + "under this name."))
                    .orElse(jar + (coordinate.isBlank() ? "" : " (" + coordinate + ")")
                            + " is a plugin the BotMaker catalog does not list.");
            return what + " It runs inside Studio with your permissions and can control your mouse, keyboard and "
                    + "screen, and nobody reviewed its code.\n\nRun it on this computer? Studio will not ask again "
                    + "for this " + (row.isPresent() && row.get().rank() != PublisherRank.COMMUNITY
                    ? "version." : "plugin.");
        }
    }

    /** What {@link #admit} lets onto the loader, and the plugins the user declined. */
    public record Admitted(List<String> loadable, List<PluginLoader.PluginFailure> refused) {
        public Admitted {
            loadable = List.copyOf(loadable);
            refused = List.copyOf(refused);
        }
    }

    /** What {@link #checkIds} binds, and the plugins whose id is not their entry's. */
    public record Checked(List<StudioPlugin> bound, List<PluginLoader.PluginFailure> refused) {
        public Checked {
            bound = List.copyOf(bound);
            refused = List.copyOf(refused);
        }
    }

    /** The sentence a declined plugin is reported with, after its jar. */
    static final String DECLINED = "not loaded: you have not agreed to run this community plugin on this computer";

    /** The metadata {@code mvn install} writes beside a SNAPSHOT it installs. */
    private static final String LOCAL_METADATA = "maven-metadata-local.xml";

    /** How long a bind waits for the first catalog read of a session before it uses the kept one. */
    private static final long CATALOG_SECONDS = 10;

    private final Supplier<CatalogIndex> catalog;
    private final Path repository;
    private final Supplier<String> login;
    private final Supplier<CommunityConsent> consent;
    private final Consumer<CommunityConsent> keep;
    private final Asker asker;
    private final Predicate<Path> declaresPlugin;
    private final Predicate<Path> builtHere;
    private final BiPredicate<Path, PluginRegistry.Plugin> isRelease;

    /**
     * @param repository     the Maven repository the classpath's jars are in, which a jar's coordinate is read
     *                       off
     * @param declaresPlugin whether a jar carries the {@code StudioPlugin} service entry
     * @param builtHere      whether a jar was installed on this computer rather than downloaded
     *                       ({@link #installedHere}): only such a dev build skips the question
     * @param isRelease      whether a jar is the one JitPack built for a row's tag
     *                       ({@link ReleaseChecksums#isRelease}): only such a jar loads on its row's word
     */
    PluginGate(Supplier<CatalogIndex> catalog, Path repository, Supplier<String> login,
               Supplier<CommunityConsent> consent, Consumer<CommunityConsent> keep, Asker asker,
               Predicate<Path> declaresPlugin, Predicate<Path> builtHere,
               BiPredicate<Path, PluginRegistry.Plugin> isRelease) {
        this.catalog = catalog;
        this.repository = repository.toAbsolutePath().normalize();
        this.login = login;
        this.consent = consent;
        this.keep = keep;
        this.asker = asker;
        this.declaresPlugin = declaresPlugin;
        this.builtHere = builtHere;
        this.isRelease = isRelease;
    }

    /** Studio's gate: the session's catalog, {@code ~/.m2}, the signed-in login, this computer's yeses. */
    public static PluginGate studio(Asker asker) {
        return new PluginGate(PluginGate::sessionCatalog,
                Path.of(System.getProperty("user.home"), ".m2", "repository"), GitHubAuth::rememberedLogin,
                CommunityConsent::load, CommunityConsent::save, asker, MavenService::declaresPlugin,
                PluginGate::installedHere, ReleaseChecksums.studio()::isRelease);
    }

    /**
     * Whether Maven installed {@code jar} from this computer ({@code mvn install}) rather than downloading it. A
     * SNAPSHOT version alone says nothing — a cloned bot's pom can name its own repository and pin any SNAPSHOT
     * from it, and its committed {@code .botmaker/settings.json} can turn dev mode on. Two of Maven's own records
     * must agree: the jar's {@code _remote.repositories} line names no repository ({@code <file>>=}), and its
     * directory holds {@code maven-metadata-local.xml} and no other repository's metadata. The line alone was
     * not enough (Phase 7b): a repository declared with an empty id writes the same {@code >=}, but leaves
     * {@code maven-metadata-.xml}; one with the id {@code local} writes {@code >local=}. A jar whose records are
     * missing or unreadable is not counted as built here.
     */
    static boolean installedHere(Path jar) {
        Path remotes = jar.resolveSibling("_remote.repositories");
        String prefix = jar.getFileName() + ">";
        try (Stream<String> lines = Files.lines(remotes)) {
            boolean installed = lines.map(String::trim).filter(line -> line.startsWith(prefix)).findFirst()
                    .map(line -> line.substring(prefix.length()).startsWith("=")).orElse(false);
            return installed && onlyLocalMetadata(jar.getParent());
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean onlyLocalMetadata(Path versionDir) throws IOException {
        if (!Files.isRegularFile(versionDir.resolve(LOCAL_METADATA))) return false;
        try (Stream<Path> files = Files.list(versionDir)) {
            return files.map(file -> file.getFileName().toString())
                    .noneMatch(name -> name.startsWith("maven-metadata-") && name.endsWith(".xml")
                            && !name.equals(LOCAL_METADATA));
        }
    }

    /** The session's catalog, or the kept one when GitHub is slow to answer the first read. */
    private static CatalogIndex sessionCatalog() {
        CatalogClient client = CatalogClient.session();
        // copy(): completing the session's shared answer here would make the kept catalog everybody's.
        return client.fetch().copy().completeOnTimeout(client.kept(), CATALOG_SECONDS, TimeUnit.SECONDS).join();
    }

    /** The catalog this bind reads, {@link CatalogIndex#EMPTY} when none can be. */
    CatalogIndex catalog() {
        try {
            return catalog.get();
        } catch (RuntimeException e) {
            return CatalogIndex.EMPTY;
        }
    }

    /**
     * {@code loadable} without the plugins the user declined, asking about each one that needs a yes and has
     * none ({@link CommunityConsent#needsAsking}). A yes is kept before the plugin loads.
     *
     * <p>A library (a jar that declares no plugin) runs no code by being on the loader, so it is not asked
     * about — unless it has classes in a package of a plugin let through (Phase 7b): one loader holds them
     * all, so such a jar could replace or join the plugin's own classes and run as the plugin. It is left off.
     *
     * @param listed  the catalog; {@link CatalogIndex#EMPTY} is read as no catalog at all
     * @param devJars the dev builds dev mode let through; one installed on this computer is not asked about
     */
    Admitted admit(CatalogIndex listed, List<String> loadable, List<String> devJars) {
        Set<Path> dev = builtHere(devJars);
        String who = login.get();
        CommunityConsent agreed = null;
        Set<String> plugins = new LinkedHashSet<>();
        Set<String> libraries = new LinkedHashSet<>();
        List<PluginLoader.PluginFailure> refused = new ArrayList<>();
        for (String entry : loadable) {
            Path jar = normal(entry);
            if (!declaresPlugin.test(jar)) {
                libraries.add(entry);
                continue;
            }
            String coordinate = coordinateOf(jar);
            String version = ReleasedPlugins.versionOf(jar);
            Optional<PluginRegistry.Plugin> row = rowFor(listed, coordinate);
            if (!CommunityConsent.needsAsking(coordinate, version, row, !listed.isEmpty(), who, dev.contains(jar),
                    () -> isRelease.test(jar, row.orElseThrow()))) {
                plugins.add(entry);
                continue;
            }
            String key = CommunityConsent.key(coordinate.isBlank() ? ReleasedPlugins.describe(entry) : coordinate,
                    version, row);
            if (agreed == null) agreed = consent.get();
            if (agreed.allows(key)) {
                plugins.add(entry);
            } else if (asks(new Asked(coordinate, ReleasedPlugins.describe(entry), row))) {
                agreed = agreed.with(key);
                keep.accept(agreed);
                plugins.add(entry);
            } else {
                refused.add(new PluginLoader.PluginFailure(ReleasedPlugins.describe(entry),
                        new IllegalStateException(DECLINED)));
            }
        }
        Map<String, String> pluginPackages = new HashMap<>();
        for (String plugin : plugins) {
            for (String pkg : packagesOf(normal(plugin))) pluginPackages.putIfAbsent(pkg, plugin);
        }
        Set<String> shadowing = new LinkedHashSet<>();
        for (String library : libraries) {
            packagesOf(normal(library)).stream().filter(pluginPackages::containsKey).findFirst().ifPresent(pkg -> {
                shadowing.add(library);
                refused.add(new PluginLoader.PluginFailure(ReleasedPlugins.describe(library),
                        new IllegalStateException(shadows(pkg, pluginPackages.get(pkg)))));
            });
        }
        List<String> admitted = loadable.stream()
                .filter(entry -> plugins.contains(entry) || libraries.contains(entry) && !shadowing.contains(entry))
                .toList();
        return new Admitted(admitted, refused);
    }

    /** The sentence a library left off for sharing a plugin's package is reported with, after its jar. */
    static String shadows(String pkg, String plugin) {
        return "not loaded: it has classes in " + pkg + ", a package of the plugin "
                + ReleasedPlugins.describe(plugin) + ", and could run as that plugin";
    }

    /**
     * The packages {@code jar} has classes in, a multi-release jar's versioned ones included; empty when it
     * cannot be read. The unnamed package counts as {@code ""}.
     */
    static Set<String> packagesOf(Path jar) {
        Set<String> packages = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().map(ZipEntry::getName).filter(name -> name.endsWith(".class"))
                    .map(name -> name.replaceFirst("^META-INF/versions/\\d+/", ""))
                    .filter(name -> !name.startsWith("META-INF/") && !name.endsWith("module-info.class"))
                    .map(name -> name.lastIndexOf('/') < 0 ? "" : name.substring(0, name.lastIndexOf('/'))
                            .replace('/', '.'))
                    .forEach(packages::add);
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
        return packages;
    }

    private boolean asks(Asked asked) {
        try {
            return asker.agrees(asked);
        } catch (RuntimeException e) {
            return false;   // a question that could not be put is not a yes
        }
    }

    /**
     * {@code plugins} without those loaded from a catalog coordinate whose id is not that entry's.
     *
     * @param jarOf   the jar a plugin's class came from, empty for one from no jar
     * @param devJars the dev builds dev mode let through; one installed on this computer is not compared
     */
    Checked checkIds(CatalogIndex listed, List<StudioPlugin> plugins, Function<StudioPlugin, Optional<Path>> jarOf,
                     List<String> devJars) {
        Set<Path> dev = builtHere(devJars);
        List<StudioPlugin> bound = new ArrayList<>();
        List<PluginLoader.PluginFailure> refused = new ArrayList<>();
        for (StudioPlugin plugin : plugins) {
            Optional<Path> jar = jarOf.apply(plugin).map(path -> path.toAbsolutePath().normalize());
            Optional<PluginRegistry.Plugin> row = jar.filter(path -> !dev.contains(path))
                    .flatMap(path -> rowFor(listed, coordinateOf(path)));
            String id = idOf(plugin);
            if (row.isEmpty() || row.get().id().equals(id)) {
                bound.add(plugin);
            } else {
                refused.add(new PluginLoader.PluginFailure(ReleasedPlugins.describe(jar.get().toString()),
                        new IllegalStateException("its id is " + (id.isEmpty() ? "unreadable" : id)
                                + ", but the catalog lists this coordinate as " + row.get().id())));
            }
        }
        return new Checked(bound, refused);
    }

    private static String idOf(StudioPlugin plugin) {
        try {
            String id = plugin.id();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /**
     * The {@code groupId:artifactId} of a jar in {@link #repository}'s layout
     * ({@code <group dirs>/<artifact>/<version>/<file>}), blank for a jar anywhere else.
     */
    String coordinateOf(Path jar) {
        if (!jar.startsWith(repository)) return "";
        Path relative = repository.relativize(jar);
        int parts = relative.getNameCount();
        if (parts < 4) return "";
        List<String> group = new ArrayList<>();
        for (int i = 0; i < parts - 3; i++) group.add(relative.getName(i).toString());
        return String.join(".", group) + ":" + relative.getName(parts - 3);
    }

    /**
     * The catalog row for {@code coordinate}: the one at it exactly, else one naming the same artifact under
     * BotMaker's other groupId ({@link DuplicatePlugins#sameArtifact}).
     */
    static Optional<PluginRegistry.Plugin> rowFor(CatalogIndex listed, String coordinate) {
        int colon = coordinate.indexOf(':');
        if (colon < 0) return Optional.empty();
        String group = coordinate.substring(0, colon);
        String artifact = coordinate.substring(colon + 1);
        return listed.plugins().stream().filter(p -> p.coordinate().equals(coordinate)).findFirst()
                .or(() -> listed.plugins().stream()
                        .filter(p -> p.isInstallable()
                                && DuplicatePlugins.sameArtifact(p.groupId(), p.artifactId(), group, artifact))
                        .findFirst());
    }

    /** The dev builds among {@code devJars} that this computer installed itself. */
    private Set<Path> builtHere(List<String> devJars) {
        return devJars.stream().map(PluginGate::normal).filter(builtHere).collect(Collectors.toSet());
    }

    private static Path normal(String entry) {
        return Path.of(entry).toAbsolutePath().normalize();
    }
}
