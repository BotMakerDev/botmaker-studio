package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.MavenService.LocalPluginBuild;
import com.botmaker.studio.sharing.PluginRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the project upgrade window is a table of: the pom's own dependencies, told apart by how much is
 * known about each.
 *
 * <p>Every case here is offline by construction — {@code of} is handed what the registry said, what
 * {@code ~/.m2} holds and a predicate. That is not a testing convenience but the shape of the class: a row
 * builder that fetched would make an unreachable registry an empty table instead of a table of rows saying
 * <i>could not check</i>.
 */
class InstalledPluginTest {

    private static final UserLibrary SDK =
            new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "1.1.5");
    private static final UserLibrary BASICS =
            new UserLibrary("com.github.LiQiyeDev", "botmaker-plugin-basics", "0.1.0");
    private static final UserLibrary JACKSON =
            new UserLibrary("com.fasterxml.jackson.core", "jackson-databind", "2.17.0");

    private static PluginRegistry.Plugin entry(String coordinate, String name, String verified,
                                               List<String> editorDependencies) {
        return new PluginRegistry.Plugin(coordinate, name, coordinate, "LiQiyeDev/x", "", List.of(), "",
                List.of(), editorDependencies, verified, "");
    }

    @Test
    void aDependencyTheRegistryListsIsARegistryRowAtItsVerifiedVersion() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(SDK),
                List.of(entry("com.github.LiQiyeDev:botmaker-sdk", "BotMaker SDK", "1.1.6", List.of())),
                List.of(), lib -> false);

        assertEquals(1, rows.size());
        InstalledPlugin row = rows.getFirst();
        assertEquals(InstalledPlugin.Source.REGISTRY, row.source());
        assertEquals("BotMaker SDK", row.displayName());
        assertEquals("1.1.5", row.installed());
        // The verified version, not JitPack's newest: the newest tag may be one nothing has ever loaded,
        // which is the rule Manage Plugins already installs by.
        assertEquals("1.1.6", row.available());
        assertTrue(row.canChange());
    }

    @Test
    void aDependencyNothingAccountsForIsNoRowAtAll() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(SDK, JACKSON),
                List.of(entry("com.github.LiQiyeDev:botmaker-sdk", "BotMaker SDK", "1.1.6", List.of())),
                List.of(), lib -> false);

        assertEquals(List.of("com.github.LiQiyeDev:botmaker-sdk"),
                rows.stream().map(InstalledPlugin::coordinate).toList(),
                "an ordinary library is not a plugin and must not be offered an upgrade here");
    }

    @Test
    void aDeclaredPluginNoRegistryKnowsIsUnlistedWithNothingToMoveTo() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(BASICS), List.of(), List.of(), lib -> true);

        InstalledPlugin row = rows.getFirst();
        assertEquals(InstalledPlugin.Source.UNLISTED, row.source());
        assertEquals("botmaker-plugin-basics", row.displayName(), "the coordinate is all that is known");
        assertEquals("", row.available());
        // Blank is the truthful answer, not a failure: nothing here reaches the network, and the window
        // fills it in once it has asked.
        assertFalse(row.canChange());
        assertTrue(row.withAvailable("0.2.0").canChange());
    }

    @Test
    void aLocalBuildWinsOverTheRegistryAndKeepsItsEditorDependencies() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(SDK),
                List.of(entry("com.github.LiQiyeDev:botmaker-sdk", "BotMaker SDK", "1.1.6",
                        List.of("org.openjfx:javafx-controls:21"))),
                List.of(new LocalPluginBuild("com.github.LiQiyeDev", "botmaker-sdk", "0.0.0-SNAPSHOT")),
                lib -> false);

        InstalledPlugin row = rows.getFirst();
        assertEquals(InstalledPlugin.Source.LOCAL_BUILD, row.source());
        assertEquals("0.0.0-SNAPSHOT", row.available());
        // A developer's own build of a plugin needs exactly what the published one needs, which is why the
        // entry's list survives the local build winning the version.
        assertEquals(List.of(new UserLibrary("org.openjfx", "javafx-controls", "21")),
                row.editorDependencies());
    }

    @Test
    void theRowsKeepTheOrderThePomDeclaresThem() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(BASICS, SDK), List.of(), List.of(), lib -> true);

        assertEquals(List.of("com.github.LiQiyeDev:botmaker-plugin-basics",
                        "com.github.LiQiyeDev:botmaker-sdk"),
                rows.stream().map(InstalledPlugin::coordinate).toList());
    }

    /**
     * The one hazard two plugins introduce that one never could — and the reason it is answered by a set of
     * names rather than by a heuristic. See {@link PluginUpgradeService#usesIn}.
     */
    @Test
    void aSimpleNameTwoPluginsBothDeclareIsAmbiguousAndOneOnlyOneDeclaresIsNot() {
        Set<String> clashing = InstalledPlugin.ambiguousTypeNames(Map.of(
                "com.github.LiQiyeDev:botmaker-sdk", Set.of("Mouse", "Point", "Wait"),
                "com.example:shapes", Set.of("Point", "Rect")));

        assertEquals(Set.of("Point"), clashing);
    }

    @Test
    void oneJarOnItsOwnMakesNothingAmbiguous() {
        assertTrue(InstalledPlugin.ambiguousTypeNames(Map.of(
                "com.github.LiQiyeDev:botmaker-sdk", Set.of("Mouse", "Point"))).isEmpty(),
                "a name is only ambiguous against another plugin, never against itself");
    }
}
