package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.project.UserLibrary;
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
            new UserLibrary("com.github.BotMakerDev", "botmaker-sdk", "1.1.5");
    private static final UserLibrary BASICS =
            new UserLibrary("com.github.BotMakerDev", "botmaker-plugin-basics", "0.1.0");
    private static final UserLibrary JACKSON =
            new UserLibrary("com.fasterxml.jackson.core", "jackson-databind", "2.17.0");

    private static PluginRegistry.Plugin entry(String coordinate, String name, String verified,
                                               List<String> editorDependencies) {
        return new PluginRegistry.Plugin(coordinate, name, coordinate, "LiQiyeDev/x", "", List.of(), "",
                editorDependencies, verified, "");
    }

    @Test
    void anArtifactDeclaredUnderBothGroupIdsIsOneGroupAndTheNewGroupIdIsKept() {
        UserLibrary oldSdk = new UserLibrary("com.github.LiQiyeDev", "botmaker-sdk", "v1.2.3");
        List<InstalledPlugin> rows = InstalledPlugin.of(List.of(oldSdk, BASICS, SDK), List.of(), lib -> true);

        List<List<InstalledPlugin>> groups = InstalledPlugin.sameArtifactGroups(rows);

        assertEquals(2, groups.size());
        assertEquals(List.of("com.github.LiQiyeDev:botmaker-sdk", "com.github.BotMakerDev:botmaker-sdk"),
                groups.getFirst().stream().map(InstalledPlugin::coordinate).toList());
        assertEquals("com.github.BotMakerDev:botmaker-sdk",
                InstalledPlugin.keeper(groups.getFirst(), row -> false).coordinate());
        assertEquals("com.github.LiQiyeDev:botmaker-sdk",
                InstalledPlugin.keeper(groups.getFirst(), row -> row.installed().equals("v1.2.3")).coordinate(),
                "the copy Studio loaded wins");
    }

    @Test
    void aDependencyTheRegistryListsIsARegistryRowAtItsVerifiedVersion() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(SDK),
                List.of(entry("com.github.BotMakerDev:botmaker-sdk", "BotMaker SDK", "1.1.6", List.of())),
                lib -> false);

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
    void anEntryStillUnderTheFormerGroupIdAccountsForTheNewOneWithItsEditorDependencies() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(SDK),
                List.of(entry("com.github.LiQiyeDev:botmaker-sdk", "BotMaker SDK", "1.1.6",
                        List.of("io.javalin:javalin:6.7.0"))),
                lib -> false);

        assertEquals(1, rows.size());
        assertEquals(InstalledPlugin.Source.REGISTRY, rows.getFirst().source());
        assertEquals(List.of(new UserLibrary("io.javalin", "javalin", "6.7.0")),
                rows.getFirst().editorDependencies());
        assertEquals("", rows.getFirst().available(), "1.1.6 was verified under the other groupId");
    }

    @Test
    void aDependencyNothingAccountsForIsNoRowAtAll() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(SDK, JACKSON),
                List.of(entry("com.github.BotMakerDev:botmaker-sdk", "BotMaker SDK", "1.1.6", List.of())),
                lib -> false);

        assertEquals(List.of("com.github.BotMakerDev:botmaker-sdk"),
                rows.stream().map(InstalledPlugin::coordinate).toList(),
                "an ordinary library is not a plugin and must not be offered an upgrade here");
    }

    @Test
    void aDeclaredPluginNoRegistryKnowsIsUnlistedWithNothingToMoveTo() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(BASICS), List.of(), lib -> true);

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
    void theRowsKeepTheOrderThePomDeclaresThem() {
        List<InstalledPlugin> rows = InstalledPlugin.of(
                List.of(BASICS, SDK), List.of(), lib -> true);

        assertEquals(List.of("com.github.BotMakerDev:botmaker-plugin-basics",
                        "com.github.BotMakerDev:botmaker-sdk"),
                rows.stream().map(InstalledPlugin::coordinate).toList());
    }

    /**
     * The one hazard two plugins introduce that one never could — and the reason it is answered by a set of
     * names rather than by a heuristic. See {@link PluginUpgradeService#usesIn}.
     */
    @Test
    void aSimpleNameTwoPluginsBothDeclareIsAmbiguousAndOneOnlyOneDeclaresIsNot() {
        Set<String> clashing = InstalledPlugin.ambiguousTypeNames(Map.of(
                "com.github.BotMakerDev:botmaker-sdk", Set.of("Mouse", "Point", "Wait"),
                "com.example:shapes", Set.of("Point", "Rect")));

        assertEquals(Set.of("Point"), clashing);
    }

    @Test
    void oneJarOnItsOwnMakesNothingAmbiguous() {
        assertTrue(InstalledPlugin.ambiguousTypeNames(Map.of(
                "com.github.BotMakerDev:botmaker-sdk", Set.of("Mouse", "Point"))).isEmpty(),
                "a name is only ambiguous against another plugin, never against itself");
    }
}
