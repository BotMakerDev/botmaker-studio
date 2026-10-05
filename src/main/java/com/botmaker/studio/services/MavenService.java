package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.UserLibrary;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maven operations for generated user projects.
 *
 * <p>Replaces the old Gradle integration: the project descriptor (pom.xml) is built with the
 * Maven Model API (no hand-written build string), and dependencies are resolved transitively
 * in-process with Maven Resolver (Aether) — no system {@code mvn} binary is required.
 */
public final class MavenService {

    private MavenService() {}

    // The pom is Studio's again (2026-08-26), one day after phase 3 moved it to the SDK. The reversal is
    // recorded rather than tidied away because the argument that moved it was not wrong, only incomplete:
    // the pom is not a file *about* the SDK, it is the file that *declares which* SDK — and, once there is
    // ever a second plugin, which plugins. The SDK is the editor's default plugin, not the editor; a plugin
    // cannot enumerate its siblings, so a pom it wrote would silently omit theirs. Only the composer can
    // write the manifest of what it composed. Creation still lands in one pass: ProjectCreator hands this
    // text to Authoring.createProject as a caller file.

    /** Default remote repositories used both in generated POMs and during resolution. */
    private static final Map<String, String> DEFAULT_REPOSITORIES = new LinkedHashMap<>();
    static {
        DEFAULT_REPOSITORIES.put("central", "https://repo.maven.apache.org/maven2/");
        DEFAULT_REPOSITORIES.put("jitpack", "https://jitpack.io");
        DEFAULT_REPOSITORIES.put("google", "https://dl.google.com/dl/android/maven2/");
    }

    /** Maven coordinate of the BotMaker SDK (published from GitHub tags via JitPack). */
    public static final String SDK_GROUP_ID = "com.github.LiQiyeDev";
    public static final String SDK_ARTIFACT_ID = "botmaker-sdk";
    /**
     * Version used for the SDK when none is supplied / JitPack is unreachable.
     *
     * <p><b>Hand-typed, and it must stay that way.</b> {@code release.sh} bumps it with a {@code sed} over
     * this string literal on every {@code --sdk} release; deriving it from {@link SdkVersion#latest()} would
     * make that bump silently stop working. What a <em>freshly created</em> pom pins is also a separate
     * question from what this build of the SDK is.
     */
    public static final String SDK_FALLBACK_VERSION = "1.3.0";

    /**
     * The contract tag a dev build of Studio writes into a project ({@code config/HostContract}); a release
     * build writes the tag it was built against instead. Hand-typed for the same reason as
     * {@link #SDK_FALLBACK_VERSION}: the release bumps this literal on every {@code --studio-api} release.
     */
    public static final String CONTRACT_FALLBACK_VERSION = "0.3.1";

    // There is no MIN_SDK_VERSION any more, and its absence is deliberate (2026-08-25).
    //
    // The floor was a statement about generation: below it Studio could not render a generated file, because
    // the templates and the FlowGraph/Wire API they call arrived in 1.1.0. Studio does not generate at all
    // now — the templates left the SDK and its own emitters are not written yet (inversion phase 2) — so a
    // floor would be a comparison with nothing behind it: a banner and a refusal about a capability neither
    // side has. Every pinned SDK therefore opens with no warning, and an incompatibility surfaces where it
    // always could, at compile time, naming the element.
    //
    // Do not reinstate it as a palette floor either. The palette is what the project's own plugins
    // catalogue, loaded from its own jars, which is strictly better than a version comparison.
    // release.sh's check_sdk_floor went with it.

    // localSdkVersions() and localPluginBuilds() — dev-build scans of ~/.m2 for *SNAPSHOT SDK and plugin builds,
    // offered in the version pickers, Browse and New Project — were deleted on 2026-10-01 (the maintainer's
    // call): a bot published from a project pinned to one named a version nobody else could resolve. A plugin
    // author still tests a local build by pinning its SNAPSHOT in the pom by hand and pressing Reload plugins;
    // Studio just does not offer it, and Publish refuses a SNAPSHOT pin (sharing/PublishPins).

    /**
     * Whether {@code jar} declares a {@code StudioPlugin} the way {@code ServiceLoader} finds one.
     *
     * <p><b>This is the project's only definition of "is this a plugin".</b>
     * {@code services/upgrade/InstalledPlugin} asks it of a dependency the pom declares that no registry
     * entry accounts for. A second rule — a naming convention, a registry lookup treated as definitive —
     * would answer differently the first time somebody published a plugin the registry has not seen, which
     * is precisely the case that reader exists for.
     */
    public static boolean declaresPlugin(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            return zip.getEntry("META-INF/services/com.botmaker.plugin.api.StudioPlugin") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Dependencies every generated project gets (mirrors the old build.gradle). */
    private record Dep(String groupId, String artifactId, String version, String scope) {}

    // There is no TOOLKIT_FALLBACK_VERSION any more, and its absence is the point (2026-09-06).
    //
    // A generated pom used to declare botmaker-plugin-toolkit at a version this constant held, `provided`,
    // for the SDK plugin's sake. It was a landmine rather than a service: botmaker-sdk has declared the
    // toolkit at plain `compile` scope since 2026-09-04, so it is transitive and arrives with the SDK — and
    // Maven's nearest-wins mediation makes a bot's own direct entry BEAT the version the pinned SDK
    // resolved. An SDK built against a newer toolkit therefore failed at runtime with NoSuchMethodError,
    // in a project whose pom looked deliberate. Two pins for one artifact is what this constant was.
    //
    // Do not reinstate it. Whichever toolkit the pinned SDK was built against is the only right answer, and
    // the SDK's own pom is the only thing that knows it. release.sh's PLUGIN_TOOLKIT -> STUDIO forcing edge
    // and check_fallback_versions' second constant went with it.

    /**
     * The whole of a <b>blank</b> project's dependencies: a test framework, and nothing else.
     *
     * <p><b>A blank project names no plugin, and therefore has no bot API</b> (2026-09-04). Until then every
     * project Studio created pinned {@code botmaker-sdk} plus the eight entries below, so "blank" meant a bot
     * project with a {@code System.out.println} in it — and a user who wanted a plain Java project could not
     * have one. The platform's own rule is that the SDK is one plugin among any number; a starting point that
     * names it is a starting point that has chosen one, which is a choice belonging to the person starting.
     *
     * <p>So a blank project opens with <b>no palette, no plugin toolbar buttons, no pictures, no capture and
     * no pilot</b>. That is not a degraded state to be minimised — it is what a project with no plugins
     * installed looks like, and it is reachable in one step from **Project ▸ Manage Plugins…**, which
     * installs the SDK as an ordinary dependency exactly as it installs anybody else's plugin. The richer
     * starting point is a published bot carrying the {@code template} tag; see {@code TemplateProject}.
     *
     * <p>JUnit is here rather than in {@link #BOT_DEPENDENCIES} because it is the one entry that is not about
     * BotMaker at all: it is what a Maven project is expected to come with, and a user who writes a test on
     * day one should not have to add it. {@link #DEFAULT_REPOSITORIES} is unchanged for both shapes, so a
     * blank project can <em>add</em> a BotMaker library later without anybody hand-editing a repository in.
     */
    private static final List<Dep> BLANK_DEPENDENCIES = List.of(
            new Dep("org.junit.jupiter", "junit-jupiter", "5.9.3", "test")
    );

    /**
     * What a pom that names the SDK plugin has to carry — <b>the list, kept, for a project that wants one</b>.
     *
     * <p>Nothing Studio creates writes this any more: a blank project takes {@link #BLANK_DEPENDENCIES} and a
     * project made from a gallery template brings its author's own pom, versions and all. It stays because it
     * is the written statement of the paragraph below, which a template author needs and which is not
     * derivable from anywhere else, and because {@link #DEFAULT_GROUP_ARTIFACTS} must go on recognising every
     * one of these in the pom of a project that already exists.
     *
     * <h2>Every entry is a library the <em>bot's own source</em> may name</h2>
     *
     * <p>The list held five more until 2026-09-06, and every one of them was here on behalf of a
     * <b>plugin</b> rather than of the bot. Three left in the morning: {@code botmaker-plugin-toolkit}, which
     * the SDK declares at {@code compile} scope and so brings transitively — a direct entry here
     * <em>outranked</em> it by nearest-wins, pinning a bot to a toolkit its SDK was not built against — and
     * {@code javafx-controls}/{@code javafx-graphics}, which {@code PluginLoader} resolves <b>parent-first</b>
     * so the host's own copy is what every plugin links whatever a bot's pom says.
     *
     * <p>The last two, {@code io.javalin:javalin} and {@code com.google.zxing:core}, were real — the SDK's
     * plugin half needs a web server and a QR encoder, its pom marks both {@code optional} so a headless bot
     * links neither, and <b>{@code optional} is not transitive</b>. What was wrong was <em>where they were
     * written down</em>: in Studio, spelled out for one plugin, so the second plugin to need something of its
     * own had no way to say so. They are {@code editorDependencies} in that plugin's registry entry now, and
     * {@link #installPlugin} declares whatever the entry names, for any plugin. Nothing here lists them, and
     * {@link #isDefaultDependency} is what stops them reading as user libraries.
     *
     * <p><b>The generalisation, restated:</b> a plugin's dependencies are declared by whoever puts that
     * plugin on a classpath — here that is this pom, because this pom is what names the plugin. What changed
     * is that the list of them is the plugin's own to publish rather than Studio's to know.
     */
    private static final List<Dep> BOT_DEPENDENCIES = List.of(
            new Dep(SDK_GROUP_ID, SDK_ARTIFACT_ID, SDK_FALLBACK_VERSION, null),
            new Dep("net.java.dev.jna", "jna", "5.13.0", null),
            new Dep("net.java.dev.jna", "jna-platform", "5.13.0", null),
            // Jackson stays even though nothing generated needs it any more: a java-model project's settings
            // are Java literals, not a JSON read at startup. It is on the list for what the user might write,
            // and taking it away would break a bot that imports it for a gain nobody would notice.
            new Dep("com.fasterxml.jackson.core", "jackson-databind", "2.15.2", null),
            new Dep("org.junit.jupiter", "junit-jupiter", "5.9.3", "test")
    );

    /**
     * {@code groupId:artifactId} of every built-in dependency — these are never treated as user libraries.
     *
     * <p>A "user library" is anything in the pom that is not on this list, so this set and the pom writers
     * must stay one list or the dialog offers to delete a dependency it cannot re-add. That is an argument
     * for one owner, and the owner is whoever writes the pom — Studio, again.
     *
     * <p><b>It is the union of both lists, and narrowing it to the blank one would be a data-loss bug.</b>
     * This classifies the dependencies of <em>any</em> project's pom, not only of one Studio wrote today: a
     * bot created before 2026-09-04, and every project unpacked from a gallery template, carries the
     * {@link #BOT_DEPENDENCIES} set. Left out of here, the SDK, the toolkit and JavaFX would read as user
     * libraries — offered for deletion in Manage Libraries, and then genuinely dropped by
     * {@link #writeUserLibraries}, which keeps what {@link #isDefaultDependency} recognises and discards the
     * rest.
     */
    /**
     * Coordinates {@link #BOT_DEPENDENCIES} <b>used to</b> write and no longer does — recognised here, never
     * generated.
     *
     * <p>The lists above describe the pom Studio writes today; this set is why removing an entry from one of
     * them is not enough. {@link #isDefaultDependency} classifies the pom of <em>any</em> project, and every
     * bot created before 2026-09-06 declares these three. Dropped from the union, they would read as user
     * libraries — offered for deletion in Manage Libraries and then genuinely discarded by
     * {@link #writeUserLibraries}, which keeps only what is recognised. A dependency stops being written long
     * before the last pom that has it is opened, so the two questions are separate and this set is the
     * second one's answer.
     */
    private static final Set<String> RETIRED_GROUP_ARTIFACTS = Set.of(
            // Retired 2026-09-06. The SDK brings the toolkit transitively at compile scope, and a direct
            // entry outranked it by nearest-wins; JavaFX is parent-first in PluginLoader, so the host's own
            // is what every plugin links. Left in an existing pom they are harmless — an unused provided
            // dependency — and taking them out is Manage Libraries' business, not a rewrite's.
            "com.github.LiQiyeDev:botmaker-plugin-toolkit",
            "org.openjfx:javafx-controls",
            "org.openjfx:javafx-graphics");

    private static final Set<String> DEFAULT_GROUP_ARTIFACTS =
            java.util.stream.Stream.concat(
                            java.util.stream.Stream.concat(
                                            BLANK_DEPENDENCIES.stream(), BOT_DEPENDENCIES.stream())
                                    .map(d -> d.groupId() + ":" + d.artifactId()),
                            RETIRED_GROUP_ARTIFACTS.stream())
                    .collect(Collectors.toUnmodifiableSet());

    /**
     * Whether a pom entry is BotMaker's rather than the user's — <b>a named coordinate, or any
     * {@code provided} dependency</b>.
     *
     * <p>The second arm arrived 2026-09-06 with {@code editorDependencies}, and it is the only rule that can
     * work: what a plugin needs in the editor is declared by <em>that plugin's</em> registry entry, so the
     * set of coordinates is open and Studio cannot hold a list of it. What it can say is that
     * {@link #installPlugin} writes them at {@code provided} and nothing else in a bot's pom is
     * {@code provided} — the scope means <i>present while the code is edited, absent when the bot runs</i>,
     * which is exactly what an editor-side companion is and is nothing a user adds through Manage Libraries
     * (that dialog writes no scope at all).
     *
     * <p>The cost is bounded and one-directional: a {@code provided} dependency somebody hand-added is not
     * <em>listed</em> in Manage Libraries, so it cannot be removed there. It is not <em>lost</em> —
     * {@link #writeUserLibraries} keeps everything this method recognises — and the failure it replaces was
     * the other way round, a companion offered for deletion and then genuinely discarded.
     */
    private static boolean isDefaultDependency(Dependency d) {
        return DEFAULT_GROUP_ARTIFACTS.contains(d.getGroupId() + ":" + d.getArtifactId())
                || "provided".equals(d.getScope());
    }

    // =========================================================================
    // POM GENERATION (Maven Model API)
    // =========================================================================

    /**
     * A <b>blank</b> project's {@code pom.xml} as text — {@link #BLANK_DEPENDENCIES}, and no plugin named.
     *
     * <p>This is what project creation writes. See {@link #botPomXml} for the shape a project that wants the
     * SDK carries, and {@link #BLANK_DEPENDENCIES} for why creation no longer writes that one.
     */
    public static String blankPomXml(ProjectConfig cfg) {
        return pomXml(cfg, null);
    }

    /**
     * A project's {@code pom.xml} as text, <b>naming the SDK plugin</b> and everything its plugin half needs
     * — {@link #BOT_DEPENDENCIES}, with the SDK pinned to {@code sdkVersion} (blank →
     * {@link #SDK_FALLBACK_VERSION}).
     *
     * <p>Nothing creates a project this way since 2026-09-04. It is what {@code ProjectRepair} rebuilds a
     * missing pom as for a project that already had one, and what a test builds a bot-shaped fixture with.
     */
    public static String botPomXml(ProjectConfig cfg, String sdkVersion) {
        return pomXml(cfg, sdkVersion == null || sdkVersion.isBlank()
                ? SDK_FALLBACK_VERSION : sdkVersion.trim());
    }

    /**
     * Builds a {@code pom.xml} for the given project using the Maven Model API and returns it as text. The
     * model is assembled as an object graph and serialized with {@link MavenXpp3Writer} — no XML string
     * templating, so a project name with an {@code &} in it cannot produce a pom that does not parse.
     *
     * <p><b>{@code sdkVersion} is {@code null} for a blank project</b>, which is the one thing this method
     * branches on: it selects {@link #BLANK_DEPENDENCIES} over {@link #BOT_DEPENDENCIES}. Null rather than
     * blank, because blank already means "pin the fallback" to every caller that ever passed a user's typed
     * version through, and a project with no SDK at all is a different statement from one whose version was
     * left empty. Both public spellings above say which they mean in their names.
     *
     * <p>Text rather than a write, because project <em>creation</em> does not write this file itself: it
     * hands the text to {@code Authoring.createProject}, which commits it in the same all-or-none pass as
     * the files the SDK owns. Composing it here and committing it there is what keeps both halves — Studio
     * knows the whole dependency set (the SDK is only one plugin), and a failed creation still leaves
     * nothing behind.
     */
    private static String pomXml(ProjectConfig cfg, String resolvedSdkVersion) {
        List<Dep> dependencies = resolvedSdkVersion == null ? BLANK_DEPENDENCIES : BOT_DEPENDENCIES;
        Model model = new Model();
        model.setModelVersion("4.0.0");
        model.setGroupId("com." + cfg.packageName());
        model.setArtifactId(cfg.projectName());
        model.setVersion("0.0.1-SNAPSHOT");
        model.setPackaging("jar");

        Properties props = new Properties();
        props.setProperty("maven.compiler.release", String.valueOf(Runtime.version().feature()));
        props.setProperty("project.build.sourceEncoding", "UTF-8");
        model.setProperties(props);

        DEFAULT_REPOSITORIES.forEach((id, url) -> {
            Repository repo = new Repository();
            repo.setId(id);
            repo.setUrl(url);
            model.addRepository(repo);
        });

        for (Dep d : dependencies) {
            Dependency dep = new Dependency();
            dep.setGroupId(d.groupId());
            dep.setArtifactId(d.artifactId());
            boolean isSdk = SDK_GROUP_ID.equals(d.groupId()) && SDK_ARTIFACT_ID.equals(d.artifactId());
            dep.setVersion(isSdk ? resolvedSdkVersion : d.version());
            if (d.scope() != null) dep.setScope(d.scope());
            model.addDependency(dep);
        }

        StringWriter out = new StringWriter();
        try {
            new MavenXpp3Writer().write(out, model);
        } catch (IOException impossible) {
            // A StringWriter does not fail. Wrapping rather than declaring keeps every caller's throws
            // clause about the filesystem, which is the only IO any of them can do anything about.
            throw new UncheckedIOException(impossible);
        }
        return out.toString();
    }

    /**
     * Writes a <b>bot-shaped</b> {@code pom.xml} — {@link #botPomXml} — to {@code projectDir/pom.xml}.
     *
     * <p>This is the <em>repair</em> path — restoring a build file somebody deleted out of an otherwise
     * intact project, where re-creating the project would (correctly) refuse. Creation goes through
     * {@link #blankPomXml} instead, so that its write is part of one atomic commit.
     *
     * <p><b>The caller chooses the shape, and must.</b> A repair that always wrote this one would hand an
     * SDK to a blank project that never had one — see {@link #writeBlankPom} and {@code ProjectRepair},
     * which reads {@code settings.json}'s recorded template to decide.
     */
    public static void writePom(Path projectDir, ProjectConfig cfg, String sdkVersion) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), botPomXml(cfg, sdkVersion));
    }

    /** Writes a <b>blank</b> {@code pom.xml} — {@link #blankPomXml} — to {@code projectDir/pom.xml}. */
    public static void writeBlankPom(Path projectDir, ProjectConfig cfg) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), blankPomXml(cfg));
    }

    // =========================================================================
    // DEPENDENCY RESOLUTION (Maven Resolver / Aether)
    // =========================================================================

    /**
     * Reads {@code projectDir/pom.xml} and resolves its (non-test) dependencies transitively,
     * returning the absolute paths of all resolved jars from the local {@code ~/.m2} repository.
     * Missing artifacts are downloaded from the POM's repositories (plus Maven Central).
     *
     * <p>Resolution is best-effort: if some artifacts fail, the ones that did resolve are still returned.
     */
    public static List<String> resolveClasspath(Path projectDir) {
        return resolveClasspath(projectDir, ProgressReporter.NONE);
    }

    /**
     * As {@link #resolveClasspath(Path)}, but reports download progress via {@code progress}: a real
     * fraction (aggregated across all concurrent transfers by bytes) plus a short message, e.g.
     * {@code "Downloading opencv-4.9.0.jar"}. It only fires for actual network transfers, so already-cached
     * opens stay quiet. It may be called from Aether's worker threads — callers that touch the UI must
     * marshal onto the FX thread.
     */
    public static List<String> resolveClasspath(Path projectDir, ProgressReporter progress) {
        return resolve(projectDir, progress).jars();
    }

    /**
     * What a resolve produced: the jars that resolved, and one line per dependency that did not.
     *
     * <p>The second half is why this exists (2026-09-29). Resolution was best-effort and said what failed on
     * stderr alone, so a plugin installed at a tag JitPack had not built was written to the pom, resolved to
     * nothing, and simply never appeared — the install reported success and the plugin was not there.
     *
     * @param problems {@code group:artifact:version — reason}, empty when everything resolved
     */
    public record Resolution(List<String> jars, List<String> problems) {

        public Resolution {
            jars = List.copyOf(jars);
            problems = List.copyOf(problems);
        }

        /** Whether {@code groupId:artifactId} is among the dependencies that failed. */
        public boolean failed(String groupId, String artifactId) {
            String prefix = groupId + ":" + artifactId + ":";
            return problems.stream().anyMatch(line -> line.startsWith(prefix));
        }
    }

    /** {@link #resolveClasspath(Path, ProgressReporter)}, keeping what failed. */
    public static Resolution resolve(Path projectDir, ProgressReporter progress) {
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            System.err.println("No pom.xml found at " + pomPath);
            return new Resolution(List.of(), List.of("pom.xml — not found"));
        }

        Model model;
        try (InputStream in = Files.newInputStream(pomPath)) {
            model = new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return new Resolution(List.of(), List.of("pom.xml — " + e.getMessage()));
        }

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = newSession(system);
        DownloadAggregator downloads = new DownloadAggregator();
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferInitiated(TransferEvent event) {
                progress.report(downloads.fraction(), "Downloading " + fileName(event.getResource()));
            }
            @Override
            public void transferProgressed(TransferEvent event) {
                downloads.progressed(event.getResource(), event.getTransferredBytes());
                progress.report(downloads.fraction(), "Downloading " + fileName(event.getResource()));
            }
            @Override
            public void transferSucceeded(TransferEvent event) {
                downloads.finished(event.getResource(), event.getTransferredBytes());
                progress.report(downloads.fraction(), "Downloaded " + fileName(event.getResource()));
            }
            @Override
            public void transferFailed(TransferEvent event) {
                downloads.finished(event.getResource(), event.getTransferredBytes());
            }
        });

        List<RemoteRepository> remoteRepos = buildRemoteRepositories(model);

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(remoteRepos);
        for (Dependency d : model.getDependencies()) {
            if ("test".equals(d.getScope())) continue;
            if ("sources".equals(d.getClassifier())) continue;
            String classifier = d.getClassifier() == null ? "" : d.getClassifier();
            // A template pins its plugin as ${botmaker.sdk.version}; handed the placeholder, the resolver looked
            // for a version of that name and the project bound no plugin at all (until 2026-10-01).
            Artifact artifact = new DefaultArtifact(
                    d.getGroupId(), d.getArtifactId(), classifier, "jar", versionOf(model, d));
            String scope = d.getScope() == null ? "compile" : d.getScope();
            collectRequest.addDependency(new org.eclipse.aether.graph.Dependency(artifact, scope));
        }

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
        List<String> jars = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            DependencyResult result = system.resolveDependencies(session, dependencyRequest);
            collectJars(result.getArtifactResults(), jars);
        } catch (DependencyResolutionException e) {
            System.err.println("Some dependencies failed to resolve: " + e.getMessage());
            if (e.getResult() != null) {
                collectJars(e.getResult().getArtifactResults(), jars);
                collectProblems(e.getResult().getArtifactResults(), problems);
            }
            // A collection failure (a pom that is not there at all) has no artifact results to name.
            if (problems.isEmpty()) problems.add(firstLine(e.getMessage()));
        }
        return new Resolution(jars, problems);
    }

    /** A resolver session over {@code ~/.m2}, the one every resolution here starts from. */
    private static DefaultRepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        Path localRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));
        // Expose the JVM's system properties (notably java.version) to the model builder so POMs whose
        // effective model depends on JDK-activated profiles resolve correctly. Without this, bytedeco's
        // javacpp-presets parent fails ("Failed to determine Java version for profile doclint-java8-disable"),
        // the descriptor read is silently ignored, and the whole opencv subtree — including the opencv main
        // jar that carries org.opencv.core.Mat — is dropped from the bot's runtime classpath.
        session.setSystemProperties(System.getProperties());
        return session;
    }

    private static void collectProblems(List<ArtifactResult> results, List<String> out) {
        if (results == null) return;
        for (ArtifactResult ar : results) {
            if (ar.isResolved() || ar.getRequest() == null || ar.getRequest().getArtifact() == null) continue;
            Artifact a = ar.getRequest().getArtifact();
            String reason = ar.getExceptions().isEmpty() ? "not found"
                    : firstLine(ar.getExceptions().getFirst().getMessage());
            out.add(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + " — " + reason);
        }
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) return "could not be resolved";
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /**
     * Aggregates bytes across all concurrent Aether transfers into a single overall fraction. New artifacts
     * are discovered mid-resolve, so the denominator grows as downloads start — the fraction is honest (real
     * bytes) but may briefly step back when a new large jar appears. Thread-safe: Aether fires transfer
     * callbacks from worker threads.
     */
    private static final class DownloadAggregator {
        /** resource identity → {transferredBytes, contentLength (-1 if unknown)} for in-flight transfers. */
        private final Map<TransferResource, long[]> active = new java.util.IdentityHashMap<>();
        private long completedBytes = 0;

        synchronized void progressed(TransferResource resource, long transferred) {
            active.put(resource, new long[]{transferred, resource.getContentLength()});
        }

        synchronized void finished(TransferResource resource, long transferred) {
            long[] prev = active.remove(resource);
            long bytes = transferred > 0 ? transferred : (prev != null ? prev[0] : 0);
            completedBytes += Math.max(0, bytes);
        }

        /** Overall completed-fraction in [0,1], or -1 when nothing with a known size is in flight yet. */
        synchronized double fraction() {
            long transferred = completedBytes;
            long total = completedBytes;
            for (long[] v : active.values()) {
                transferred += v[0];
                total += Math.max(v[1], v[0]); // unknown length → count its own transferred bytes as the total
            }
            return total > 0 ? Math.min(1.0, (double) transferred / total) : -1;
        }
    }

    /** The trailing file name of a transfer resource (e.g. {@code opencv-4.9.0.jar}), for progress text. */
    private static String fileName(TransferResource resource) {
        String name = resource.getResourceName();
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static void collectJars(List<ArtifactResult> results, List<String> out) {
        if (results == null) return;
        for (ArtifactResult ar : results) {
            if (ar.getArtifact() != null && ar.getArtifact().getFile() != null) {
                out.add(ar.getArtifact().getFile().getAbsolutePath());
            }
        }
    }

    private static List<RemoteRepository> buildRemoteRepositories(Model model) {
        Map<String, String> repos = new LinkedHashMap<>(DEFAULT_REPOSITORIES);
        for (Repository r : model.getRepositories()) {
            if (r.getUrl() != null) repos.put(r.getId(), r.getUrl());
        }
        // Disable snapshot fetching on every remote: in this project SNAPSHOT coordinates are always
        // local-only dev builds (botmaker-sdk / botmaker-shared at 0.0.0-SNAPSHOT, installed to ~/.m2 by
        // the umbrella reactor). Letting a remote (notably jitpack) answer for a SNAPSHOT could shadow the
        // freshly reinstalled local jar. Releases are non-SNAPSHOT, so user libraries are unaffected.
        RepositoryPolicy noSnapshots = new RepositoryPolicy(
                false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_WARN);
        List<RemoteRepository> result = new ArrayList<>();
        repos.forEach((id, url) ->
                result.add(new RemoteRepository.Builder(id, "default", url)
                        .setSnapshotPolicy(noSnapshots)
                        .build()));
        return result;
    }

    // =========================================================================
    // USER LIBRARIES (pom.xml is the source of truth)
    // =========================================================================

    /**
     * Reads the user-added libraries from {@code projectDir/pom.xml}: every dependency that is not one
     * of the built-in {@link #DEFAULT_DEPENDENCIES}. Returns an empty list if the pom is missing or
     * unreadable.
     */
    public static List<UserLibrary> readUserLibraries(Path projectDir) {
        Model model = readModel(projectDir);
        if (model == null) return List.of();
        return model.getDependencies().stream()
                .filter(d -> !isDefaultDependency(d))
                .map(d -> new UserLibrary(d.getGroupId(), d.getArtifactId(), d.getVersion()))
                .collect(Collectors.toList());
    }

    /**
     * <b>Every</b> dependency {@code projectDir/pom.xml} declares, the built-in ones included.
     *
     * <p>The second question about a pom, and the one that had no reader until 2026-09-05.
     * {@link #readUserLibraries} answers <i>what did the user add</i> — which is the right question for
     * <b>Manage Libraries</b>, whose rows are things a user may delete. It is the wrong question for
     * <b>Manage Plugins</b>, which asks <i>is this plugin installed</i>: {@code botmaker-sdk} is on
     * {@link #DEFAULT_GROUP_ARTIFACTS}, so the one plugin that exists today was invisible to that check by
     * construction and its row read <i>Install</i> forever. Pressing it again then appended a second
     * {@code botmaker-sdk} dependency, because {@link #writeUserLibraries} keeps the pom's defaults
     * <em>and</em> appends the list it is handed.
     *
     * <p>Versions are whatever the pom says, uninterpolated — a {@code ${property}} pin comes back as text.
     * Every caller so far compares {@code groupId:artifactId} and never the version, which is the same rule
     * {@code PluginRegistry.Plugin.isInstalledIn} states for itself: a plugin pinned to an older version is
     * installed. {@link #resolveArtifact(Path, String, String, String, String)} reads such a pin through the
     * pom's properties, so the text may be handed to it as is.
     */
    public static List<UserLibrary> readDeclaredLibraries(Path projectDir) {
        Model model = readModel(projectDir);
        if (model == null) return List.of();
        return model.getDependencies().stream()
                .map(d -> new UserLibrary(d.getGroupId(), d.getArtifactId(), d.getVersion()))
                .collect(Collectors.toList());
    }

    /**
     * The {@code <properties>} of {@code projectDir/pom.xml}, empty when the pom is missing or unreadable.
     *
     * <p>For reading a pin {@link #readDeclaredLibraries} hands back as {@code ${property}} text, which is
     * meaningless to anybody outside this project — a gallery entry naming the plugins a bot requires, say.
     */
    public static Map<String, String> readProperties(Path projectDir) {
        Model model = readModel(projectDir);
        if (model == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        model.getProperties().forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    /**
     * Declares {@code plugin} in {@code projectDir/pom.xml}, replacing any dependency already on that
     * {@code groupId:artifactId}, and declares at {@code provided} whatever that plugin's registry entry
     * says it needs to load in the editor at all.
     *
     * <p><b>Idempotent by coordinate.</b> The pom is edited in place rather than rebuilt through
     * {@link #writeUserLibraries}, which cannot express this: that method keeps every default dependency the
     * pom already has and appends everything it is handed, so installing a plugin that is <em>also</em> a
     * default — which the SDK is — writes it twice. Two versions of one artifact on a classpath is the least
     * diagnosable failure this platform has.
     *
     * <p><b>{@code editorDependencies} is the plugin's own list, and until 2026-09-06 it was Studio's.</b>
     * The rule is the platform's: <i>whoever puts a plugin on a classpath supplies what that plugin needs</i>,
     * and here that is this pom, because this pom is what names the plugin. What a plugin needs is a fact
     * about the plugin, though, and this method used to hold it as {@code if (isSdk(…))} over a list spelled
     * out in Studio's own source — the one privilege plugin #1 had, and exactly the privilege
     * {@code BrowsePluginsTab}'s javadoc says a plugin platform must not grant. The list comes from the
     * caller now, out of the plugin's registry entry, and this method has no idea which plugin it is
     * installing.
     *
     * <p><b>Why the entry declares them and the plugin's pom cannot.</b> These are precisely the dependencies
     * a resolve does not reach: the SDK marks its web server and QR encoder {@code optional} so a headless
     * bot links neither, and <b>{@code optional} is not transitive</b>. A list that could be read off the pom
     * would not need writing down.
     *
     * <p>They are declared {@code provided} — <i>present while the code is edited, absent when the bot
     * runs</i>. {@link #resolveClasspath} filters out only {@code test}, so they are on the classpath Studio
     * binds plugins from, while {@code mvn package} and the run/debug launchers exclude them by definition,
     * so the headless case the {@code optional} flags protect is still protected.
     *
     * <p>An entry the pom already declares is left alone, version and all — the list is a floor, not a pin
     * this dialog is entitled to move.
     */
    public static void installPlugin(Path projectDir, UserLibrary plugin,
                                     List<UserLibrary> editorDependencies) throws IOException {
        Model model = requireModel(projectDir);
        model.getDependencies().removeIf(d -> sameArtifact(d, plugin.groupId(), plugin.artifactId()));
        Dependency dep = new Dependency();
        dep.setGroupId(plugin.groupId());
        dep.setArtifactId(plugin.artifactId());
        dep.setVersion(plugin.version());
        model.getDependencies().add(dep);

        for (UserLibrary companion : editorDependencies) {
            boolean present = model.getDependencies().stream()
                    .anyMatch(d -> sameArtifact(d, companion.groupId(), companion.artifactId()));
            if (present) continue;
            Dependency added = new Dependency();
            added.setGroupId(companion.groupId());
            added.setArtifactId(companion.artifactId());
            added.setVersion(companion.version());
            added.setScope("provided");
            model.getDependencies().add(added);
        }
        writeModel(projectDir, model);
    }

    /**
     * Removes from {@code projectDir/pom.xml} every declared plugin that another declared plugin already brings,
     * and answers what was removed, each with the plugin that brings it.
     *
     * <p><b>Why a direct entry must go.</b> Maven's nearest-wins makes the pom's own entry beat the version the
     * bringing plugin was built against, and the failure is a linkage error inside that plugin. A template that
     * declares {@code botmaker-plugin-basics} gains the SDK, which brings basics itself, and the template's entry
     * would then pin basics where the SDK never was. The umbrella states the rule; this applies it, for any
     * plugin, after every install (2026-10-01).
     *
     * <p>Plugins only, told apart by the service file in their jar ({@link #declaresPlugin}): a library the bot
     * pins on purpose is the user's to pin. Each declared plugin's tree is collected on its own, so Maven's
     * conflict resolution over the whole pom (which keeps the direct entry and drops the deeper one) cannot hide
     * the edge. Best-effort: a plugin whose tree cannot be collected brings nothing, so nothing is removed for it.
     * May block on the network — call it off the FX thread.
     */
    public static List<Shadowed> dropShadowedPlugins(Path projectDir) throws IOException {
        Model model = requireModel(projectDir);
        List<Dependency> plugins = new ArrayList<>();
        for (Dependency d : model.getDependencies()) {
            String scope = d.getScope() == null ? "compile" : d.getScope();
            if (!"compile".equals(scope) && !"runtime".equals(scope)) continue;
            // A template pins its plugin as ${botmaker.basics.version}; a resolver handed the placeholder
            // resolves nothing, and the plugin would never be seen.
            resolveArtifact(model, d.getGroupId(), d.getArtifactId(), "", versionOf(model, d))
                    .filter(MavenService::declaresPlugin).ifPresent(jar -> plugins.add(d));
        }
        if (plugins.size() < 2) return List.of();

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = newSession(system);
        List<RemoteRepository> repositories = buildRemoteRepositories(model);
        Map<String, Set<String>> brings = new LinkedHashMap<>();
        for (Dependency plugin : plugins) {
            Set<String> below = new java.util.HashSet<>();
            CollectRequest request = new CollectRequest();
            request.setRoot(new org.eclipse.aether.graph.Dependency(new DefaultArtifact(
                    plugin.getGroupId(), plugin.getArtifactId(), "", "jar", versionOf(model, plugin)), "compile"));
            request.setRepositories(repositories);
            try {
                collectBelow(system.collectDependencies(session, request).getRoot(), below, true);
            } catch (org.eclipse.aether.collection.DependencyCollectionException e) {
                if (e.getResult() != null && e.getResult().getRoot() != null) {
                    collectBelow(e.getResult().getRoot(), below, true);
                }
            }
            brings.put(coordinate(plugin), below);
        }

        List<Shadowed> removed = shadowed(brings);
        if (removed.isEmpty()) return removed;
        Set<String> gone = new java.util.HashSet<>();
        for (Shadowed s : removed) gone.add(s.coordinate());
        model.getDependencies().removeIf(d -> gone.contains(coordinate(d)));
        writeModel(projectDir, model);
        return removed;
    }

    /** A declared plugin {@link #dropShadowedPlugins} removed, and the declared plugin that brings it. */
    public record Shadowed(String coordinate, String broughtBy) {

        /** One sentence naming what was removed and why, or {@code ""} for nothing. */
        public static String sentence(List<Shadowed> removed) {
            if (removed == null || removed.isEmpty()) return "";
            return removed.stream()
                    .map(s -> artifact(s.coordinate()) + " is brought by " + artifact(s.broughtBy())
                            + ", so its own entry was removed.")
                    .collect(Collectors.joining(" "));
        }

        private static String artifact(String coordinate) {
            return coordinate.substring(coordinate.indexOf(':') + 1);
        }
    }

    /**
     * Which of {@code brings}' keys another key brings: {@code brings} maps each declared plugin's
     * {@code groupId:artifactId} to every coordinate in its own tree. Never removes both of two plugins that
     * claim to bring each other, which no real pom can produce but a broken one might. Pure.
     */
    static List<Shadowed> shadowed(Map<String, Set<String>> brings) {
        List<Shadowed> out = new ArrayList<>();
        Set<String> removed = new java.util.HashSet<>();
        for (String plugin : brings.keySet()) {
            for (Map.Entry<String, Set<String>> other : brings.entrySet()) {
                if (other.getKey().equals(plugin) || removed.contains(other.getKey())) continue;
                if (other.getValue().contains(plugin)) {
                    out.add(new Shadowed(plugin, other.getKey()));
                    removed.add(plugin);
                    break;
                }
            }
        }
        return out;
    }

    private static void collectBelow(org.eclipse.aether.graph.DependencyNode node, Set<String> out, boolean root) {
        if (node == null) return;
        if (!root && node.getArtifact() != null) {
            out.add(node.getArtifact().getGroupId() + ":" + node.getArtifact().getArtifactId());
        }
        for (org.eclipse.aether.graph.DependencyNode child : node.getChildren()) collectBelow(child, out, false);
    }

    /** {@code d}'s version, with a whole-{@code ${property}} pin read from the pom's properties. */
    private static String versionOf(Model model, Dependency d) {
        String v = d.getVersion();
        if (v == null) return null;
        return propertyOf(v).map(model.getProperties()::getProperty).orElse(v);
    }

    private static String coordinate(Dependency d) {
        return d.getGroupId() + ":" + d.getArtifactId();
    }

    /**
     * Declares {@code library} at {@code compile} scope unless the pom already names its coordinate, and
     * answers whether it wrote. A declared version is never moved: that is Manage Libraries' question.
     */
    public static boolean declareIfAbsent(Path projectDir, UserLibrary library) throws IOException {
        Model model = requireModel(projectDir);
        boolean present = model.getDependencies().stream()
                .anyMatch(d -> sameArtifact(d, library.groupId(), library.artifactId()));
        if (present) return false;
        Dependency dep = new Dependency();
        dep.setGroupId(library.groupId());
        dep.setArtifactId(library.artifactId());
        dep.setVersion(library.version());
        model.getDependencies().add(dep);
        writeModel(projectDir, model);
        return true;
    }

    /**
     * Removes {@code groupId:artifactId} from {@code projectDir/pom.xml}, with the
     * {@code editorDependencies} {@link #installPlugin} declared alongside it.
     *
     * <p>The mirror image, and it has to be: leaving a {@code provided} web server behind in the pom of a
     * project that no longer names the plugin that needed it is a dependency nothing in the project explains.
     *
     * <p><b>The list is the caller's here too, and that is the one asymmetry worth knowing.</b> Install reads
     * it from the entry of the plugin being installed; remove reads it from the entry of the plugin being
     * removed, which is the same entry — so a plugin whose entry has changed its list since install leaves
     * behind whatever it no longer names. That is the honest outcome for a record kept by the plugin rather
     * than by the project, and it is the same shape as a version the user pinned by hand.
     */
    public static void removePlugin(Path projectDir, String groupId, String artifactId,
                                    List<UserLibrary> editorDependencies) throws IOException {
        Model model = requireModel(projectDir);
        model.getDependencies().removeIf(d -> sameArtifact(d, groupId, artifactId));
        for (UserLibrary companion : editorDependencies) {
            model.getDependencies()
                    .removeIf(d -> sameArtifact(d, companion.groupId(), companion.artifactId()));
        }
        writeModel(projectDir, model);
    }

    private static boolean sameArtifact(Dependency d, String groupId, String artifactId) {
        return groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId());
    }

    private static Model requireModel(Path projectDir) throws IOException {
        Model model = readModel(projectDir);
        if (model == null) {
            throw new IOException("No pom.xml found at " + projectDir.resolve("pom.xml"));
        }
        return model;
    }

    private static void writeModel(Path projectDir, Model model) throws IOException {
        try (OutputStream out = Files.newOutputStream(projectDir.resolve("pom.xml"))) {
            new MavenXpp3Writer().write(out, model);
        }
    }

    /**
     * The BotMaker SDK version {@code projectDir/pom.xml} declares — <b>empty when it declares none</b>.
     *
     * <p>It answered {@link #SDK_FALLBACK_VERSION} for a pom naming no SDK until 2026-09-04, which was
     * harmless while every pom Studio wrote named one and became a lie the moment a blank project could
     * exist. The readers of this answer resolve jars with it, offer upgrades against it and print it in an
     * about box; every one of them would have been describing a dependency the project does not have.
     *
     * <p><b>Empty is not an error.</b> A project with no SDK is an ordinary project — it is what New Project
     * now creates — so each caller degrades rather than refusing: no docs, no surface index, no upgrade
     * offered, no version reported.
     */
    public static Optional<String> readSdkVersion(Path projectDir) {
        return readDependencyVersion(projectDir, SDK_GROUP_ID, SDK_ARTIFACT_ID);
    }

    /**
     * The version {@code projectDir/pom.xml} declares for one coordinate — <b>empty when it declares none</b>.
     *
     * <p>The general form of {@link #readSdkVersion}, and the one the project upgrade window asks per row:
     * a plugin is installed because its coordinate is in the pom, so this is the same question for every
     * plugin, plugin #1 included. Empty is not an error anywhere here — a project that does not declare a
     * plugin is an ordinary project, and every caller degrades rather than refusing.
     *
     * <p>A pin written as {@code ${property}} answers the property's value: the template pins its SDK as
     * {@code ${botmaker.sdk.version}} (2026-09-24), and a resolver handed the placeholder text resolves nothing.
     */
    public static Optional<String> readDependencyVersion(Path projectDir, String groupId, String artifactId) {
        Model model = readModel(projectDir);
        if (model == null) return Optional.empty();
        return model.getDependencies().stream()
                .filter(d -> groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId()))
                .map(Dependency::getVersion)
                .filter(v -> v != null && !v.isBlank())
                .map(v -> propertyOf(v).map(model.getProperties()::getProperty).orElse(v))
                .findFirst();
    }

    /** {@code name} for a version written exactly {@code ${name}}, otherwise empty. */
    private static Optional<String> propertyOf(String version) {
        String v = version.strip();
        return v.startsWith("${") && v.endsWith("}") && v.length() > 3
                ? Optional.of(v.substring(2, v.length() - 1))
                : Optional.empty();
    }

    // resolveSdkSourcesJar stood here until 2026-09-28. The API docs read every bound plugin's sources jar
    // now (services/ApiDocsService), through resolveArtifact below.

    /**
     * Resolves the SDK's own (classifier-less) jar for an <em>arbitrary</em> version — not necessarily the
     * one this project pins. That is what {@code services/upgrade/PluginUpgradeService} compares against: answering "what breaks
     * if I move to v2.0.0" means reading v2.0.0's bytecode, which nothing else in Studio ever needs.
     *
     * <p>The project's own pom is still consulted, for its {@code <repositories>} — JitPack is declared
     * there, so a version that has never been resolved on this machine downloads on demand.
     */
    public static Optional<Path> resolveSdkJar(Path projectDir, String version) {
        return resolveSdkArtifact(projectDir, version, "");
    }

    /**
     * The same, for a caller that has <b>no project</b> — {@code ProjectCreator}, which must know what the
     * chosen SDK contains <em>before</em> a pom exists to read repositories from.
     *
     * <p>Resolving against an empty model is not a compromise here: {@link #buildRemoteRepositories} starts
     * from {@link #DEFAULT_REPOSITORIES}, and those are exactly the repositories {@link #blankPomXml} is about to
     * declare. The only thing a project pom adds is a repository the <em>user</em> put there, which by
     * definition a project that does not exist yet has none of.
     */
    public static Optional<Path> resolveSdkJar(String version) {
        return resolveSdkArtifact(new Model(), version, "");
    }

    /**
     * Resolves one BotMaker SDK artifact from the local repo, downloading it via the project pom's
     * repositories if absent. {@code classifier} is {@code ""} for the jar itself, {@code "sources"} for the
     * sources jar. Best-effort: empty when the pom is missing, the artifact cannot be resolved, or offline.
     * May block on the network — call off the FX thread.
     */
    public static Optional<Path> resolveSdkArtifact(Path projectDir, String version, String classifier) {
        return resolveArtifact(projectDir, SDK_GROUP_ID, SDK_ARTIFACT_ID, classifier, version);
    }

    /**
     * The same for <b>any</b> coordinate — what the project upgrade window resolves a plugin's two jars with.
     *
     * <p>The SDK-named entry points above are two-line delegations to this since 2026-09-15. They stay
     * because their callers are about creating and repairing a project that names the SDK, not about an
     * upgrade, and have no coordinate to hand.
     *
     * <p>The project's own pom is read for its {@code <repositories>} — JitPack is declared there — so a
     * version that has never been resolved on this machine downloads on demand. Best-effort throughout:
     * empty for a missing pom, a blank version, an unresolvable artifact or no network. May block — call it
     * off the FX thread.
     */
    public static Optional<Path> resolveArtifact(Path projectDir, String groupId, String artifactId,
                                                 String classifier, String version) {
        Model model = readModel(projectDir);
        if (model == null) return Optional.empty();
        return resolveArtifact(model, groupId, artifactId, classifier, version);
    }

    private static Optional<Path> resolveSdkArtifact(Model model, String version, String classifier) {
        return resolveArtifact(model, SDK_GROUP_ID, SDK_ARTIFACT_ID, classifier, version);
    }

    private static Optional<Path> resolveArtifact(Model model, String groupId, String artifactId,
                                                  String classifier, String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        // A pin read off the pom as written (readDeclaredLibraries) is still ${botmaker.sdk.version} text here;
        // the pom it came from says what it means (2026-10-01: the gamebot's SDK jar never resolved).
        String pinned = propertyOf(version).map(model.getProperties()::getProperty).orElse(version);
        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, "jar", pinned.trim());

        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = newSession(system);

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(buildRemoteRepositories(model));
        try {
            ArtifactResult result = system.resolveArtifact(session, request);
            var file = result.getArtifact().getFile();
            return file != null ? Optional.of(file.toPath()) : Optional.empty();
        } catch (ArtifactResolutionException e) {
            System.err.println("Could not resolve artifact " + artifact + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Replaces the user-added libraries with {@code libs}, leaving the SDK version unchanged — and adding
     * none to a project that declares no SDK.
     *
     * @see #writeUserLibraries(Path, List, String)
     */
    public static void writeUserLibraries(Path projectDir, List<UserLibrary> libs) throws IOException {
        writeUserLibraries(projectDir, libs, readSdkVersion(projectDir).orElse(""));
    }

    /**
     * Replaces the user-added libraries in {@code projectDir/pom.xml} with {@code libs} and pins the
     * BotMaker SDK to {@code sdkVersion}, leaving the other built-in dependencies, repositories and
     * properties untouched. The pom is read, mutated and written back in place (no regeneration).
     *
     * <p><b>A blank {@code sdkVersion} pins nothing, and does not add the SDK.</b>
     * {@link #setManagedDependencyVersion} only ever edits a dependency that is already declared, so a blank
     * project keeps naming no plugin however often its libraries are edited — which is what makes
     * {@code LibraryService} usable on one at all.
     */
    public static void writeUserLibraries(Path projectDir, List<UserLibrary> libs, String sdkVersion)
            throws IOException {
        Model model = requireModel(projectDir);

        // Keep the built-in deps, drop the previous user deps, then append the new ones.
        List<Dependency> kept = model.getDependencies().stream()
                .filter(MavenService::isDefaultDependency)
                .collect(Collectors.toList());
        for (UserLibrary lib : libs) {
            Dependency dep = new Dependency();
            dep.setGroupId(lib.groupId());
            dep.setArtifactId(lib.artifactId());
            dep.setVersion(lib.version());
            kept.add(dep);
        }
        model.setDependencies(kept);

        if (sdkVersion != null && !sdkVersion.isBlank()) {
            setManagedDependencyVersion(model, SDK_GROUP_ID, SDK_ARTIFACT_ID, sdkVersion.trim());
        }

        writeModel(projectDir, model);
    }

    /**
     * Re-pins every coordinate in {@code versionsByCoordinate} — {@code "groupId:artifactId"} to the version
     * it should now declare — in one read and one write of the pom.
     *
     * <p><b>One write, several rows.</b> That is the whole reason it takes a map: the project upgrade window
     * moves several plugins in one pass, and a pom rewritten once per row would leave the project in a state
     * no row describes if the second write failed. Everything else about the pom — the other dependencies,
     * the repositories, the properties — is untouched, exactly as {@link #installPlugin} leaves them.
     *
     * <p><b>It only ever re-versions what the pom already declares</b>, the same rule
     * {@link #setManagedDependencyVersion} has always had. A coordinate that is not there is silently
     * skipped rather than added: the window's rows are built from the pom, so a missing one means the pom
     * moved underneath the window, and quietly acquiring a dependency is the wrong answer to that.
     *
     * <p>A blank version is skipped for the same reason a blank {@code sdkVersion} pins nothing.
     */
    public static void setDependencyVersions(Path projectDir, Map<String, String> versionsByCoordinate)
            throws IOException {
        Model model = requireModel(projectDir);
        versionsByCoordinate.forEach((coordinate, version) -> {
            if (version == null || version.isBlank()) return;
            int colon = coordinate.indexOf(':');
            if (colon <= 0 || colon == coordinate.length() - 1) return;
            setManagedDependencyVersion(model, coordinate.substring(0, colon),
                    coordinate.substring(colon + 1), version.trim());
        });
        writeModel(projectDir, model);
    }

    /**
     * Sets the version of the matching dependency already present in the model (no-op if absent). A pin
     * written as {@code ${property}} the pom declares moves the property, so the pom keeps its spelling.
     */
    private static void setManagedDependencyVersion(Model model, String groupId, String artifactId,
                                                    String version) {
        for (Dependency d : model.getDependencies()) {
            if (groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId())) {
                Optional<String> property = d.getVersion() == null ? Optional.empty() : propertyOf(d.getVersion())
                        .filter(model.getProperties()::containsKey);
                if (property.isPresent()) model.getProperties().setProperty(property.get(), version);
                else d.setVersion(version);
            }
        }
    }

    private static Model readModel(Path projectDir) {
        Path pomPath = projectDir.resolve("pom.xml");
        if (!Files.exists(pomPath)) return null;
        try (InputStream in = Files.newInputStream(pomPath)) {
            return new MavenXpp3Reader().read(in);
        } catch (Exception e) {
            System.err.println("Failed to read pom.xml: " + e.getMessage());
            return null;
        }
    }
}
