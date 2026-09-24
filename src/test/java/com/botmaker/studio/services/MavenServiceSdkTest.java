package com.botmaker.studio.services;

import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.UserLibrary;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pom shapes, and the classification that has to keep recognising both.
 *
 * <p>Since 2026-09-04 a project Studio creates is <b>blank</b> — a test framework and nothing else — while
 * every project made before that date, and every one unpacked from a gallery template, carries the SDK plus
 * the entries that serve its plugin half. Both shapes are live on one machine at once, which is what makes
 * the classification cases below the load-bearing ones here.
 *
 * <p>Since 2026-09-06 there is a <b>third</b> shape and it is the same argument one turn further on: a bot
 * pom no longer declares the toolkit or JavaFX, so a pom that <em>does</em> is an older pom rather than a
 * hand-edited one, and it has to keep classifying as built in.
 */
class MavenServiceSdkTest {

    @TempDir
    Path projectsRoot;

    // ---- the two shapes -------------------------------------------------------------------------------

    @Test
    void aBlankPomNamesNoPluginAtAll() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writeBlankPom(projectDir, cfg);

        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), groupArtifacts(projectDir));
        assertTrue(MavenService.readSdkVersion(projectDir).isEmpty(),
                "a pom naming no SDK must answer empty, not the fallback");
    }

    /**
     * A blank project still declares the repositories, which is what lets it <em>become</em> a bot project
     * later without anybody hand-editing XML: Manage Plugins adds the SDK as an ordinary dependency and it
     * resolves from JitPack.
     */
    @Test
    void aBlankPomKeepsTheRepositoriesSoAPluginCanBeAddedLater() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        MavenService.writeBlankPom(cfg.projectPath(), cfg);

        List<String> repos = readModel(cfg.projectPath()).getRepositories().stream()
                .map(org.apache.maven.model.Repository::getId).toList();
        assertTrue(repos.contains("jitpack"), repos.toString());
        assertTrue(repos.contains("central"), repos.toString());
    }

    @Test
    void writePomPinsSdkVersionAndReadsItBack() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writePom(projectDir, cfg, "1.0.5");

        assertEquals("1.0.5", MavenService.readSdkVersion(projectDir).orElseThrow());
    }

    @Test
    void blankSdkVersionFallsBackToConstant() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();

        MavenService.writePom(projectDir, cfg, "");

        assertEquals(MavenService.SDK_FALLBACK_VERSION,
                MavenService.readSdkVersion(projectDir).orElseThrow());
    }

    // ---- the classification, which is where the data loss would be ------------------------------------

    /**
     * <b>The hazard this whole file exists for.</b> {@code DEFAULT_GROUP_ARTIFACTS} classifies the pom of
     * <em>any</em> project, not only of one created today. Narrowed to the blank list, every entry a bot
     * project carries — the SDK, the toolkit, JavaFX, Javalin, ZXing, JNA, Jackson — would read as a user
     * library: offered for deletion in Manage Libraries, and then genuinely dropped by
     * {@code writeUserLibraries}, which keeps what it recognises and discards the rest.
     */
    @Test
    void aBotPomsBuiltInsAreNeverUserLibraries() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.6");

        assertEquals(List.of(), MavenService.readUserLibraries(projectDir),
                "a freshly written bot pom has no user libraries at all");
    }

    /** The same fact from the write side: editing libraries on a bot project keeps all nine built-ins. */
    @Test
    void editingLibrariesOnABotProjectPreservesEveryBuiltIn() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.6");
        List<String> before = groupArtifacts(projectDir);

        MavenService.writeUserLibraries(projectDir, List.of(new UserLibrary("com.example", "widget", "2.3.4")));

        List<String> after = groupArtifacts(projectDir);
        for (String built : before) {
            assertTrue(after.contains(built), built + " was dropped from the pom");
        }
        assertTrue(after.contains("com.example:widget"));
    }

    /** And a blank project does not acquire an SDK by having its libraries edited. */
    @Test
    void editingLibrariesOnABlankProjectDoesNotAddAnSdk() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);

        MavenService.writeUserLibraries(projectDir, List.of(new UserLibrary("com.example", "widget", "2.3.4")));

        assertTrue(MavenService.readSdkVersion(projectDir).isEmpty());
        assertFalse(groupArtifacts(projectDir).contains(
                MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID));
    }

    // ---- user libraries -------------------------------------------------------------------------------

    @Test
    void writeUserLibrariesUpdatesSdkAndKeepsUserLibs() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.6");

        UserLibrary userLib = new UserLibrary("com.example", "widget", "2.3.4");
        MavenService.writeUserLibraries(projectDir, List.of(userLib), "1.0.4");

        // SDK re-versioned...
        assertEquals("1.0.4", MavenService.readSdkVersion(projectDir).orElseThrow());

        // ...user libs preserved, and the SDK is NOT treated as a user library.
        List<UserLibrary> userLibs = MavenService.readUserLibraries(projectDir);
        assertEquals(List.of(userLib), userLibs);
        assertFalse(userLibs.stream().anyMatch(l -> l.artifactId().equals(MavenService.SDK_ARTIFACT_ID)));
    }

    @Test
    void writeUserLibrariesTwoArgPreservesSdkVersion() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.0.3");

        MavenService.writeUserLibraries(projectDir, List.of(new UserLibrary("g", "a", "1")));

        assertEquals("1.0.3", MavenService.readSdkVersion(projectDir).orElseThrow());
        assertTrue(MavenService.readUserLibraries(projectDir).stream()
                .anyMatch(l -> l.groupArtifact().equals("g:a")));
    }

    // ---- installing a plugin --------------------------------------------------------------------------

    /** What the SDK's registry entry declares, which is where this list lives since 2026-09-06. */
    private static final List<UserLibrary> SDK_EDITOR_DEPENDENCIES = List.of(
            new UserLibrary("io.javalin", "javalin", "6.7.0"),
            new UserLibrary("com.google.zxing", "core", "3.5.3"));

    /**
     * The defect this holds shut, and it was silent in the worst way: a blank project that installed the SDK
     * through <b>Manage Plugins</b> got the SDK jar and none of the {@code provided} entries its plugin
     * half needs, so {@code SdkPlugin} could not resolve {@code AbstractStudioPlugin},
     * {@code ServiceLoader} failed, {@code PluginLoader} caught it — correctly — and the user got an empty
     * palette and one line on stderr. Nothing failed to compile at any point.
     *
     * <p>The list is the caller's now, out of the plugin's own registry entry, so this test hands it in the
     * way {@code ManagePluginsDialog} does.
     */
    @Test
    void installingAPluginDeclaresTheEditorDependenciesItWasGiven() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);

        MavenService.installPlugin(projectDir, new UserLibrary(
                        MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.6"),
                SDK_EDITOR_DEPENDENCIES);

        List<String> after = groupArtifacts(projectDir);
        assertTrue(after.contains(MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID));
        for (String companion : List.of("io.javalin:javalin", "com.google.zxing:core")) {
            assertTrue(after.contains(companion), companion + " was not declared");
        }
        // And the three that stopped being companions on 2026-09-06. The toolkit is `compile` in the SDK's
        // own pom, so it arrives transitively — and a direct entry here would BEAT it by nearest-wins,
        // pinning the bot to a toolkit its SDK was never built against. JavaFX is parent-first in
        // PluginLoader, so the host's own is what the plugin links whatever this pom says.
        for (String gone : List.of("com.github.LiQiyeDev:botmaker-plugin-toolkit",
                "org.openjfx:javafx-controls", "org.openjfx:javafx-graphics")) {
            assertFalse(after.contains(gone), gone + " must not be declared by an install");
        }
        // provided, never compile: the bot itself must link neither when it runs.
        for (Dependency d : readModel(projectDir).getDependencies()) {
            if (d.getArtifactId().equals("core") || d.getArtifactId().equals("javalin")) {
                assertEquals("provided", d.getScope(), d.getArtifactId() + " must be provided");
            }
        }
        assertEquals("1.1.6", MavenService.readSdkVersion(projectDir).orElseThrow());
    }

    /**
     * Pressing <i>Install</i> on a row that already reads installed must not write the coordinate twice.
     *
     * <p>It did, until 2026-09-05, and by two independent routes: the dialog could not tell the SDK was
     * installed (it asked {@code readUserLibraries}, which classes the SDK as built in), and the writer it
     * used kept the pom's built-ins <em>and</em> appended the list it was handed.
     */
    @Test
    void installingTwiceLeavesExactlyOneDependency() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);
        UserLibrary sdk = new UserLibrary(
                MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.6");

        MavenService.installPlugin(projectDir, sdk, SDK_EDITOR_DEPENDENCIES);
        MavenService.installPlugin(projectDir, new UserLibrary(
                        MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.7"),
                SDK_EDITOR_DEPENDENCIES);

        List<String> sdkRows = groupArtifacts(projectDir).stream()
                .filter(ga -> ga.equals(MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID))
                .toList();
        assertEquals(1, sdkRows.size(), "the SDK was declared more than once");
        assertEquals("1.1.7", MavenService.readSdkVersion(projectDir).orElseThrow(),
                "re-installing must move the version, not add a second row");
        // And the companions are a floor, not a re-write: still one of each.
        assertEquals(1, groupArtifacts(projectDir).stream()
                .filter(ga -> ga.equals("io.javalin:javalin")).count());
    }

    /** Removing the SDK takes back what installing it declared — the pom must not keep orphans. */
    @Test
    void removingTheSdkTakesItsCompanionsWithIt() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);
        MavenService.installPlugin(projectDir, new UserLibrary(
                        MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "1.1.6"),
                SDK_EDITOR_DEPENDENCIES);

        MavenService.removePlugin(projectDir, MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID,
                SDK_EDITOR_DEPENDENCIES);

        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), groupArtifacts(projectDir));
        assertTrue(MavenService.readSdkVersion(projectDir).isEmpty());
    }

    /**
     * A plugin whose entry names nothing gets nothing — which is most plugins. One generated by
     * {@code botmaker-plugin-archetype} declares its toolkit at {@code compile} scope, so it is transitive
     * and needs nothing from this pom.
     */
    @Test
    void installingAPluginWithNoEditorDependenciesDeclaresOnlyThatPlugin() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);

        MavenService.installPlugin(projectDir,
                new UserLibrary("com.example", "shiny-plugin", "0.1.0"), List.of());

        assertEquals(List.of("org.junit.jupiter:junit-jupiter", "com.example:shiny-plugin"),
                groupArtifacts(projectDir));
    }

    /**
     * <b>And a plugin that is not the SDK gets exactly the same service.</b> This is the whole of Phase 3:
     * the branch that declared companions used to be {@code if (isSdk(…))} over a list written in Studio's
     * own source, so a second plugin needing an {@code optional} dependency of its own had no way to ask.
     * The list is the plugin's registry entry now and this method never learns whose it is.
     */
    @Test
    void aThirdPartyPluginGetsItsOwnEditorDependencies() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writeBlankPom(projectDir, cfg);
        UserLibrary plugin = new UserLibrary("com.example", "shiny-plugin", "0.1.0");
        List<UserLibrary> companions = List.of(new UserLibrary("org.example", "shiny-server", "2.0.0"));

        MavenService.installPlugin(projectDir, plugin, companions);

        assertTrue(groupArtifacts(projectDir).contains("org.example:shiny-server"));
        for (Dependency d : readModel(projectDir).getDependencies()) {
            if (d.getArtifactId().equals("shiny-server")) {
                assertEquals("provided", d.getScope(), "an editor dependency is never a bot's dependency");
            }
        }
        // And it is not a user library, so Manage Libraries neither offers it for deletion nor discards it.
        assertTrue(MavenService.readUserLibraries(projectDir).stream()
                .noneMatch(l -> l.groupArtifact().equals("org.example:shiny-server")));

        MavenService.removePlugin(projectDir, plugin.groupId(), plugin.artifactId(), companions);

        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), groupArtifacts(projectDir));
    }

    /** Every dependency, defaults included — the question Manage Plugins asks. */
    @Test
    void readDeclaredLibrariesSeesTheBuiltInsThatReadUserLibrariesHides() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.1.6");

        List<String> declared = MavenService.readDeclaredLibraries(projectDir).stream()
                .map(UserLibrary::groupArtifact).toList();

        assertTrue(declared.contains(MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID));
        assertFalse(MavenService.readUserLibraries(projectDir).stream()
                        .anyMatch(l -> l.artifactId().equals(MavenService.SDK_ARTIFACT_ID)),
                "the narrow reader must keep its meaning");
    }

    // ---- what a bot pom declares, and what it deliberately does not (2026-09-06) -----------------------

    /**
     * A bot pom names the SDK and the libraries a bot's own source may use — and no toolkit, no JavaFX and,
     * since Phase 3, none of the SDK plugin's editor dependencies either.
     *
     * <p>Those last two, javalin and zxing, are real and are declared by {@link MavenService#installPlugin}
     * from the plugin's registry entry. What this pom is for is the <em>bot</em>: {@code writePom} is
     * {@code ProjectRepair} rebuilding a lost pom from what it can see on disk, and what a plugin needs in
     * the editor is not on disk — it is in an entry only the network has. <b>The accepted cost, stated
     * rather than glossed:</b> a repaired project keeps its SDK and loses the pilot's server until the user
     * visits Manage Plugins, which is one visit and no lost work. The alternative is Studio holding one
     * plugin's list again, which is the privilege Phase 3 removed.
     *
     * <p>Both absences are load-bearing and neither is tidiness. {@code botmaker-sdk} declares
     * {@code botmaker-plugin-toolkit} at plain {@code compile} scope, so it is <b>transitive</b> and arrives
     * with the SDK; a direct entry here sits at depth 1 and Maven's <b>nearest-wins</b> mediation makes it
     * beat the SDK's own pin at depth 2 — so a bot could compile the editor against a toolkit its pinned SDK
     * was never built against, and fail at runtime with {@code NoSuchMethodError}. JavaFX is in
     * {@code PluginLoader.PARENT_FIRST}, so every plugin is handed the <em>host's</em> classes whatever this
     * pom resolves; the two entries were three downloads nothing ever linked a class from.
     *
     * <p>What stays is the rule, one word wider than it was: this list holds what a <b>bot's own source</b>
     * may name, and nothing that is here on a plugin's behalf.
     */
    @Test
    void aBotPomNamesNoToolkitAndNoJavaFx() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.1.6");

        List<String> declared = groupArtifacts(projectDir);
        for (String named : List.of(MavenService.SDK_GROUP_ID + ":" + MavenService.SDK_ARTIFACT_ID,
                "net.java.dev.jna:jna", "org.junit.jupiter:junit-jupiter")) {
            assertTrue(declared.contains(named), named + " is missing from a bot pom");
        }
        for (String absent : List.of("com.github.LiQiyeDev:botmaker-plugin-toolkit",
                "org.openjfx:javafx-controls", "org.openjfx:javafx-graphics",
                "io.javalin:javalin", "com.google.zxing:core")) {
            assertFalse(declared.contains(absent), absent + " must not be written into a bot pom");
        }
    }

    /**
     * A pom written <em>before</em> 2026-09-06 still classifies its toolkit and JavaFX as built in.
     *
     * <p>This is the half that a deletion alone would get wrong, and it would be a data-loss bug rather than
     * an untidy one: {@code isDefaultDependency} classifies the pom of <em>any</em> project, not only of one
     * created today. A coordinate dropped from every list reads as a <b>user library</b> — offered for
     * deletion in Manage Libraries, and then genuinely discarded by {@code writeUserLibraries}, which keeps
     * what it recognises and throws the rest away. A dependency stops being written long before the last pom
     * that has it is opened, which is what {@code RETIRED_GROUP_ARTIFACTS} is for.
     */
    @Test
    void aPomWrittenBeforeTheToolkitLeftStillReadsItAsBuiltIn() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.1.6");

        // The shape every project created before 2026-09-06 has on disk.
        Model model = readModel(projectDir);
        for (String[] retired : new String[][] {
                {"com.github.LiQiyeDev", "botmaker-plugin-toolkit", "0.0.5"},
                {"org.openjfx", "javafx-controls", "21"},
                {"org.openjfx", "javafx-graphics", "21"}}) {
            Dependency dependency = new Dependency();
            dependency.setGroupId(retired[0]);
            dependency.setArtifactId(retired[1]);
            dependency.setVersion(retired[2]);
            dependency.setScope("provided");
            model.getDependencies().add(dependency);
        }
        writeModel(projectDir, model);

        assertEquals(List.of(), MavenService.readUserLibraries(projectDir),
                "an older bot pom's toolkit and JavaFX must not read as user libraries");

        // And the write side: editing libraries keeps them rather than discarding what it does not write.
        MavenService.writeUserLibraries(projectDir,
                List.of(new UserLibrary("com.example", "widget", "2.3.4")));
        List<String> after = groupArtifacts(projectDir);
        for (String kept : List.of("com.github.LiQiyeDev:botmaker-plugin-toolkit",
                "org.openjfx:javafx-controls", "org.openjfx:javafx-graphics")) {
            assertTrue(after.contains(kept), kept + " was dropped from an older bot's pom");
        }
    }

    // ---- re-pinning several plugins at once (the project upgrade window's write) -----------------------

    /**
     * The window moves several rows under one snapshot, so the pom moves once. Everything it does not name
     * stays exactly as it was — which is the difference between this and {@code writeUserLibraries}, whose
     * job is to <em>replace</em> the user-added list.
     */
    @Test
    void setDependencyVersionsMovesEveryNamedCoordinateInOneWrite() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.1.5");
        MavenService.installPlugin(projectDir,
                new UserLibrary("com.example", "shapes", "0.1.0"), List.of());

        MavenService.setDependencyVersions(projectDir, java.util.Map.of(
                "com.github.LiQiyeDev:botmaker-sdk", "1.2.0",
                "com.example:shapes", "0.2.0"));

        assertEquals("1.2.0", MavenService.readSdkVersion(projectDir).orElse(""));
        assertEquals("0.2.0",
                MavenService.readDependencyVersion(projectDir, "com.example", "shapes").orElse(""));
        assertTrue(groupArtifacts(projectDir).contains("org.junit.jupiter:junit-jupiter"),
                "a re-pin must leave every other dependency the pom declares alone");
    }

    /**
     * A coordinate the pom does not declare is skipped, never added. The window's rows are built <em>from</em>
     * the pom, so a name it does not recognise means the pom moved underneath the window — and quietly
     * acquiring a dependency is the wrong answer to that.
     */
    @Test
    void setDependencyVersionsAddsNothingAndSkipsABlankVersion() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.1.5");
        List<String> before = groupArtifacts(projectDir);

        MavenService.setDependencyVersions(projectDir, java.util.Map.of(
                "com.example:never-installed", "9.9.9",
                "com.github.LiQiyeDev:botmaker-sdk", "   "));

        assertEquals(before, groupArtifacts(projectDir));
        assertEquals("1.1.5", MavenService.readSdkVersion(projectDir).orElse(""),
                "a blank version pins nothing, the same rule writeUserLibraries has always had");
    }

    /**
     * The template pins its SDK as {@code ${botmaker.sdk.version}} so the umbrella can build it against the
     * reactor. A project copied from it must read the property's value, and an upgrade must move the property
     * rather than overwrite the placeholder with a literal.
     */
    @Test
    void aPropertyPinIsReadThroughAndMovedInPlace() throws Exception {
        ProjectConfig cfg = ProjectConfig.forProject("TestBot", projectsRoot);
        Path projectDir = cfg.projectPath();
        MavenService.writePom(projectDir, cfg, "1.1.5");
        Model model = readModel(projectDir);
        model.getProperties().setProperty("botmaker.sdk.version", "1.1.14");
        model.getDependencies().stream()
                .filter(d -> "botmaker-sdk".equals(d.getArtifactId()))
                .forEach(d -> d.setVersion("${botmaker.sdk.version}"));
        writeModel(projectDir, model);

        assertEquals("1.1.14", MavenService.readSdkVersion(projectDir).orElse(""));

        MavenService.setDependencyVersions(projectDir, java.util.Map.of("com.github.LiQiyeDev:botmaker-sdk", "2.0.0"));

        Model after = readModel(projectDir);
        assertEquals("2.0.0", after.getProperties().getProperty("botmaker.sdk.version"));
        assertTrue(after.getDependencies().stream().anyMatch(d -> "botmaker-sdk".equals(d.getArtifactId())
                && "${botmaker.sdk.version}".equals(d.getVersion())), "the placeholder was overwritten");
        assertEquals("2.0.0", MavenService.readSdkVersion(projectDir).orElse(""));
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static void writeModel(Path projectDir, Model model) throws Exception {
        try (java.io.OutputStream out = Files.newOutputStream(projectDir.resolve("pom.xml"))) {
            new org.apache.maven.model.io.xpp3.MavenXpp3Writer().write(out, model);
        }
    }

    private static List<String> groupArtifacts(Path projectDir) throws Exception {
        return readModel(projectDir).getDependencies().stream()
                .map(d -> d.getGroupId() + ":" + d.getArtifactId())
                .toList();
    }

    private static Model readModel(Path projectDir) throws Exception {
        try (InputStream in = Files.newInputStream(projectDir.resolve("pom.xml"))) {
            return new MavenXpp3Reader().read(in);
        }
    }
}
